package com.example.toybatch.faulttolerance.writer

import com.example.toybatch.faulttolerance.fault.Fault
import com.example.toybatch.faulttolerance.fault.Faults
import com.example.toybatch.faulttolerance.fault.InvalidItemException
import com.example.toybatch.faulttolerance.fault.TransientException
import com.example.toybatch.faulttolerance.support.AttemptRecorder
import com.example.toybatch.faulttolerance.support.FakeExternalApi
import com.example.toybatch.faulttolerance.support.FaultToleranceSteps
import com.example.toybatch.faulttolerance.support.FaultToleranceSteps.Companion.retryTransient
import com.example.toybatch.faulttolerance.writer.ExternalSendWriter.SendMode
import org.springframework.batch.core.job.Job
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 02-2 의 Q3, Q4 — skip 한도와 writer 에서의 skip(scan). 기본: reader 1~20, chunk 5.
 * (Q4-1 의 "skip 은 롤백된다" 는 basic.SkipJobConfig 의 ftSkipJob 을 같이 쓴다)
 *
 *  Q3. skip 한도는 item 단위? chunk 단위?
 *   ftSkipScopeJob               process 2, 4 | 7, 9 Invalid, skipLimit=3  ❌ 4번째(9)에서 FAILED (스텝 전체 누적, item 단위)
 *
 *  Q4. writer 에서 skip
 *   ftScanIoJob                  100건 / chunk 100 / write 10,30,50,70 Invalid, skipLimit=10  → write 호출 101번
 *   ftScanSkipLimitJob           위와 같은데 skipLimit=3, reader 상태 저장                      ❗ 재시작해도 70~100 처리 안 됨
 *   ftWriteNonSkippableJob       skip 대상이 아닌 예외 → scan 없이 청크 롤백, FAILED
 *   ftScanExternalJob            write 13 Invalid, 외부 전송 writer                             ❗ 11, 12 두 번 전송
 *   ftScanExternalAfterCommitJob 위와 같은데 item 마다 커밋 후 전송 예약                         ✅ 한 번씩 (롤백되면 예약도 버려짐)
 *   ftScanExternalOnSuccessJob   위와 같은데 write() 가 성공했을 때만 청크 전체 전송 예약           ✅ 한 번씩 (scan 은 1건짜리 write 라 건별로 예약)
 *   ftRetryScanExternalOnSuccessJob  write 13 이 계속 Transient, retry 2번 → 소진 → skip → scan    ✅ 한 번씩
 */
@Configuration
class WriterSkipJobConfig(
    private val steps: FaultToleranceSteps,
    private val recorder: AttemptRecorder,
    private val externalApi: FakeExternalApi,
) {

    // ================================================================ Q3. skip 한도 단위

    @Bean
    fun ftSkipScopeJob(): Job =
        steps.job(SKIP_SCOPE_JOB, Faults(process = listOf(2, 4, 7, 9).map { Fault.invalid(it) })) {
            faultTolerant()
            skip(InvalidItemException::class.java)
            skipLimit(3)
            skipListener(steps.skipListener(SKIP_SCOPE_JOB))
        }

    // ================================================================ Q4. writer 에서 skip

    @Bean
    fun ftScanIoJob(): Job =
        steps.job(SCAN_IO_JOB, Faults(write = BAD_OF_100), totalCount = 100, chunkSize = 100) {
            faultTolerant()
            skip(InvalidItemException::class.java)
            skipLimit(10)
        }

    @Bean
    fun ftScanSkipLimitJob(): Job =
        steps.job(SCAN_SKIP_LIMIT_JOB, Faults(write = BAD_OF_100), totalCount = 100, chunkSize = 100, readerSaveState = true) {
            faultTolerant()
            skip(InvalidItemException::class.java)
            skipLimit(3)
        }

    @Bean
    fun ftWriteNonSkippableJob(): Job =
        steps.job(WRITE_NON_SKIPPABLE_JOB, Faults(write = listOf(Fault.transientAlways(13)))) {
            faultTolerant()
            skip(InvalidItemException::class.java) // TransientException 은 skip 대상 아님, retry 도 없음
        }

    @Bean
    fun ftScanExternalJob(): Job = externalJob(SCAN_EXTERNAL_JOB, SendMode.IMMEDIATE)

    @Bean
    fun ftScanExternalAfterCommitJob(): Job = externalJob(SCAN_EXTERNAL_AFTER_COMMIT_JOB, SendMode.AFTER_COMMIT_PER_ITEM)

    @Bean
    fun ftScanExternalOnSuccessJob(): Job = externalJob(SCAN_EXTERNAL_ON_SUCCESS_JOB, SendMode.AFTER_COMMIT_ON_SUCCESS)

    /** retry 와 skip 을 같이 건 경우: 청크 write 를 같은 트랜잭션에서 3번 시도 → 소진 → 롤백 → scan */
    @Bean
    fun ftRetryScanExternalOnSuccessJob(): Job {
        val faults = Faults(write = listOf(Fault.transientAlways(13)))
        val writer = ExternalSendWriter(RETRY_SCAN_EXTERNAL_ON_SUCCESS_JOB, externalApi, recorder,
            SendMode.AFTER_COMMIT_ON_SUCCESS, faults.write)
        return steps.job(RETRY_SCAN_EXTERNAL_ON_SUCCESS_JOB, faults, writer = writer) {
            faultTolerant()
            retryPolicy(retryTransient(maxRetries = 2))
            skip(TransientException::class.java)
        }
    }

    // ================================================================

    /** write 13 이 잘못된 데이터 → skip → scan. 외부 전송 시점만 다르다 */
    private fun externalJob(jobName: String, mode: SendMode): Job {
        val faults = Faults(write = listOf(Fault.invalid(13)))
        return steps.job(jobName, faults, writer = ExternalSendWriter(jobName, externalApi, recorder, mode, faults.write)) {
            faultTolerant()
            skip(InvalidItemException::class.java)
        }
    }

    companion object {
        const val SKIP_SCOPE_JOB = "ftSkipScopeJob"
        const val SCAN_IO_JOB = "ftScanIoJob"
        const val SCAN_SKIP_LIMIT_JOB = "ftScanSkipLimitJob"
        const val WRITE_NON_SKIPPABLE_JOB = "ftWriteNonSkippableJob"
        const val SCAN_EXTERNAL_JOB = "ftScanExternalJob"
        const val SCAN_EXTERNAL_AFTER_COMMIT_JOB = "ftScanExternalAfterCommitJob"
        const val SCAN_EXTERNAL_ON_SUCCESS_JOB = "ftScanExternalOnSuccessJob"
        const val RETRY_SCAN_EXTERNAL_ON_SUCCESS_JOB = "ftRetryScanExternalOnSuccessJob"

        /** 100건 중 잘못된 데이터 4건 */
        val BAD_OF_100 = listOf(10, 30, 50, 70).map { Fault.invalid(it) }
    }
}
