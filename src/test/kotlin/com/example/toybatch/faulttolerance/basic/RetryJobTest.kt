package com.example.toybatch.faulttolerance.basic

import com.example.toybatch.faulttolerance.support.FaultToleranceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus

class RetryJobTest : FaultToleranceTestSupport() {

    @Test
    fun `일시적 오류는 재시도해서 성공하면 아무 일 없던 것처럼 끝난다`() {
        val execution = run(RetryJobConfig.PROCESS_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(attempts(RetryJobConfig.PROCESS_JOB, "process", 7)).isEqualTo(3) // 실패 2번 + 성공 1번
        assertThat(savedItems(RetryJobConfig.PROCESS_JOB)).isEqualTo((1..20).toList())
        // processor 재시도는 그 자리에서 다시 호출할 뿐, 청크를 롤백하지 않는다
        assertThat(execution.stepExecutions.single().rollbackCount).isZero()
    }

    @Test
    fun `재시도를 다 써도 실패하면 skip 설정이 없으므로 스텝이 FAILED 된다`() {
        val execution = run(RetryJobConfig.EXHAUSTED_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(attempts(RetryJobConfig.EXHAUSTED_JOB, "process", 7)).isEqualTo(3) // 최초 1 + 재시도 2
        // 1~5 청크만 커밋. 6~10 청크는 롤백
        assertThat(savedItems(RetryJobConfig.EXHAUSTED_JOB)).isEqualTo((1..5).toList())
    }

    @Test
    fun `retry 대상이 아닌 예외는 한 번만 시도하고 바로 실패한다`() {
        val execution = run(RetryJobConfig.NOT_RETRYABLE_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.FAILED)
        // 한 번만 더 하면 성공할 상황이었지만 InvalidItemException 은 includes 에 없어서 재시도 안 함
        assertThat(attempts(RetryJobConfig.NOT_RETRYABLE_JOB, "process", 7)).isEqualTo(1)
        assertThat(savedItems(RetryJobConfig.NOT_RETRYABLE_JOB)).isEqualTo((1..5).toList())
    }

    @Test
    fun `writer 재시도는 롤백 없이 청크 전체를 다시 쓰므로 멱등하지 않으면 중복이 생긴다`() {
        val execution = run(RetryJobConfig.WRITE_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        // 청크 [11..15]: 11, 12 insert 후 13 에서 실패 → 같은 트랜잭션에서 write([11..15]) 재호출
        // → 11, 12 가 한 번 더 insert 되고 그대로 커밋 ❗
        val saved = savedItems(RetryJobConfig.WRITE_JOB)
        assertThat(saved).hasSize(22)
        assertThat(saved.groupingBy { it }.eachCount().filterValues { it > 1 }.keys).containsExactly(11, 12)
        println("  ❗ 중복 저장된 item = ${saved.groupingBy { it }.eachCount().filterValues { it > 1 }}")
    }

    @Test
    fun `writer가 멱등하면 재시도해도 중복이 없다`() {
        val execution = run(RetryJobConfig.WRITE_IDEMPOTENT_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(attempts(RetryJobConfig.WRITE_IDEMPOTENT_JOB, "write", 11)).isEqualTo(2) // 두 번 쓰였지만
        assertThat(savedItems(RetryJobConfig.WRITE_IDEMPOTENT_JOB)).isEqualTo((1..20).toList()) // 한 줄
    }
}
