package com.example.toybatch.flow.conditional

import com.example.toybatch.flow.support.FlowReaderConfig
import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * 04-1. chunk 스텝의 ExitStatus 로 분기하는 flow 예제. (decider 는 04-2 DeciderJobConfig)
 *
 *   flowJob
 *     processStep(chunk=10) ──FAILED──→ alertStep   (processStep 은 ABANDONED, 잡은 COMPLETED!)
 *        │ ──NO_DATA──→ end
 *        │ ──*────────→ reportStep
 *
 * processStep 의 ExitStatus 는 ExitStatusListener 가 읽은 건수를 보고 정한다.
 * on() 은 스텝의 ExitStatus 코드와 매칭한다.
 * 같은 스텝에 on() 이 여러 개면 선언 순서가 아니라 "더 구체적인 패턴"이 먼저 적용된다 (NO_DATA > *).
 * reader / processor 는 flow.support.FlowReaderConfig 에 있다.
 */
@Configuration
class FlowJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val readers: FlowReaderConfig,
) {

    @Bean
    fun flowJob(): Job {
        // from(step) 은 객체로 상태를 찾는다. 스텝을 매번 새로 만들면 다른 상태가 되므로 한 번만 만들어 재사용한다
        val processStep = processStep()

        return JobBuilder(JOB_NAME, jobRepository)
            .start(processStep)
                .on("FAILED").to(alertStep())    // 실패를 "처리"했으므로 잡은 COMPLETED 로 끝난다
            .from(processStep)
                .on(ExitStatusListener.NO_DATA).end()
            .from(processStep)
                .on("*").to(reportStep())
            .end()
            .build()
    }

    private fun processStep(): Step = StepBuilder("$JOB_NAME.processStep", jobRepository)
        .chunk<Int, Int>(CHUNK_SIZE)
        .transactionManager(transactionManager)
        .reader(readers.flowNumberReader(null))
        .processor(readers.flowValidateProcessor(null))
        .writer(ItemWriter { chunk -> log.info(">>> [processStep] write {}", chunk.items) })
        .listener(ExitStatusListener() as StepExecutionListener)
        .build()

    private fun reportStep(): Step = loggingStep("reportStep")
    private fun alertStep(): Step = loggingStep("alertStep")

    /** 분기 뒤에 오는 스텝은 흐름만 보려는 거라 로그만 찍는 tasklet 으로 둔다 */
    private fun loggingStep(name: String): Step = StepBuilder("$JOB_NAME.$name", jobRepository)
        .tasklet({ _, _ ->
            log.info(">>> [{}] 실행됨", name)
            RepeatStatus.FINISHED
        }, transactionManager)
        .build()

    companion object {
        const val JOB_NAME = "flowJob"
        private const val CHUNK_SIZE = 10
        private val log = LoggerFactory.getLogger(FlowJobConfig::class.java)
    }
}
