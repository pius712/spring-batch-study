package com.example.toybatch

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

/**
 * 주의: Spring Boot 에서는 @EnableBatchProcessing 을 붙이지 않는다.
 * 붙이면 Boot 의 배치 자동설정(JobRepository, 스키마 초기화, JobLauncherApplicationRunner)이 꺼진다.
 */
@SpringBootApplication
class ToyBatchApplication

fun main(args: Array<String>) {
    exitProcess(SpringApplication.exit(runApplication<ToyBatchApplication>(*args)))
}
