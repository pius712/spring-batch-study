package com.example.toybatch.faulttolerance.support

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "어떤 item 이 어느 단계에서 몇 번 시도됐는지", "무엇이 스킵됐는지"를 메모리에 기록한다.
 * DB 에 기록하면 청크 롤백 때 같이 사라지므로 일부러 트랜잭션 밖(메모리)에 둔다.
 */
@Component
class AttemptRecorder {

    private val attempts = ConcurrentHashMap<String, Int>()
    private val skips = CopyOnWriteArrayList<String>()
    private val writeCalls = ConcurrentHashMap<String, MutableList<Int>>()

    /** 시도 횟수를 1 올리고 이번이 몇 번째 시도인지 반환 */
    fun attempt(jobName: String, stage: String, item: Int): Int =
        attempts.merge("$jobName:$stage:$item", 1, Int::plus)!!

    fun attempts(jobName: String, stage: String, item: Int): Int =
        attempts["$jobName:$stage:$item"] ?: 0

    fun skipped(jobName: String, stage: String, item: Int) {
        skips += "$jobName:$stage:$item"
    }

    /** 예: ["read:3", "process:8", "write:13"] */
    fun skips(jobName: String): List<String> =
        skips.filter { it.startsWith("$jobName:") }.map { it.removePrefix("$jobName:") }

    /** writer.write() 가 불릴 때마다 청크 크기를 기록 → 호출 횟수 = DB 왕복(I/O) 횟수 */
    fun writeCalled(jobName: String, chunkSize: Int) {
        writeCalls.computeIfAbsent(jobName) { CopyOnWriteArrayList() } += chunkSize
    }

    fun writeCalls(jobName: String): List<Int> = writeCalls[jobName].orEmpty()

    fun clear() {
        attempts.clear()
        skips.clear()
        writeCalls.clear()
    }
}
