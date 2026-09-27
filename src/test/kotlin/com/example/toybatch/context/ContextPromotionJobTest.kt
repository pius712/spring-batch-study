package com.example.toybatch.context

import com.example.toybatch.common.FailureInjector
import com.example.toybatch.context.ContextPromotionJobConfig.Companion.SEEN_TOTAL_KEY
import com.example.toybatch.context.StepContextSumWriter.Companion.TOTAL_KEY
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.step.StepExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest

/**
 * docs/05-1-step-context.md 5절. 1..30 합계(정답 465)를 다음 스텝에 넘긴다. chunk 10, 25 에서 실패 → 재시작.
 */
@SpringBootTest(properties = [
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1", // ContextJobTest 와 같은 컨텍스트 재사용
])
class ContextPromotionJobTest @Autowired constructor(
    private val jobOperator: JobOperator,
    private val persisted: PersistedContextReader,
    private val failureInjector: FailureInjector,
    @Qualifier(ContextPromotionJobConfig.PROMOTION_JOB) private val promotionJob: Job,
    @Qualifier(ContextPromotionJobConfig.JOB_EC_SUM_JOB) private val jobEcSumJob: Job,
) {

    @BeforeEach
    fun setUp() = failureInjector.disable()

    @Test
    fun `잘 쓴 예 - 누적은 Step EC 에 하고 스텝이 끝나면 PromotionListener 가 Job EC 로 옮겨 다음 스텝에 넘긴다`() {
        val execution = run(promotionJob, params(1), "✅ 정상 실행")

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(persisted.stepContext(step(execution, "sumStep").id)[TOTAL_KEY].toString()).isEqualTo("465")
        assertThat(persisted.jobContext(execution.id)[TOTAL_KEY].toString()).`as`("스텝이 끝날 때 옮겨짐").isEqualTo("465")
        assertThat(seenByReport(execution)).isEqualTo("465")
    }

    @Test
    fun `잘 쓴 예 - 실패하면 Job EC 로 옮기지 않고, 재시작하면 Step EC 로 이어서 정확한 합계를 넘긴다`() {
        val params = params(2)
        failureInjector.failAt(25)
        val first = run(promotionJob, params, "✅ 1차 (25 에서 실패)")

        assertThat(first.status).isEqualTo(BatchStatus.FAILED)
        val firstSum = persisted.stepContext(step(first, "sumStep").id)
        assertThat(firstSum[TOTAL_KEY].toString()).`as`("1..20 까지만 커밋").isEqualTo("210")
        assertThat(persisted.jobContext(first.id)).`as`("FAILED 라 옮기지 않음").doesNotContainKey(TOTAL_KEY)

        failureInjector.disable()
        val second = run(promotionJob, params, "✅ 2차 (재시작)")

        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(step(second, "sumStep").readCount).`as`("21..30 만 다시 읽음").isEqualTo(10)
        assertThat(persisted.jobContext(second.id)[TOTAL_KEY].toString()).isEqualTo("465")
        assertThat(seenByReport(second)).`as`("정답").isEqualTo("465")
    }

    @Test
    fun `나쁜 예 - 청크마다 Job EC 에 누적하면 실패한 청크 몫이 저장되고 재시작 때 두 번 더해진다`() {
        val params = params(3)
        failureInjector.failAt(25)
        val first = run(jobEcSumJob, params, "❌ 1차 (25 에서 실패)")

        assertThat(first.status).isEqualTo(BatchStatus.FAILED)
        assertThat(step(first, "sumStep").writeCount).`as`("커밋된 건 1..20").isEqualTo(20)
        assertThat(persisted.jobContext(first.id)[TOTAL_KEY].toString())
            .`as`("그런데 롤백된 21..30 몫(255)까지 더해진 465 가 저장됨").isEqualTo("465")

        failureInjector.disable()
        val second = run(jobEcSumJob, params, "❌ 2차 (재시작)")

        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(seenByReport(second)).`as`("465 + 21..30 을 또 더함 = 720 ❗").isEqualTo("720")
    }

    // ------------------------------------------------------------------ helpers

    private fun run(job: Job, params: JobParameters, title: String): JobExecution {
        println("\n==================== ${job.name} : $title ====================")
        val execution = jobOperator.start(job, params)
        execution.stepExecutions.sortedBy { it.id }.forEach {
            println("  ${it.stepName} status=${it.status} read=${it.readCount} write=${it.writeCount} " +
                "DB Step EC=${userKeys(persisted.stepContext(it.id))}")
        }
        println("  DB Job EC=${userKeys(persisted.jobContext(execution.id))}")
        return execution
    }

    private fun userKeys(map: Map<String, Any>) = map.filterKeys { !it.startsWith("batch.") }

    private fun params(case: Long) = JobParametersBuilder().addLong("case", case).toJobParameters()

    private fun step(execution: JobExecution, name: String): StepExecution =
        execution.stepExecutions.first { it.stepName.endsWith(".$name") }

    private fun seenByReport(execution: JobExecution): String? =
        step(execution, "reportStep").executionContext.get(SEEN_TOTAL_KEY)?.toString()
}
