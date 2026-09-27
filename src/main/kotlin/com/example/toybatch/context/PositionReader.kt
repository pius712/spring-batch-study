package com.example.toybatch.context

import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader

/**
 * 1..totalCount 를 읽는 reader. ItemStream 으로 Step ExecutionContext 를 받는다.
 *
 *   open(ec)   : 스텝 시작 시 1번. 재시작이면 ec 에 이전 위치가 들어 있다
 *   update(ec) : 청크 커밋 직전마다 + 스텝 끝에 한 번 더. 여기서 쓴 값이 BATCH_STEP_EXECUTION_CONTEXT 에 저장된다
 *                (청크 때는 그 청크 트랜잭션으로 같이 커밋. read() 가 null 을 준 마지막 빈 청크 뒤에도 불린다)
 *
 * 인자로 오는 ec 는 stepExecution.executionContext 그 자체다 (Job EC 가 아님).
 * open class: readStep 에서는 @StepScope 빈으로 쓰는데, 스코프 프록시(CGLIB)가 상속할 수 있어야 한다.
 */
open class PositionReader(private val name: String, private val totalCount: Int) : ItemStreamReader<Int> {

    private var position = 0

    override fun open(executionContext: ExecutionContext) {
        position = executionContext.getInt(POSITION_KEY, 0)
        log.info("    [{} reader] open(ec)   : ec={} → position={} 부터 읽는다 (ec 식별자 {})",
            name, executionContext, position, System.identityHashCode(executionContext))
    }

    override fun update(executionContext: ExecutionContext) {
        executionContext.putInt(POSITION_KEY, position)
        log.info("    [{} reader] update(ec) : {}={} 를 ec 에 씀 → 이 값이 Step EC 로 DB 에 저장된다 (ec 식별자 {})",
            name, POSITION_KEY, position, System.identityHashCode(executionContext))
    }

    override fun read(): Int? {
        val item = if (position < totalCount) ++position else null
        log.info("    [{} reader] read()     : {}", name, item ?: "null (끝)")
        return item
    }

    companion object {
        const val POSITION_KEY = "reader.position"
        private val log = LoggerFactory.getLogger(PositionReader::class.java)
    }
}
