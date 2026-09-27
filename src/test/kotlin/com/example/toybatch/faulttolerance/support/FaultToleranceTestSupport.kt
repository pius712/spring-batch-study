package com.example.toybatch.faulttolerance.support

import org.junit.jupiter.api.BeforeEach
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.step.StepExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate

/** retry / skip / 조합 테스트가 같은 스프링 컨텍스트를 쓰도록 설정과 헬퍼를 모아둔다 */
@SpringBootTest(properties = [
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:fault-tolerance-test;DB_CLOSE_DELAY=-1",
])
abstract class FaultToleranceTestSupport {

    @Autowired lateinit var jobOperator: JobOperator
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var recorder: AttemptRecorder
    @Autowired lateinit var applicationContext: ApplicationContext
    @Autowired lateinit var externalApi: FakeExternalApi

    @BeforeEach
    fun resetState() {
        recorder.clear()
        externalApi.clear()
        jdbcTemplate.update("DELETE FROM ft_result")
        jdbcTemplate.update("DELETE FROM ft_skip_log")
    }

    fun run(jobName: String, params: JobParameters = newParams()): JobExecution {
        println("\n==================== $jobName ====================")
        val job = applicationContext.getBean(jobName, Job::class.java)
        return jobOperator.start(job, params).also { printSummary(it.stepExecutions.single()) }
    }

    fun newParams(): JobParameters = JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters()

    /** ft_result 에 저장된 값 (중복 포함, 정렬) */
    fun savedItems(jobName: String): List<Int> = jdbcTemplate.queryForList(
        "SELECT item_value FROM ft_result WHERE job_name = ? ORDER BY item_value", Int::class.java, jobName).filterNotNull()

    /** SkipListener 가 청크 트랜잭션 안에서 ft_skip_log 에 남긴 기록. 예: ["read:3", "process:8"] */
    fun skipLog(jobName: String): List<String> = jdbcTemplate.queryForList(
        "SELECT stage || ':' || item_value FROM ft_skip_log WHERE job_name = ? ORDER BY item_value",
        String::class.java, jobName).filterNotNull()

    fun attempts(jobName: String, stage: String, item: Int) = recorder.attempts(jobName, stage, item)

    fun exitDescription(execution: JobExecution): String = execution.stepExecutions.single().exitStatus.exitDescription

    private fun printSummary(step: StepExecution) {
        println("""
            |  ----- ${step.stepName} : ${step.status}
            |  read=${step.readCount} write=${step.writeCount} filter=${step.filterCount}
            |  skip(read/process/write)=${step.readSkipCount}/${step.processSkipCount}/${step.writeSkipCount}
            |  commit=${step.commitCount} rollback=${step.rollbackCount}
            |  failures=${step.failureExceptions.map { "${it.javaClass.simpleName}: ${it.message}" }}
        """.trimMargin())
    }
}
