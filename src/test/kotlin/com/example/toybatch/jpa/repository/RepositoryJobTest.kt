package com.example.toybatch.jpa.repository

import com.example.toybatch.common.FailureInjector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.launch.JobRestartException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate

/**
 * docs/03-2-jpa-repository.md 의 케이스를 재현한다.
 * 공통 준비: jpa_order 에 id 1~100 (amount = id*100, status=READY), chunk = pageSize = 10.
 */
@SpringBootTest(properties = [
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:jpa-test;DB_CLOSE_DELAY=-1", // JpaJobTest 와 같은 컨텍스트를 재사용
])
class RepositoryJobTest @Autowired constructor(
    private val jobOperator: JobOperator,
    private val jdbcTemplate: JdbcTemplate,
    private val failureInjector: FailureInjector,
    private val applicationContext: ApplicationContext,
) {

    @BeforeEach
    fun setUp() {
        failureInjector.disable()
        jdbcTemplate.update("DELETE FROM jpa_settlement")
        jdbcTemplate.update("DELETE FROM jpa_order")
        (1..100).forEach {
            jdbcTemplate.update("INSERT INTO jpa_order(id, amount, status) VALUES (?, ?, 'READY')", it, it * 100)
        }
    }

    // ================================================================ 기본 + 재시작

    @Test
    fun `RepositoryItemReader 와 RepositoryItemWriter 로 repository 를 그대로 쓸 수 있다`() {
        val execution = run(RepositoryJobConfig.REPO_PAGING_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(settledIds()).isEqualTo((1L..100L).toList())
    }

    @Test
    fun `RepositoryItemReader 는 offset 방식이라 재시작 사이에 앞쪽 행이 지워지면 누락된다`() {
        val params = params()
        runFailingAt57(RepositoryJobConfig.REPO_PAGING_JOB, params)

        jdbcTemplate.update("DELETE FROM jpa_order WHERE id BETWEEN 1 AND 5")
        val second = run(RepositoryJobConfig.REPO_PAGING_JOB, params)

        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        // read.count=50 → page 5 (OFFSET 50) → [6..100] 에서 56 부터 ❗
        assertThat(settledIds()).hasSize(95).doesNotContain(51L, 52L, 53L, 54L, 55L)
    }

    @Test
    fun `키 기반 reader 는 마지막 키를 저장하므로 앞쪽 행이 지워져도 정확히 이어간다`() {
        val params = params()
        val first = runFailingAt57(RepositoryJobConfig.REPO_KEYSET_JOB, params)
        assertThat(first.stepExecutions.single().executionContext.getLong("allOrdersKeysetReader.last.key")).isEqualTo(50L)

        jdbcTemplate.update("DELETE FROM jpa_order WHERE id BETWEEN 1 AND 5")
        val second = run(RepositoryJobConfig.REPO_KEYSET_JOB, params)

        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(settledIds()).isEqualTo((1L..100L).toList()) // id > 50 부터
    }

    // ================================================================ 상태 플래그 조회

    @Test
    fun `RepositoryItemReader 로 status 조건을 읽으면서 status 를 바꾸면 절반이 누락된다`() {
        val execution = run(RepositoryJobConfig.REPO_STATUS_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(doneIds()).hasSize(50).doesNotContain(11L, 20L, 31L, 40L)
    }

    @Test
    fun `키 기반 reader 는 status 를 바꿔서 목록이 당겨져도 전부 처리한다`() {
        val execution = run(RepositoryJobConfig.REPO_KEYSET_STATUS_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(doneIds()).isEqualTo((1L..100L).toList())
    }

    // ================================================================ processor 에서 엔티티만 바꾸기

    @Test
    fun `RepositoryItemReader 는 청크 트랜잭션 안에서 읽으므로 processor 에서 바꾼 엔티티가 dirty checking 으로 저장된다`() {
        val execution = run(RepositoryJobConfig.REPO_DIRTY_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(doneIds()).isEqualTo((1L..100L).toList())
    }

    @Test
    fun `하지만 청크가 롤백되고 scan 으로 넘어가면 엔티티 변경은 사라지고 정산만 다시 쓰인다`() {
        jdbcTemplate.update("UPDATE jpa_order SET amount = -100 WHERE id = 13")

        val execution = run(RepositoryJobConfig.REPO_DIRTY_SKIP_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        // 청크 [11..20] 롤백 → 주문 변경(dirty)도 롤백. scan 은 writer 만 다시 부르므로 정산만 한 건씩 저장
        assertThat(settledIds()).contains(11L, 12L, 14L, 20L).doesNotContain(13L)
        assertThat((11L..20L).map { status(it) }).containsOnly("READY") // ❗ 정산은 됐는데 주문은 READY
    }

    // ================================================================ RepositoryItemWriter + skip

    @Test
    fun `RepositoryItemWriter 는 flush 를 안 해서 커밋 때 터지고, skip 이 동작하지 않고 잡이 UNKNOWN 이 된다`() {
        jdbcTemplate.update("UPDATE jpa_order SET amount = -100 WHERE id = 13")
        val params = params()

        val execution = run(RepositoryJobConfig.REPO_WRITER_SKIP_JOB, params)

        // write() 는 saveAll 만 하고 성공 → 커밋 때 INSERT 가 나가면서 CHECK 위반
        // → skip 대상이 되기 전에 트랜잭션이 깨지고, 스텝 메타데이터 버전까지 어긋나서 UNKNOWN
        assertThat(execution.status).isEqualTo(BatchStatus.UNKNOWN)
        assertThat(execution.stepExecutions.single().writeSkipCount).isZero()
        assertThat(settledIds()).isEqualTo((1L..10L).toList())
        println("  failures = ${execution.allFailureExceptions.map { it.javaClass.simpleName }}")

        // UNKNOWN 은 "어디까지 커밋됐는지 프레임워크도 모른다" 는 뜻이라 재시작도 거부된다
        assertThatThrownBy { run(RepositoryJobConfig.REPO_WRITER_SKIP_JOB, params) }
            .isInstanceOf(JobRestartException::class.java)
            .hasMessageContaining("UNKNOWN")
    }

    @Test
    fun `saveAllAndFlush 로 write 안에서 SQL 을 내보내면 skip 이 정상 동작한다`() {
        jdbcTemplate.update("UPDATE jpa_order SET amount = -100 WHERE id = 13")

        val execution = run(RepositoryJobConfig.REPO_FLUSH_WRITER_SKIP_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(execution.stepExecutions.single().writeSkipCount).isEqualTo(1)
        assertThat(settledIds()).hasSize(99).doesNotContain(13L)
    }

    // ------------------------------------------------------------------ helpers

    private fun runFailingAt57(jobName: String, params: JobParameters): JobExecution {
        failureInjector.failAt(57)
        val first = run(jobName, params)
        assertThat(first.status).isEqualTo(BatchStatus.FAILED)
        assertThat(settledIds()).isEqualTo((1L..50L).toList())
        failureInjector.disable()
        return first
    }

    private fun run(jobName: String, params: JobParameters = params()): JobExecution {
        println("\n==================== $jobName ====================")
        return jobOperator.start(applicationContext.getBean(jobName, Job::class.java), params)
    }

    private fun params(): JobParameters = JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters()

    private fun settledIds(): List<Long> = jdbcTemplate.queryForList(
        "SELECT order_id FROM jpa_settlement ORDER BY order_id", Long::class.java).filterNotNull()

    private fun doneIds(): List<Long> = jdbcTemplate.queryForList(
        "SELECT id FROM jpa_order WHERE status = 'DONE' ORDER BY id", Long::class.java).filterNotNull()

    private fun status(id: Long): String =
        jdbcTemplate.queryForObject("SELECT status FROM jpa_order WHERE id = ?", String::class.java, id)!!
}
