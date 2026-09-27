package com.example.toybatch.context

import org.slf4j.LoggerFactory
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.listener.ChunkListener
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.infrastructure.item.Chunk

/**
 * 학습용 추적 리스너. 스텝/청크 경계마다 "메모리의 값" 과 "DB 에 저장된 값" 을 나란히 찍는다.
 *
 *   beforeStep  : 스텝 시작. Step EC 는 비어 있고(새 스텝), Job EC 는 앞 스텝 값이 들어 있다
 *   beforeChunk : 청크를 읽은 직후, process/write 전 (트랜잭션 안)
 *   afterChunk  : write 직후, 커밋 전 (Batch 6). 이번 청크 건수는 아직 StepExecution 에 안 더해졌다
 *   afterStep   : 스텝 끝. Job EC 는 아직 DB 에 안 들어갔다 (afterStep 다음에 저장된다)
 */
class ContextTraceListener(private val persisted: PersistedContextReader) :
    StepExecutionListener, ChunkListener<Int, Int> {

    private lateinit var stepExecution: StepExecution
    private var chunkNo = 0

    override fun beforeStep(stepExecution: StepExecution) {
        this.stepExecution = stepExecution
        chunkNo = 0
        log.info("")
        log.info("┌─ [beforeStep] {}  (stepExecutionId={}, jobExecutionId={})",
            stepExecution.stepName, stepExecution.id, stepExecution.jobExecutionId)
        trace("│  ")
    }

    override fun beforeChunk(chunk: Chunk<Int>) {
        chunkNo++
        log.info("├─ [beforeChunk #{}] 읽은 아이템 {}", chunkNo, chunk.items)
        trace("│  ")
    }

    override fun afterChunk(chunk: Chunk<Int>) {
        log.info("├─ [afterChunk  #{}] write 끝, 아직 커밋 전. 쓴 아이템 {}", chunkNo, chunk.items)
        trace("│  ")
    }

    override fun afterStep(stepExecution: StepExecution): ExitStatus? {
        log.info("└─ [afterStep] {} status={} (Job EC 는 이 다음에 DB 저장)", stepExecution.stepName, stepExecution.status)
        trace("   ")
        log.info("")
        return null
    }

    private fun trace(indent: String) {
        val se = stepExecution
        log.info("{}StepExecution   : read={}, write={}, commit={}, rollback={}",
            indent, se.readCount, se.writeCount, se.commitCount, se.rollbackCount)
        log.info("{}Step EC (메모리): {}", indent, userKeys(se.executionContext.toMap()))
        log.info("{}Step EC (DB)    : {}", indent, userKeys(persisted.stepContext(se.id)))
        log.info("{}Job  EC (메모리): {}", indent, userKeys(se.jobExecution.executionContext.toMap()))
        log.info("{}Job  EC (DB)    : {}", indent, userKeys(persisted.jobContext(se.jobExecutionId)))
    }

    /** batch.* 는 Spring Batch 가 넣는 내부 키라 빼고 본다 */
    private fun userKeys(map: Map<String, Any>) = map.filterKeys { !it.startsWith("batch.") }.toSortedMap()

    companion object {
        private val log = LoggerFactory.getLogger(ContextTraceListener::class.java)
    }
}
