package com.example.toybatch.restart.basic

import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * restart 예제의 reader 들.
 *
 * @StepScope + jobExecutionContext 바인딩.
 * 재시작 때 prepareStep 은 건너뛰지만 jobExecutionContext 가 DB 에서 복구되므로 totalCount 가 주입된다.
 * (잡 설정에서 null 을 넘겨 호출하는 건 프록시를 받기 위한 관례. 실제 값은 스텝 실행 시점에 주입된다)
 */
@Configuration
class RestartReaderConfig {

    @Bean
    @StepScope
    fun statefulNumberReader(@Value("#{jobExecutionContext['totalCount']}") totalCount: Int?): NumberReader =
        NumberReader("numberReader", totalCount!!, saveState = true)

    /** 비교용: 위치를 저장하지 않는다 → 재시작하면 처음부터 다시 읽는다 */
    @Bean
    @StepScope
    fun statelessNumberReader(@Value("#{jobExecutionContext['totalCount']}") totalCount: Int?): NumberReader =
        NumberReader("numberReader", totalCount!!, saveState = false)
}
