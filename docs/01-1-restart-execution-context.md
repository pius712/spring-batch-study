# 01-1. 재시작과 ExecutionContext

코드: 아래 [코드 지도](#코드-지도) 참고. 문서 안의 잡·빈·클래스 이름을 누르면 정의된 줄로 이동한다.


## 코드 지도

| 파일 | 역할 |
|---|---|
| [RestartJobConfig.kt](../src/main/kotlin/com/example/toybatch/restart/basic/RestartJobConfig.kt) | 잡 정의: restartJob, restartNoStateJob (prepareStep → numberStep) |
| [RestartReaderConfig.kt](../src/main/kotlin/com/example/toybatch/restart/basic/RestartReaderConfig.kt) | reader 빈 (@StepScope + jobExecutionContext 주입) |
| [NumberReader.kt](../src/main/kotlin/com/example/toybatch/restart/basic/NumberReader.kt) | ItemStream 을 직접 구현한 reader. open / update 에서 위치 저장·복구 |
| [PrepareTasklet.kt](../src/main/kotlin/com/example/toybatch/restart/basic/PrepareTasklet.kt) | Job ExecutionContext 에 totalCount 저장 |
| [ResultWriter.kt](../src/main/kotlin/com/example/toybatch/restart/basic/ResultWriter.kt) | 결과 테이블 insert + 실패 주입 |
| [ExecutionContextLoggingListener.kt](../src/main/kotlin/com/example/toybatch/common/ExecutionContextLoggingListener.kt) | context 내용을 로그로 출력 |
| [FailureInjector.kt](../src/main/kotlin/com/example/toybatch/common/FailureInjector.kt) | "57 에서 실패" 같은 실패 주입 스위치 |
| [BatchInfraConfig.kt](../src/main/kotlin/com/example/toybatch/common/BatchInfraConfig.kt) | ExecutionContext 를 JSON 으로 저장 |
| [RestartJobTest.kt](../src/test/kotlin/com/example/toybatch/restart/basic/RestartJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |

## 한 줄 요약

**ExecutionContext = 잡/스텝이 "어디까지 했는지" 적어두는 메모장(Map). 청크 커밋마다 DB에 같이 저장되고, 재시작하면 그 메모를 다시 꺼내준다.**

## 먼저 용어

| 개념 | 뜻 | 이 예제에서 |
|------|----|------------|
| JobInstance | 잡 이름 + identifying JobParameters 조합. "논리적인 한 번의 실행 단위" | [`restartJob`](../src/main/kotlin/com/example/toybatch/restart/basic/RestartJobConfig.kt#L36) + `targetDate=2026-09-25` |
| JobExecution | JobInstance 를 실제로 돌린 "시도" 1회 | 1차(FAILED), 2차(COMPLETED) → 2개 |
| StepExecution | 스텝을 실제로 돌린 시도 1회 | |
| **재시작** | FAILED/STOPPED 인 JobInstance 를 **같은 파라미터로** 다시 실행하는 것 | 파라미터가 하나라도 다르면 재시작이 아니라 새 JobInstance |

## ExecutionContext 는 두 종류

| | Job ExecutionContext | Step ExecutionContext |
|---|---|---|
| 테이블 | `BATCH_JOB_EXECUTION_CONTEXT` | `BATCH_STEP_EXECUTION_CONTEXT` |
| 범위 | 잡 전체 (스텝 간 공유) | 해당 스텝만 |
| 저장 시점 | 스텝이 끝날 때마다 | **청크 커밋마다** (같은 트랜잭션) |
| 주 용도 | 앞 스텝 결과를 뒤 스텝에 전달, 잡 단위 상태 | reader/writer 의 진행 위치 |
| 예제 | `totalCount=100` (PrepareTasklet) | `numberReader.read.count=50` (NumberReader) |

재시작 시 Spring Batch 는 **직전 JobExecution 의 Job context** 와 **직전 StepExecution 의 Step context** 를 복사해서 새 Execution 에 넣어준다.

## 예제 흐름

```
restartJob
 ├─ prepareStep (tasklet) : jobContext.totalCount = 100
 └─ numberStep  (chunk=10): 1..100 읽어서 테이블에 insert, 57에서 일부러 실패
```

### 1차 실행 (57에서 실패)

```
prepareStep  COMPLETED   jobContext  = {totalCount=100}
numberStep   write 1~10  → commit  stepContext = {numberReader.read.count=10}
             write 11~20 → commit  stepContext = {numberReader.read.count=20}
             ...
             write 41~50 → commit  stepContext = {numberReader.read.count=50}
             51~60 청크에서 예외 → 롤백 (insert 롤백, read.count=60 은 아예 저장 시도도 안 함)
             → FAILED. DB 에는 결과 50건, read.count=50 이 남음
```

### 2차 실행 (같은 파라미터 = 재시작)

```
prepareStep  "Step already complete" → 건너뜀 (COMPLETED 스텝은 다시 안 돈다)
numberStep   jobContext 복구 → totalCount=100 주입됨 (prepareStep 안 돌았는데도!)
             stepContext 복구 → reader.open() 에서 read.count=50 확인 → 51부터 읽음
             write 51~100 → COMPLETED. 결과 총 100건, 중복 없음
```

### 비교: `restartNoStateJob` (reader 가 상태를 저장 안 함)

재시작은 되지만(prepareStep 스킵) reader 가 1부터 다시 읽는다 → 1~50 이 **두 번** 들어가서 150건.
"재시작"은 프레임워크가 해주지만, **어디서부터 이어갈지는 ExecutionContext 에 적어둔 컴포넌트만 안다**는 것이 핵심.

## 왜 "청크 커밋과 같은 트랜잭션"이 중요한가

Batch 6 `ChunkOrientedStep.doExecute()` 의 순서:

```
[청크 트랜잭션 시작]
   read x10 → process → write(insert)
   ChunkListener.afterChunk()           ← 주의: 커밋 "전"이다 (Batch 5 는 커밋 후)
   (여기까지 예외가 났으면 → rollback-only 표시하고 아래를 건너뜀)
   reader.update(stepContext)           ← 위치 기록
   jobRepository.updateExecutionContext ← DB 저장
[커밋]
```

데이터와 위치가 **같이 커밋되거나, 둘 다 안 남으므로** "데이터는 들어갔는데 위치는 옛날 값" 같은 불일치가 안 생긴다.
(Batch 6 는 청크가 실패하면 `update()` 를 아예 부르지 않는다. 그래서 context 에는 마지막 성공 청크의 값이 남는다)
(단, writer 가 파일/외부 API 처럼 트랜잭션 밖 자원이면 이 보장은 깨진다 → 멱등성 고민 필요)

## 실무에서 어떻게 쓰이나

- **대부분은 직접 안 짠다.** `FlatFileItemReader`, `JdbcPagingItemReader`, `JdbcCursorItemReader`, `FlatFileItemWriter` 등이 이미 `ItemStream` 을 구현해서 `{name}.read.count`, 마지막 페이지 키, 파일 쓰기 위치 등을 저장한다.
  - 그래서 이 reader 들엔 **`.name("...")` 이 필수** (context 키 prefix) 이고, 스텝 하나에 같은 reader 두 개 쓰면 이름이 겹치지 않게 해야 한다.
  - `saveState(false)` 로 끌 수 있다. 멀티스레드 스텝에서는 순서가 보장되지 않아 위치가 의미 없으므로 꺼야 한다.
- **커스텀 reader 를 만들 때** `ItemReader` 만 구현하면 재시작 시 처음부터 다시 읽는다. `ItemStreamReader` 를 구현하거나 `AbstractItemCountingItemStreamItemReader` 를 상속하자.
  - Kotlin 이면 `@StepScope` 빈으로 쓸 클래스는 **`open class`** 로 만든다. `@StepScope` 는 CGLIB 로 클래스를 상속한 프록시를 만드는데 Kotlin 클래스는 기본이 final 이다. (`@Configuration`/`@Component` 는 `kotlin("plugin.spring")` 이 자동으로 open 해주지만, 빈 메서드가 반환하는 클래스는 해당 안 됨)
- **Job context 로 스텝 간 데이터 전달**: 앞 스텝에서 계산한 기준일, 대상 건수, 생성한 파일 경로 등을 넣고 뒤 스텝에서 `@StepScope` + `#{jobExecutionContext['key']}` 로 받는다. 재시작해도 복구되므로 앞 스텝을 다시 안 돌려도 된다.
  - 스텝 context 에 넣은 값을 잡 context 로 올리려면 `ExecutionContextPromotionListener`.
- **context 에는 작은 값만.** 직렬화되어 DB 에 저장된다. `SHORT_CONTEXT` 는 2500자, 넘으면 `SERIALIZED_CONTEXT`(CLOB). 대량 데이터(리스트 등)를 넣지 말 것.

## 관련 옵션

| 설정 | 효과 |
|------|------|
| `step.allowStartIfComplete(true)` | 재시작 시 COMPLETED 스텝도 다시 실행 (예: 매번 해야 하는 준비/정리 스텝) |
| `step.startLimit(n)` | 그 스텝의 최대 실행 횟수 |
| `job.preventRestart()` | 재시작 금지 (실패하면 새 파라미터로만 실행 가능) |
| `JobParametersIncrementer` (`RunIdIncrementer`) | 매번 파라미터를 바꿔서 새 JobInstance 로 실행 → 재시작과 반대 개념이니 주의 |

## 직접 확인해보기

- 테스트 로그에서 `>>>` 와 `[beforeChunk]` 줄을 따라가면 context 가 어떻게 변하는지 보인다.
  (Batch 6 는 `afterChunk` 가 커밋 전에 불려서, "직전 커밋에 저장된 값"은 다음 청크의 `beforeChunk` 에서 찍는다)
- `printContextTables()` 가 `BATCH_*_EXECUTION_CONTEXT` 테이블 원본을 출력한다.
  (`common/BatchInfraConfig` 에서 serializer 를 JSON 으로 바꿔둬서 읽을 수 있다. 기본값은 Java 직렬화+Base64라 못 읽음)
- 이어서 읽기: DB reader 에서도 이게 안전한지는 [01-2](01-2-restart-jdbc-reader.md) 참고.
- 해볼 것: `prepareStep` 에 `.allowStartIfComplete(true)` 켜고 다시 돌려보기, chunk size 를 바꿔서 실패 시 커밋 지점이 어떻게 바뀌는지 보기.

## 테스트로 따라가기

테스트 이름이 곧 시나리오다. 위 표·설명과 같은 순서로 보면 된다.

**[RestartJobTest](../src/test/kotlin/com/example/toybatch/restart/basic/RestartJobTest.kt)**

- [실패한 잡을 같은 파라미터로 다시 실행하면 마지막 커밋 지점부터 이어서 처리한다](../src/test/kotlin/com/example/toybatch/restart/basic/RestartJobTest.kt#L38)
- [reader가 상태를 저장하지 않으면 재시작해도 처음부터 다시 읽어서 중복이 생긴다](../src/test/kotlin/com/example/toybatch/restart/basic/RestartJobTest.kt#L79)
