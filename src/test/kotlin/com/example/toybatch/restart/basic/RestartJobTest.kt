package com.example.toybatch.restart.basic

import com.example.toybatch.common.FailureInjector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.step.StepExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(properties = [
    "spring.batch.job.enabled=false", // 앱 기동 시 잡 자동 실행 끄기
    "spring.datasource.url=jdbc:h2:mem:restart-test;DB_CLOSE_DELAY=-1",
])
class RestartJobTest @Autowired constructor(
    private val jobOperator: JobOperator,
    private val jdbcTemplate: JdbcTemplate,
    private val failureInjector: FailureInjector,
    @Qualifier(RestartJobConfig.JOB_NAME) private val restartJob: Job,
    @Qualifier(RestartJobConfig.NO_STATE_JOB_NAME) private val restartNoStateJob: Job,
) {

    @BeforeEach
    fun setUp() {
        failureInjector.disable()
    }

    @Test
    fun `실패한 잡을 같은 파라미터로 다시 실행하면 마지막 커밋 지점부터 이어서 처리한다`() {
        val params = JobParametersBuilder()
            .addString("targetDate", "2026-09-25")
            .addLong("case", 1L)
            .toJobParameters()

        // ---------- 1차 실행: 57 번째 아이템에서 실패
        banner("1차 실행 (57에서 실패)")
        failureInjector.failAt(57)
        val first = jobOperator.start(restartJob, params)

        assertThat(first.status).isEqualTo(BatchStatus.FAILED)
        // chunk=10 이므로 1~50 까지만 커밋, 51~60 청크는 롤백
        assertThat(countRows(RestartJobConfig.JOB_NAME)).isEqualTo(50)
        assertThat(step(first, "restartJob.numberStep").executionContext.getInt("numberReader.read.count")).isEqualTo(50)
        printContextTables()

        // ---------- 2차 실행: 같은 파라미터 → 새 JobInstance 가 아니라 "재시작"
        banner("2차 실행 (재시작)")
        failureInjector.disable()
        val second = jobOperator.start(restartJob, params)

        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(second.jobInstance.instanceId).`as`("같은 JobInstance").isEqualTo(first.jobInstance.instanceId)
        assertThat(second.id).`as`("JobExecution 은 새로 생김").isNotEqualTo(first.id)

        // prepareStep 은 1차에서 COMPLETED → 2차에서는 아예 실행되지 않음
        assertThat(second.stepExecutions.map { it.stepName }).containsExactly("restartJob.numberStep")

        // 51~100 만 추가로 처리 → 중복 없이 총 100 건
        assertThat(countRows(RestartJobConfig.JOB_NAME)).isEqualTo(100)
        assertThat(countDistinct(RestartJobConfig.JOB_NAME)).isEqualTo(100)
        assertThat(step(second, "restartJob.numberStep").readCount).isEqualTo(50)
        printContextTables()

        // ---------- 3차 실행: 이미 COMPLETED 된 JobInstance 는 다시 못 돌린다
        assertThatThrownBy { jobOperator.start(restartJob, params) }
            .isInstanceOf(JobInstanceAlreadyCompleteException::class.java)
    }

    @Test
    fun `reader가 상태를 저장하지 않으면 재시작해도 처음부터 다시 읽어서 중복이 생긴다`() {
        val params = JobParametersBuilder()
            .addString("targetDate", "2026-09-25")
            .addLong("case", 2L)
            .toJobParameters()

        banner("[saveState=false] 1차 실행 (57에서 실패)")
        failureInjector.failAt(57)
        val first = jobOperator.start(restartNoStateJob, params)
        assertThat(first.status).isEqualTo(BatchStatus.FAILED)
        assertThat(countRows(RestartJobConfig.NO_STATE_JOB_NAME)).isEqualTo(50)

        banner("[saveState=false] 2차 실행 (재시작)")
        failureInjector.disable()
        val second = jobOperator.start(restartNoStateJob, params)
        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)

        // 재시작은 됐지만(prepareStep 스킵) reader 가 1 부터 다시 읽음 → 1~50 이 두 번 들어감
        assertThat(countRows(RestartJobConfig.NO_STATE_JOB_NAME)).isEqualTo(150)
        assertThat(countDistinct(RestartJobConfig.NO_STATE_JOB_NAME)).isEqualTo(100)
    }

    // ------------------------------------------------------------------ helpers

    private fun step(jobExecution: JobExecution, stepName: String): StepExecution =
        jobExecution.stepExecutions.first { it.stepName == stepName }

    private fun countRows(jobName: String): Int = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM restart_demo_result WHERE job_name = ?", Int::class.java, jobName)!!

    private fun countDistinct(jobName: String): Int = jdbcTemplate.queryForObject(
        "SELECT COUNT(DISTINCT item_value) FROM restart_demo_result WHERE job_name = ?", Int::class.java, jobName)!!

    /** 재시작 때 실제로 읽어가는 DB 테이블 내용을 그대로 찍어본다 */
    private fun printContextTables() {
        println("\n----- BATCH_JOB_EXECUTION (+ CONTEXT) -----")
        jdbcTemplate.queryForList("""
            SELECT e.JOB_INSTANCE_ID, e.JOB_EXECUTION_ID, e.STATUS, c.SHORT_CONTEXT
              FROM BATCH_JOB_EXECUTION e
              JOIN BATCH_JOB_EXECUTION_CONTEXT c ON c.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID
             ORDER BY e.JOB_EXECUTION_ID
        """).forEach { println("  $it") }

        println("----- BATCH_STEP_EXECUTION (+ CONTEXT) -----")
        jdbcTemplate.queryForList("""
            SELECT s.JOB_EXECUTION_ID, s.STEP_EXECUTION_ID, s.STEP_NAME, s.STATUS,
                   s.READ_COUNT, s.WRITE_COUNT, s.COMMIT_COUNT, s.ROLLBACK_COUNT, c.SHORT_CONTEXT
              FROM BATCH_STEP_EXECUTION s
              JOIN BATCH_STEP_EXECUTION_CONTEXT c ON c.STEP_EXECUTION_ID = s.STEP_EXECUTION_ID
             ORDER BY s.STEP_EXECUTION_ID
        """).forEach { println("  $it") }
        println()
    }

    private fun banner(title: String) = println("\n==================== $title ====================")
}
