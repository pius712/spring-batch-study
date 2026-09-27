package com.example.toybatch.faulttolerance.fault

/**
 * "item 이 몇 번째 시도까지 실패하는가" 규칙.
 *
 *   Fault.transientTimes(7, 2) : 7 은 1·2번째 시도에서 TransientException, 3번째부터 성공
 *   Fault.transientAlways(7)   : 7 은 항상 TransientException
 *   Fault.invalid(7)           : 7 은 항상 InvalidItemException
 */
data class Fault(val item: Int, val invalid: Boolean, val failTimes: Int) {

    /** attempt 번째 시도에서 실패해야 하면 예외를 던진다 */
    fun check(stage: String, attempt: Int) {
        if (attempt > failTimes) return
        val message = "[$stage] item=$item attempt=$attempt"
        throw if (invalid) InvalidItemException(item, "$message 잘못된 데이터")
              else TransientException("$message 일시적 오류")
    }

    companion object {
        fun transientTimes(item: Int, failTimes: Int) = Fault(item, invalid = false, failTimes = failTimes)
        fun transientAlways(item: Int) = Fault(item, invalid = false, failTimes = Int.MAX_VALUE)
        fun invalid(item: Int, failTimes: Int = Int.MAX_VALUE) = Fault(item, invalid = true, failTimes = failTimes)
    }
}

/** 단계별 실패 규칙 묶음 */
data class Faults(
    val read: List<Fault> = emptyList(),
    val process: List<Fault> = emptyList(),
    val write: List<Fault> = emptyList(),
)

internal fun List<Fault>.byItem(): Map<Int, Fault> = associateBy { it.item }
