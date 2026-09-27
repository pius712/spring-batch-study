package com.example.toybatch.jpa.domainlayer.domain

import com.example.toybatch.jpa.support.JpaOrder
import com.example.toybatch.jpa.support.JpaSettlement
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

class InvalidOrderException(message: String) : RuntimeException(message)

/**
 * 도메인 계층의 계산 컴포넌트라고 가정한다. 금액이 음수면 검증 예외를 던진다.
 * 같은 로직을 @Transactional 유무만 다르게 두 개 둔다.
 */
@Component
class SettlementCalculator {

    fun calculate(order: JpaOrder): JpaSettlement {
        if (order.amount < 0) throw InvalidOrderException("금액이 음수: $order")
        return JpaSettlement(order.id, order.amount * 3 / 100)
    }

    /** 참여 중인 트랜잭션에서 RuntimeException 이 나가면 Spring 이 그 트랜잭션을 rollback-only 로 표시한다 */
    @Transactional
    fun calculateTransactional(order: JpaOrder): JpaSettlement = calculate(order)
}
