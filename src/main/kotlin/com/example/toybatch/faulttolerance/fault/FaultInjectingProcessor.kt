package com.example.toybatch.faulttolerance.fault

import com.example.toybatch.faulttolerance.support.AttemptRecorder
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.ItemProcessor

/** 외부 API 호출 같은 가공 단계라고 가정. 규칙에 걸린 item 은 정해진 횟수만큼 실패한다. */
class FaultInjectingProcessor(
    private val jobName: String,
    private val recorder: AttemptRecorder,
    faults: List<Fault>,
) : ItemProcessor<Int, Int> {

    private val faults = faults.byItem()

    override fun process(item: Int): Int {
        val attempt = recorder.attempt(jobName, "process", item)
        faults[item]?.let {
            log.info("    [process] item={} attempt={}", item, attempt)
            it.check("process", attempt)
        }
        return item
    }

    companion object {
        private val log = LoggerFactory.getLogger(FaultInjectingProcessor::class.java)
    }
}
