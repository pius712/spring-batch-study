# spring-batch-study

Spring Batch 학습용 예제 모음. (Spring Boot 4.1 / Spring Batch 6.0 / Hibernate 7.4 / Kotlin 2.3 / Java 21 / H2)

## 구조

```
com.example.toybatch
├── common/            여러 예제가 같이 쓰는 것 (실패 주입기, 배치 인프라 설정, ExecutionContext 로깅 리스너)
├── restart/
│   ├── basic/         01-1. 재시작과 ExecutionContext
│   └── jdbc/          01-2. DB reader 재시작 (버그 나는/안 나는 케이스)
├── faulttolerance/
│   ├── basic/         02-1. retry / skip / 둘의 조합
│   ├── writer/        02-2. writer 에서의 retry/skip (질문별 잡 + 해결 writer)
│   ├── fault/         실패 주입 부품 (Fault, 예외, FaultInjectingReader/Processor/Writer)
│   └── support/       공통 뼈대·기록 (FaultToleranceSteps, AttemptRecorder, 리스너)
├── jpa/
│   ├── basic/         03-1. JPA reader / writer (EntityManager 기반)
│   ├── repository/    03-2. Spring Data Repository (RepositoryItemReader/Writer, 키 기반 reader)
│   ├── domainlayer/   03-3. 도메인 계층 reader / writer 재사용 (domain/ = 도메인 코드라고 가정한 부분)
│   └── support/       공통: 엔티티, 리포지토리, processor, 잡 뼈대(JpaSteps)
├── flow/
│   ├── conditional/   04-1. 조건부 flow (ExitStatus 로 분기)
│   ├── decider/       04-2. JobExecutionDecider
│   ├── builder/       04-3. FlowBuilder (재사용, split)
│   └── support/       공통 reader / processor
└── context/           05-1. StepExecution / StepContribution / Job·Step ExecutionContext
```
```

`compare/batch5/` 는 메인 빌드와 별개인 Boot 3.5 / **Batch 5.2** 프로젝트다. 02 의 retry/scan 케이스를 Batch 5 로 돌려서 6 과 비교한다.
실행: `./gradlew -p compare/batch5 test`

새 주제는 `com.example.toybatch.<주제>.<세부주제>` 패키지 + `docs/NN-M-<주제>.md` + 같은 패키지의 테스트로 추가한다.
한 주제 안에서 여러 예제가 같이 쓰는 코드는 `<주제>/support` 에, 여러 주제가 같이 쓰는 코드는 `common` 에 둔다.
문서 맨 위에는 "코드 지도"(파일 링크), 맨 아래에는 "테스트로 따라가기"(테스트 메서드 링크)를 둔다.
잡/스텝 이름은 다른 예제와 겹치지 않게 `<잡이름>.<스텝이름>` 형태로 짓는다.
reader 는 `<주제>ReaderConfig` 에 `@StepScope` 빈으로, writer 는 필요하면 `<주제>WriterConfig` 로 잡 설정과 분리한다.

## 예제 목록

| # | 패키지 | 문서 | 내용 |
|---|--------|------|------|
| 01-1 | `restart.basic` | [01-1-restart-execution-context.md](docs/01-1-restart-execution-context.md) | 실패한 잡 재시작 시 ExecutionContext 로 이어서 처리하기 |
| 01-2 | `restart.jdbc` | [01-2-restart-jdbc-reader.md](docs/01-2-restart-jdbc-reader.md) | DB reader 에서 재시작이 안전한가? (개수 vs 키 기반, 범위 고정 vs 상태 플래그) |
| 02-1 | `faulttolerance` | [02-1-fault-tolerance.md](docs/02-1-fault-tolerance.md) | retry, skip, 조합. Batch 6 에서 바뀐 동작(write 재시도, scan) 포함 |
| 02-2 | `faulttolerance` | [02-2-writer-fault-tolerance.md](docs/02-2-writer-fault-tolerance.md) | writer retry/skip 질문별: 재시도 중복과 해결, retry/skip 한도 단위, scan I/O, skip 한도 초과 후 재시작 누락 |
| 03-1 | `jpa.basic` | [03-1-jpa-reader-writer.md](docs/03-1-jpa-reader-writer.md) | JPA reader/writer. dirty checking 함정, 진짜 `JpaPagingItemReader` 오프셋 누락, JPA writer 재시도 |
| 03-2 | `jpa.repository` | [03-2-jpa-repository.md](docs/03-2-jpa-repository.md) | `RepositoryItemReader`/`Writer` 와 직접 만든 키 기반 repository reader. offset 누락, flush 안 하는 writer 로 UNKNOWN |
| 03-3 | `jpa.domainlayer` | [03-3-jpa-domain-layer.md](docs/03-3-jpa-domain-layer.md) | 도메인 계층 reader/writer(안에서 JpaRepository)를 배치에서 재사용. REQUIRES_NEW 불일치, `@Transactional` 도메인 메서드 예외를 skip 하면 청크가 조용히 사라짐 |
| 04-1 | `flow.conditional` | [04-1-flow.md](docs/04-1-flow.md) | 조건부 flow. chunk 스텝의 ExitStatus 를 리스너로 바꿔 분기 (NO_DATA), 실패를 분기로 처리하면 잡이 COMPLETED (스텝은 ABANDONED) |
| 04-2 | `flow.decider` | [04-2-decider.md](docs/04-2-decider.md) | `JobExecutionDecider` 로 분기. 리스너로 ExitStatus 바꾸기와 비교 (기록, 재시작 시 재평가) |
| 04-3 | `flow.builder` | [04-3-flow-builder.md](docs/04-3-flow-builder.md) | `FlowBuilder` 로 Flow 를 만들어 여러 잡에서 재사용, `split` 으로 병렬 실행 |
| 05-1 | `context` | [05-1-step-context.md](docs/05-1-step-context.md) | chunk 스텝에서 StepExecution / StepContribution / Job·Step EC 를 받는 법, 각각 언제 어느 테이블에 저장되는지 |

## 읽는 순서

번호 순서대로 읽으면 된다. 뒤 문서가 앞 문서의 개념을 전제로 한다.
각 문서 맨 위 **코드 지도**에서 파일로, 본문의 잡·빈 이름에서 정의된 줄로, 맨 아래 **테스트로 따라가기**에서 테스트 메서드로 바로 갈 수 있다.

```
01-1 재시작 원리 → 01-2 DB reader 재시작 → 02-1 retry/skip → 02-2 writer retry/skip 심화 → 03-1 JPA → 03-2 Repository → 03-3 도메인 계층 → 04-1 Flow → 04-2 Decider → 04-3 FlowBuilder → 05-1 Context
```

- JPA 가 급하면: 01-1 → 01-2 → 03-1 → 03-2 → 03-3 (retry/skip 이 나오면 02 로 돌아가서)
- 장애 대응(retry/skip) 설계가 급하면: 01-1 → 02-1 → 02-2

## 실행

```bash
# 테스트 (in-memory H2, 가장 빠르게 보는 방법)
./gradlew test --tests '*RestartJobTest'
./gradlew test --tests '*RestartJdbcJobTest'
./gradlew test --tests '*faulttolerance*'
./gradlew test --tests '*WriterFaultToleranceTest'
./gradlew test --tests '*JpaJobTest'
./gradlew test --tests '*RepositoryJobTest'
./gradlew test --tests '*DomainLayerJobTest'
./gradlew test --tests '*FlowJobTest'
./gradlew test --tests '*DeciderJobTest'
./gradlew test --tests '*FlowBuilderJobTest'
./gradlew test --tests '*ContextJobTest'

# 앱으로 직접 실행 (파일 H2: ./data/batch-db, 프로세스를 여러 번 띄워도 메타데이터 유지)
./gradlew bootRun --args='--spring.batch.job.name=restartJob --demo.fail-at=57 targetDate=2026-09-25'  # 1차: 실패
./gradlew bootRun --args='--spring.batch.job.name=restartJob targetDate=2026-09-25'                     # 2차: 재시작

# 메타데이터 초기화
rm -rf data
```
