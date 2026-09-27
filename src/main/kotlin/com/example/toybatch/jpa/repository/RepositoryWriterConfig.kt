package com.example.toybatch.jpa.repository

import com.example.toybatch.jpa.support.JpaOrder
import com.example.toybatch.jpa.support.JpaOrderRepository
import com.example.toybatch.jpa.support.JpaSettlement
import com.example.toybatch.jpa.support.JpaSettlementRepository
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.batch.infrastructure.item.data.RepositoryItemWriter
import org.springframework.batch.infrastructure.item.data.builder.RepositoryItemWriterBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 03-2. Spring Data Repository 기반 writer 들. 상태가 없어서 싱글톤으로 여러 잡이 같이 쓴다. */
@Configuration
class RepositoryWriterConfig(
    private val orderRepository: JpaOrderRepository,
    private val settlementRepository: JpaSettlementRepository,
) {

    /**
     * RepositoryItemWriter: 기본은 repository.saveAll(chunk). methodName 을 주면 그 메서드를 item 마다 호출.
     * repository 는 청크 트랜잭션에 참여한다. JpaItemWriter 와 달리 flush 를 직접 안 하므로 SQL 은 커밋 직전에 나간다.
     */
    @Bean
    fun settlementRepositoryWriter(): RepositoryItemWriter<JpaSettlement> =
        RepositoryItemWriterBuilder<JpaSettlement>().repository(settlementRepository).build()

    @Bean
    fun orderRepositoryWriter(): RepositoryItemWriter<JpaOrder> =
        RepositoryItemWriterBuilder<JpaOrder>().repository(orderRepository).build()

    /**
     * RepositoryItemWriter 대신 람다로 repository 를 직접 호출. saveAllAndFlush 라서 SQL 오류가 write() 안에서 난다
     * → skip/retry 가 정상적으로 동작한다.
     */
    @Bean
    fun settlementSaveAndFlushWriter(): ItemWriter<JpaSettlement> =
        ItemWriter { chunk -> settlementRepository.saveAllAndFlush(chunk.items) }
}
