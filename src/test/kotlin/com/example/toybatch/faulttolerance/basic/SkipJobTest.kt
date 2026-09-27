package com.example.toybatch.faulttolerance.basic

import com.example.toybatch.faulttolerance.support.FaultToleranceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus

class SkipJobTest : FaultToleranceTestSupport() {

    @Test
    fun `read, process, write 에서 난 잘못된 데이터를 각각 건너뛰고 COMPLETED 된다`() {
        val execution = run(SkipJobConfig.SKIP_JOB)
        val step = execution.stepExecutions.single()

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(savedItems(SkipJobConfig.SKIP_JOB)).isEqualTo((1..20).toList() - listOf(3, 8, 13))
        assertThat(listOf(step.readSkipCount, step.processSkipCount, step.writeSkipCount)).containsExactly(1L, 1L, 1L)
        assertThat(recorder.skips(SkipJobConfig.SKIP_JOB)).containsExactly("read:3", "process:8", "write:13")
    }

    @Test
    fun `write 스킵은 청크를 롤백하고 한 건씩 다시 쓰는 scan 으로 범인을 찾는다`() {
        run(SkipJobConfig.SKIP_JOB)

        // 청크 [11..15] 를 쓰다가 13 에서 실패 → 청크 롤백 → 한 건씩 각자 트랜잭션으로 다시 write
        assertThat(attempts(SkipJobConfig.SKIP_JOB, "write", 11)).isEqualTo(2) // 청크 1번 + scan 1번
        assertThat(attempts(SkipJobConfig.SKIP_JOB, "write", 15)).isEqualTo(1) // 청크에선 13 에서 멈춰서 못 감
        // Batch 6 scan 은 이미 가공된 결과를 그대로 다시 쓴다 → processor 는 다시 안 불린다 (Batch 5 는 다시 불렀다)
        assertThat((11..15).map { attempts(SkipJobConfig.SKIP_JOB, "process", it) }).containsOnly(1)
    }

    @Test
    fun `write 스킵 때 SkipListener 가 DB 에 남긴 기록은 롤백되어 사라진다`() {
        run(SkipJobConfig.SKIP_JOB)

        // 메모리 기록에는 3건 다 있지만
        assertThat(recorder.skips(SkipJobConfig.SKIP_JOB)).hasSize(3)
        // onSkipInWrite 는 실패한 item 의 scan 트랜잭션(롤백될 트랜잭션) 안에서 불린다
        // → 같은 트랜잭션으로 insert 한 스킵 로그도 같이 롤백 ❗
        assertThat(skipLog(SkipJobConfig.SKIP_JOB)).containsExactly("read:3", "process:8")
    }

    @Test
    fun `skipLimit 을 넘으면 스텝이 FAILED 된다`() {
        val execution = run(SkipJobConfig.SKIP_LIMIT_JOB)

        assertThat(execution.status).isEqualTo(BatchStatus.FAILED)
        // 3, 8 은 skip 되어 [1..10] 두 청크는 커밋, 13 에서 세 번째 skip → 한도(2) 초과 → [11..15] 롤백
        assertThat(savedItems(SkipJobConfig.SKIP_LIMIT_JOB)).isEqualTo((1..10).toList() - listOf(3, 8))
        println("  exitDescription = ${exitDescription(execution).lineSequence().first()}")
    }

    @Test
    fun `같은 청크에서 write skip 이 나면 앞서 호출된 process skip 리스너의 DB 기록도 같이 롤백된다`() {
        val job = SkipJobConfig.SKIP_LISTENER_CONTRACT_JOB
        val execution = run(job)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(savedItems(job)).isEqualTo((1..20).toList() - listOf(8, 9))
        // 리스너는 두 번 다 불렸지만 (메모리 기록)
        assertThat(recorder.skips(job)).containsExactly("process:8", "write:9")
        // DB 기록은 둘 다 없다 ❗
        //   process 8 : skip 즉시 청크 트랜잭션 안에서 리스너 호출 → 같은 청크의 write 9 때문에 청크 롤백 → 같이 사라짐
        //               scan 은 processor 도 process skip 리스너도 다시 부르지 않는다
        //   write 9   : 롤백될 scan 트랜잭션 안에서 리스너 호출 → 사라짐
        // Batch 5 에서는 둘 다 남는다 (compare/batch5 의 E). Batch 6.0.4+ 에서는 의도된 동작 (#5436, #5494):
        // 리스너 안의 트랜잭션 작업은 REQUIRES_NEW 로 하라는 게 공식 권장
        assertThat(skipLog(job)).isEmpty()
    }
}
