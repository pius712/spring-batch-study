package com.example.toybatch.context

import com.example.toybatch.common.FailureInjector
import org.slf4j.LoggerFactory
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamWriter
import org.springframework.batch.infrastructure.item.ItemWriter

/**
 * ✅ 잘 쓴 예: 스텝 안의 누적값은 Step EC 에 둔다 (ItemStream).
 *
 *   open(ec)   : 재시작이면 ec 에 "직전 커밋까지의 합계" 가 있다 → 거기서 이어간다
 *   write      : 메모리의 합계에 더한다
 *   update(ec) : 청크 커밋 직전에 합계를 ec 에 쓴다 → 청크와 같은 트랜잭션으로 저장
 *                청크가 롤백되면 update 가 안 불리거나 같이 롤백돼서 DB 의 합계도 그 청크 전 값으로 남는다
 *
 * 스텝이 끝나면 ExecutionContextPromotionListener 가 이 키를 Job EC 로 옮긴다 (COMPLETED 일 때만).
 */
class StepContextSumWriter(private val failureInjector: FailureInjector) : ItemStreamWriter<Int> {

    private var total = 0L

    override fun open(executionContext: ExecutionContext) {
        total = executionContext.getLong(TOTAL_KEY, 0L)
        log.info("    [sumStep writer ✅] open(ec)   : {}={} 부터 이어서 더한다", TOTAL_KEY, total)
    }

    override fun write(chunk: Chunk<out Int>) {
        total += chunk.items.sum()
        log.info("    [sumStep writer ✅] write({}..{}) : 메모리 합계 = {}", chunk.items.first(), chunk.items.last(), total)
        chunk.items.forEach { failureInjector.check(it) } // 더한 "뒤에" 같은 청크에서 실패할 수 있다
    }

    override fun update(executionContext: ExecutionContext) {
        executionContext.putLong(TOTAL_KEY, total)
        log.info("    [sumStep writer ✅] update(ec) : Step EC 에 {}={} (청크 커밋 직전 + 스텝 끝)", TOTAL_KEY, total)
    }

    companion object {
        /** 만든 스텝 이름을 붙인 키. Job EC 에서 다른 스텝 키와 겹치지 않게 */
        const val TOTAL_KEY = "sumStep.total"
        private val log = LoggerFactory.getLogger(StepContextSumWriter::class.java)
    }
}

/**
 * ❌ 비교용 나쁜 예: 청크마다 Job EC 에 바로 누적한다.
 * Job EC 는 청크 트랜잭션과 안 묶이고 스텝이 끝날 때(실패해도) 저장된다 → 롤백된 청크 몫이 남고, 재시작 때 또 더해진다.
 */
class JobContextSumWriter(private val failureInjector: FailureInjector) : ItemWriter<Int>, StepExecutionListener {

    private lateinit var stepExecution: StepExecution

    override fun beforeStep(stepExecution: StepExecution) {
        this.stepExecution = stepExecution
        log.info("    [sumStep writer ❌] beforeStep : Job EC 에서 복구된 {}={}",
            StepContextSumWriter.TOTAL_KEY, stepExecution.jobExecution.executionContext.getLong(StepContextSumWriter.TOTAL_KEY, 0L))
    }

    override fun write(chunk: Chunk<out Int>) {
        val jobContext = stepExecution.jobExecution.executionContext
        val total = jobContext.getLong(StepContextSumWriter.TOTAL_KEY, 0L) + chunk.items.sum()
        jobContext.putLong(StepContextSumWriter.TOTAL_KEY, total)
        log.info("    [sumStep writer ❌] write({}..{}) : Job EC 합계 = {}", chunk.items.first(), chunk.items.last(), total)
        chunk.items.forEach { failureInjector.check(it) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(JobContextSumWriter::class.java)
    }
}
