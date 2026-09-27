package com.example.toybatch.faulttolerance.writer

import com.example.toybatch.faulttolerance.fault.Fault
import com.example.toybatch.faulttolerance.fault.byItem
import com.example.toybatch.faulttolerance.support.AttemptRecorder
import com.example.toybatch.faulttolerance.support.FakeExternalApi
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * item 마다 외부 시스템에 전송하는 writer. 전송 시점을 세 가지로 바꿔볼 수 있다.
 */
class ExternalSendWriter(
    private val jobName: String,
    private val api: FakeExternalApi,
    private val recorder: AttemptRecorder,
    private val mode: SendMode,
    faults: List<Fault>,
) : ItemWriter<Int> {

    enum class SendMode {
        /** write() 안에서 바로 전송 → 청크가 롤백돼도, 재시도해도 이미 보낸 건 못 되돌린다 */
        IMMEDIATE,

        /**
         * item 마다 "커밋 후 전송" 을 예약.
         * 롤백되면 예약도 버려지므로 scan 중복은 막는다. 하지만 write 재시도는 같은 트랜잭션이라
         * 실패한 시도에서 예약한 것도 남아 있다가 커밋 때 같이 나간다 → 재시도 중복은 못 막는다.
         */
        AFTER_COMMIT_PER_ITEM,

        /** write() 가 끝까지 성공했을 때만 청크 전체 전송을 한 번 예약 → 롤백도, 재시도도 중복이 없다 */
        AFTER_COMMIT_ON_SUCCESS,
    }

    private val faults = faults.byItem()

    override fun write(chunk: Chunk<out Int>) {
        recorder.writeCalled(jobName, chunk.size())
        for (item in chunk) {
            val attempt = recorder.attempt(jobName, "write", item)
            faults[item]?.check("write", attempt)
            when (mode) {
                SendMode.IMMEDIATE -> api.send(jobName, item)
                SendMode.AFTER_COMMIT_PER_ITEM -> afterCommit { api.send(jobName, item) }
                SendMode.AFTER_COMMIT_ON_SUCCESS -> Unit
            }
        }
        if (mode == SendMode.AFTER_COMMIT_ON_SUCCESS) {
            val items = chunk.items.toList()
            afterCommit { items.forEach { api.send(jobName, it) } }
        }
    }

    private fun afterCommit(action: () -> Unit) =
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
}
