package com.example.toybatch.flow.conditional

import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.step.StepExecution

/**
 * chunk 스텝이 끝난 뒤 읽은 건수를 보고 ExitStatus 를 바꾼다. → 잡의 flow 가 이 코드로 분기한다.
 *
 *   readCount == 0 : NO_DATA
 *   그 외          : 그대로 (COMPLETED / FAILED)
 *
 * chunk 스텝은 tasklet 처럼 contribution 을 직접 만질 곳이 없어서 afterStep 에서 바꾸는 게 정석이다.
 * BatchStatus(성공했나)는 그대로 두고 ExitStatus(분기용 코드)만 바꾼다.
 */
class ExitStatusListener : StepExecutionListener {

    override fun afterStep(stepExecution: StepExecution): ExitStatus {
        val exitStatus = when {
            stepExecution.status != BatchStatus.COMPLETED -> stepExecution.exitStatus // 실패는 FAILED 그대로
            stepExecution.readCount == 0L -> ExitStatus(NO_DATA)
            else -> stepExecution.exitStatus
        }
        log.info(">>> [{}] read={}, write={} → exitStatus={}", stepExecution.stepName,
            stepExecution.readCount, stepExecution.writeCount, exitStatus.exitCode)
        return exitStatus
    }

    companion object {
        const val NO_DATA = "NO_DATA"
        private val log = LoggerFactory.getLogger(ExitStatusListener::class.java)
    }
}
