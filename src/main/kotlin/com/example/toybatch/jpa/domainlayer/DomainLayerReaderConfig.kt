package com.example.toybatch.jpa.domainlayer

import com.example.toybatch.jpa.support.JpaOrder
import com.example.toybatch.jpa.repository.KeysetRepositoryItemReader
import com.example.toybatch.jpa.domainlayer.domain.OrderReader
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 03-2 의 KeysetRepositoryItemReader 를 그대로 쓴다. 람다 안에서 repository 대신 도메인 reader 를 부를 뿐이다.
 * @StepScope: reader 가 상태(마지막 키)를 가지므로 스텝 실행마다 새로 만든다.
 */
@Configuration
class DomainLayerReaderConfig(private val orderReader: OrderReader) {

    @Bean
    @StepScope
    fun domainOrderReader(): KeysetRepositoryItemReader<JpaOrder> =
        KeysetRepositoryItemReader(
            "domainOrderReader",
            PAGE_SIZE,
            keyOf = { it.id }) { lastId, limit -> orderReader.readAfter(lastId, limit.max())
        }

    companion object {
        const val PAGE_SIZE = 10 // chunk 크기와 같게 (03-2 체크리스트)
    }
}
