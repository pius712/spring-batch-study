package com.example.toybatch.restart.jdbc

import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader
import org.springframework.batch.infrastructure.item.database.Order
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * 01-2 (restart.jdbc) 예제의 reader 들. 전부 rj_order 를 읽는다.
 *
 * reader 를 @StepScope 빈으로 만들 때 반환 타입은 ItemStream 을 구현한 구체 타입으로 둔다.
 * ItemReader 로 두면 프록시가 ItemStream 이 아니라서 스텝이 open/update 를 안 불러준다.
 */
@Configuration
class RestartJdbcReaderConfig(
    private val dataSource: DataSource,
    private val jdbcTemplate: JdbcTemplate,
) {

    // ================================================================ 방법 1. 범위 고정

    /** 개수 기반: 재시작 시 쿼리를 다시 실행하고 앞의 read.count 개를 건너뛴다 */
    @Bean
    @StepScope
    fun rangeCursorReader(@Value("#{jobParameters['targetDate']}") targetDate: String?): JdbcCursorItemReader<OrderRow> =
        JdbcCursorItemReaderBuilder<OrderRow>()
            .name("rangeCursorReader")
            .dataSource(dataSource)
            .sql("SELECT $COLUMNS FROM rj_order WHERE order_date = ? ORDER BY id")
            .queryArguments(targetDate!!)
            .rowMapper(OrderRow.ROW_MAPPER)
            .build()

    /** 키 기반: 재시작 시 context 의 start.after(마지막 id) 로 WHERE id > ? 부터 조회한다 */
    @Bean
    @StepScope
    fun rangePagingReader(@Value("#{jobParameters['targetDate']}") targetDate: String?): JdbcPagingItemReader<OrderRow> =
        JdbcPagingItemReaderBuilder<OrderRow>()
            .name("rangePagingReader")
            .dataSource(dataSource)
            .selectClause(COLUMNS)
            .fromClause("rj_order")
            .whereClause("order_date = :targetDate")
            .parameterValues(mapOf("targetDate" to targetDate!!))
            .sortKeys(mapOf("id" to Order.ASCENDING)) // 유일한 키(PK)여야 한다
            .pageSize(PAGE_SIZE)
            .rowMapper(OrderRow.ROW_MAPPER)
            .build()

    // ================================================================ 방법 2. 상태 플래그

    /** ❌ 함정: 상태 플래그인데 saveState=true (기본값 그대로) */
    @Bean
    @StepScope
    fun statusCursorReaderWithState(): JdbcCursorItemReader<OrderRow> =
        statusCursorReader("statusCursorReaderWithState", saveState = true)

    /** ✅ 어디까지 했는지는 status 컬럼이 기억하므로 context 에 위치를 남기지 않는다 */
    @Bean
    @StepScope
    fun statusCursorReaderNoState(): JdbcCursorItemReader<OrderRow> =
        statusCursorReader("statusCursorReaderNoState", saveState = false)

    /** ❌ 오프셋 페이징 + 상태 플래그: 처리한 행이 빠지면서 목록이 당겨진다 */
    @Bean
    @StepScope
    fun statusOffsetPagingReader(): OffsetPagingOrderReader =
        OffsetPagingOrderReader(jdbcTemplate, "status = 'READY'").apply {
            setName("statusOffsetPagingReader")
            pageSize = PAGE_SIZE
            isSaveState = false
        }

    /** ✅ 키 기반 페이징 + 상태 플래그: WHERE status='READY' AND id > 마지막키 라서 당겨져도 상관없다 */
    @Bean
    @StepScope
    fun statusKeyPagingReader(): JdbcPagingItemReader<OrderRow> =
        JdbcPagingItemReaderBuilder<OrderRow>()
            .name("statusKeyPagingReader")
            .dataSource(dataSource)
            .selectClause(COLUMNS)
            .fromClause("rj_order")
            .whereClause("status = 'READY'")
            .sortKeys(mapOf("id" to Order.ASCENDING))
            .pageSize(PAGE_SIZE)
            .rowMapper(OrderRow.ROW_MAPPER)
            .saveState(false)
            .build()

    // ================================================================

    private fun statusCursorReader(name: String, saveState: Boolean): JdbcCursorItemReader<OrderRow> =
        JdbcCursorItemReaderBuilder<OrderRow>()
            .name(name)
            .dataSource(dataSource)
            .sql("SELECT $COLUMNS FROM rj_order WHERE status = 'READY' ORDER BY id")
            .rowMapper(OrderRow.ROW_MAPPER)
            .saveState(saveState)
            .build()

    companion object {
        // JdbcPagingItemReader 는 pageSize 와 chunk 크기를 맞춰두면 "청크 커밋 = 페이지 경계" 라서 동작을 따라가기 쉽다
        const val PAGE_SIZE = RestartJdbcJobConfig.CHUNK_SIZE
        private const val COLUMNS = "id, order_date, status"
    }
}
