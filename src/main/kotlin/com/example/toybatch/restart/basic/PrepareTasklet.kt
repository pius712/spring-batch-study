package com.example.toybatch.restart.basic

import org.slf4j.LoggerFactory
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.StepContribution
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus

/**
 * step1. "처리할 총 건수"를 계산해서 Job ExecutionContext 에 넣는다.
 *
 * - Job ExecutionContext 는 스텝 간 공유 저장소이자, 재시작 시 복구되는 잡 단위 상태다.
 * - 재시작하면 이 스텝은 이미 COMPLETED 라서 다시 실행되지 않는다.
 *   그런데도 step2 는 totalCount 를 읽을 수 있다 → DB(BATCH_JOB_EXECUTION_CONTEXT)에서 복구되기 때문.
 */
class PrepareTasklet(private val totalCount: Int) : Tasklet {

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val jobContext = chunkContext.stepContext.stepExecution.jobExecution.executionContext
        jobContext.putInt(TOTAL_COUNT_KEY, totalCount)
        log.info(">>> [prepareStep] 실행됨. jobExecutionContext 에 {}={} 저장", TOTAL_COUNT_KEY, totalCount)
        return RepeatStatus.FINISHED
    }

    companion object {
        const val TOTAL_COUNT_KEY = "totalCount"
        private val log = LoggerFactory.getLogger(PrepareTasklet::class.java)
    }
}
