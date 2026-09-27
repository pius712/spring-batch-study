package com.example.toybatch.context

import org.springframework.stereotype.Component

/** 스텝 안에서 본 값을 테스트가 확인할 수 있게 모아둔다 */
@Component
class ContextRecorder {
    val writes = mutableListOf<WriteObservation>()
    var read: ReadObservation? = null

    fun clear() {
        writes.clear()
        read = null
    }
}

/** writeStep 의 writer 가 청크 하나를 쓸 때 본 것 */
data class WriteObservation(
    val items: List<Int>,
    val stepExecutionReadCount: Long,  // 앞 청크까지 apply 된 누적값 (이번 청크분은 청크가 끝나야 더해진다)
    val persistedPosition: Any?,       // DB(BATCH_STEP_EXECUTION_CONTEXT)에 저장된 reader 위치
    val persistedLastItem: Any?,       // DB(BATCH_JOB_EXECUTION_CONTEXT)에 저장된 job.lastItem
)

/** readStep 의 reader 가 @StepScope 로 주입받은 것 */
data class ReadObservation(
    val jobLastItem: Any?,             // 앞 스텝이 Job EC 에 넣은 값 → 보인다
    val ownPosition: Any?,             // 앞 스텝의 Step EC 값 → 안 보인다 (스텝마다 따로)
)
