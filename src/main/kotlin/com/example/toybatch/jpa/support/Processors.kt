package com.example.toybatch.jpa.support

import com.example.toybatch.common.FailureInjector
import org.springframework.batch.infrastructure.item.ItemProcessor

/**
 * 주문 → 정산 결과(JpaSettlement) 로 바꾼다.
 * touchOrder=true 면 읽어온 주문 엔티티도 settle() 로 바꿔 둔다.
 * "엔티티 값만 바꿔두면 JPA 가 알아서(dirty checking) 저장해주겠지" 라는 흔한 기대를 재현하는 용도.
 */
class SettlementProcessor(
    private val failureInjector: FailureInjector,
    private val touchOrder: Boolean = false,
) : ItemProcessor<JpaOrder, JpaSettlement> {

    override fun process(order: JpaOrder): JpaSettlement {
        failureInjector.check(order.id.toInt())
        if (touchOrder) {
            order.settle()
        }
        return JpaSettlement(order.id, order.amount * 3 / 100)
    }
}

/** 주문 엔티티 자체를 정산 상태로 바꿔서 그대로 넘긴다 → writer 가 JpaItemWriter<JpaOrder> 로 merge */
class SettleOrderProcessor : ItemProcessor<JpaOrder, JpaOrder> {

    override fun process(order: JpaOrder): JpaOrder = order.apply { settle() }
}
