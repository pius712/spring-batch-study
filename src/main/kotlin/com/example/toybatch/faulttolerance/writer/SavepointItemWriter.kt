package com.example.toybatch.faulttolerance.writer

import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.jdbc.datasource.DataSourceUtils
import javax.sql.DataSource

/**
 * write() 한 번을 "전부 되거나 전부 안 되거나" 로 만드는 decorator.
 *
 * 청크 트랜잭션 안에 savepoint 를 찍고 delegate 를 호출한다. 실패하면 savepoint 까지만 롤백하고 예외를 다시 던진다.
 * → Batch 6 의 write 재시도는 롤백 없이 write() 를 다시 부르지만, 직전 시도가 남긴 흔적이 이미 지워져 있어서 중복이 안 생긴다.
 *
 * 청크 트랜잭션이 쓰는 JDBC 커넥션을 DataSourceUtils 로 꺼내서 쓴다 (JdbcTemplate 도 같은 커넥션을 쓴다).
 */
class SavepointItemWriter<T : Any>(
    private val delegate: ItemWriter<T>,
    private val dataSource: DataSource,
) : ItemWriter<T> {

    override fun write(chunk: Chunk<out T>) {
        val connection = DataSourceUtils.getConnection(dataSource)
        val savepoint = connection.setSavepoint()
        try {
            delegate.write(chunk)
            connection.releaseSavepoint(savepoint)
        } catch (e: Exception) {
            connection.rollback(savepoint)
            throw e
        }
    }
}
