package com.example.toybatch.flow.decider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest

/**
 * docs/04-2-decider.md 의 케이스를 재현한다. processStep 은 chunk=10, decider 기준은 writeCount 100.
 */
@SpringBootTest(properties = [
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:decider-test;DB_CLOSE_DELAY=-1",
])
class DeciderJobTest @Autowired constructor(
    private val jobOperator: JobOperator,
    @Qualifier(DeciderJobConfig.JOB_NAME) private val deciderJob: Job,
) {

    @Test
    fun `쓴 건수가 기준 미만이면 decider 가 SMALL 을 돌려줘서 processStep 만 실행하고 끝난다`() {
        val execution = run(inputCount = 30)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(stepNames(execution)).containsExactly("processStep")
    }

    @Test
    fun `쓴 건수가 기준 이상이면 decider 가 LARGE 를 돌려줘서 reportStep 까지 실행한다`() {
        val execution = run(inputCount = 150)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        // decider 는 스텝이 아니라서 StepExecution 이 남지 않는다
        assertThat(stepNames(execution)).containsExactly("processStep", "reportStep")
    }

    // ------------------------------------------------------------------ helpers

    private fun run(inputCount: Long): JobExecution = jobOperator.start(deciderJob, JobParametersBuilder()
        .addLong("inputCount", inputCount)
        .toJobParameters())

    /** "deciderJob.processStep" → "processStep", 실행 순서대로 */
    private fun stepNames(execution: JobExecution): List<String> =
        execution.stepExecutions.sortedBy { it.id }.map { it.stepName.substringAfter("${DeciderJobConfig.JOB_NAME}.") }
}
