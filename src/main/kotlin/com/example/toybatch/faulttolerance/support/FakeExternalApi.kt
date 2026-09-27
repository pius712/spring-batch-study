package com.example.toybatch.faulttolerance.support

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 트랜잭션에 참여하지 않는 외부 시스템(알림 발송, 외부 API, 메시지 발행)을 흉내낸다.
 * DB 롤백과 무관하게 "보낸 건 보낸 것" 이라 중복 호출을 그대로 기록한다.
 */
@Component
class FakeExternalApi {

    private val sent = CopyOnWriteArrayList<String>()

    fun send(jobName: String, item: Int) {
        log.info("    [external] send item={}", item)
        sent += "$jobName:$item"
    }

    /** 같은 item 이 몇 번 보내졌는지 */
    fun sentCounts(jobName: String): Map<Int, Int> = sent
        .filter { it.startsWith("$jobName:") }
        .groupingBy { it.substringAfter(':').toInt() }
        .eachCount()

    fun clear() = sent.clear()

    companion object {
        private val log = LoggerFactory.getLogger(FakeExternalApi::class.java)
    }
}
