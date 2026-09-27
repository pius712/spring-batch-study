package com.example.toybatch.flow.support

import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.infrastructure.item.support.ListItemReader
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * flow 예제의 reader / processor. JobParameter 로 "몇 건을 읽고, 어디서 실패할지" 정한다.
 *
 *   inputCount : 1..inputCount 를 읽는다 (0 이면 읽을 게 없음)
 *   failAt     : 이 번호에서 예외 → 스텝 FAILED
 *
 * (잡 설정에서 null 을 넘겨 호출하는 건 프록시를 받기 위한 관례. 실제 값은 스텝 실행 시점에 주입된다)
 */
@Configuration
class FlowReaderConfig {

    @Bean
    @StepScope
    fun flowNumberReader(@Value("#{jobParameters['inputCount']}") inputCount: Long?): ListItemReader<Int> =
        ListItemReader((1..inputCount!!.toInt()).toList())

    @Bean
    @StepScope
    fun flowValidateProcessor(@Value("#{jobParameters['failAt']}") failAt: Long?): ValidateProcessor =
        ValidateProcessor(failAt?.toInt())
}
