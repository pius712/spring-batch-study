package com.example.toybatch.faulttolerance.basic

import com.example.toybatch.faulttolerance.fault.Fault
import com.example.toybatch.faulttolerance.fault.Faults
import com.example.toybatch.faulttolerance.support.FaultToleranceSteps
import com.example.toybatch.faulttolerance.support.FaultToleranceSteps.Companion.retryTransient
import com.example.toybatch.faulttolerance.support.LoggingRetryListener
import org.springframework.batch.core.job.Job
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Retry 예제. 전부 "TransientException 만 최대 2번 재시도(총 3번 시도)", skip 없음.
 *
 *   ftRetryProcessJob          process 7 이 2번 실패 → 3번째 성공                     ✅ 20건
 *   ftRetryExhaustedJob        process 7 이 계속 실패 → 재시도 소진                    ❌ FAILED
 *   ftRetryNotRetryableJob     process 7 이 InvalidItemException 1번 → retry 대상 아님 ❌ 1번만 시도하고 FAILED
 *   ftRetryWriteJob            write 13 이 1번 실패 → writer 를 그 자리에서 다시 호출   ❗ 11, 12 가 두 번 insert
 *   ftRetryWriteIdempotentJob  위와 같은데 writer 가 MERGE(멱등)                    ✅ 20건
 */
@Configuration
class RetryJobConfig(private val steps: FaultToleranceSteps) {

    @Bean
    fun ftRetryProcessJob(): Job =
        retryJob(PROCESS_JOB,
            Faults(process = listOf(Fault.transientTimes(7, 2))))

    @Bean
    fun ftRetryExhaustedJob(): Job =
        retryJob(EXHAUSTED_JOB,
            Faults(process = listOf(Fault.transientAlways(7))))

    /** failTimes=1 이라 한 번만 더 하면 성공하지만, InvalidItemException 은 retry 대상이 아니라 다시 안 한다 */
    @Bean
    fun ftRetryNotRetryableJob(): Job =
        retryJob(NOT_RETRYABLE_JOB, Faults(process = listOf(Fault.invalid(7, failTimes = 1))))

    @Bean
    fun ftRetryWriteJob(): Job =
        retryJob(WRITE_JOB, Faults(write = listOf(Fault.transientTimes(13, 1))))

    @Bean
    fun ftRetryWriteIdempotentJob(): Job =
        retryJob(WRITE_IDEMPOTENT_JOB, Faults(write = listOf(Fault.transientTimes(13, 1))), idempotentWriter = true)

    private fun retryJob(jobName: String, faults: Faults, idempotentWriter: Boolean = false): Job =
        steps.job(jobName, faults, idempotentWriter) {
            faultTolerant() // ❗ Batch 6 에서는 이걸 빼면 retry/skip 설정이 전부 무시된다
            retryPolicy(retryTransient(maxRetries = 2))
            retryListener(LoggingRetryListener())
        }

    companion object {
        const val PROCESS_JOB = "ftRetryProcessJob"
        const val EXHAUSTED_JOB = "ftRetryExhaustedJob"
        const val NOT_RETRYABLE_JOB = "ftRetryNotRetryableJob"
        const val WRITE_JOB = "ftRetryWriteJob"
        const val WRITE_IDEMPOTENT_JOB = "ftRetryWriteIdempotentJob"
    }
}
