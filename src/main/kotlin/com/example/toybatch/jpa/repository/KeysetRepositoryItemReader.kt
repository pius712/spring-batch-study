package com.example.toybatch.jpa.repository

import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.data.domain.Limit

/**
 * Repository 메서드를 람다로 직접 호출하는 키 기반(keyset) 페이징 reader.
 *
 *   KeysetRepositoryItemReader("readyOrders", 10, { it.id }) { lastId, limit ->
 *       orderRepository.findByStatusAndIdGreaterThanOrderByIdAsc("READY", lastId, limit)
 *   }
 *
 * - RepositoryItemReader 처럼 메서드 이름을 문자열로 넘기지 않으니 컴파일 타임에 검사된다.
 * - "몇 번째(offset)" 가 아니라 "마지막 키 다음" 을 조회하므로
 *   status 를 바꿔서 조회 결과가 당겨져도, 재시작 사이에 앞쪽 행이 지워져도 정확하다.
 * - 재시작용으로 마지막으로 넘겨준 item 의 키를 "{name}.last.key" 로 저장한다.
 *
 * fetch 는 read() 안에서, 즉 청크 트랜잭션 안에서 호출된다 → 읽은 엔티티는 청크 트랜잭션의 EntityManager 소속.
 * open class: @StepScope 빈은 CGLIB 로 이 클래스를 상속한 프록시가 된다 (Kotlin 기본 final 이면 기동 실패).
 * pageSize 를 chunk 크기와 같게 두어야 "이번 청크에서 읽은 엔티티 = 이번 트랜잭션에서 조회한 엔티티" 가 된다.
 */
open class KeysetRepositoryItemReader<T : Any>(
    private val name: String,
    private val pageSize: Int,
    private val keyOf: (T) -> Long,
    private val saveState: Boolean = true,
    private val fetch: (lastKey: Long, limit: Limit) -> List<T>,
) : ItemStreamReader<T> {

    private val buffer = ArrayDeque<T>()
    private var lastFetchedKey = 0L  // 다음 조회의 기준 (버퍼 끝)
    private var lastReadKey = 0L     // 마지막으로 read() 가 넘겨준 item 의 키 (재시작 기준)
    private var exhausted = false

    private val key get() = "$name.last.key"

    override fun open(executionContext: ExecutionContext) {
        buffer.clear()
        exhausted = false
        lastReadKey = if (saveState && executionContext.containsKey(key)) executionContext.getLong(key) else 0L
        lastFetchedKey = lastReadKey
        if (lastReadKey > 0) {
            log.info(">>> [{}] open: 재시작 감지! {}={} → 그 다음 키부터 읽는다", name, key, lastReadKey)
        }
    }

    override fun read(): T? {
        if (buffer.isEmpty() && !exhausted) {
            val page = fetch(lastFetchedKey, Limit.of(pageSize))
            log.info("    [keyset] key > {} LIMIT {} → keys {}", lastFetchedKey, pageSize, page.map(keyOf))
            if (page.isEmpty()) exhausted = true
            page.lastOrNull()?.let { lastFetchedKey = keyOf(it) }
            buffer.addAll(page)
        }
        return buffer.removeFirstOrNull()?.also { lastReadKey = keyOf(it) }
    }

    override fun update(executionContext: ExecutionContext) {
        if (saveState) {
            executionContext.putLong(key, lastReadKey)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(KeysetRepositoryItemReader::class.java)
    }
}
