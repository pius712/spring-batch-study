package com.example.toybatch.faulttolerance.writer

import com.example.toybatch.faulttolerance.basic.RetryJobConfig
import com.example.toybatch.faulttolerance.basic.SkipJobConfig
import com.example.toybatch.faulttolerance.fault.FaultInjectingReader
import com.example.toybatch.faulttolerance.support.FaultToleranceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus

/** docs/02-2-writer-fault-tolerance.md 의 질문을 순서대로 확인한다 */
class WriterFaultToleranceTest : FaultToleranceTestSupport() {

    // ================================================================ Q1
    @Test
    fun `Q1 재현 - write 재시도는 롤백 없이 write 를 다시 불러서 앞 item 이 중복 insert 된다`() {
        run(RetryJobConfig.WRITE_JOB)
        val counts = savedItems(RetryJobConfig.WRITE_JOB).groupingBy { it }.eachCount()
        assertThat(counts.filterValues { it > 1 }).isEqualTo(mapOf(11 to 2, 12 to 2))
    }

    @Test
    fun `Q1 재현 - 외부 전송도 재시도 횟수만큼 중복된다`() {
        run(WriterRetryJobConfig.RETRY_EXTERNAL_JOB)
        val sent = externalApi.sentCounts(WriterRetryJobConfig.RETRY_EXTERNAL_JOB)
        println("  sent = ${sent.filterKeys { it in 11..15 }}")
        assertThat(sent.filterValues { it > 1 }).isEqualTo(mapOf(11 to 2, 12 to 2))
    }

    @Test
    fun `Q1 해결 - 멱등 writer(MERGE)`() {
        run(RetryJobConfig.WRITE_IDEMPOTENT_JOB)
        assertThat(savedItems(RetryJobConfig.WRITE_IDEMPOTENT_JOB)).isEqualTo((1..20).toList())
    }

    @Test
    fun `Q1 해결 - savepoint 로 write 한 번을 원자적으로 만들면 재시도 전에 흔적이 지워진다`() {
        val execution = run(WriterRetryJobConfig.RETRY_WRITE_SAVEPOINT_JOB)
        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(attempts(WriterRetryJobConfig.RETRY_WRITE_SAVEPOINT_JOB, "write", 11)).isEqualTo(2) // 두 번 썼지만
        assertThat(savedItems(WriterRetryJobConfig.RETRY_WRITE_SAVEPOINT_JOB)).isEqualTo((1..20).toList()) // 한 줄 (INSERT writer 인데도)
    }

    @Test
    fun `Q1 함정 - item 마다 커밋 후 전송을 예약해도 재시도는 같은 트랜잭션이라 실패한 시도의 예약까지 나간다`() {
        run(WriterRetryJobConfig.RETRY_EXTERNAL_AFTER_COMMIT_JOB)
        assertThat(externalApi.sentCounts(WriterRetryJobConfig.RETRY_EXTERNAL_AFTER_COMMIT_JOB).filterValues { it > 1 })
            .isEqualTo(mapOf(11 to 2, 12 to 2))
    }

    @Test
    fun `Q1 해결 - write 가 끝까지 성공했을 때만 전송을 예약하면 한 번씩만 나간다`() {
        run(WriterRetryJobConfig.RETRY_EXTERNAL_ON_SUCCESS_JOB)
        val sent = externalApi.sentCounts(WriterRetryJobConfig.RETRY_EXTERNAL_ON_SUCCESS_JOB)
        assertThat(sent).hasSize(20)
        assertThat(sent.values).containsOnly(1)
    }

    // ================================================================ Q2
    @Test
    fun `Q2 - process 재시도 한도는 item 마다 따로 센다`() {
        val execution = run(WriterRetryJobConfig.RETRY_SCOPE_PROCESS_JOB)
        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        // 같은 청크 [11..15] 에서 12 는 1번, 14 는 2번 재시도 → 합치면 3번이지만 각자 한도(2) 안이라 성공
        assertThat(attempts(WriterRetryJobConfig.RETRY_SCOPE_PROCESS_JOB, "process", 12)).isEqualTo(2)
        assertThat(attempts(WriterRetryJobConfig.RETRY_SCOPE_PROCESS_JOB, "process", 14)).isEqualTo(3)
    }

