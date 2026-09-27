package com.example.toybatch.restart.jdbc

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
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

/**
 * docs/01-2-restart-jdbc-reader.md 의 케이스를 재현한다.
 *
 * 공통 준비: rj_order 에 id 1~100 (order_date=2026-09-25, status=READY), chunk=10.
 * 재시작 케이스는 1차에서 id 57 에서 실패 → 1~50 커밋, 51~60 롤백 → (데이터 변경) → 2차 재시작.
 */
@SpringBootTest(properties = [
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:restart-jdbc-test;DB_CLOSE_DELAY=-1",
])
class RestartJdbcJobTest @Autowired constructor(
    private val jobOperator: JobOperator,
    private val jdbcTemplate: JdbcTemplate,
    private val failureInjector: FailureInjector,
    @Qualifier(RestartJdbcJobConfig.CURSOR_JOB) private val cursorJob: Job,
    @Qualifier(RestartJdbcJobConfig.PAGING_JOB) private val pagingJob: Job,
    @Qualifier(RestartJdbcJobConfig.STATUS_STATE_JOB) private val statusStateJob: Job,
    @Qualifier(RestartJdbcJobConfig.STATUS_NO_STATE_JOB) private val statusNoStateJob: Job,
    @Qualifier(RestartJdbcJobConfig.OFFSET_STATUS_JOB) private val offsetStatusJob: Job,
    @Qualifier(RestartJdbcJobConfig.KEY_STATUS_JOB) private val keyStatusJob: Job,
) {

    @BeforeEach
    fun setUp() {
        failureInjector.disable()
        jdbcTemplate.update("DELETE FROM rj_result")
        jdbcTemplate.update("DELETE FROM rj_order")
        insertOrders(1L..100L, TARGET_DATE)
    }

    // ================================================================ 방법 1. 범위 고정

    @Test
    fun `개수 기반 cursor - 범위 밖에 데이터가 추가되면 재시작해도 안전하다`() {
        val params = params(1)
        val first = runFailingAt57(cursorJob, params)
        assertThat(first.stepExecutions.single().executionContext.getInt("rangeCursorReader.read.count")).isEqualTo(50)

        banner("범위 밖(다음 날짜) 주문 101~120 추가")
        insertOrders(101L..120L, "2026-09-26")

        val second = restart(cursorJob, params)
        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        // 범위 안 결과가 그대로라 "50번째" 가 같은 행 → 51 부터 정확히 이어감
        assertThat(processedIds(RestartJdbcJobConfig.CURSOR_JOB)).isEqualTo((1L..100L).toList())
    }

    @Test
    fun `개수 기반 cursor - 범위 안의 처리된 행이 삭제되면 재시작 때 누락된다`() {
        val params = params(2)
        runFailingAt57(cursorJob, params)

        banner("이미 처리한 주문 1~5 삭제 (예: 주문 취소)")
        jdbcTemplate.update("DELETE FROM rj_order WHERE id BETWEEN 1 AND 5")

        val second = restart(cursorJob, params)

        // 에러 없이 COMPLETED 인데...
        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        // 조회 결과가 6~100 (95건) 으로 당겨졌는데 "앞의 50개 건너뛰기" → 56 부터 읽음
        val processed = processedIds(RestartJdbcJobConfig.CURSOR_JOB)
        assertThat(processed).doesNotContain(51L, 52L, 53L, 54L, 55L) // ❗ 누락
        assertThat(processed).hasSize(95)
        printMissing(processed)
    }

    @Test
    fun `키 기반 paging - 범위 안의 처리된 행이 삭제되어도 재시작은 정확하다`() {
        val params = params(3)
        val first = runFailingAt57(pagingJob, params)
        // 개수(read.count) 와 함께 마지막 정렬 키(start.after)가 저장된다
        println("  stepContext = ${first.stepExecutions.single().executionContext}")

        banner("이미 처리한 주문 1~5 삭제 (예: 주문 취소)")
        jdbcTemplate.update("DELETE FROM rj_order WHERE id BETWEEN 1 AND 5")

        val second = restart(pagingJob, params)
        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        // WHERE ... AND id > 50 으로 조회하므로 앞쪽 삭제와 무관하게 51 부터
        assertThat(processedIds(RestartJdbcJobConfig.PAGING_JOB)).isEqualTo((1L..100L).toList())
    }

    // ================================================================ 방법 2. 상태 플래그

    @Test
    fun `상태 플래그인데 saveState가 true면 재시작 때 전부 건너뛰고 COMPLETED 된다`() {
        val params = params(4)
        runFailingAt57(statusStateJob, params)
        assertThat(countReady()).isEqualTo(50) // 51~100 이 남아 있음

        val second = restart(statusStateJob, params)

        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        // READY 51~100 (50건) 을 조회한 뒤 read.count=50 만큼 건너뜀 → 한 건도 처리 안 함 ❗
        assertThat(second.stepExecutions.single().writeCount).isZero()
        assertThat(processedIds(RestartJdbcJobConfig.STATUS_STATE_JOB)).isEqualTo((1L..50L).toList())
        assertThat(countReady()).isEqualTo(50)
        println("  ❗ COMPLETED 인데 READY 로 남은 주문 = ${countReady()}건")
    }

    @Test
    fun `상태 플래그 + saveState false면 재시작 때 남은 것만 처리한다`() {
        val params = params(5)
        runFailingAt57(statusNoStateJob, params)

        val second = restart(statusNoStateJob, params)

        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        // 처음부터 다시 조회하지만 1~50 은 DONE 이라 안 나온다 → 51~100 만 처리
        assertThat(processedIds(RestartJdbcJobConfig.STATUS_NO_STATE_JOB)).isEqualTo((1L..100L).toList())
        assertThat(countReady()).isZero()
    }

    // ================================================================ 덤: 재시작이 아니어도 터지는 오프셋 페이징

    @Test
    fun `오프셋 페이징 + 상태 플래그는 재시작 없이 정상 실행해도 절반이 누락된다`() {
        banner("오프셋 페이징 + status 플래그 (실패 없음)")
        val execution = jobOperator.start(offsetStatusJob, params(6))

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        // page0: 1~10 → page1(OFFSET 10): 11~20 을 건너뛰고 21~30 → page2(OFFSET 20): 41~50 ...
        val processed = processedIds(RestartJdbcJobConfig.OFFSET_STATUS_JOB)
        assertThat(processed).hasSize(50) // ❗ 절반 누락
        assertThat(processed).doesNotContain(11L, 20L, 31L, 40L)
        printMissing(processed)
    }

    @Test
    fun `키 기반 페이징 + 상태 플래그는 목록이 당겨져도 전부 처리한다`() {
        banner("키 기반 페이징 + status 플래그 (실패 없음)")
        val execution = jobOperator.start(keyStatusJob, params(7))

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(processedIds(RestartJdbcJobConfig.KEY_STATUS_JOB)).isEqualTo((1L..100L).toList())
    }

    // ------------------------------------------------------------------ helpers

    private fun runFailingAt57(job: Job, params: JobParameters): JobExecution {
        banner("${job.name} 1차 실행 (57에서 실패)")
        failureInjector.failAt(57)
        val first = jobOperator.start(job, params)
        assertThat(first.status).isEqualTo(BatchStatus.FAILED)
        assertThat(processedIds(job.name)).isEqualTo((1L..50L).toList())
        return first
    }

    private fun restart(job: Job, params: JobParameters): JobExecution {
        banner("${job.name} 2차 실행 (재시작)")
        failureInjector.disable()
        return jobOperator.start(job, params)
    }

    private fun params(caseNo: Long): JobParameters = JobParametersBuilder()
        .addString("targetDate", TARGET_DATE)
        .addLong("case", caseNo)
        .toJobParameters()

    private fun insertOrders(ids: LongRange, orderDate: String) = ids.forEach {
        jdbcTemplate.update("INSERT INTO rj_order(id, order_date, status) VALUES (?, ?, 'READY')", it, orderDate)
    }

    private fun processedIds(jobName: String): List<Long> = jdbcTemplate.queryForList(
        "SELECT order_id FROM rj_result WHERE job_name = ? ORDER BY order_id", Long::class.java, jobName).filterNotNull()

    private fun countReady(): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rj_order WHERE status = 'READY'", Int::class.java)!!

    private fun printMissing(processed: List<Long>) {
        val missing = (1L..100L).filterNot { it in processed }
        println("  ❗ 처리 안 된 id (${missing.size}건) = $missing")
    }

    private fun banner(title: String) = println("\n==================== $title ====================")

    companion object {
        private const val TARGET_DATE = "2026-09-25"
    }
}
