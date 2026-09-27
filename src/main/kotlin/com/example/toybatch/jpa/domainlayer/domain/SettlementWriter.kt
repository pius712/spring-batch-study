package com.example.toybatch.jpa.domainlayer.domain

import com.example.toybatch.jpa.support.JpaSettlement
import com.example.toybatch.jpa.support.JpaSettlementRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 도메인 계층의 저장 컴포넌트라고 가정한다 */
@Component
class SettlementWriter(private val settlementRepository: JpaSettlementRepository) {

    /** 기본(REQUIRED): 청크 트랜잭션에 참여한다. flush 까지 해서 SQL 오류가 이 안에서 난다 */
    @Transactional
    fun saveAll(settlements: List<JpaSettlement>) {
        settlementRepository.saveAllAndFlush(settlements)
    }

    /** REQUIRES_NEW: 청크 트랜잭션과 별개로 먼저 커밋된다 (감사 로그 등에서 흔히 쓰는 설정) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun saveAllInNewTransaction(settlements: List<JpaSettlement>) {
        settlementRepository.saveAllAndFlush(settlements)
    }
}
