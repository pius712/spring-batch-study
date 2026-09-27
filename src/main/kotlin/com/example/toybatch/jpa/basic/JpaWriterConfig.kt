package com.example.toybatch.jpa.basic

import com.example.toybatch.jpa.support.JpaOrder
import com.example.toybatch.jpa.support.JpaSettlement
import jakarta.persistence.EntityManagerFactory
import org.springframework.batch.infrastructure.item.database.JpaItemWriter
import org.springframework.batch.infrastructure.item.database.builder.JpaItemWriterBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 03-1. EntityManager 기반 writer 들. 상태가 없어서 싱글톤으로 여러 잡이 같이 쓴다. */
@Configuration
class JpaWriterConfig(private val emf: EntityManagerFactory) {

    /** 청크 트랜잭션의 EntityManager 로 merge(기본) 후 flush. PK 직접 지정 엔티티라 merge 는 SELECT 후 INSERT */
    @Bean
    fun settlementJpaWriter(): JpaItemWriter<JpaSettlement> =
        JpaItemWriterBuilder<JpaSettlement>().entityManagerFactory(emf).build()

    @Bean
    fun orderJpaWriter(): JpaItemWriter<JpaOrder> =
        JpaItemWriterBuilder<JpaOrder>().entityManagerFactory(emf).build()

    /** 정산 저장 + 주문 상태 변경을 같은 청크 트랜잭션으로 */
    @Bean
    fun settleAndMarkDoneWriter(): SettleAndMarkDoneWriter = SettleAndMarkDoneWriter(emf, settlementJpaWriter())
}
