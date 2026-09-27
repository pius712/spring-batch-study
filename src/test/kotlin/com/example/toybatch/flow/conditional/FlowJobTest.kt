package com.example.toybatch.flow.conditional

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.step.StepExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest

/**
 * docs/04-1-flow.md 의 케이스를 재현한다. processStep 은 chunk=10.
 */
@SpringBootTest(properties = [
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:flow-test;DB_CLOSE_DELAY=-1",
])
class FlowJobTest @Autowired constructor(
    private val jobOperator: JobOperator,
    @Qualifier(FlowJobConfig.JOB_NAME) private val flowJob: Job,
) {

    @Test
    fun `읽은 게 있으면 processStep 이 COMPLETED 로 끝나서 reportStep 으로 간다`() {
        val execution = run(inputCount = 30)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(stepNames(execution)).containsExactly("processStep", "reportStep")
        assertThat(processStep(execution).writeCount).isEqualTo(30)
    }

    @Test
    fun `읽은 게 없으면 리스너가 ExitStatus 를 NO_DATA 로 바꿔서 바로 끝난다`() {
        val execution = run(inputCount = 0)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(stepNames(execution)).containsExactly("processStep")
        val step = processStep(execution)
        assertThat(step.status).`as`("스텝 자체는 성공").isEqualTo(BatchStatus.COMPLETED)
        assertThat(step.exitStatus.exitCode).`as`("분기용 코드만 다름").isEqualTo(ExitStatusListener.NO_DATA)
    }

    @Test
    fun `chunk 스텝이 실패해도 FAILED 로 분기해서 처리하면 스텝은 ABANDONED, 잡은 COMPLETED 로 끝난다`() {
        val execution = run(inputCount = 30, failAt = 15)

        assertThat(stepNames(execution)).containsExactly("processStep", "alertStep")
        val step = processStep(execution)
        assertThat(step.writeCount).`as`("첫 청크(1~10)만 커밋").isEqualTo(10)
        assertThat(step.exitStatus.exitCode).isEqualTo("FAILED")
        // 실패 뒤에 flow 가 계속 진행되면 그 스텝은 ABANDONED 로 바뀐다 (재시작해도 다시 실행하지 않음)
        assertThat(step.status).isEqualTo(BatchStatus.ABANDONED)
        // 실패를 flow 가 "처리"했으므로 잡은 성공 → 같은 파라미터로 재시작할 수 없다
        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
    }

    // ------------------------------------------------------------------ helpers

    private fun run(inputCount: Long, failAt: Long? = null): JobExecution {
        val params = JobParametersBuilder().addLong("inputCount", inputCount)
        failAt?.let { params.addLong("failAt", it) }
        return jobOperator.start(flowJob, params.toJobParameters())
    }

    private fun processStep(execution: JobExecution): StepExecution =
        execution.stepExecutions.first { it.stepName == "${FlowJobConfig.JOB_NAME}.processStep" }

    /** "flowJob.processStep" → "processStep", 실행 순서대로 */
    private fun stepNames(execution: JobExecution): List<String> =
        execution.stepExecutions.sortedBy { it.id }.map { it.stepName.substringAfter("${FlowJobConfig.JOB_NAME}.") }
}
