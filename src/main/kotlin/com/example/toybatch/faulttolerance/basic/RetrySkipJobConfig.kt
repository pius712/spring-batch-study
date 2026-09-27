package com.example.toybatch.faulttolerance.basic

import com.example.toybatch.faulttolerance.fault.Fault
import com.example.toybatch.faulttolerance.fault.Faults
import com.example.toybatch.faulttolerance.fault.InvalidItemException
import com.example.toybatch.faulttolerance.fault.TransientException
import com.example.toybatch.faulttolerance.support.FaultToleranceSteps
import com.example.toybatch.faulttolerance.support.FaultToleranceSteps.Companion.retryTransient
import com.example.toybatch.faulttolerance.support.LoggingRetryListener
import org.springframework.batch.core.job.Job
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Retry + Skip 조합. 실무에서 가장 흔한 형태:
 *   - TransientException   : 2번 재시도, 그래도 안 되면 skip
 *   - InvalidItemException : 재시도 없이 바로 skip
 *
 *   process 4   Transient 2번 → 3번째 성공              → 저장됨
 *   process 7   Transient 계속 → 3번 시도 후 skip
 *   process 9   Invalid        → 1번 시도 후 바로 skip
 *   write   12  Transient 1번  → writer 재호출로 성공     → 저장됨 (writer 가 멱등이라 중복 없음)
 *   write   14  Invalid        → 재시도 없이 scan → 14 만 skip
 *
 *   결과: 20 - {7, 9, 14} = 17건, COMPLETED
 */
@Configuration
class RetrySkipJobConfig(private val steps: FaultToleranceSteps) {

    @Bean
    fun ftRetrySkipJob(): Job {
        val faults = Faults(
            process = listOf(Fault.transientTimes(4, 2), Fault.transientAlways(7), Fault.invalid(9)),
            write = listOf(Fault.transientTimes(12, 1), Fault.invalid(14)),
        )
        return steps.job(JOB_NAME, faults, idempotentWriter = true) {
            faultTolerant()
            retryPolicy(retryTransient(maxRetries = 2))
            retryListener(LoggingRetryListener())
            // retry 를 다 써도 실패하면 skip 정책을 본다. 그래서 Transient 도 skip 목록에 넣는다
            skip(TransientException::class.java, InvalidItemException::class.java)
            skipLimit(10)
            skipListener(steps.skipListener(JOB_NAME))
        }
    }

    companion object {
        const val JOB_NAME = "ftRetrySkipJob"
    }
}
