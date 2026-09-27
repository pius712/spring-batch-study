package com.example.toybatch.jpa.domainlayer

import com.example.toybatch.common.FailureInjector
import com.example.toybatch.jpa.support.JpaSettlement
import com.example.toybatch.jpa.support.JpaSteps
import com.example.toybatch.jpa.domainlayer.domain.InvalidOrderException
import com.example.toybatch.jpa.domainlayer.domain.SettlementCalculator
import com.example.toybatch.jpa.domainlayer.domain.SettlementWriter
import org.springframework.batch.core.job.Job
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 03-3. 도메인 계층의 reader / writer (안에서 JpaRepository 사용) 를 배치에서 재사용하기.
 * reader 는 DomainLayerReaderConfig 에 있다. 도메인 컴포넌트는 domain 패키지.
 *
 *   domainJob               도메인 reader(키 기반) → 도메인 계산 → 도메인 writer(REQUIRED)     ✅ 재시작도 OK
 *   domainNewTxWriterJob    위와 같은데 writer 가 REQUIRES_NEW                                ❌ 청크가 롤백돼도 정산은 남는다
 *   domainSkipJob           processor 가 도메인 계산(@Transactional 없음), 검증 실패 skip      ✅ 13 만 skip
 *   domainTxSkipJob         processor 가 도메인 계산(@Transactional), 검증 실패 skip           ❌ 13 이 있던 청크 [11..20] 이 조용히 사라짐
 *
 * writer 는 "정산 저장 → 뒤이은 작업" 순서다. 뒤이은 작업에서 FailureInjector 로 실패를 만든다.
 */
@Configuration
class DomainLayerJobConfig(
    private val steps: JpaSteps,
    private val readers: DomainLayerReaderConfig,
    private val calculator: SettlementCalculator,
    private val settlementWriter: SettlementWriter,
    private val failureInjector: FailureInjector,
) {

    @Bean
    fun domainJob(): Job = steps.job(DOMAIN_JOB,
        readers.domainOrderReader(), calculator::calculate, writer(settlementWriter::saveAll))

    @Bean
    fun domainNewTxWriterJob(): Job = steps.job(DOMAIN_NEW_TX_WRITER_JOB,
        readers.domainOrderReader(), calculator::calculate, writer(settlementWriter::saveAllInNewTransaction))

    @Bean
    fun domainSkipJob(): Job = steps.job(DOMAIN_SKIP_JOB,
        readers.domainOrderReader(), calculator::calculate, writer(settlementWriter::saveAll)) {
        faultTolerant()
        skip(InvalidOrderException::class.java)
        skipLimit(10)
    }

    @Bean
    fun domainTxSkipJob(): Job = steps.job(DOMAIN_TX_SKIP_JOB,
        readers.domainOrderReader(), calculator::calculateTransactional, writer(settlementWriter::saveAll)) {
        faultTolerant()
        skip(InvalidOrderException::class.java)
        skipLimit(10)
    }

    /** 도메인 writer 로 저장한 뒤, 같은 청크에서 이어지는 작업(예: 주문 상태 변경)이 실패할 수 있다 */
    private fun writer(save: (List<JpaSettlement>) -> Unit) = ItemWriter<JpaSettlement> { chunk ->
        save(chunk.items)
        chunk.items.forEach { failureInjector.check(it.orderId.toInt()) }
    }

    companion object {
        const val DOMAIN_JOB = "domainJob"
        const val DOMAIN_NEW_TX_WRITER_JOB = "domainNewTxWriterJob"
        const val DOMAIN_SKIP_JOB = "domainSkipJob"
        const val DOMAIN_TX_SKIP_JOB = "domainTxSkipJob"
    }
}
