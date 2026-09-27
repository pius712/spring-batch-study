package com.example.toybatch.faulttolerance.writer

import com.example.toybatch.faulttolerance.fault.Fault
import com.example.toybatch.faulttolerance.fault.FaultInjectingWriter
import com.example.toybatch.faulttolerance.fault.Faults
import com.example.toybatch.faulttolerance.support.AttemptRecorder
import com.example.toybatch.faulttolerance.support.FakeExternalApi
import com.example.toybatch.faulttolerance.support.FaultToleranceSteps
import com.example.toybatch.faulttolerance.support.FaultToleranceSteps.Companion.retryTransient
import com.example.toybatch.faulttolerance.writer.ExternalSendWriter.SendMode
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * 02-2 의 Q1, Q2 — writer 에서의 retry. 기본: reader 1~20, chunk 5.
 * (Q1 의 중복 / 멱등 기본 케이스는 basic.RetryJobConfig 의 ftRetryWriteJob, ftRetryWriteIdempotentJob 을 같이 쓴다)
 *
 *  Q1. write 재시도가 롤백 없이 일어나서 생기는 중복
 *   ftRetryWriteSavepointJob       write 13 Transient 1번, INSERT writer 를 savepoint 로 감쌈   ✅ 중복 없음
 *   ftRetryExternalJob             write 13 Transient 1번, 외부 전송 writer                     ❗ 11, 12 두 번 전송
 *   ftRetryExternalAfterCommitJob  위와 같은데 item 마다 커밋 후 전송 예약                       ❗ 그래도 두 번 (같은 트랜잭션이라 예약이 남음)
 *   ftRetryExternalOnSuccessJob    write() 가 끝까지 성공했을 때만 전송 예약                     ✅ 한 번씩
 *
 *  Q2. retry 한도는 item 단위? chunk 단위?
 *   ftRetryScopeProcessJob         process 12 ×1, 14 ×2, maxRetries=2   ✅ (item 마다 따로 센다)
 *   ftRetryScopeWriteJob           write   12 ×1, 14 ×2, maxRetries=2   ❌ (write 호출 = 청크 하나로 센다)
 *   ftRetryScopeWrite3Job          write   12 ×1, 14 ×2, maxRetries=3   ✅
 */
@Configuration
class WriterRetryJobConfig(
    private val steps: FaultToleranceSteps,
    private val jdbcTemplate: JdbcTemplate,
    private val dataSource: DataSource,
    private val recorder: AttemptRecorder,
    private val externalApi: FakeExternalApi,
) {

    // ================================================================ Q1. write 재시도 중복과 해결

    @Bean
    fun ftRetryWriteSavepointJob(): Job {
        val faults = Faults(write = listOf(Fault.transientTimes(13, 1)))
        val insertWriter = FaultInjectingWriter(RETRY_WRITE_SAVEPOINT_JOB, jdbcTemplate, recorder, idempotent = false, faults.write)
        return steps.job(RETRY_WRITE_SAVEPOINT_JOB, faults,
            writer = SavepointItemWriter(
                insertWriter, dataSource)) {
            faultTolerant()
            retryPolicy(retryTransient(maxRetries = 2))
        }
    }

    @Bean
    fun ftRetryExternalJob(): Job = externalJob(RETRY_EXTERNAL_JOB, SendMode.IMMEDIATE)

    @Bean
    fun ftRetryExternalAfterCommitJob(): Job =
        externalJob(RETRY_EXTERNAL_AFTER_COMMIT_JOB, SendMode.AFTER_COMMIT_PER_ITEM)

    @Bean
    fun ftRetryExternalOnSuccessJob(): Job = externalJob(RETRY_EXTERNAL_ON_SUCCESS_JOB, SendMode.AFTER_COMMIT_ON_SUCCESS)

    // ================================================================ Q2. retry 한도 단위

    @Bean
    fun ftRetryScopeProcessJob(): Job =
        steps.job(RETRY_SCOPE_PROCESS_JOB, Faults(process = TWO_FAILURES_IN_ONE_CHUNK)) {
            faultTolerant()
            retryPolicy(retryTransient(maxRetries = 2))
        }

    @Bean
    fun ftRetryScopeWriteJob(): Job =
        steps.job(RETRY_SCOPE_WRITE_JOB, Faults(write = TWO_FAILURES_IN_ONE_CHUNK), idempotentWriter = true) {
            faultTolerant()
            retryPolicy(retryTransient(maxRetries = 2))
        }

    @Bean
    fun ftRetryScopeWrite3Job(): Job =
        steps.job(RETRY_SCOPE_WRITE3_JOB, Faults(write = TWO_FAILURES_IN_ONE_CHUNK), idempotentWriter = true) {
            faultTolerant()
            retryPolicy(retryTransient(maxRetries = 3))
        }

    // ================================================================

    /** write 13 이 1번 Transient 실패, 최대 2번 재시도. 외부 전송 시점만 다르다 */
    private fun externalJob(jobName: String, mode: SendMode): Job {
        val faults = Faults(write = listOf(Fault.transientTimes(13, 1)))
        return steps.job(jobName, faults, writer = ExternalSendWriter(jobName, externalApi, recorder, mode, faults.write)) {
            faultTolerant()
            retryPolicy(retryTransient(maxRetries = 2))
        }
    }

    companion object {
        const val RETRY_WRITE_SAVEPOINT_JOB = "ftRetryWriteSavepointJob"
        const val RETRY_EXTERNAL_JOB = "ftRetryExternalJob"
        const val RETRY_EXTERNAL_AFTER_COMMIT_JOB = "ftRetryExternalAfterCommitJob"
        const val RETRY_EXTERNAL_ON_SUCCESS_JOB = "ftRetryExternalOnSuccessJob"
        const val RETRY_SCOPE_PROCESS_JOB = "ftRetryScopeProcessJob"
        const val RETRY_SCOPE_WRITE_JOB = "ftRetryScopeWriteJob"
        const val RETRY_SCOPE_WRITE3_JOB = "ftRetryScopeWrite3Job"

        /** 같은 청크 [11..15] 안에서 12 는 1번, 14 는 2번 Transient 실패 */
        private val TWO_FAILURES_IN_ONE_CHUNK = listOf(Fault.transientTimes(12, 1), Fault.transientTimes(14, 2))
    }
}
