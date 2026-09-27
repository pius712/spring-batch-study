package com.example.toybatch.faulttolerance.basic

import com.example.toybatch.faulttolerance.fault.Fault
import com.example.toybatch.faulttolerance.fault.Faults
import com.example.toybatch.faulttolerance.fault.InvalidItemException
import com.example.toybatch.faulttolerance.support.FaultToleranceSteps
import org.springframework.batch.core.job.Job
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Skip 예제. 전부 "InvalidItemException 은 skip", retry 없음.
 *
 *   ftSkipJob       read 3, process 8, write 13 이 잘못된 데이터 → 셋 다 skip    ✅ 17건 COMPLETED
 *                   (write 는 청크를 롤백하고 한 건씩 다시 써보는 scan 으로 범인을 찾는다)
 *   ftSkipLimitJob  process 3, 8, 13 실패, skipLimit=2 → 세 번째에서 한도 초과   ❌ FAILED
 *   ftSkipListenerContractJob  같은 청크 [6..10] 에서 process 8 skip + write 9 skip
 *                   → SkipListener 가 DB 에 남긴 기록이 둘 다 롤백된다 (6.0.4+ 의도된 동작, #5436 / #5494)
 */
@Configuration
class SkipJobConfig(private val steps: FaultToleranceSteps) {

    @Bean
    fun ftSkipJob(): Job {
        val faults = Faults(
            read = listOf(Fault.invalid(3)),
            process = listOf(Fault.invalid(8)),
            write = listOf(Fault.invalid(13)),
        )
        return steps.job(SKIP_JOB, faults) {
            faultTolerant()
            skip(InvalidItemException::class.java)
            skipLimit(10)
            skipListener(steps.skipListener(SKIP_JOB))
        }
    }

    @Bean
    fun ftSkipLimitJob(): Job {
        val faults = Faults(process = listOf(Fault.invalid(3), Fault.invalid(8), Fault.invalid(13)))
        return steps.job(SKIP_LIMIT_JOB, faults) {
            faultTolerant()
            skip(InvalidItemException::class.java)
            skipLimit(2) // 스텝 전체에서 2건까지만 허용
            skipListener(steps.skipListener(SKIP_LIMIT_JOB))
        }
    }

    @Bean
    fun ftSkipListenerContractJob(): Job {
        val faults = Faults(process = listOf(Fault.invalid(8)), write = listOf(Fault.invalid(9)))
        return steps.job(SKIP_LISTENER_CONTRACT_JOB, faults) {
            faultTolerant()
            skip(InvalidItemException::class.java)
            skipListener(steps.skipListener(SKIP_LISTENER_CONTRACT_JOB))
        }
    }

    companion object {
        const val SKIP_LISTENER_CONTRACT_JOB = "ftSkipListenerContractJob"
        const val SKIP_JOB = "ftSkipJob"
        const val SKIP_LIMIT_JOB = "ftSkipLimitJob"
    }
}
