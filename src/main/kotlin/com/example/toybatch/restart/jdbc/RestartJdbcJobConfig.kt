package com.example.toybatch.restart.jdbc

import com.example.toybatch.common.FailureInjector
import com.example.toybatch.common.ExecutionContextLoggingListener
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * 01-2. DB reader 재시작 예제. 모든 잡은 스텝 1개(chunk=10)이고, 읽는 테이블은 rj_order.
 * reader 는 RestartJdbcReaderConfig 에 있다.
 *
 *   [방법 1. 범위 고정 : WHERE order_date = :targetDate]
 *   rjCursorJob         JdbcCursorItemReader  (개수 기반, read.count)       범위 안 행이 바뀌면 ❌
 *   rjPagingJob         JdbcPagingItemReader  (키 기반, start.after=id)     범위 안 행이 바뀌어도 ✅
 *
 *   [방법 2. 상태 플래그 : WHERE status = 'READY', writer 가 DONE 으로 변경]
 *   rjStatusStateJob    cursor, saveState=true                              재시작하면 전부 건너뜀 ❌
 *   rjStatusNoStateJob  cursor, saveState=false                             ✅
 *   rjOffsetStatusJob   오프셋 페이징(LIMIT/OFFSET)                          재시작 없이도 절반 누락 ❌
 *   rjKeyStatusJob      JdbcPagingItemReader, saveState=false               ✅
 */
@Configuration
class RestartJdbcJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val jdbcTemplate: JdbcTemplate,
    private val failureInjector: FailureInjector,
    private val readers: RestartJdbcReaderConfig,
) {

    // ================================================================ 방법 1. 범위 고정

    @Bean
    fun rjCursorJob(): Job = job(CURSOR_JOB, readers.rangeCursorReader(null), markDone = false)

    @Bean
    fun rjPagingJob(): Job = job(PAGING_JOB, readers.rangePagingReader(null), markDone = false)

    // ================================================================ 방법 2. 상태 플래그

    @Bean
    fun rjStatusStateJob(): Job = job(STATUS_STATE_JOB, readers.statusCursorReaderWithState(), markDone = true)

    @Bean
    fun rjStatusNoStateJob(): Job = job(STATUS_NO_STATE_JOB, readers.statusCursorReaderNoState(), markDone = true)

    @Bean
    fun rjOffsetStatusJob(): Job = job(OFFSET_STATUS_JOB, readers.statusOffsetPagingReader(), markDone = true)

    @Bean
    fun rjKeyStatusJob(): Job = job(KEY_STATUS_JOB, readers.statusKeyPagingReader(), markDone = true)

    // ================================================================ 공통

    private fun job(jobName: String, reader: ItemStreamReader<OrderRow>, markDone: Boolean): Job =
        JobBuilder(jobName, jobRepository)
            .start(
                StepBuilder("$jobName.orderStep", jobRepository)
                    .chunk<OrderRow, OrderRow>(CHUNK_SIZE)
                    .transactionManager(transactionManager)
                    .reader(reader)
                    .writer(OrderWriter(jobName, jdbcTemplate, failureInjector, markDone))
                    .listener(ExecutionContextLoggingListener())
                    .build()
            )
            .build()

    companion object {
        const val CURSOR_JOB = "rjCursorJob"
        const val PAGING_JOB = "rjPagingJob"
        const val STATUS_STATE_JOB = "rjStatusStateJob"
        const val STATUS_NO_STATE_JOB = "rjStatusNoStateJob"
        const val OFFSET_STATUS_JOB = "rjOffsetStatusJob"
        const val KEY_STATUS_JOB = "rjKeyStatusJob"

        const val CHUNK_SIZE = 10
    }
}
