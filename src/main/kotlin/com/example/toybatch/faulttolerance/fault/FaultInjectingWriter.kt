package com.example.toybatch.faulttolerance.fault

import com.example.toybatch.faulttolerance.support.AttemptRecorder
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.jdbc.core.JdbcTemplate

/**
 * ft_result 에 한 건씩 저장. 규칙에 걸린 item 에서 예외가 나면 그 앞 item 들은 이미 저장된 상태다.
 *
 * idempotent=false : INSERT            → 같은 item 을 두 번 쓰면 두 줄이 된다
 * idempotent=true  : MERGE ... KEY(..) → 같은 item 을 몇 번 써도 한 줄 (멱등)
 */
class FaultInjectingWriter(
    private val jobName: String,
    private val jdbcTemplate: JdbcTemplate,
    private val recorder: AttemptRecorder,
    private val idempotent: Boolean,
    faults: List<Fault>,
) : ItemWriter<Int> {

    private val faults = faults.byItem()

    override fun write(chunk: Chunk<out Int>) {
        log.info("    [write] {}", chunk.items)
        recorder.writeCalled(jobName, chunk.size())
        for (item in chunk) {
            val attempt = recorder.attempt(jobName, "write", item)
            faults[item]?.check("write", attempt)
            val sql = if (idempotent) {
                "MERGE INTO ft_result(job_name, item_value) KEY(job_name, item_value) VALUES (?, ?)"
            } else {
                "INSERT INTO ft_result(job_name, item_value) VALUES (?, ?)"
            }
            jdbcTemplate.update(sql, jobName, item)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(FaultInjectingWriter::class.java)
    }
}
