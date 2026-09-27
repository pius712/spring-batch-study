package com.example.toybatch.jpa.support

import org.springframework.data.domain.Limit
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface JpaOrderRepository : JpaRepository<JpaOrder, Long> {

    /** RepositoryItemReader 용: 마지막 파라미터가 Pageable 이어야 한다 (page 번호 = offset 방식) */
    fun findByStatus(status: String, pageable: Pageable): Page<JpaOrder>

    /** 키 기반(keyset) 페이징용: "lastId 다음부터 limit 개" */
    fun findByStatusAndIdGreaterThanOrderByIdAsc(status: String, id: Long, limit: Limit): List<JpaOrder>

    fun findByIdGreaterThanOrderByIdAsc(id: Long, limit: Limit): List<JpaOrder>
}

interface JpaSettlementRepository : JpaRepository<JpaSettlement, Long>
