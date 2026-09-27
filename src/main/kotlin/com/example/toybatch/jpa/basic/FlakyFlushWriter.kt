package com.example.toybatch.jpa.basic

import com.example.toybatch.jpa.support.JpaSettlement
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamWriter
import org.springframework.batch.infrastructure.item.database.JpaItemWriter

/**
 * flush 시점에 나는 "일시적인 DB 오류"(데드락, 락 타임아웃 같은)를 흉내내는 writer.
 *
 * failOrderId 가 들어있는 청크를 처음 쓸 때만 그 정산의 fee 를 -1 로 바꿔서 CHECK 제약 위반을 일으킨다.
 * 두 번째부터는 정상 값으로 쓴다 → "다시 하면 성공하는" 오류.
 * 예외는 Hibernate 가 flush 하다가 직접 던지므로, 진짜 DB 오류처럼 트랜잭션 상태도 같이 건드린다.
 */
class FlakyFlushWriter(
    private val delegate: JpaItemWriter<JpaSettlement>,
    private val failOrderId: Long,
) : ItemStreamWriter<JpaSettlement> {

    private var failed = false

    override fun write(chunk: Chunk<out JpaSettlement>) {
        val target = chunk.items.firstOrNull { it.orderId == failOrderId }
        if (target == null || failed) {
            delegate.write(chunk)
            return
        }
        failed = true
        val originalFee = target.fee
        target.fee = -1
        try {
            log.info("    [flaky] orderId={} 첫 시도 → fee=-1 로 flush 해서 DB 오류 유발", failOrderId)
            delegate.write(chunk)
        } finally {
            target.fee = originalFee
        }
    }

    /** 잡을 여러 번 돌려도 매번 "첫 시도는 실패" 하도록 스텝 시작 때 초기화 */
    override fun open(executionContext: ExecutionContext) {
        failed = false
    }

    companion object {
        private val log = LoggerFactory.getLogger(FlakyFlushWriter::class.java)
    }
}
