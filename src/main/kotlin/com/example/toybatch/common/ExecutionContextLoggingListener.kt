package com.example.toybatch.common

import org.slf4j.LoggerFactory
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.listener.ChunkListener
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.infrastructure.item.Chunk

/**
 * ExecutionContext 에 실제로 뭐가 들어있는지 눈으로 보기 위한 리스너.
 * (Spring Batch 가 내부적으로 넣는 batch.* 키들도 같이 보인다)
 *
 * 스텝마다 새로 만들어 붙이는 전제라 StepExecution 을 필드에 들고 있는다.
 */
class ExecutionContextLoggingListener : StepExecutionListener, ChunkListener<Any, Any> {

    private lateinit var stepExecution: StepExecution

    override fun beforeStep(stepExecution: StepExecution) {
        this.stepExecution = stepExecution
        log.info(">>> [beforeStep] {} (stepExecutionId={}, jobExecutionId={})",
            stepExecution.stepName, stepExecution.id, stepExecution.jobExecutionId)
        log.info("    jobExecutionContext  = {}", stepExecution.jobExecution.executionContext)
        log.info("    stepExecutionContext = {}", stepExecution.executionContext)
    }

    /**
     * Batch 6 의 ChunkOrientedStep 은 afterChunk 를 커밋 "전"에 부른다
     * (write → afterChunk → reader.update(ctx) → context 저장 → 커밋).
     * 그래서 다음 청크를 읽은 직후인 beforeChunk 에서 찍어야 "직전 커밋 때 DB 에 저장된 값"이 보인다.
     */
    override fun beforeChunk(chunk: Chunk<Any>) {
        log.info("    [beforeChunk] 직전 커밋에서 저장된 stepExecutionContext = {}", stepExecution.executionContext)
    }

    override fun afterStep(stepExecution: StepExecution): ExitStatus? {
        log.info(">>> [afterStep] {} status={} context={}",
            stepExecution.stepName, stepExecution.status, stepExecution.executionContext)
        return null
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExecutionContextLoggingListener::class.java)
    }
}
