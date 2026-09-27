package com.example.toybatch.context

import org.springframework.batch.core.repository.ExecutionContextSerializer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 메모리의 ExecutionContext 말고, DB 에 "지금 저장돼 있는" 값을 직접 읽는다.
 * Job EC 와 Step EC 는 테이블이 다르다.
 */
@Component
class PersistedContextReader(
    private val jdbcTemplate: JdbcTemplate,
    private val serializer: ExecutionContextSerializer,
) {

    fun stepContext(stepExecutionId: Long): Map<String, Any> =
        read("SELECT SHORT_CONTEXT FROM BATCH_STEP_EXECUTION_CONTEXT WHERE STEP_EXECUTION_ID = ?", stepExecutionId)

    fun jobContext(jobExecutionId: Long): Map<String, Any> =
        read("SELECT SHORT_CONTEXT FROM BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID = ?", jobExecutionId)

    private fun read(sql: String, id: Long): Map<String, Any> {
        val context = jdbcTemplate.queryForList(sql, String::class.java, id).firstOrNull() ?: return emptyMap()
        return serializer.deserialize(context.byteInputStream())
    }
}
