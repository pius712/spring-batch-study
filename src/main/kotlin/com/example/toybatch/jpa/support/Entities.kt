package com.example.toybatch.jpa.support

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/** 주문. 정산하면 fee 가 채워지고 status 가 DONE 이 된다 */
@Entity
@Table(name = "jpa_order")
class JpaOrder(
    @Id
    val id: Long,
    var amount: Int,
    var status: String = "READY",
    var fee: Int? = null,
) {
    fun settle() {
        fee = amount * 3 / 100
        status = "DONE"
    }

    override fun toString() = "JpaOrder(id=$id, status=$status)"
}

/** 정산 결과. PK 를 order_id 로 직접 지정(자동 생성 아님). fee 는 DB CHECK 로 음수 금지 */
@Entity
@Table(name = "jpa_settlement")
class JpaSettlement(
    @Id
    @Column(name = "order_id")
    val orderId: Long,
    var fee: Int,
) {
    override fun toString() = "JpaSettlement(orderId=$orderId, fee=$fee)"
}
