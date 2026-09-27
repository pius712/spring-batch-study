package com.example.toybatch.jpa.repository

import com.example.toybatch.jpa.support.JpaOrder
import com.example.toybatch.jpa.support.JpaOrderRepository
import com.example.toybatch.jpa.support.JpaSteps
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.infrastructure.item.data.RepositoryItemReader
import org.springframework.batch.infrastructure.item.data.builder.RepositoryItemReaderBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Sort

/**
 * 03-2. jpa_order 를 Spring Data Repository 로 읽는 reader 들 (RepositoryItemReader, 직접 만든 키 기반 reader).
 *
 * 전부 @StepScope 라서 스텝이 실행될 때마다 새 인스턴스가 만들어진다 → 여러 잡이 같은 빈을 써도 상태가 섞이지 않는다.
 * 반환 타입은 ItemStream 을 구현한 구체 타입으로 둔다 (ItemReader 로 두면 프록시가 ItemStream 이 아니라서 open/update 가 안 불린다).
 */
@Configuration
class RepositoryReaderConfig(private val orderRepository: JpaOrderRepository) {

    /**
     * RepositoryItemReader: repository 의 메서드를 "이름(문자열)" 으로 지정하고, 마지막 인자로 Pageable 을 붙여 호출한다.
     * PageRequest.of(page, pageSize) → OFFSET 방식이라 JpaPagingItemReader 와 같은 성질을 가진다.
     * 청크 트랜잭션 안에서 호출되므로 읽은 엔티티는 청크 트랜잭션의 EntityManager 소속.
     */
    @Bean
    @StepScope
    fun allOrdersRepositoryReader(): RepositoryItemReader<JpaOrder> = RepositoryItemReaderBuilder<JpaOrder>()
        .name("allOrdersRepositoryReader")
        .repository(orderRepository)
        .methodName("findAll") // findAll(Pageable)
        .sorts(mapOf("id" to Sort.Direction.ASC))
        .pageSize(JpaSteps.CHUNK_SIZE)
        .build()

    @Bean
    @StepScope
    fun readyOrdersRepositoryReader(): RepositoryItemReader<JpaOrder> = RepositoryItemReaderBuilder<JpaOrder>()
        .name("readyOrdersRepositoryReader")
        .repository(orderRepository)
        .methodName("findByStatus") // findByStatus("READY", Pageable)
        .arguments(listOf("READY"))
        .sorts(mapOf("id" to Sort.Direction.ASC))
        .pageSize(JpaSteps.CHUNK_SIZE)
        .build()

    /** Repository 메서드를 람다로 직접 호출하는 키 기반 reader (직접 만든 것) */
    @Bean
    @StepScope
    fun allOrdersKeysetReader(): KeysetRepositoryItemReader<JpaOrder> =
        KeysetRepositoryItemReader("allOrdersKeysetReader", JpaSteps.CHUNK_SIZE, keyOf = { it.id }) { lastId, limit ->
            orderRepository.findByIdGreaterThanOrderByIdAsc(lastId, limit)
        }

    @Bean
    @StepScope
    fun readyOrdersKeysetReader(): KeysetRepositoryItemReader<JpaOrder> =
        KeysetRepositoryItemReader("readyOrdersKeysetReader", JpaSteps.CHUNK_SIZE, keyOf = { it.id }) { lastId, limit ->
            orderRepository.findByStatusAndIdGreaterThanOrderByIdAsc("READY", lastId, limit)
        }
}
