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

    companion object {
        const val LAST_ITEM_KEY = "job.lastItem"
        private val log = LoggerFactory.getLogger(ContextObservingWriter::class.java)
    }

    private lateinit var stepExecution: StepExecution

    override fun beforeStep(stepExecution: StepExecution) {
        this.stepExecution = stepExecution
        log.info("    [writeStep writer] beforeStep: StepExecution 을 받아둠 (id={}) → write() 에서 Step EC / Job EC 를 꺼낼 수 있다",
            stepExecution.id)
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
        log.info("    [writeStep writer] write({})", observation.items)
        log.info("        stepExecution.readCount = {}  ← 앞 청크까지 누적 (이번 청크분은 청크가 끝나야 더해짐)", observation.stepExecutionReadCount)
        log.info("        DB Step EC reader.position = {}  ← 직전 커밋까지 저장된 값", observation.persistedPosition)
        log.info("        DB Job  EC job.lastItem    = {}  ← 스텝 도중에는 저장 안 됨", observation.persistedLastItem)

        // → BATCH_JOB_EXECUTION_CONTEXT (스텝 끝에 저장)
        // ❗ 저장 시점을 보여주려고 일부러 청크마다 쓴다. 실제로는 스텝이 끝날 때 결과만 써야 한다
        //    (청크 트랜잭션과 안 묶여서, 이 스텝이 실패하면 롤백된 청크에서 쓴 값까지 저장된다. 05-1 문서 5절)
        jobExecution.executionContext.putInt(LAST_ITEM_KEY, chunk.items.last())
        log.info("        Job EC (메모리) 에 {}={} 씀", LAST_ITEM_KEY, chunk.items.last())
    }


}
