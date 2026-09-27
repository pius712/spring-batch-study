package com.example.toybatch.context

import com.example.toybatch.common.FailureInjector
import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.listener.ExecutionContextPromotionListener
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
 * 05-1 의 5절. Job EC 를 잘 쓴 예 vs 나쁜 예. 둘 다 "1..30 의 합계를 구해서 다음 스텝에 넘긴다".
 *
 *   contextPromotionJob  ✅  sumStep: 합계를 Step EC 에 누적 → 끝나면 ExecutionContextPromotionListener 가 Job EC 로
 *                            reportStep: #{jobExecutionContext} 에서 합계를 읽는다
 *   contextJobEcSumJob   ❌  sumStep: 청크마다 Job EC 에 바로 누적 → reportStep 은 같음
 *
 * 25 에서 실패시킨 뒤 재시작하면 ✅ 는 465 (정답), ❌ 는 720 (21~30 이 두 번 더해짐).
 */
@Configuration
class ContextPromotionJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val failureInjector: FailureInjector,
) {

    companion object {
        const val PROMOTION_JOB = "contextPromotionJob"
        const val JOB_EC_SUM_JOB = "contextJobEcSumJob"
        const val SEEN_TOTAL_KEY = "report.seenTotal"
        private const val TOTAL_COUNT = 30
        private const val CHUNK_SIZE = 10
        private val log = LoggerFactory.getLogger(ContextPromotionJobConfig::class.java)
    }

    @Bean
    fun contextPromotionJob(): Job {
        val writer = StepContextSumWriter(failureInjector)
        val sumStep = StepBuilder("$PROMOTION_JOB.sumStep", jobRepository)
            .chunk<Int, Int>(CHUNK_SIZE)
            .transactionManager(transactionManager)
            .reader(PositionReader("$PROMOTION_JOB.sumStep", TOTAL_COUNT))
            .writer(writer) // ItemStream 이라 open/update 가 불린다
            .listener(promotion(StepContextSumWriter.TOTAL_KEY) as StepExecutionListener)
            .build()
        return JobBuilder(PROMOTION_JOB, jobRepository)
            .start(sumStep)
            .next(reportStep(PROMOTION_JOB))
            .build()
    }

    @Bean
    fun contextJobEcSumJob(): Job {
        val writer = JobContextSumWriter(failureInjector)
        val sumStep = StepBuilder("$JOB_EC_SUM_JOB.sumStep", jobRepository)
            .chunk<Int, Int>(CHUNK_SIZE)
            .transactionManager(transactionManager)
            .reader(PositionReader("$JOB_EC_SUM_JOB.sumStep", TOTAL_COUNT))
            .writer(writer as ItemWriter<Int>)
            .listener(writer as StepExecutionListener)
            .build()
        return JobBuilder(JOB_EC_SUM_JOB, jobRepository)
            .start(sumStep)
            .next(reportStep(JOB_EC_SUM_JOB))
            .build()
    }

    /**
     * 스텝 끝(afterStep)에 Step EC 의 지정한 키를 Job EC 로 복사한다.
     * 기본 statuses = [COMPLETED] → 실패한 스텝의 값은 옮기지 않는다.
     */
    private fun promotion(vararg keys: String) = ExecutionContextPromotionListener().apply {
        setKeys(arrayOf(*keys))
        afterPropertiesSet()
    }

    /** 다음 스텝: Job EC 의 합계를 "받기만" 한다. 받은 값을 자기 Step EC 에 남겨 테스트에서 확인 */
    private fun reportStep(jobName: String): Step = StepBuilder("$jobName.reportStep", jobRepository)
        .tasklet({ contribution, chunkContext ->
            val seen = chunkContext.stepContext.jobExecutionContext[StepContextSumWriter.TOTAL_KEY]
            log.info("    [reportStep] Job EC 에서 받은 {} = {}", StepContextSumWriter.TOTAL_KEY, seen)
            contribution.stepExecution.executionContext.put(SEEN_TOTAL_KEY, seen?.toString())
            RepeatStatus.FINISHED
        }, transactionManager)
        .build()
}
