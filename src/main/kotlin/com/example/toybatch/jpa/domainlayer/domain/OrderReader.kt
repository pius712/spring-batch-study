package com.example.toybatch.jpa.domainlayer.domain

import com.example.toybatch.jpa.support.JpaOrder
import com.example.toybatch.jpa.support.JpaOrderRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 도메인 계층의 조회 컴포넌트라고 가정한다. (서비스/API 에서도 쓰는 코드를 배치가 재사용하는 상황)
 * 안에서 JpaRepository 를 쓴다. 배치 입장에서는 "repository 를 한 번 감싼 것" 일 뿐이다.
 *
 * 배치에서 재사용하려면 "마지막 키 다음 N개" 메서드가 있어야 한다 (offset/Page 만 있으면 03-2 의 누락이 그대로 생긴다).
 */
@Component
class OrderReader(private val orderRepository: JpaOrderRepository) {

    /** 도메인 코드에 흔히 붙어 있는 readOnly. 청크 트랜잭션 안에서 불리면 그 트랜잭션에 참여한다 */
    @Transactional(readOnly = true)
    fun readAfter(lastId: Long, size: Int): List<JpaOrder> =
        orderRepository.findByIdGreaterThanOrderByIdAsc(lastId, Limit.of(size))
}
