package com.example.toybatch.jpa.support

import com.example.toybatch.common.ExecutionContextLoggingListener
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.batch.infrastructure.item.ItemReader
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager

/** jpa 예제 잡들의 공통 뼈대: jpa_order 를 읽는 스텝 1개, chunk = 10 */
@Component
class JpaSteps(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager, // JPA 가 있으면 Boot 가 JpaTransactionManager 를 만든다
) {

    fun <O : Any> job(
        jobName: String,
        reader: ItemReader<JpaOrder>,
        processor: ItemProcessor<JpaOrder, O>,
        writer: ItemWriter<O>,
        faultTolerance: ChunkOrientedStepBuilder<JpaOrder, O>.() -> Unit = {},
    ): Job {
        val step = StepBuilder("$jobName.step", jobRepository)
            .chunk<JpaOrder, O>(CHUNK_SIZE)
            .transactionManager(transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .listener(ExecutionContextLoggingListener())
            .apply(faultTolerance)
            .build()
        return JobBuilder(jobName, jobRepository).start(step).build()
    }

    companion object {
        const val CHUNK_SIZE = 10
    }
}