    @Test
    fun `Q2 - write 재시도 한도는 write 호출, 즉 청크 하나 단위로 센다`() {
        val execution = run(WriterRetryJobConfig.RETRY_SCOPE_WRITE_JOB)
        // 청크 [11..15] 의 write 호출
        //   1번째: 11 ✔, 12 ✘                       ← 재시도 1
        //   2번째: 11 ✔, 12 ✔, 13 ✔, 14 ✘           ← 재시도 2
        //   3번째: 11 ✔, 12 ✔, 13 ✔, 14 ✘ → 한도(2) 소진
        // item 별로는 12 가 1번, 14 가 2번 실패했을 뿐인데도 청크 전체로 세서 실패한다
        assertThat(execution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(recorder.writeCalls(WriterRetryJobConfig.RETRY_SCOPE_WRITE_JOB)).isEqualTo(listOf(5, 5, 5, 5, 5)) // 청크1, 청크2, 청크3 ×3
        assertThat(attempts(WriterRetryJobConfig.RETRY_SCOPE_WRITE_JOB, "write", 14)).isEqualTo(2)
    }

    @Test
    fun `Q2 - 같은 상황에서 maxRetries 를 3 으로 올리면 성공한다`() {
        val execution = run(WriterRetryJobConfig.RETRY_SCOPE_WRITE3_JOB)
        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(recorder.writeCalls(WriterRetryJobConfig.RETRY_SCOPE_WRITE3_JOB)).hasSize(3 + 4) // 청크3 만 4번 호출
    }

    // ================================================================ Q3
    @Test
    fun `Q3 - skip 한도는 item 단위이고 스텝 전체에서 누적된다`() {
        val execution = run(WriterSkipJobConfig.SKIP_SCOPE_JOB)
        // 청크 [1..5] 에서 2, 4 skip (누적 2) → 커밋
        // 청크 [6..10] 에서 7 skip (누적 3), 9 → 누적 4 > 한도 3 → 청크 롤백, FAILED
        assertThat(execution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(savedItems(WriterSkipJobConfig.SKIP_SCOPE_JOB)).isEqualTo(listOf(1, 3, 5))
        assertThat(recorder.skips(WriterSkipJobConfig.SKIP_SCOPE_JOB)).containsExactly("process:2", "process:4", "process:7")
    }

    // ================================================================ Q4
    @Test
    fun `Q4-1 write 에서 skip 이 나면 청크는 롤백된다 (재시도와 다르다)`() {
        run(SkipJobConfig.SKIP_JOB)
        // INSERT writer 인데도 중복이 없다 = 첫 write 에서 들어간 11, 12 가 롤백됐다는 뜻
        assertThat(attempts(SkipJobConfig.SKIP_JOB, "write", 11)).isEqualTo(2)
        assertThat(savedItems(SkipJobConfig.SKIP_JOB).groupingBy { it }.eachCount().values).containsOnly(1)
    }

    @Test
    fun `Q4-2 100건 청크에서 4건이 잘못되면 scan 으로 write 가 101번 불린다`() {
        val execution = run(WriterSkipJobConfig.SCAN_IO_JOB)
        val step = execution.stepExecutions.single()

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        val calls = recorder.writeCalls(WriterSkipJobConfig.SCAN_IO_JOB)
        assertThat(calls).hasSize(101)          // 청크 write 1번(실패) + 1건짜리 write 100번
        assertThat(calls.first()).isEqualTo(100)
        assertThat(calls.drop(1)).containsOnly(1)
        assertThat(step.writeSkipCount).isEqualTo(4)
        assertThat(savedItems(WriterSkipJobConfig.SCAN_IO_JOB)).hasSize(96)
        println("  write 호출 ${calls.size}번, commit=${step.commitCount}, rollback=${step.rollbackCount}")
    }

    @Test
    fun `Q4-3 같은 청크에서 skipLimit 3 이면 scan 도중 4번째에서 멈추고, 그 앞은 이미 커밋돼 있다`() {
        val execution = run(WriterSkipJobConfig.SCAN_SKIP_LIMIT_JOB)

        // scan 은 1건씩 각자 트랜잭션: 1~9 커밋, 10 skip(1), ..., 30 skip(2), ..., 50 skip(3), ..., 69 커밋, 70 → 한도 초과
        assertThat(execution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(savedItems(WriterSkipJobConfig.SCAN_SKIP_LIMIT_JOB)).isEqualTo((1..69).toList() - listOf(10, 30, 50))
        assertThat(execution.stepExecutions.single().writeSkipCount).isEqualTo(3)
    }

    @Test
    fun `Q4-3 함정 - 그 상태로 재시작하면 70 ~ 100 은 영영 처리되지 않는다`() {
        val params = newParams()
        val first = run(WriterSkipJobConfig.SCAN_SKIP_LIMIT_JOB, params)
        // scan 트랜잭션도 커밋할 때마다 reader 위치를 저장한다. reader 는 이미 100 까지 읽어둔 상태
        assertThat(first.stepExecutions.single().executionContext.getInt(FaultInjectingReader.KEY)).isEqualTo(100)

        val second = run(WriterSkipJobConfig.SCAN_SKIP_LIMIT_JOB, params)

        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED) // ❗ 에러 없이 끝나지만
        assertThat(second.stepExecutions.single().readCount).isZero() // 한 건도 안 읽었다
        val missing = (1..100).filterNot { it in savedItems(WriterSkipJobConfig.SCAN_SKIP_LIMIT_JOB) }
        assertThat(missing).containsAll((70..100).toList())
        println("  ❗ 재시작 후에도 처리 안 된 item (${missing.size}건) = $missing")
    }

    @Test
    fun `Q4-4 skip 대상이 아닌 예외는 scan 없이 청크 롤백 후 FAILED`() {
        val execution = run(WriterSkipJobConfig.WRITE_NON_SKIPPABLE_JOB)
        assertThat(execution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(savedItems(WriterSkipJobConfig.WRITE_NON_SKIPPABLE_JOB)).isEqualTo((1..10).toList()) // 11, 12 insert 도 롤백
        assertThat(attempts(WriterSkipJobConfig.WRITE_NON_SKIPPABLE_JOB, "write", 11)).isEqualTo(1)     // scan 안 함
        assertThat(recorder.writeCalls(WriterSkipJobConfig.WRITE_NON_SKIPPABLE_JOB)).isEqualTo(listOf(5, 5, 5))
    }

    @Test
    fun `Q4-5 재현 - scan 은 DB 는 롤백해주지만 외부 전송은 중복된다`() {
        run(WriterSkipJobConfig.SCAN_EXTERNAL_JOB)
        val sent = externalApi.sentCounts(WriterSkipJobConfig.SCAN_EXTERNAL_JOB)
        println("  sent = ${sent.filterKeys { it in 11..15 }}")
        assertThat(sent.filterValues { it > 1 }).isEqualTo(mapOf(11 to 2, 12 to 2))
        assertThat(sent).doesNotContainKey(13)
    }

    @Test
    fun `Q4-5 해결 - 커밋 후 전송하면 롤백된 시도는 전송되지 않는다`() {
        run(WriterSkipJobConfig.SCAN_EXTERNAL_AFTER_COMMIT_JOB)
        val sent = externalApi.sentCounts(WriterSkipJobConfig.SCAN_EXTERNAL_AFTER_COMMIT_JOB)
        assertThat(sent).hasSize(19).doesNotContainKey(13)
        assertThat(sent.values).containsOnly(1)
    }

    @Test
    fun `Q4-5 성공 후 1번 예약 writer 도 scan 에서 건별로 한 번씩 전송된다`() {
        run(WriterSkipJobConfig.SCAN_EXTERNAL_ON_SUCCESS_JOB)
        val jobName = WriterSkipJobConfig.SCAN_EXTERNAL_ON_SUCCESS_JOB
        val sent = externalApi.sentCounts(jobName)
        println("  sent = ${sent.filterKeys { it in 11..15 }}, writeCalls = ${recorder.writeCalls(jobName)}")
        assertThat(sent).hasSize(19).doesNotContainKey(13)
        assertThat(sent.values).containsOnly(1)
    }

    @Test
    fun `Q4-5 retry 와 skip 을 같이 걸어도 성공 후 1번 예약이면 한 번씩 전송된다`() {
        val execution = run(WriterSkipJobConfig.RETRY_SCAN_EXTERNAL_ON_SUCCESS_JOB)
        val jobName = WriterSkipJobConfig.RETRY_SCAN_EXTERNAL_ON_SUCCESS_JOB
        val sent = externalApi.sentCounts(jobName)
        println("  sent = ${sent.filterKeys { it in 11..15 }}, writeCalls = ${recorder.writeCalls(jobName)}, " +
            "write attempts 11 = ${recorder.attempts(jobName, "write", 11)}, 13 = ${recorder.attempts(jobName, "write", 13)}")
        println("  step = ${execution.stepExecutions.single()}")
        assertThat(sent).hasSize(19).doesNotContainKey(13)
        assertThat(sent.values).containsOnly(1)
    }
}
