package com.example.toybatch.faulttolerance.support

import com.example.toybatch.faulttolerance.fault.FaultInjectingProcessor
import com.example.toybatch.faulttolerance.fault.FaultInjectingReader
import com.example.toybatch.faulttolerance.fault.FaultInjectingWriter
import com.example.toybatch.faulttolerance.fault.Faults
import com.example.toybatch.faulttolerance.fault.TransientException
import com.example.toybatch.common.ExecutionContextLoggingListener
import java.time.Duration
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.core.retry.RetryPolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager

/**
 * faulttolerance 예제 잡들의 공통 뼈대: reader(1~20) → processor → writer, chunk=5, 스텝 1개.
 * 잡마다 다른 건 "어디서 몇 번 실패하는지"(Faults)와 retry/skip 설정뿐이다.
 */
@Component
class FaultToleranceSteps(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val jdbcTemplate: JdbcTemplate,
    private val recorder: AttemptRecorder,
) {

    fun job(
        jobName: String,
        faults: Faults,
        idempotentWriter: Boolean = false,
        totalCount: Int = TOTAL_COUNT,
        chunkSize: Int = CHUNK_SIZE,
        readerSaveState: Boolean = false,
        writer: ItemWriter<Int> = FaultInjectingWriter(jobName, jdbcTemplate, recorder, idempotentWriter, faults.write),
        faultTolerance: ChunkOrientedStepBuilder<Int, Int>.() -> Unit,
    ): Job {
        val step = StepBuilder("$jobName.step", jobRepository)
            .chunk<Int, Int>(chunkSize)
            .transactionManager(transactionManager)
            .reader(FaultInjectingReader(totalCount, faults.read, readerSaveState))
            .processor(FaultInjectingProcessor(jobName, recorder, faults.process))
            .writer(writer)
            .listener(ExecutionContextLoggingListener())
            .apply(faultTolerance)
            .build()
        return JobBuilder(jobName, jobRepository).start(step).build()
    }

    fun skipListener(jobName: String) = RecordingSkipListener(jobName, recorder, jdbcTemplate)

    companion object {
        const val TOTAL_COUNT = 20
        const val CHUNK_SIZE = 5

        /**
         * .retry(X::class.java).retryLimit(n) 로도 되지만, 그렇게 만든 정책은 재시도 간격이 기본 1초라서
         * 예제에서는 간격을 10ms 로 줄인 RetryPolicy 를 직접 만든다.
         * maxRetries(2) = 최초 1회 + 재시도 2회 = 총 3번 시도.
         */
        fun retryTransient(maxRetries: Long): RetryPolicy = RetryPolicy.builder()
            .includes(TransientException::class.java)
            .maxRetries(maxRetries)
            .delay(Duration.ofMillis(10))
            .build()
    }
}
