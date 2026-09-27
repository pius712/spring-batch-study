plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21" // @Configuration/@Component 클래스를 open 으로 (CGLIB 프록시용)
    kotlin("plugin.jpa") version "2.3.21"    // @Entity 에 기본 생성자 추가
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// Hibernate 가 지연 로딩 프록시(상속)를 만들 수 있도록 엔티티를 open 으로
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

repositories {
    mavenCentral()
}

dependencies {
    // Boot 4 부터 starter-batch 는 JobRepository 를 메모리(Resourceless)로 쓴다. 메타데이터를 DB 에 저장하려면 batch-jdbc
    implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa") // jpa 예제 (03)
    implementation("org.springframework.boot:spring-boot-starter-jackson") // ExecutionContext 를 JSON 으로 저장해서 DB 에서 읽어보려고
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-batch-jdbc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
    }
}
