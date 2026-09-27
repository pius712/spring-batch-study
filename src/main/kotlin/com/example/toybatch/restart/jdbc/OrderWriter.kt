package com.example.toybatch.restart.jdbc

import com.example.toybatch.common.FailureInjector
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.jdbc.core.JdbcTemplate

/**
 * rj_result 에 "처리했다"는 기록을 insert.
 * markDone=true 면 원본 행도 status='DONE' 으로 바꾼다 (방법 2. 상태 플래그).
 * 둘 다 청크 트랜잭션 안이라 예외가 나면 insert/update 가 같이 롤백된다.
 */
class OrderWriter(
    private val jobName: String,
    private val jdbcTemplate: JdbcTemplate,
    private val failureInjector: FailureInjector,
    private val markDone: Boolean,
) : ItemWriter<OrderRow> {

    override fun write(chunk: Chunk<out OrderRow>) {
        for (order in chunk) {
            failureInjector.check(order.id.toInt())
            jdbcTemplate.update("INSERT INTO rj_result(job_name, order_id) VALUES (?, ?)", jobName, order.id)
            if (markDone) {
                jdbcTemplate.update("UPDATE rj_order SET status = 'DONE' WHERE id = ?", order.id)
            }
        }
        log.info("    write ids = {}", chunk.items.map { it.id })
    }

    companion object {
        private val log = LoggerFactory.getLogger(OrderWriter::class.java)
    }
}
