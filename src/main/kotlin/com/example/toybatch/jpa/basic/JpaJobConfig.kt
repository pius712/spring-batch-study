package com.example.toybatch.jpa.basic

import com.example.toybatch.common.FailureInjector
import com.example.toybatch.jpa.support.JpaSteps
import com.example.toybatch.jpa.support.SettleOrderProcessor
import com.example.toybatch.jpa.support.SettlementProcessor
import jakarta.persistence.PersistenceException
import java.time.Duration
import org.springframework.batch.core.job.Job
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.retry.RetryPolicy

/**
 * 03. JPA reader / writer 예제 (EntityManager 기반: JpaPagingItemReader, JpaCursorItemReader, JpaItemWriter).
 * reader 는 JpaReaderConfig, writer 는 JpaWriterConfig 에 있다. (엔티티·리포지토리·processor 는 jpa.support)
 *
 * 세 컴포넌트가 쓰는 EntityManager 가 전부 다르다는 게 핵심이다.
 *   JpaPagingItemReader  : 자기 전용 EM. 페이지를 읽을 때마다 "자기 트랜잭션"을 열고 flush → clear → 조회 → 커밋
 *   JpaCursorItemReader  : 자기 전용 EM. 트랜잭션 없음. 청크 커밋 때마다 clear
 *   JpaItemWriter        : 청크 트랜잭션에 묶인 EM. merge(기본) 또는 persist 후 flush
 *
 *  [기본]
 *   jpaPagingJob            paging → 주문을 정산으로 변환 → JpaItemWriter                  ✅ (재시작도 OK)
 *  [상태 플래그 조회]
 *   jpaPagingStatusJob      paging(WHERE status='READY') + 주문을 DONE 으로 merge          ❌ 절반 누락
 *   jpaCursorStatusJob      cursor(WHERE status='READY') + 주문을 DONE 으로 merge          ✅
 *  [processor 에서 엔티티만 바꾸기 (dirty checking 기대)]
 *   jpaCursorDirtyJob       cursor + processor 가 order.settle()                         ❌ 하나도 저장 안 됨
 *   jpaPagingDirtyJob       paging + processor 가 order.settle()                         ⚠️ 저장은 되는데 reader 트랜잭션으로
 *   jpaPagingDirtySkipJob   위 + 정산 13 쓰기 실패 → skip                                ❌ 정산은 없는데 주문은 DONE
 *   jpaSettleAndMarkSkipJob 정산 + 상태 변경을 writer 에서 같이, 13 skip                   ✅ 13 은 둘 다 안 남음
 *  [JPA writer + retry]
 *   jpaWriteRetryJob        flush 가 한 번 실패 → write 재시도                             ❌ 같은 트랜잭션에서 PK 충돌 → FAILED
 *   jpaWriteRetryScanJob    위 + skip 설정 → scan 이 한 건씩 새 트랜잭션으로 다시 씀        ✅
 */
@Configuration
class JpaJobConfig(
    private val steps: JpaSteps,
    private val readers: JpaReaderConfig,
    private val writers: JpaWriterConfig,
    private val failureInjector: FailureInjector,
) {

    // ================================================================ 기본

    @Bean
    fun jpaPagingJob(): Job = steps.job(PAGING_JOB,
        readers.allOrdersPagingReader(), SettlementProcessor(failureInjector), writers.settlementJpaWriter())

    // ================================================================ 상태 플래그 조회

    /** 01-2 의 오프셋 페이징 문제를 진짜 JpaPagingItemReader 로 재현 */
    @Bean
    fun jpaPagingStatusJob(): Job = steps.job(PAGING_STATUS_JOB,
        readers.readyOrdersPagingReader(), SettleOrderProcessor(), writers.orderJpaWriter())

    /** 쿼리를 한 번만 실행하는 cursor 는 목록이 당겨질 일이 없다 */
    @Bean
    fun jpaCursorStatusJob(): Job = steps.job(CURSOR_STATUS_JOB,
        readers.readyOrdersCursorReader(), SettleOrderProcessor(), writers.orderJpaWriter())

    // ================================================================ processor 에서 엔티티만 바꾸기

    @Bean
    fun jpaCursorDirtyJob(): Job = steps.job(CURSOR_DIRTY_JOB,
        readers.allOrdersCursorReader(), SettlementProcessor(failureInjector, touchOrder = true),
        writers.settlementJpaWriter())

    @Bean
    fun jpaPagingDirtyJob(): Job = steps.job(PAGING_DIRTY_JOB,
        readers.allOrdersPagingReader(), SettlementProcessor(failureInjector, touchOrder = true),
        writers.settlementJpaWriter())

    @Bean
    fun jpaPagingDirtySkipJob(): Job = steps.job(PAGING_DIRTY_SKIP_JOB,
        readers.allOrdersPagingReader(), SettlementProcessor(failureInjector, touchOrder = true),
        writers.settlementJpaWriter()) {
        faultTolerant()
        skip(PersistenceException::class.java)
        skipLimit(10)
    }

    @Bean
    fun jpaSettleAndMarkSkipJob(): Job = steps.job(SETTLE_AND_MARK_SKIP_JOB,
        readers.allOrdersPagingReader(), SettlementProcessor(failureInjector), writers.settleAndMarkDoneWriter()) {
        faultTolerant()
        skip(PersistenceException::class.java)
        skipLimit(10)
    }

    // ================================================================ JPA writer + retry

    @Bean
    fun jpaWriteRetryJob(): Job = steps.job(WRITE_RETRY_JOB,
        readers.allOrdersPagingReader(), SettlementProcessor(failureInjector),
        FlakyFlushWriter(writers.settlementJpaWriter(), failOrderId = 13)) {
        faultTolerant()
        retryPolicy(retryPersistence())
    }

    @Bean
    fun jpaWriteRetryScanJob(): Job = steps.job(WRITE_RETRY_SCAN_JOB,
        readers.allOrdersPagingReader(), SettlementProcessor(failureInjector),
        FlakyFlushWriter(writers.settlementJpaWriter(), failOrderId = 13)) {
        faultTolerant()
        retryPolicy(retryPersistence())
        // 재시도가 소진되면 skip 가능 여부를 보고 → 가능하면 scan (한 건씩 새 트랜잭션 = 새 EntityManager)
        skip(PersistenceException::class.java)
        skipLimit(10)
    }

    /** flush 때 나는 DB 오류(PersistenceException)를 최대 2번 재시도 */
    private fun retryPersistence(): RetryPolicy = RetryPolicy.builder()
        .includes(PersistenceException::class.java)
        .maxRetries(2)
        .delay(Duration.ofMillis(10))
        .build()

    companion object {
        const val PAGING_JOB = "jpaPagingJob"
        const val PAGING_STATUS_JOB = "jpaPagingStatusJob"
        const val CURSOR_STATUS_JOB = "jpaCursorStatusJob"
        const val CURSOR_DIRTY_JOB = "jpaCursorDirtyJob"
        const val PAGING_DIRTY_JOB = "jpaPagingDirtyJob"
        const val PAGING_DIRTY_SKIP_JOB = "jpaPagingDirtySkipJob"
        const val SETTLE_AND_MARK_SKIP_JOB = "jpaSettleAndMarkSkipJob"
        const val WRITE_RETRY_JOB = "jpaWriteRetryJob"
        const val WRITE_RETRY_SCAN_JOB = "jpaWriteRetryScanJob"
    }
}
