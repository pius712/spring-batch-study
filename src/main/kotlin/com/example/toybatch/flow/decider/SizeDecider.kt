package com.example.toybatch.flow.decider

import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.flow.FlowExecutionStatus
import org.springframework.batch.core.job.flow.JobExecutionDecider
import org.springframework.batch.core.step.StepExecution

/**
 * 스텝 없이 "다음에 어디로 갈지"만 정하는 분기점. 바로 앞 스텝의 StepExecution 을 받는다.
 *
 * - 스텝이 아니라서 BATCH_STEP_EXECUTION 에 기록이 남지 않는다.
 * - 재시작하면 다시 평가된다 (COMPLETED 스텝처럼 건너뛰지 않음).
 * - 스텝의 ExitStatus 를 조작하지 않고 분기할 수 있어서, 분기 조건이 스텝의 성공/실패가 아닐 때 쓴다.
 */
class SizeDecider(private val largeThreshold: Long) : JobExecutionDecider {

    override fun decide(jobExecution: JobExecution, stepExecution: StepExecution?): FlowExecutionStatus {
        val writeCount = stepExecution!!.writeCount
        val status = if (writeCount >= largeThreshold) FlowExecutionStatus(LARGE) else FlowExecutionStatus(SMALL)
        log.info(">>> [sizeDecider] writeCount={}, threshold={} → {}", writeCount, largeThreshold, status.name)
        return status
    }

    companion object {
        const val LARGE = "LARGE"
        const val SMALL = "SMALL"
        private val log = LoggerFactory.getLogger(SizeDecider::class.java)
    }
}
