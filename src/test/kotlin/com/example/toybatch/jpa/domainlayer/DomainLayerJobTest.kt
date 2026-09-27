package com.example.toybatch.jpa.domainlayer

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
 * docs/03-3-jpa-domain-layer.md 의 케이스를 재현한다.
 * 공통 준비: jpa_order 에 id 1~100 (amount = id*100, status=READY), chunk = pageSize = 10.
 */
@SpringBootTest(properties = [
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:jpa-test;DB_CLOSE_DELAY=-1", // 다른 jpa 테스트와 같은 컨텍스트를 재사용
])
class DomainLayerJobTest @Autowired constructor(
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

    @Test
    fun `도메인 reader writer 를 03-2 방식으로 끼우면 그대로 동작하고 재시작도 이어간다`() {
        val params = params()
        failureInjector.failAt(57)
        val first = run(DomainLayerJobConfig.DOMAIN_JOB, params)

        assertThat(first.status).isEqualTo(BatchStatus.FAILED)
        assertThat(settledIds()).`as`("51~60 청크는 롤백").isEqualTo((1L..50L).toList())
        assertThat(first.stepExecutions.single().executionContext.getLong("domainOrderReader.last.key")).isEqualTo(50L)

        failureInjector.disable()
        val second = run(DomainLayerJobConfig.DOMAIN_JOB, params)

        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(settledIds()).isEqualTo((1L..100L).toList())
    }

    @Test
    fun `도메인 writer 가 REQUIRES_NEW 면 청크가 롤백돼도 정산이 먼저 커밋돼서 남는다`() {
        failureInjector.failAt(57)
        val execution = run(DomainLayerJobConfig.DOMAIN_NEW_TX_WRITER_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(execution.stepExecutions.single().executionContext.getLong("domainOrderReader.last.key"))
            .`as`("배치는 50 까지 처리했다고 기록").isEqualTo(50L)
        assertThat(settledIds()).`as`("그런데 51~60 정산이 이미 커밋돼 있다").isEqualTo((1L..60L).toList())
    }

    @Test
    fun `@Transactional 없는 도메인 계산에서 검증 예외가 나면 그 건만 skip 된다`() {
        jdbcTemplate.update("UPDATE jpa_order SET amount = -100 WHERE id = 13")

        val execution = run(DomainLayerJobConfig.DOMAIN_SKIP_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(execution.stepExecutions.single().processSkipCount).isEqualTo(1)
        assertThat(settledIds()).hasSize(99).doesNotContain(13L)
    }

    @Test
    fun `@Transactional 도메인 계산에서 검증 예외를 skip 하면 그 청크 전체가 조용히 롤백돼서 사라진다`() {
        jdbcTemplate.update("UPDATE jpa_order SET amount = -100 WHERE id = 13")

        val execution = run(DomainLayerJobConfig.DOMAIN_TX_SKIP_JOB)
        val step = execution.stepExecutions.single()

        // 메타데이터로는 정상: 13 만 skip, 99건 write, 롤백 0번
        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(step.processSkipCount).isEqualTo(1)
        assertThat(step.writeCount).isEqualTo(99)
        assertThat(step.rollbackCount).isEqualTo(0)

        // 실제 DB: 13 이 있던 청크 [11..20] 이 통째로 없다 ❗ (에러 로그도 없음)
        assertThat(settledIds()).hasSize(90).doesNotContainAnyElementsOf(11L..20L)
    }

    // ------------------------------------------------------------------ helpers

    private fun run(jobName: String, params: JobParameters = params()): JobExecution {
        println("\n==================== $jobName ====================")
        return jobOperator.start(applicationContext.getBean(jobName, Job::class.java), params)
    }

    private fun params(): JobParameters = JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters()

    private fun settledIds(): List<Long> = jdbcTemplate.queryForList(
        "SELECT order_id FROM jpa_settlement ORDER BY order_id", Long::class.java).filterNotNull()
}
