package com.example.toybatch.restart.basic

import com.example.toybatch.common.FailureInjector
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 결과 테이블에 insert. FailureInjector 가 켜져 있으면 특정 값에서 예외를 던진다.
 * 예외가 나면 해당 청크 전체가 롤백되고, 청크 커밋 때 하는 reader 위치 저장도 일어나지 않는다.
 */
class ResultWriter(
    private val jobName: String,
    private val jdbcTemplate: JdbcTemplate,
    private val failureInjector: FailureInjector,
) : ItemWriter<Int> {

    override fun write(chunk: Chunk<out Int>) {
        for (item in chunk) {
            failureInjector.check(item)
            jdbcTemplate.update("INSERT INTO restart_demo_result(job_name, item_value) VALUES (?, ?)", jobName, item)
        }
        log.info("    write {} ~ {}", chunk.items.first(), chunk.items.last())
    }

    companion object {
        private val log = LoggerFactory.getLogger(ResultWriter::class.java)
    }
}
