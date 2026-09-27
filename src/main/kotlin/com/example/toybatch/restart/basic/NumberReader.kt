package com.example.toybatch.restart.basic

import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader

/**
 * 1 ~ totalCount 를 순서대로 읽는 reader.
 *
 * 재시작의 핵심은 ItemStream 의 3개 콜백이다.
 *
 *   open(ctx)   : 스텝 시작 시 1회. 재시작이면 ctx 에 지난번 저장값이 들어있다 → 그 위치부터 다시 읽는다.
 *   update(ctx) : 청크 커밋 "직전"마다 호출. 지금 위치를 ctx 에 적는다.
 *                 → 청크 트랜잭션과 같이 커밋되므로 "커밋된 데이터"와 "저장된 위치"가 항상 일치한다.
 *                 → 청크가 롤백되면 update 자체가 호출되지 않는다.
 *   close()     : 스텝 종료 시.
 *
 * FlatFileItemReader, JdbcPagingItemReader 같은 기본 reader 들도 전부 이 방식으로
 * "{name}.read.count" 같은 키를 저장한다. (AbstractItemCountingItemStreamItemReader 참고)
 * 여기서는 학습용으로 그걸 직접 구현했다.
 *
 * saveState=false 면 위치를 저장/복구하지 않는다 → 재시작 시 처음부터 다시 읽는다(중복 처리).
 *
 * open class 인 이유: @StepScope 빈은 CGLIB 로 이 클래스를 상속한 프록시가 된다. final 이면 프록시를 못 만든다.
 */
open class NumberReader(
    private val name: String,
    private val totalCount: Int,
    private val saveState: Boolean,
) : ItemStreamReader<Int> {

    private var current = 0 // 지금까지 read() 로 넘겨준 건수

    private val key get() = "$name.read.count"

    override fun open(executionContext: ExecutionContext) {
        if (saveState && executionContext.containsKey(key)) {
            current = executionContext.getInt(key)
            log.info(">>> [{}] open: 재시작 감지! stepExecutionContext 에서 {}={} 복구 → {} 부터 읽는다",
                name, key, current, current + 1)
        } else {
            current = 0
            log.info(">>> [{}] open: 저장된 위치 없음(saveState={}) → 1 부터 읽는다", name, saveState)
        }
    }

    override fun read(): Int? {
        if (current >= totalCount) {
            return null // null = 데이터 끝
        }
        return ++current
    }

    override fun update(executionContext: ExecutionContext) {
        if (saveState) {
            executionContext.putInt(key, current)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NumberReader::class.java)
    }
}
