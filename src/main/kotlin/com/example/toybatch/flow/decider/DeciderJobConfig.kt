package com.example.toybatch.flow.decider

import com.example.toybatch.flow.support.FlowReaderConfig
import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * 04-2. JobExecutionDecider 로 분기하는 예제.
 *
 *   deciderJob
 *     processStep(chunk=10) → sizeDecider ──LARGE──→ reportStep
 *                                  └───────*──────→ end
 *
 * 스텝의 ExitStatus 를 바꾸지 않고, 스텝 사이에 끼운 decider 가 다음 갈 곳을 정한다.
 * reader 는 flow.support.FlowReaderConfig 에 있다 (04-1 과 같은 reader).
 */
@Configuration
class DeciderJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val readers: FlowReaderConfig,
) {

    @Bean
    fun deciderJob(): Job {
        val sizeDecider = SizeDecider(LARGE_THRESHOLD) // from() 에서 다시 찾으므로 한 번만 만든다

        return JobBuilder(JOB_NAME, jobRepository)
            .start(processStep())
            .next(sizeDecider)
                .on(SizeDecider.LARGE).to(reportStep())
            .from(sizeDecider)
                .on("*").end()
            .end()
            .build()
    }

    private fun processStep(): Step = StepBuilder("$JOB_NAME.processStep", jobRepository)
        .chunk<Int, Int>(CHUNK_SIZE)
        .transactionManager(transactionManager)
        .reader(readers.flowNumberReader(null))
        .writer(ItemWriter { chunk -> log.info(">>> [processStep] write {}", chunk.items) })
        .build()

    private fun reportStep(): Step = StepBuilder("$JOB_NAME.reportStep", jobRepository)
        .tasklet({ _, _ ->
            log.info(">>> [reportStep] 실행됨")
            RepeatStatus.FINISHED
        }, transactionManager)
        .build()

    companion object {
        const val JOB_NAME = "deciderJob"
        private const val CHUNK_SIZE = 10
        private const val LARGE_THRESHOLD = 100L
        private val log = LoggerFactory.getLogger(DeciderJobConfig::class.java)
    }
}
