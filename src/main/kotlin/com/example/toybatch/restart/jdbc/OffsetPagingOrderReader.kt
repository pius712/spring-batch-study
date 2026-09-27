package com.example.toybatch.restart.jdbc

import org.springframework.batch.infrastructure.item.database.AbstractPagingItemReader
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JpaPagingItemReader 처럼 "LIMIT n OFFSET page*n" 으로 읽는 오프셋 기반 페이징 reader.
 * (프로젝트에 JPA 가 없어서 같은 동작을 JDBC 로 흉내냈다)
 *
 * 매 페이지마다 쿼리를 새로 날리므로, 앞 페이지에서 처리한 행이 조회 조건에서 빠지면
 * 목록이 앞으로 당겨져서 그만큼 건너뛴다.
 *
 * open class: @StepScope 프록시(CGLIB)가 상속할 수 있어야 한다.
 */
open class OffsetPagingOrderReader(
    private val jdbcTemplate: JdbcTemplate,
    private val whereClause: String,
) : AbstractPagingItemReader<OrderRow>() {

    override fun doReadPage() {
        val page = results ?: CopyOnWriteArrayList<OrderRow>().also { results = it }
        page.clear()
        val offset = getPage() * pageSize
        page.addAll(jdbcTemplate.query(
            "SELECT id, order_date, status FROM rj_order WHERE $whereClause ORDER BY id LIMIT ? OFFSET ?",
            OrderRow.ROW_MAPPER, pageSize, offset))
        logger.info("    [offset paging] page=${getPage()} OFFSET $offset → ids ${page.map { it.id }}")
    }
}
