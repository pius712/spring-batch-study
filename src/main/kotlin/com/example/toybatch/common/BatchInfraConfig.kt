package com.example.toybatch.common

import org.springframework.batch.core.repository.ExecutionContextSerializer
import org.springframework.batch.core.repository.dao.JacksonExecutionContextStringSerializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class BatchInfraConfig {

    /**
     * 기본 serializer(DefaultExecutionContextSerializer)는 Java 직렬화 + Base64 라서
     * BATCH_*_EXECUTION_CONTEXT 테이블을 조회해도 사람이 읽을 수 없다.
     * 학습용으로 JSON 으로 저장하게 바꾼다. (Boot 가 이 빈을 JobRepository 에 자동으로 꽂아준다)
     */
    @Bean
    fun executionContextSerializer(): ExecutionContextSerializer = JacksonExecutionContextStringSerializer()
}
