package com.example.toybatch.common

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 예제에서 "일부러 실패"를 만들기 위한 스위치.
 *
 * JobParameter 로 실패 여부를 넘기면 파라미터가 달라져서 재시작이 아니라 새 JobInstance 가 된다.
 * 그래서 파라미터와 무관한 외부 설정(프로퍼티 / 테스트 코드)으로 제어한다.
 */
@Component
class FailureInjector(@Value("\${demo.fail-at:-1}") failAt: Int) {

    @Volatile
    private var failAt: Int = failAt

    fun failAt(value: Int) {
        failAt = value
    }

    fun disable() {
        failAt = -1
    }

    fun check(item: Int) {
        check(item != failAt) { "[FailureInjector] item=$item 에서 일부러 실패" }
    }
}
