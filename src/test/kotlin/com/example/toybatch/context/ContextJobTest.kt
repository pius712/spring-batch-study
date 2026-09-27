package com.example.toybatch.context

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
 * docs/05-1-step-context.md 의 케이스를 재현한다. writeStep 은 1..30, chunk=10 → 청크 3개.
 */
@SpringBootTest(properties = [
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1",
])
class ContextJobTest @Autowired constructor(
    private val jobOperator: JobOperator,
    private val recorder: ContextRecorder,
    private val persisted: PersistedContextReader,
    @Qualifier(ContextJobConfig.JOB_NAME) private val contextJob: Job,
) {

    @Test
    fun `StepContribution 은 청크마다 따로 쌓이고, 청크가 끝나야 StepExecution 에 더해진다`() {
        val execution = run(1)

        // write 시점에 보이는 StepExecution.readCount 는 "앞 청크까지" 누적값
        assertThat(recorder.writes.map { it.stepExecutionReadCount }).containsExactly(0, 10, 20)

        val writeStep = step(execution, "writeStep")
        assertThat(writeStep.readCount).isEqualTo(30)
        assertThat(writeStep.writeCount).isEqualTo(30)
        assertThat(writeStep.commitCount).`as`("청크 1개 = 커밋 1번").isEqualTo(3)
    }

    @Test
    fun `Step EC 는 청크 커밋마다, Job EC 는 스텝이 끝날 때 각자의 테이블에 저장된다`() {
        val execution = run(2)

        // 각 청크를 쓰는 시점에 DB 에 저장돼 있던 값
        assertThat(recorder.writes.map { it.persistedPosition?.toString() }).`as`("직전 커밋까지 저장")
            .containsExactly(null, "10", "20")
        assertThat(recorder.writes.map { it.persistedLastItem?.toString() }).`as`("스텝 도중에는 저장 안 됨")
            .containsExactly(null, null, null)

        // 잡이 끝난 뒤
        val writeStep = step(execution, "writeStep")
        assertThat(persisted.stepContext(writeStep.id)[PositionReader.POSITION_KEY].toString()).isEqualTo("30")
        assertThat(persisted.jobContext(execution.id)[ContextObservingWriter.LAST_ITEM_KEY].toString()).isEqualTo("30")
        assertThat(persisted.jobContext(execution.id)).`as`("Step EC 값은 Job EC 에 없다")
            .doesNotContainKey(PositionReader.POSITION_KEY)
    }

    @Test
    fun `다음 스텝에서는 Job EC 만 보이고, 앞 스텝의 Step EC 는 안 보인다`() {
        run(3)
        val read = recorder.read!!

        assertThat(read.jobLastItem).isEqualTo(30)
        assertThat(read.ownPosition).`as`("Step EC 는 스텝마다 따로").isNull()
    }

    // ------------------------------------------------------------------ helpers

    private fun run(case: Long): JobExecution {
        recorder.clear()
        val execution = jobOperator.start(contextJob, JobParametersBuilder().addLong("case", case).toJobParameters())
        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        return execution
    }

    private fun step(execution: JobExecution, name: String): StepExecution =
        execution.stepExecutions.first { it.stepName == "${ContextJobConfig.JOB_NAME}.$name" }
}
