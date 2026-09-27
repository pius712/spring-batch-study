package com.example.toybatch.faulttolerance.support

import com.example.toybatch.faulttolerance.fault.InvalidItemException
import org.slf4j.LoggerFactory
import org.springframework.batch.core.listener.SkipListener
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 스킵된 item 을 두 군데에 남긴다.
 *   - AttemptRecorder (메모리)             : 무조건 남는다
 *   - ft_skip_log 테이블 (청크 트랜잭션 안) : 그 트랜잭션이 롤백되면 같이 사라진다
 * 두 기록을 비교하면 "스킵 리스너가 어느 트랜잭션에서 불리는지"가 보인다.
 */
class RecordingSkipListener(
    private val jobName: String,
    private val recorder: AttemptRecorder,
    private val jdbcTemplate: JdbcTemplate,
) : SkipListener<Int, Int> {

    // read 단계 스킵은 item 이 아직 없어서 예외에서 꺼낸다
    override fun onSkipInRead(t: Throwable) = record("read", (t as? InvalidItemException)?.item ?: -1, t)

    override fun onSkipInProcess(item: Int, t: Throwable) = record("process", item, t)

    override fun onSkipInWrite(item: Int, t: Throwable) = record("write", item, t)

    private fun record(stage: String, item: Int, t: Throwable) {
        log.info("    [skip] {} item={} ({})", stage, item, t.javaClass.simpleName)
        recorder.skipped(jobName, stage, item)
        jdbcTemplate.update("INSERT INTO ft_skip_log(job_name, stage, item_value) VALUES (?, ?, ?)", jobName, stage, item)
    }

    companion object {
        private val log = LoggerFactory.getLogger(RecordingSkipListener::class.java)
    }
}
