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
import org.springframework.jdbc.core.JdbcTemplate

/**
 * docs/05-1-step-context.md 의 케이스를 재현한다. writeStep 은 1..30, chunk=10 → 청크 3개. readStep 은 1..3.
 *
 * 학습용이라 로그를 많이 찍는다. 테스트 하나 돌리고 로그를 위에서부터 따라 읽으면 된다.
 *   [writeStep reader] / [writeStep writer] / [readStep ...]  : 컴포넌트가 불린 순서와 받은 값
 *   ┌─ ├─ └─                                                   : ContextTraceListener. 메모리 EC 와 DB EC 비교
 *   ===== 결과 =====                                           : 잡이 끝난 뒤 StepExecution 과 BATCH_* 테이블 원본
 */
@SpringBootTest(properties = [
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1",
])
class ContextJobTest @Autowired constructor(
    private val jobOperator: JobOperator,
    private val recorder: ContextRecorder,
    private val persisted: PersistedContextReader,
    private val jdbcTemplate: JdbcTemplate,
    @Qualifier(ContextJobConfig.JOB_NAME) private val contextJob: Job,
) {

    @Test
    fun `StepContribution 은 청크마다 따로 쌓이고, 청크가 끝나야 StepExecution 에 더해진다`() {
        val execution = run(1, "StepContribution 은 청크마다 따로, 청크가 끝나야 StepExecution 에 더해진다")

        // write 시점에 보이는 StepExecution.readCount 는 "앞 청크까지" 누적값
        assertThat(recorder.writes.map { it.stepExecutionReadCount }).containsExactly(0, 10, 20)

        val writeStep = step(execution, "writeStep")
        assertThat(writeStep.readCount).isEqualTo(30)
        assertThat(writeStep.writeCount).isEqualTo(30)
        assertThat(writeStep.commitCount).`as`("청크 1개 = 커밋 1번").isEqualTo(3)
    }

    @Test
    fun `Step EC 는 청크 커밋마다, Job EC 는 스텝이 끝날 때 각자의 테이블에 저장된다`() {
        val execution = run(2, "Step EC 는 청크 커밋마다, Job EC 는 스텝이 끝날 때 DB 에 저장된다")

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

    // write step -> read step 으로 구성되어 있음.
    //
    @Test
    fun `같은 키라도 Step EC 는 스텝마다 따로 저장되고, 다음 스텝에는 Job EC 만 넘어간다`() {
        val execution = run(3, "같은 키(reader.position)라도 Step EC 는 스텝마다 따로, 다음 스텝에는 Job EC 만")
        val writeStep = step(execution, "writeStep")
        val readStep = step(execution, "readStep")

        // DB: 두 스텝이 같은 키(reader.position)를 쓰지만 BATCH_STEP_EXECUTION_CONTEXT 의 서로 다른 행에 각자 값이 있다
        assertThat(persisted.stepContext(writeStep.id)[PositionReader.POSITION_KEY].toString()).isEqualTo("30")
        assertThat(persisted.stepContext(readStep.id)[PositionReader.POSITION_KEY].toString())
            .isEqualTo(ContextReaderConfig.READ_STEP_COUNT.toString())

        // readStep 이 시작할 때 주입받은 값
        val read = recorder.read!!
        assertThat(read.jobLastItem).`as`("앞 스텝이 Job EC 에 넣은 값").isEqualTo(30)
        assertThat(read.ownPosition).`as`("앞 스텝 EC 에 같은 키가 30 으로 있지만 이 스텝 EC 는 비어 있다").isNull()
    }

    // ------------------------------------------------------------------ helpers

    private fun run(case: Long, scenario: String): JobExecution {
        recorder.clear()
        println("\n\n==================== [case $case] $scenario ====================")
        val execution = jobOperator.start(contextJob, JobParametersBuilder().addLong("case", case).toJobParameters())
        printResult(execution)
        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        return execution
    }

    /** 잡이 끝난 뒤: StepExecution 요약, writer 가 본 값, 그리고 DB 테이블 원본 */
    private fun printResult(execution: JobExecution) {
        println("\n===== 결과: jobExecutionId=${execution.id}, status=${execution.status} =====")
        println("----- StepExecution -----")
        execution.stepExecutions.sortedBy { it.id }.forEach {
            println("  ${it.stepName} (id=${it.id}) status=${it.status} read=${it.readCount} write=${it.writeCount} " +
                "commit=${it.commitCount} rollback=${it.rollbackCount}")
        }
        println("----- writeStep writer 가 청크마다 본 값 (write 시점) -----")
        recorder.writes.forEachIndexed { i, w ->
            println("  청크 #${i + 1} ${w.items.first()}..${w.items.last()} : stepExecution.readCount=${w.stepExecutionReadCount}, " +
                "DB Step EC reader.position=${w.persistedPosition}, DB Job EC job.lastItem=${w.persistedLastItem}")
        }
        println("----- readStep reader 가 주입받은 값 (스텝 시작 시점) -----")
        println("  ${recorder.read}")
        println("----- BATCH_JOB_EXECUTION_CONTEXT (이 잡 실행) -----")
        jdbcTemplate.queryForList(
            "SELECT JOB_EXECUTION_ID, SHORT_CONTEXT FROM BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID = ?", execution.id,
        ).forEach { println("  $it") }
        println("----- BATCH_STEP_EXECUTION + BATCH_STEP_EXECUTION_CONTEXT (이 잡 실행) -----")
        jdbcTemplate.queryForList("""
            SELECT s.STEP_EXECUTION_ID, s.STEP_NAME, s.READ_COUNT, s.WRITE_COUNT, s.COMMIT_COUNT, c.SHORT_CONTEXT
              FROM BATCH_STEP_EXECUTION s
              JOIN BATCH_STEP_EXECUTION_CONTEXT c ON c.STEP_EXECUTION_ID = s.STEP_EXECUTION_ID
             WHERE s.JOB_EXECUTION_ID = ?
             ORDER BY s.STEP_EXECUTION_ID
        """.trimIndent(), execution.id).forEach { println("  $it") }
        println()
    }

    private fun step(execution: JobExecution, name: String): StepExecution =
        execution.stepExecutions.first { it.stepName == "${ContextJobConfig.JOB_NAME}.$name" }
}
