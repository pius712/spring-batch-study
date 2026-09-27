package com.example.toybatch.context

import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * 05-1. chunk 스텝에서 StepExecution / StepContribution / Job·Step ExecutionContext 가 어떻게 전달되고 저장되는지.
 *
 *   contextJob
 *     writeStep (chunk=10, 1..30) : reader 는 ItemStream 으로 Step EC, writer 는 StepExecutionListener 로 StepExecution
 *     readStep                    : @StepScope reader 가 #{jobExecutionContext} / #{stepExecutionContext} 를 주입받음
 *
 * reader 는 ContextReaderConfig 에 있다.
 */
@Configuration
class ContextJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val recorder: ContextRecorder,
    private val persisted: PersistedContextReader,
    private val readers: ContextReaderConfig,
) {

    companion object {
        const val JOB_NAME = "contextJob"
        private const val TOTAL_COUNT = 30
        private const val CHUNK_SIZE = 10
        private val log = LoggerFactory.getLogger(ContextJobConfig::class.java)
    }

    @Bean
    fun contextJob(): Job = JobBuilder(JOB_NAME, jobRepository)
        .start(writeStep())
        .next(readStep())
        .build()


    private fun readStep(): Step = StepBuilder("$JOB_NAME.readStep", jobRepository)
        .chunk<Int, Int>(CHUNK_SIZE)
        .transactionManager(transactionManager)
        .reader(readers.lateBindingReader(null, null))
        .writer(ItemWriter { chunk -> log.info("    [readStep writer] write({})", chunk.items) })
        .listener(ContextTraceListener(persisted))
        .build()

    private fun writeStep(): Step {
        val writer = ContextObservingWriter(recorder, persisted)
        return StepBuilder("$JOB_NAME.writeStep", jobRepository)
            .chunk<Int, Int>(CHUNK_SIZE)
            .transactionManager(transactionManager)
            .reader(PositionReader("writeStep", TOTAL_COUNT)) // ItemStream 이라 스텝이 open/update/close 를 불러준다
            .writer(writer)
            .listener(writer as StepExecutionListener)
            .listener(ContextTraceListener(persisted))
            .build()
    }



}
