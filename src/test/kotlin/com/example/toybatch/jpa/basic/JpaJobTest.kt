package com.example.toybatch.jpa.basic

import com.example.toybatch.common.FailureInjector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate

/**
 * docs/03-jpa-reader-writer.md 의 케이스를 재현한다.
 * 공통 준비: jpa_order 에 id 1~100 (amount = id*100, status=READY), chunk = pageSize = 10.
 */
@SpringBootTest(properties = [
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:jpa-test;DB_CLOSE_DELAY=-1",
])
class JpaJobTest @Autowired constructor(
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

    // ================================================================ 기본

    @Test
    fun `JpaPagingItemReader 에서 JpaItemWriter 로 저장하고, 실패 후 재시작하면 이어서 처리한다`() {
        val params = params()

        failureInjector.failAt(57)
        val first = run(JpaJobConfig.PAGING_JOB, params)
        assertThat(first.status).isEqualTo(BatchStatus.FAILED)
        assertThat(settledIds()).isEqualTo((1L..50L).toList())

        failureInjector.disable()
        val second = run(JpaJobConfig.PAGING_JOB, params)
        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        // read.count=50 → OFFSET 50 부터. 조회 대상(전체 주문)이 안 바뀌니 정확히 이어간다
        assertThat(second.stepExecutions.single().readCount).isEqualTo(50)
        assertThat(settledIds()).isEqualTo((1L..100L).toList())
    }

    // ================================================================ 상태 플래그 조회

    @Test
    fun `JpaPagingItemReader 로 status 조건을 읽으면서 status 를 바꾸면 절반이 누락된다`() {
        val execution = run(JpaJobConfig.PAGING_STATUS_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        // page1 을 읽기 직전 1~10 이 DONE 으로 커밋돼 있어서 OFFSET 10 이 21 부터를 가리킨다
        assertThat(doneIds()).hasSize(50)
        assertThat(doneIds()).doesNotContain(11L, 20L, 31L, 40L)
        println("  ❗ READY 로 남은 주문 = ${(1L..100L).filterNot { it in doneIds() }}")
    }

    @Test
    fun `JpaCursorItemReader 는 쿼리를 한 번만 실행하므로 status 를 바꿔도 전부 처리한다`() {
        val execution = run(JpaJobConfig.CURSOR_STATUS_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(doneIds()).isEqualTo((1L..100L).toList())
    }

    // ================================================================ processor 에서 엔티티만 바꾸기

    @Test
    fun `cursor reader 가 준 엔티티를 processor 에서 바꿔도 저장되지 않는다`() {
        val execution = run(JpaJobConfig.CURSOR_DIRTY_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(settledIds()).hasSize(100)
        // reader 의 EntityManager 는 트랜잭션이 없고 청크마다 clear 된다 → 변경은 그냥 버려진다 ❗
        assertThat(doneIds()).isEmpty()
    }

    @Test
    fun `paging reader 가 준 엔티티는 processor 에서 바꾸면 저장되는데, 청크 트랜잭션이 아니라 reader 트랜잭션으로 저장된다`() {
        val execution = run(JpaJobConfig.PAGING_DIRTY_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        // 다음 페이지를 읽을 때 reader 가 자기 트랜잭션에서 flush 하면서 저장된다 (마지막 페이지는 빈 페이지 조회 때)
        assertThat(doneIds()).isEqualTo((1L..100L).toList())
    }

    @Test
    fun `그래서 청크가 롤백되어도 processor 에서 바꾼 상태는 남는다 - skip 된 건인데 DONE`() {
        jdbcTemplate.update("UPDATE jpa_order SET amount = -100 WHERE id = 13") // 정산 fee 가 음수 → CHECK 위반

        val execution = run(JpaJobConfig.PAGING_DIRTY_SKIP_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(execution.stepExecutions.single().writeSkipCount).isEqualTo(1)
        assertThat(settledIds()).doesNotContain(13L) // 정산은 skip 되어 없는데
        assertThat(status(13)).isEqualTo("DONE")     // ❗ 주문은 DONE → 다시는 정산 대상으로 안 잡힌다
    }

    @Test
    fun `정산 저장과 상태 변경을 writer 에서 같은 트랜잭션으로 하면 skip 된 건은 둘 다 안 남는다`() {
        jdbcTemplate.update("UPDATE jpa_order SET amount = -100 WHERE id = 13")

        val execution = run(JpaJobConfig.SETTLE_AND_MARK_SKIP_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(settledIds()).hasSize(99).doesNotContain(13L)
        assertThat(doneIds()).hasSize(99).doesNotContain(13L)
        assertThat(status(13)).isEqualTo("READY") // 고쳐서 다시 돌리면 정산된다
    }

    // ================================================================ JPA writer + retry

    @Test
    fun `JPA writer 의 write 재시도는 같은 트랜잭션에서 이미 insert 된 행과 PK 가 충돌해서 성공할 수 없다`() {
        val execution = run(JpaJobConfig.WRITE_RETRY_JOB)

        // 1차 flush: 11, 12 insert ✔ → 13 CHECK 위반 ✘
        // 재시도   : 롤백 없이 같은 트랜잭션 → 11 을 다시 insert → PK 중복 ✘ (두 번째 재시도도 같음)
        assertThat(execution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(settledIds()).isEqualTo((1L..10L).toList())
        val causes = generateSequence(execution.stepExecutions.single().failureExceptions.single()) { it.cause }
            .toList() + execution.stepExecutions.single().failureExceptions.single().cause!!.suppressed
        println("  failure chain = ${causes.map { it.javaClass.simpleName }}")
        assertThat(causes.map { it.message.orEmpty() }).anyMatch { it.contains("Unique index or primary key violation") }
    }

    @Test
    fun `retry 에 skip 을 같이 걸면 scan 이 한 건씩 새 트랜잭션으로 다시 써서 살아난다`() {
        val execution = run(JpaJobConfig.WRITE_RETRY_SCAN_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        // 재시도 소진 → 청크 롤백 → scan: 11 ~ 20 을 각자 트랜잭션(새 EntityManager)으로 → 13 도 이번엔 성공
        assertThat(settledIds()).isEqualTo((1L..100L).toList())
        assertThat(execution.stepExecutions.single().writeSkipCount).isZero()
    }

    // ------------------------------------------------------------------ helpers

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
