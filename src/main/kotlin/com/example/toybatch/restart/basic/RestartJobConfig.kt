package com.example.toybatch.restart.basic

import com.example.toybatch.common.ExecutionContextLoggingListener
import com.example.toybatch.common.FailureInjector
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * 재시작 + ExecutionContext 예제.
 *
 *   restartJob         : prepareStep(tasklet) → numberStep(chunk=10, reader saveState=true)
 *   restartNoStateJob  : 위와 동일하지만 reader saveState=false (비교용: 재시작하면 처음부터 다시 읽음)
 *
 * reader 는 RestartReaderConfig 에 있다.
 */
@Configuration
class RestartJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val jdbcTemplate: JdbcTemplate,
    private val failureInjector: FailureInjector,
    private val readers: RestartReaderConfig,
) {

    // ---------------------------------------------------------------- restartJob

    @Bean
    fun restartJob(): Job = JobBuilder(JOB_NAME, jobRepository)
        .start(prepareStep("restartJob.prepareStep"))
        .next(numberStep("restartJob.numberStep", JOB_NAME, readers.statefulNumberReader(null)))
        .build()

    // ---------------------------------------------------------------- restartNoStateJob (비교용)

    @Bean
    fun restartNoStateJob(): Job = JobBuilder(NO_STATE_JOB_NAME, jobRepository)
        .start(prepareStep("restartNoStateJob.prepareStep"))
        .next(numberStep("restartNoStateJob.numberStep", NO_STATE_JOB_NAME, readers.statelessNumberReader(null)))
        .build()

    // ---------------------------------------------------------------- 공통 스텝

    private fun prepareStep(stepName: String): Step = StepBuilder(stepName, jobRepository)
        .tasklet(PrepareTasklet(TOTAL_COUNT), transactionManager)
        .listener(ExecutionContextLoggingListener() as StepExecutionListener) // tasklet 스텝은 listener 오버로드가 많아서 타입을 지정
        // .allowStartIfComplete(true) // 켜면 재시작 때도 이 스텝이 다시 실행된다
        .build()

    private fun numberStep(stepName: String, jobName: String, reader: NumberReader): Step =
        StepBuilder(stepName, jobRepository)
            .chunk<Int, Int>(CHUNK_SIZE)
            .transactionManager(transactionManager)
            .reader(reader) // ItemStream 구현체라 스텝이 자동으로 open/update/close 를 호출해준다
            .writer(ResultWriter(jobName, jdbcTemplate, failureInjector))
            .listener(ExecutionContextLoggingListener()) // StepExecutionListener + ChunkListener 둘 다 등록된다
            .build()

    companion object {
        const val JOB_NAME = "restartJob"
        const val NO_STATE_JOB_NAME = "restartNoStateJob"
        private const val TOTAL_COUNT = 100
        private const val CHUNK_SIZE = 10
    }
}
