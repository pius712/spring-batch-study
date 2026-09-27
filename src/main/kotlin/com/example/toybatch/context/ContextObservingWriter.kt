package com.example.toybatch.context

import org.slf4j.LoggerFactory
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

/**
 * writer 는 write(chunk) 로 아이템만 받는다. StepExecution 이 필요하면 StepExecutionListener 로 받아둔다.
 * StepExecution 에서 Step EC / Job EC 둘 다 꺼낼 수 있다.
 *
 * 청크마다: 지금 보이는 누적 건수와 DB 에 저장된 값을 기록하고, Job EC 에 마지막 아이템을 쓴다.
 */
class ContextObservingWriter(
    private val recorder: ContextRecorder,
    private val persisted: PersistedContextReader,
) : ItemWriter<Int>, StepExecutionListener {

    private lateinit var stepExecution: StepExecution

    override fun beforeStep(stepExecution: StepExecution) {
        this.stepExecution = stepExecution
    }

    override fun write(chunk: Chunk<out Int>) {
        val jobExecution = stepExecution.jobExecution
        val observation = WriteObservation(
            items = chunk.items.toList(),
            stepExecutionReadCount = stepExecution.readCount,
            persistedPosition = persisted.stepContext(stepExecution.id)[PositionReader.POSITION_KEY],
            persistedLastItem = persisted.jobContext(jobExecution.id)[LAST_ITEM_KEY],
        )
        recorder.writes += observation
        log.info(">>> [writeStep] {}", observation)

        jobExecution.executionContext.putInt(LAST_ITEM_KEY, chunk.items.last()) // → BATCH_JOB_EXECUTION_CONTEXT (스텝 끝에 저장)
    }

    companion object {
        const val LAST_ITEM_KEY = "job.lastItem"
        private val log = LoggerFactory.getLogger(ContextObservingWriter::class.java)
    }
}
