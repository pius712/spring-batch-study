package com.example.toybatch.faulttolerance.fault

import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader

/**
 * 1 ~ totalCount 를 읽는 reader. 규칙에 걸린 item 은 "읽다가 파싱 실패" 처럼 예외를 던진다.
 * 위치를 먼저 옮기고 던지므로 다음 read() 는 다음 item 을 준다.
 *
 * saveState=false(기본): 잡을 여러 번 돌려도 매번 1 부터 읽는다 (재시작 지원 안 함)
 * saveState=true       : "ftReader.read.count" 로 위치를 저장/복구한다 (01 의 NumberReader 와 같은 방식)
 */
class FaultInjectingReader(
    private val totalCount: Int,
    faults: List<Fault>,
    private val saveState: Boolean = false,
) : ItemStreamReader<Int> {

    private val faults = faults.byItem()
    private var current = 0

    override fun open(executionContext: ExecutionContext) {
        current = if (saveState && executionContext.containsKey(KEY)) executionContext.getInt(KEY) else 0
    }

    override fun read(): Int? {
        if (current >= totalCount) return null
        val item = ++current
        faults[item]?.check("read", attempt = 1)
        return item
    }

    override fun update(executionContext: ExecutionContext) {
        if (saveState) executionContext.putInt(KEY, current)
    }

    companion object {
        const val KEY = "ftReader.read.count"
    }
}
