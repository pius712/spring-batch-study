package com.example.toybatch.faulttolerance.support

import org.slf4j.LoggerFactory
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryListener
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.Retryable

/**
 * Batch 6 는 retry 를 spring-retry 가 아니라 Spring Framework 7 의 core retry(RetryTemplate)로 한다.
 * 그래서 리스너도 org.springframework.core.retry.RetryListener 다.
 */
class LoggingRetryListener : RetryListener {

    override fun beforeRetry(retryPolicy: RetryPolicy, retryable: Retryable<*>) {
        log.info("    [retry] {} 재시도", retryable.name)
    }

    override fun onRetrySuccess(retryPolicy: RetryPolicy, retryable: Retryable<*>, result: Any?) {
        log.info("    [retry] {} 재시도 성공", retryable.name)
    }

    override fun onRetryPolicyExhaustion(retryPolicy: RetryPolicy, retryable: Retryable<*>, exception: RetryException) {
        log.info("    [retry] {} 재시도 소진 → {}", retryable.name, exception.cause?.message)
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoggingRetryListener::class.java)
    }
}
