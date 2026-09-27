package com.example.toybatch.context

import org.slf4j.LoggerFactory
import org.springframework.batch.core.configuration.annotation.StepScope
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
    ): PositionReader {
        val observation = ReadObservation(jobLastItem, ownPosition)
        recorder.read = observation
        log.info("    [readStep] @StepScope 빈 생성 = 주입 시점. jobExecutionContext[job.lastItem]={}, stepExecutionContext[reader.position]={}",
            jobLastItem, ownPosition)
        // writeStep 과 같은 키(reader.position)를 쓰는 reader. 같은 키라도 스텝마다 따로 저장되는지 보려고 일부러 겹친다
        return PositionReader("readStep", READ_STEP_COUNT)
    }

    companion object {
        const val READ_STEP_COUNT = 3
        private val log = LoggerFactory.getLogger(ContextReaderConfig::class.java)
    }
}
