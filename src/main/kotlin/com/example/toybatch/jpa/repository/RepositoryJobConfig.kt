package com.example.toybatch.jpa.repository

import com.example.toybatch.common.FailureInjector
import com.example.toybatch.jpa.basic.JpaWriterConfig
import com.example.toybatch.jpa.support.JpaSteps
import com.example.toybatch.jpa.support.SettleOrderProcessor
import com.example.toybatch.jpa.support.SettlementProcessor
import jakarta.persistence.PersistenceException
import org.springframework.batch.core.job.Job
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException

/**
 * 03-2. Spring Data JPA Repository 를 그대로 쓰는 예제.
 * reader 는 RepositoryReaderConfig, writer 는 RepositoryWriterConfig 에 있다. (repoDirtySkipJob 만 03-1 의 JpaWriterConfig 를 쓴다)
 *
 *   RepositoryItemReader  : repository 메서드를 이름(문자열)으로 + Pageable(offset). 청크 트랜잭션 안에서 조회
 *   KeysetRepositoryItemReader (직접 만듦) : repository 메서드를 람다로 + "마지막 키 다음" 조회
 *   RepositoryItemWriter  : repository.saveAll(). flush 안 함 → SQL 은 커밋 때 나간다
 *
 *  [기본]
 *   repoPagingJob          RepositoryItemReader → RepositoryItemWriter              ✅ (재시작 사이 앞쪽 삭제 시 ❌ 누락)
 *   repoKeysetJob          키 기반 reader → RepositoryItemWriter                    ✅ (재시작 사이 앞쪽 삭제돼도 ✅)
 *  [상태 플래그 조회]
 *   repoStatusJob          RepositoryItemReader(findByStatus) + DONE 저장           ❌ 절반 누락
 *   repoKeysetStatusJob    키 기반 reader(status + id > ?) + DONE 저장              ✅
 *  [processor 에서 엔티티만 바꾸기]
 *   repoDirtyJob           processor 가 order.settle()                            ⚠️ 저장된다 (청크 트랜잭션의 dirty checking)
 *   repoDirtySkipJob       위 + 정산 13 쓰기 실패 → skip                           ❌ 정산 11~20(13 제외)은 있는데 주문 11~20 은 READY
 *  [RepositoryItemWriter + skip]
 *   repoWriterSkipJob      RepositoryItemWriter + 정산 13 실패 → skip 기대          ❌ 커밋 때 터져서 skip 안 됨, 잡 UNKNOWN
 *   repoFlushWriterSkipJob saveAllAndFlush writer + skip                          ✅ 13 만 skip
 */
@Configuration
class RepositoryJobConfig(
    private val steps: JpaSteps,
    private val readers: RepositoryReaderConfig,
    private val writers: RepositoryWriterConfig,
    private val jpaWriters: JpaWriterConfig,
    private val failureInjector: FailureInjector,
) {
    companion object {
        const val REPO_PAGING_JOB = "repoPagingJob"
        const val REPO_KEYSET_JOB = "repoKeysetJob"
        const val REPO_STATUS_JOB = "repoStatusJob"
        const val REPO_KEYSET_STATUS_JOB = "repoKeysetStatusJob"
        const val REPO_DIRTY_JOB = "repoDirtyJob"
        const val REPO_DIRTY_SKIP_JOB = "repoDirtySkipJob"
        const val REPO_WRITER_SKIP_JOB = "repoWriterSkipJob"
        const val REPO_FLUSH_WRITER_SKIP_JOB = "repoFlushWriterSkipJob"
    }

    @Bean
    fun repoPagingJob(): Job = steps.job(
        REPO_PAGING_JOB,
        readers.allOrdersRepositoryReader(),
        SettlementProcessor(failureInjector),
        writers.settlementRepositoryWriter())

    @Bean
    fun repoKeysetJob(): Job = steps.job(REPO_KEYSET_JOB,
        readers.allOrdersKeysetReader(), SettlementProcessor(failureInjector), writers.settlementRepositoryWriter())

    @Bean
    fun repoStatusJob(): Job = steps.job(REPO_STATUS_JOB,
        readers.readyOrdersRepositoryReader(), SettleOrderProcessor(), writers.orderRepositoryWriter())

    @Bean
    fun repoKeysetStatusJob(): Job = steps.job(REPO_KEYSET_STATUS_JOB,
        readers.readyOrdersKeysetReader(), SettleOrderProcessor(), writers.orderRepositoryWriter())

    @Bean
    fun repoDirtyJob(): Job = steps.job(REPO_DIRTY_JOB,
        readers.allOrdersRepositoryReader(), SettlementProcessor(failureInjector, touchOrder = true),
        writers.settlementRepositoryWriter())

    @Bean
    fun repoDirtySkipJob(): Job = steps.job(REPO_DIRTY_SKIP_JOB,
        readers.allOrdersRepositoryReader(), SettlementProcessor(failureInjector, touchOrder = true),
        jpaWriters.settlementJpaWriter()) {
        faultTolerant()
        skip(PersistenceException::class.java)
        skipLimit(10)
    }

    @Bean
    fun repoWriterSkipJob(): Job = steps.job(REPO_WRITER_SKIP_JOB,
        readers.allOrdersRepositoryReader(), SettlementProcessor(failureInjector), writers.settlementRepositoryWriter()) {
        faultTolerant()
        skip(PersistenceException::class.java, DataAccessException::class.java)
        skipLimit(10)
    }

    @Bean
    fun repoFlushWriterSkipJob(): Job = steps.job(REPO_FLUSH_WRITER_SKIP_JOB,
        readers.allOrdersRepositoryReader(), SettlementProcessor(failureInjector), writers.settlementSaveAndFlushWriter()) {
        faultTolerant()
        skip(PersistenceException::class.java, DataAccessException::class.java)
        skipLimit(10)
    }


}
