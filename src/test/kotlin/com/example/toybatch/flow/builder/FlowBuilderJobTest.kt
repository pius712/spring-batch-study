package com.example.toybatch.flow.builder

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.step.StepExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest

/**
 * docs/04-3-flow-builder.md 의 케이스를 재현한다.
 */
@SpringBootTest(properties = [
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:flow-builder-test;DB_CLOSE_DELAY=-1",
])
class FlowBuilderJobTest @Autowired constructor(
    private val jobOperator: JobOperator,
    @Qualifier(FlowBuilderJobConfig.JOB_NAME) private val flowBuilderJob: Job,
    @Qualifier(FlowBuilderJobConfig.SPLIT_JOB_NAME) private val splitJob: Job,
) {

    @Test
    fun `Flow 를 이어 붙이면 Flow 안의 스텝이 순서대로 같은 스레드에서 실행된다`() {
        val execution = jobOperator.start(flowBuilderJob, JobParameters())

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(execution.stepExecutions.sortedBy { it.id }.map { it.stepName }).containsExactly(
            "${FlowBuilderJobConfig.FLOW_PREFIX}.loadUsersStep",
            "${FlowBuilderJobConfig.FLOW_PREFIX}.loadOrdersStep",
            "${FlowBuilderJobConfig.JOB_NAME}.processStep",
        )
        assertThat(execution.stepExecutions.map { threadOf(it) }.toSet())
            .`as`("잡을 실행한 스레드 하나").containsExactly(Thread.currentThread().name)
    }

    @Test
    fun `split 으로 묶은 Flow 는 각자 다른 스레드에서 돌고, 둘 다 끝난 뒤 다음 스텝이 실행된다`() {
        val execution = jobOperator.start(splitJob, JobParameters())

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        val users = step(execution, "${FlowBuilderJobConfig.FLOW_PREFIX}.loadUsersStep")
        val orders = step(execution, "${FlowBuilderJobConfig.FLOW_PREFIX}.loadOrdersStep")
        val process = step(execution, "${FlowBuilderJobConfig.SPLIT_JOB_NAME}.processStep")

        assertThat(threadOf(users)).startsWith("split-")
        assertThat(threadOf(orders)).startsWith("split-")
        assertThat(threadOf(users)).`as`("Flow 마다 스레드가 따로").isNotEqualTo(threadOf(orders))

        // split 은 안의 Flow 가 전부 끝날 때까지 기다린다
        assertThat(process.startTime).isAfterOrEqualTo(users.endTime).isAfterOrEqualTo(orders.endTime)
    }

    // ------------------------------------------------------------------ helpers

    private fun step(execution: JobExecution, stepName: String): StepExecution =
        execution.stepExecutions.first { it.stepName == stepName }

    private fun threadOf(stepExecution: StepExecution): String =
        stepExecution.executionContext.getString(FlowBuilderJobConfig.THREAD_KEY)
}
