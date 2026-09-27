package com.example.toybatch.jpa.basic

import com.example.toybatch.jpa.support.JpaOrder
import com.example.toybatch.jpa.support.JpaSteps
import jakarta.persistence.EntityManagerFactory
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.infrastructure.item.database.JpaCursorItemReader
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader
import org.springframework.batch.infrastructure.item.database.builder.JpaCursorItemReaderBuilder
import org.springframework.batch.infrastructure.item.database.builder.JpaPagingItemReaderBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 03-1. jpa_order 를 EntityManager 로 읽는 reader 들 (JpaPagingItemReader, JpaCursorItemReader).
 *
 * 전부 @StepScope 라서 스텝이 실행될 때마다 새 인스턴스가 만들어진다 → 여러 잡이 같은 빈을 써도 상태가 섞이지 않는다.
 * 반환 타입은 ItemStream 을 구현한 구체 타입으로 둔다 (ItemReader 로 두면 프록시가 ItemStream 이 아니라서 open/update 가 안 불린다).
 */
@Configuration
class JpaReaderConfig(private val emf: EntityManagerFactory) {

    /** 페이지마다 자기 트랜잭션: begin → flush → clear → SELECT ... OFFSET → commit */
    @Bean
    @StepScope
    fun allOrdersPagingReader(): JpaPagingItemReader<JpaOrder> = pagingReader("allOrdersPagingReader", ALL_ORDERS)

    @Bean
    @StepScope
    fun readyOrdersPagingReader(): JpaPagingItemReader<JpaOrder> = pagingReader("readyOrdersPagingReader", READY_ORDERS)

    /** 쿼리 1번 + 결과 스트리밍. 자기 EntityManager, 트랜잭션 없음, 청크 커밋 때마다 clear */
    @Bean
    @StepScope
    fun allOrdersCursorReader(): JpaCursorItemReader<JpaOrder> = cursorReader("allOrdersCursorReader", ALL_ORDERS)

    @Bean
    @StepScope
    fun readyOrdersCursorReader(): JpaCursorItemReader<JpaOrder> = cursorReader("readyOrdersCursorReader", READY_ORDERS)

    private fun pagingReader(name: String, jpql: String): JpaPagingItemReader<JpaOrder> =
        JpaPagingItemReaderBuilder<JpaOrder>()
            .name(name)
            .entityManagerFactory(emf)
            .queryString(jpql)
            .pageSize(JpaSteps.CHUNK_SIZE)
            .build()

    private fun cursorReader(name: String, jpql: String): JpaCursorItemReader<JpaOrder> =
        JpaCursorItemReaderBuilder<JpaOrder>()
            .name(name)
            .entityManagerFactory(emf)
            .queryString(jpql)
            .build()

    companion object {
        private const val ALL_ORDERS = "SELECT o FROM JpaOrder o ORDER BY o.id"
        private const val READY_ORDERS = "SELECT o FROM JpaOrder o WHERE o.status = 'READY' ORDER BY o.id"
    }
}
