package com.example.toybatch.flow.builder

import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.FlowBuilder
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.job.flow.Flow
import org.springframework.batch.core.job.flow.support.SimpleFlow
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.transaction.PlatformTransactionManager

/**
 * 04-3. FlowBuilder 로 스텝 묶음(Flow)을 만들어 잡에 조립하는 예제.
 *
 *   usersFlow  : loadUsersStep
 *   ordersFlow : loadOrdersStep
 *
 *   flowBuilderJob : usersFlow → ordersFlow → processStep            (Flow 를 순서대로 이어 붙임)
 *   splitJob       : [usersFlow ∥ ordersFlow] → processStep          (split 으로 두 Flow 를 병렬 실행, 둘 다 끝나면 다음)
 *
 * 같은 Flow 빈을 두 잡이 같이 쓴다. Flow 는 잡이 아니라서 혼자 실행할 수 없고, 잡(또는 FlowStep) 안에 넣어야 돈다.
 */
@Configuration
class FlowBuilderJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
) {

    // ---------------------------------------------------------------- 재사용할 Flow

    @Bean
    fun usersFlow(): Flow = FlowBuilder<SimpleFlow>("usersFlow")
        .start(loggingStep("$FLOW_PREFIX.loadUsersStep"))
        .build()

    @Bean
    fun ordersFlow(): Flow = FlowBuilder<SimpleFlow>("ordersFlow")
        .start(loggingStep("$FLOW_PREFIX.loadOrdersStep"))
        .build()

    // ---------------------------------------------------------------- flowBuilderJob (순차)

    @Bean
    fun flowBuilderJob(): Job = JobBuilder(JOB_NAME, jobRepository)
        .start(usersFlow())
        .next(ordersFlow())
        .next(loggingStep("$JOB_NAME.processStep"))
        .end() // start(flow) 로 시작하면 JobFlowBuilder 가 되므로 end() 로 닫는다
        .build()

    // ---------------------------------------------------------------- splitJob (병렬)

    @Bean
    fun splitJob(): Job {
        val parallelLoad = FlowBuilder<SimpleFlow>("parallelLoad")
            .split(SimpleAsyncTaskExecutor("split-")) // Flow 하나당 스레드 하나
            .add(usersFlow(), ordersFlow())
            .build()

        return JobBuilder(SPLIT_JOB_NAME, jobRepository)
            .start(parallelLoad)
            .next(loggingStep("$SPLIT_JOB_NAME.processStep")) // split 안의 Flow 가 전부 끝나야 실행된다
            .end()
            .build()
    }

    /** 흐름만 보는 예제라 로그만 찍는다. 어느 스레드에서 돌았는지 Step ExecutionContext 에 남긴다 */
    private fun loggingStep(name: String): Step = StepBuilder(name, jobRepository)
        .tasklet({ contribution, _ ->
            val thread = Thread.currentThread().name
            contribution.stepExecution.executionContext.putString(THREAD_KEY, thread)
            log.info(">>> [{}] 실행됨 (thread={})", name, thread)
            RepeatStatus.FINISHED
        }, transactionManager)
        .build()

    companion object {
        const val JOB_NAME = "flowBuilderJob"
        const val SPLIT_JOB_NAME = "splitJob"
        const val FLOW_PREFIX = "sharedFlow"
        const val THREAD_KEY = "thread"
        private val log = LoggerFactory.getLogger(FlowBuilderJobConfig::class.java)
    }
}
