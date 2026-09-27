package com.example.toybatch.faulttolerance.basic

import com.example.toybatch.faulttolerance.support.FaultToleranceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus

class RetrySkipJobTest : FaultToleranceTestSupport() {

    private val job = RetrySkipJobConfig.JOB_NAME

    @Test
    fun `일시적 오류는 재시도하고, 재시도로도 안 되거나 잘못된 데이터면 건너뛴다`() {
        val execution = run(job)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(savedItems(job)).isEqualTo((1..20).toList() - listOf(7, 9, 14))
        assertThat(recorder.skips(job)).containsExactlyInAnyOrder("process:7", "process:9", "write:14")
    }

    @Test
    fun `예외 종류와 단계에 따라 몇 번 시도하는지가 다르다`() {
        run(job)

        assertThat(attempts(job, "process", 4)).`as`("Transient 2번 → 3번째 성공").isEqualTo(3)
        assertThat(attempts(job, "process", 7)).`as`("Transient 계속 → 3번 시도 후 skip").isEqualTo(3)
        assertThat(attempts(job, "process", 9)).`as`("Invalid → retry 대상 아님, 1번 후 skip").isEqualTo(1)

        // write 청크 [11..15]
        //   1차 write : 11 ✔, 12 ✘(Transient)
        //   재시도    : 11 ✔, 12 ✔, 13 ✔, 14 ✘(Invalid, retry 대상 아님) → 청크 롤백 → scan
        //   scan      : 11 ✔, 12 ✔, 13 ✔, 14 ✘ skip, 15 ✔  (한 건씩 각자 트랜잭션)
        assertThat(attempts(job, "write", 11)).isEqualTo(3)
        assertThat(attempts(job, "write", 12)).isEqualTo(3)
        assertThat(attempts(job, "write", 14)).isEqualTo(2)
        assertThat(attempts(job, "write", 15)).isEqualTo(1)
    }
}
