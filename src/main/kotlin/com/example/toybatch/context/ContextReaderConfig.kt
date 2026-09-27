package com.example.toybatch.context

import org.slf4j.LoggerFactory
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.infrastructure.item.support.ListItemReader
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * @StepScope 빈은 스텝이 시작될 때 만들어지므로 SpEL 로 컨텍스트 값을 주입받을 수 있다.
 *
 *   #{jobParameters['key']}        JobParameters
 *   #{jobExecutionContext['key']}  Job EC  (앞 스텝이 넣은 값이 보인다)
 *   #{stepExecutionContext['key']} 이 스텝의 Step EC (다른 스텝 것은 안 보인다)
 *
 * (잡 설정에서 null 을 넘겨 호출하는 건 프록시를 받기 위한 관례. 실제 값은 스텝 실행 시점에 주입된다)
 */
@Configuration
class ContextReaderConfig(private val recorder: ContextRecorder) {

    @Bean
    @StepScope
    fun lateBindingReader(
        @Value("#{jobExecutionContext['job.lastItem']}") jobLastItem: Int?,
        @Value("#{stepExecutionContext['reader.position']}") ownPosition: Int?,
    ): ListItemReader<Int> {
        val observation = ReadObservation(jobLastItem, ownPosition)
        recorder.read = observation
        log.info(">>> [readStep] {}", observation)
        return ListItemReader(listOfNotNull(jobLastItem))
    }

    companion object {
        private val log = LoggerFactory.getLogger(ContextReaderConfig::class.java)
    }
}
