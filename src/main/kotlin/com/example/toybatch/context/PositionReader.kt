package com.example.toybatch.context

import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader

/**
 * 1..totalCount 를 읽는 reader. ItemStream 으로 Step ExecutionContext 를 받는다.
 *
 *   open(ec)   : 스텝 시작 시 1번. 재시작이면 ec 에 이전 위치가 들어 있다
 *   update(ec) : 청크 커밋 직전마다. 여기서 쓴 값이 같은 트랜잭션으로 BATCH_STEP_EXECUTION_CONTEXT 에 저장된다
 *
 * 인자로 오는 ec 는 stepExecution.executionContext 그 자체다 (Job EC 가 아님).
 */
class PositionReader(private val totalCount: Int) : ItemStreamReader<Int> {

    private var position = 0

    override fun open(executionContext: ExecutionContext) {
        position = executionContext.getInt(POSITION_KEY, 0)
    }

    override fun update(executionContext: ExecutionContext) {
        executionContext.putInt(POSITION_KEY, position)
    }

    override fun read(): Int? = if (position < totalCount) ++position else null

    companion object {
        const val POSITION_KEY = "reader.position"
    }
}
