# 05-1. StepExecution, StepContribution, Job / Step ExecutionContext

코드: 아래 [코드 지도](#코드-지도) 참고. 재시작과 ExecutionContext 기본은 [01-1](01-1-restart-execution-context.md).

## 코드 지도

| 파일 | 역할 |
|---|---|
| [ContextJobConfig.kt](../src/main/kotlin/com/example/toybatch/context/ContextJobConfig.kt) | 잡 정의: [`contextJob`](../src/main/kotlin/com/example/toybatch/context/ContextJobConfig.kt#L41) = [`writeStep`](../src/main/kotlin/com/example/toybatch/context/ContextJobConfig.kt#L55) (chunk=10, 1..30) → [`readStep`](../src/main/kotlin/com/example/toybatch/context/ContextJobConfig.kt#L47) |
| [PositionReader.kt](../src/main/kotlin/com/example/toybatch/context/PositionReader.kt) | ItemStream 의 [open](../src/main/kotlin/com/example/toybatch/context/PositionReader.kt#L21) / [update](../src/main/kotlin/com/example/toybatch/context/PositionReader.kt#L27) 으로 **Step EC** 를 받는 reader |
| [ContextObservingWriter.kt](../src/main/kotlin/com/example/toybatch/context/ContextObservingWriter.kt) | [beforeStep](../src/main/kotlin/com/example/toybatch/context/ContextObservingWriter.kt#L27) 으로 **StepExecution** 을 받아두고, [write](../src/main/kotlin/com/example/toybatch/context/ContextObservingWriter.kt#L33) 에서 **Job EC** 에 쓴다 |
| [ContextReaderConfig.kt](../src/main/kotlin/com/example/toybatch/context/ContextReaderConfig.kt) | [`lateBindingReader`](../src/main/kotlin/com/example/toybatch/context/ContextReaderConfig.kt#L23): `@StepScope` 로 `#{jobExecutionContext}` / `#{stepExecutionContext}` 주입 |
| [ContextTraceListener.kt](../src/main/kotlin/com/example/toybatch/context/ContextTraceListener.kt) | 학습용 추적 리스너. 스텝/청크 경계마다 메모리 EC 와 DB EC, StepExecution 건수를 나란히 로그로 찍는다 |
| [PersistedContextReader.kt](../src/main/kotlin/com/example/toybatch/context/PersistedContextReader.kt) | 메모리가 아니라 **DB 에 지금 저장된** Job / Step EC 를 읽는다 (확인용) |
| [ContextRecorder.kt](../src/main/kotlin/com/example/toybatch/context/ContextRecorder.kt) | 스텝 안에서 본 값을 테스트로 넘기는 기록기 |
| [ContextPromotionJobConfig.kt](../src/main/kotlin/com/example/toybatch/context/ContextPromotionJobConfig.kt) | 5절. Job EC 잘 쓴 예 [`contextPromotionJob`](../src/main/kotlin/com/example/toybatch/context/ContextPromotionJobConfig.kt#L44) vs 나쁜 예 [`contextJobEcSumJob`](../src/main/kotlin/com/example/toybatch/context/ContextPromotionJobConfig.kt#L60) |
| [SumWriters.kt](../src/main/kotlin/com/example/toybatch/context/SumWriters.kt) | 합계 writer 두 개: [`StepContextSumWriter`](../src/main/kotlin/com/example/toybatch/context/SumWriters.kt#L22) (✅ Step EC 에 누적) / [`JobContextSumWriter`](../src/main/kotlin/com/example/toybatch/context/SumWriters.kt#L53) (❌ Job EC 에 누적) |
| [ContextJobTest.kt](../src/test/kotlin/com/example/toybatch/context/ContextJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |
| [ContextPromotionJobTest.kt](../src/test/kotlin/com/example/toybatch/context/ContextPromotionJobTest.kt) | 5절 테스트 |

## 한 줄 요약

**StepExecution 은 "스텝 실행 기록", ExecutionContext 는 그 기록에 붙은 Map, StepContribution 은 "이번 청크의 변화량"이다. Step EC 는 청크 커밋마다, Job EC 는 스텝이 끝날 때 각자의 테이블에 저장된다.**

## 1. 무엇이 무엇을 들고 있나

```
JobExecution  ─────────────────────────────  BATCH_JOB_EXECUTION
 ├─ ExecutionContext (Job EC)  ──────────────  BATCH_JOB_EXECUTION_CONTEXT    잡 전체가 공유, 스텝이 끝날 때 저장
 └─ StepExecution  (스텝마다) ───────────────  BATCH_STEP_EXECUTION           status, exitStatus, read/write/commit 건수
     ├─ ExecutionContext (Step EC) ──────────  BATCH_STEP_EXECUTION_CONTEXT   그 스텝만, 청크 커밋마다 저장
     └─ StepContribution (청크마다 새로) ─────  (저장 안 됨)                    이번 청크의 read/write/skip 건수
```

- **ExecutionContext 는 클래스 하나다.** 누가 들고 있느냐로 Job EC 와 Step EC 가 나뉘고, 저장되는 테이블도 다르다.
- **StepContribution 은 저장되지 않는다.** 청크가 끝나면 StepExecution 에 더해지고 버려진다.

## 2. chunk 스텝의 컴포넌트는 무엇으로 받나

reader / processor / writer 는 `read()`, `process(item)`, `write(chunk)` 처럼 **아이템만 받는다**. 실행 정보가 필요하면 아래 셋 중 하나로 받는다.

| 방법 | 받는 것 | 이 예제 |
|---|---|---|
| `ItemStream.open(ec)` / `update(ec)` | **Step EC** (`stepExecution.executionContext` 그 자체) | `PositionReader`: 읽은 위치를 저장하고 재시작 때 복구 |
| `StepExecutionListener.beforeStep(stepExecution)` | **StepExecution** → 건수, Step EC, `jobExecution.executionContext`(Job EC) | `ContextObservingWriter`: 받아두고 write 에서 Job EC 에 쓴다 |
| `@StepScope` + `@Value("#{...}")` | `jobParameters`, `jobExecutionContext`, `stepExecutionContext` 의 **값** (스텝이 시작될 때 한 번) | `lateBindingReader` |

StepContribution 은 컴포넌트에 넘어오지 않는다. 스텝이 read / write 하면서 대신 채운다.
(tasklet 스텝은 `execute(contribution, chunkContext)` 로 직접 받는다. Batch 6 의 chunk 스텝은 ChunkContext 를 쓰지 않고, `ChunkListener` 도 `beforeChunk(Chunk)` 쪽을 쓴다. ChunkContext 를 받는 버전은 deprecated)

## 3. 청크 하나가 도는 순서 (`ChunkOrientedStep`)

```
스텝 시작:  beforeStep(stepExecution) → reader.open(Step EC)

청크마다 (트랜잭션 1개):
  contribution = stepExecution.createStepContribution()   ← 청크마다 새로
  read × 10    → contribution.readCount += 1
  process
  write(chunk) → contribution.writeCount += 10
  stepExecution.apply(contribution)                         ← 이번 청크 건수를 누적값에 더한다 (실패해도 finally 로)
  reader.update(Step EC)                                    ← 여기서 위치를 쓰고
  Step EC 저장 + StepExecution 저장                           ← 같은 트랜잭션으로 커밋

스텝 끝:   afterStep(stepExecution) → Job EC 저장
```

그래서 테스트에서 이렇게 보인다.

| 청크 | write 시점의 `stepExecution.readCount` | DB 의 Step EC `reader.position` | DB 의 Job EC `job.lastItem` |
|---|---|---|---|
| 1 (1~10) | 0 | 없음 | 없음 |
| 2 (11~20) | 10 | 10 | 없음 |
| 3 (21~30) | 20 | 20 | 없음 |
| 잡 끝난 뒤 | 30 | 30 | **30** |

- `readCount` 가 한 박자 늦다 → 이번 청크 건수는 contribution 에 쌓여 있다가 청크가 끝나야 더해진다.
- Step EC 는 직전 커밋까지 DB 에 있다 → 재시작하면 여기서 이어 읽는다 (01-1).
- Job EC 는 스텝 도중에 DB 에 없다 → **스텝 도중에 Job EC 에 쓴 값은 그 스텝이 죽으면 사라진다.** 재시작에 필요한 진행 위치는 Step EC 에 둔다.

## 4. 다음 스텝에서 보이는 것

readStep 도 writeStep 과 **같은 키(`reader.position`)** 를 쓰는 `PositionReader` 로 3건을 읽는다. 일부러 키를 겹쳐서, 같은 키라도 스텝마다 따로 저장되는지 본다.

| | 값 | 근거 |
|---|---|---|
| DB `BATCH_STEP_EXECUTION_CONTEXT` 의 writeStep 행, `reader.position` | **30** | 잡이 끝난 뒤 DB 조회 |
| DB `BATCH_STEP_EXECUTION_CONTEXT` 의 readStep 행, `reader.position` | **3** | 〃 → 같은 키, 다른 행, 다른 값 |
| readStep 시작 시 `#{jobExecutionContext['job.lastItem']}` | **30** | 앞 스텝이 Job EC 에 넣은 값이 보인다 |
| readStep 시작 시 `#{stepExecutionContext['reader.position']}` | **null** | 앞 스텝 EC 에 같은 키가 30 으로 있어도 안 보인다 |

- 분리돼 있다는 **근거는 DB 의 두 행**이다. 마지막 줄의 null 만으로는 증명이 안 된다 (키 오타나 바인딩 실패여도 null 이 나온다). 그래서 DB 로 같이 확인한다.
- `job.lastItem = 30` 은 DB 가 아니라 **같은 JobExecution 의 메모리 객체**로 넘어온 값이다. Job EC 가 DB 에 저장되는 시점은 [3.](#3-청크-하나가-도는-순서-chunkorientedstep) 의 테스트가 따로 확인한다.

스텝 사이에 값을 넘기려면 Job EC 를 쓴다. Step EC 의 값을 넘기고 싶으면 `ExecutionContextPromotionListener` 로 스텝이 끝날 때 Job EC 에 복사한다.

## 5. 재시작은 무엇을 기준으로 하나 — 그럼 Job EC 는 왜 있나

### 재시작 기준은 "스텝 상태 + Step EC" 다

Batch 6.0.5 `SimpleStepHandler.handleStep` 소스 기준:

```
1. 같은 JobInstance(잡 이름 + identifying 파라미터)면 재시작, 아니면 새 실행
2. 스텝마다 last = 그 스텝의 마지막 StepExecution
     COMPLETED  → 건너뜀 (allowStartIfComplete 면 다시 실행)
     ABANDONED  → 건너뜀
     UNKNOWN    → JobRestartException (사람이 확인해야 함)
     startLimit 초과 → StartLimitExceededException
     그 외(FAILED, STOPPED, 처음) → 실행
3. 재시작하는 스텝은 새 StepExecution 에 last 의 Step EC 를 그대로 넘긴다
     → reader.open(ec) 가 위치를 보고 이어서 읽는다
```

- **어느 스텝부터**: 스텝 상태로 정한다.
- **스텝 안 어디부터**: Step EC 로 정한다 (정확히는 Step EC 를 받은 reader 가 정한다).
- **Job EC 는 재시작 판단에 쓰이지 않는다.** 이전 JobExecution 의 Job EC 를 새 JobExecution 에 복사해 줄 뿐이다.

### 실패한 스텝의 Job EC 는 믿으면 안 된다

Job EC 는 청크 트랜잭션과 묶여 있지 않고 **스텝이 끝날 때(실패해도)** 저장된다.
[`contextJobEcSumJob`](../src/main/kotlin/com/example/toybatch/context/ContextPromotionJobConfig.kt#L60) (❌): 1..30 의 합계를 **청크마다 Job EC 에 누적**하고, 25 에서 실패시킨 뒤 재시작한다.

```
1차 : 청크 1..10 → Job EC 55, 청크 11..20 → 210 (여기까지 커밋)
      청크 21..30 → Job EC 465 로 더한 뒤 25 에서 실패 → 청크 롤백
      FAILED, write=20
      DB Step EC = {reader.position=20}   ← 청크와 같이 롤백 → 데이터와 일치 ✅
      DB Job  EC = {sumStep.total=465}    ← 롤백된 21..30 몫(255)까지 저장 ❗
2차 : 재시작. Job EC 465 복구, reader 는 21 부터 → 21..30 을 또 더함 → 720 ❗ (정답 465)
```

- 재시작 위치(Step EC)는 정확하다. 틀린 건 Job EC 뿐이다.
- 프로세스가 kill 등으로 죽으면 스텝 끝 저장 자체가 없으니 앞 스텝이 끝났을 때의 Job EC 가 복구될 것이다 (코드상 추론, 테스트 안 함).

### Job EC 는 "스텝 사이" 에 결과를 넘기려고 있다

Step EC 는 그 스텝의 StepExecution 에만 붙어 있어서 다른 스텝이 못 본다 ([4.](#4-다음-스텝에서-보이는-것)). 메모리에 두면 재시작(새 프로세스) 때 사라진다.
**COMPLETED 스텝은 재시작 때 다시 안 도니까**, 그 스텝이 만든 결과를 뒤 스텝이 쓰려면 DB 에 남는 잡 단위 저장소가 필요하다. 그게 Job EC 다.

01-1 의 [`PrepareTasklet`](../src/main/kotlin/com/example/toybatch/restart/basic/PrepareTasklet.kt) 이 정석적인 사용이다:

```
prepareStep : 총 건수를 계산해서 Job EC 에 totalCount=100 → COMPLETED
numberStep  : #{jobExecutionContext['totalCount']} 로 받아서 읽다가 FAILED
재시작      : prepareStep 은 COMPLETED 라 건너뛰는데도 numberStep 은 totalCount=100 을 받는다 (DB 의 Job EC 에서 복구)
```

| | Step EC | Job EC |
|---|---|---|
| 목적 | **스텝 안**에서 어디까지 했나 (재시작 위치) | **스텝 사이**에 넘기는 결과 |
| 누가 읽나 | 같은 스텝의 다음 실행 (`reader.open`) | 뒤 스텝, decider, 잡 리스너 |
| 쓰는 시점 | 청크마다 (`ItemStream.update`) — 청크와 같이 커밋 | **스텝이 성공적으로 끝날 때 한 번** |
| 예 | reader 위치, 마지막 키 | 총 건수, 만든 파일 경로, 앞 스텝의 집계 결과, 분기 판단용 값 |

**규칙: Job EC 에는 스텝이 끝날 때 결과만 쓰고, 뒤 스텝에서 읽는다.** COMPLETED 스텝이 남긴 값은 그 스텝이 다시 안 도니 안전하다.
### 잘 쓴 예: Step EC 에 누적 → `ExecutionContextPromotionListener` 로 옮기기

[`contextPromotionJob`](../src/main/kotlin/com/example/toybatch/context/ContextPromotionJobConfig.kt#L44) (✅): 같은 시나리오를 이렇게 바꾼다.

```kotlin
// 1. 누적은 writer 의 Step EC 에 (ItemStream) — 청크와 같이 커밋된다
class StepContextSumWriter : ItemStreamWriter<Int> {
    override fun open(ec: ExecutionContext)   { total = ec.getLong("sumStep.total", 0L) }  // 재시작이면 직전 커밋 값
    override fun write(chunk: Chunk<out Int>) { total += chunk.items.sum() }
    override fun update(ec: ExecutionContext) { ec.putLong("sumStep.total", total) }
}

// 2. 스텝이 끝나면 지정한 키만 Job EC 로 옮긴다. 기본 statuses = [COMPLETED] → 실패하면 안 옮긴다
.listener(ExecutionContextPromotionListener().apply { setKeys(arrayOf("sumStep.total")); afterPropertiesSet() })

// 3. 다음 스텝은 읽기만
chunkContext.stepContext.jobExecutionContext["sumStep.total"]   // 또는 @StepScope + #{jobExecutionContext['sumStep.total']}
```

| | ✅ `contextPromotionJob` | ❌ `contextJobEcSumJob` |
|---|---|---|
| 1차 FAILED 후 DB Step EC | `sumStep.total=210` (1..20) | (없음) |
| 1차 FAILED 후 DB Job EC | **비어 있음** (FAILED 라 안 옮김) | `sumStep.total=465` (롤백된 몫 포함) |
| 재시작 | Step EC 의 210 부터 21..30 더함 | Job EC 의 465 에 21..30 또 더함 |
| 다음 스텝이 받은 합계 | **465** ✅ | **720** ❌ |

### Job EC best practice

- [ ] **결과만, 스텝이 성공적으로 끝날 때 쓴다.** 청크마다 쓰지 않는다. → `ExecutionContextPromotionListener`, 또는 `afterStep` 에서 `COMPLETED` 일 때만. tasklet 이면 `put` 을 `FINISHED` 직전 마지막 동작으로
- [ ] **스텝 안의 누적/진행은 Step EC** (ItemStream `update`) 에 둔다
- [ ] **작고 단순한 값만** (숫자, 문자열, 날짜, 파일 경로, 배치 ID). ID 목록·엔티티·큰 데이터는 테이블/파일에 두고 Job EC 엔 키만 (`SHORT_CONTEXT` 2500자, 매 저장마다 직렬화)
- [ ] **도메인 클래스를 넣지 않는다.** 재시작 때 역직렬화하는데 클래스가 바뀌면 깨진다
- [ ] **키에 만든 스텝 이름을 붙인다** (`sumStep.total`). 병렬 스텝(`split`)은 같은 Job EC 를 공유하니 스텝마다 다른 키만 쓴다 (동시 쓰기의 실제 동작은 확인 안 함)
- [ ] **뒤 스텝은 읽기만 한다.** 여러 스텝이 같은 키를 차례로 고치는 구조면 Job EC 가 아니라 테이블
- [ ] **밖에서 주는 입력은 JobParameters**, 앞 스텝이 계산한 결과만 Job EC
- [ ] **DB 로 다시 구할 수 있으면 다시 구한다.** Job EC 는 다시 구할 수 없거나 비싼 값을 넘길 때
- [ ] 만든 스텝이 `allowStartIfComplete(true)` 면 재시작마다 다시 돌며 덮어쓴다는 걸 안다

> ❗ 이 예제의 [`ContextObservingWriter`](../src/main/kotlin/com/example/toybatch/context/ContextObservingWriter.kt) 는 **저장 시점을 보여주려고 일부러** 청크마다 Job EC 에 쓴다. 실제 코드에서 따라 하면 위의 "실패한 스텝의 Job EC" 문제가 생긴다.

## 주의

- `#{stepExecutionContext}` / `#{jobExecutionContext}` 는 **값 복사가 아니라 읽기 전용 Map**(`Collections.unmodifiableMap`)에서 꺼낸다. `StepContext.getStepExecutionContext()` 에 put 하면 `UnsupportedOperationException` 이다. 쓰려면 `stepExecution.executionContext` 에 직접 쓴다.
- `@StepScope` 주입은 **빈이 만들어질 때(보통 스텝 시작 시 open) 한 번**이다. 스텝 도중에 EC 가 바뀌어도 주입된 값은 그대로다.
- EC 는 JSON(이 프로젝트 설정) 으로 직렬화돼 `SHORT_CONTEXT`(2500자) 에 들어가고, 넘치면 `SERIALIZED_CONTEXT` 에 들어간다. 큰 데이터를 넣는 곳이 아니다.

## 직접 확인해보기

`./gradlew test --tests '*ContextJobTest'` 를 돌리고 테스트 로그를 위에서부터 읽으면 된다. 로그가 많은 건 일부러다.

| 로그 | 누가 찍나 | 볼 것 |
|---|---|---|
| `[writeStep reader] open / read / update` | `PositionReader` | open 이 받은 ec, read 순서, update 가 **afterChunk 다음**에 불리는 것, 스텝 끝에 한 번 더 불리는 것 |
| `[writeStep writer] write(...)` | `ContextObservingWriter` | write 시점의 누적 건수(한 박자 늦음), DB 의 Step EC / Job EC |
| `┌─ ├─ └─` | `ContextTraceListener` | beforeStep / beforeChunk / afterChunk / afterStep 마다 메모리 EC vs DB EC |
| `[readStep] @StepScope 빈 생성` | `ContextReaderConfig` | readStep 이 주입받은 Job EC / Step EC 값 |
| `===== 결과 =====` | 테스트 | StepExecution 요약과 `BATCH_*_EXECUTION_CONTEXT` 테이블 원본 |

로그에서 바로 보이는 것:
- `afterChunk` 에서도 `read=0, write=0` 이다 → 이번 청크 건수는 afterChunk **뒤에** StepExecution 에 더해진다.
- `afterChunk` 의 Step EC (메모리) 에 아직 `reader.position` 이 없다 → `reader.update(ec)` 가 afterChunk 다음이라서.
- writeStep 의 `afterStep` 에서도 `Job EC (DB) = {}` 이다 → Job EC 는 afterStep **다음에** 저장되고, readStep 의 `beforeStep` 에서야 DB 에 보인다.
- `ec 식별자` 가 스텝마다 다르고, 한 스텝 안에서는 같다 → Step EC 는 스텝마다 객체 하나.

해볼 것: writeStep 에 `ExecutionContextPromotionListener` (keys = `reader.position`) 를 붙이고 readStep 에서 `#{jobExecutionContext['reader.position']}` 가 보이는지 확인하기.

## 테스트로 따라가기

**[ContextJobTest](../src/test/kotlin/com/example/toybatch/context/ContextJobTest.kt)**

- [StepContribution 은 청크마다 따로 쌓이고, 청크가 끝나야 StepExecution 에 더해진다](../src/test/kotlin/com/example/toybatch/context/ContextJobTest.kt#L37)
- [Step EC 는 청크 커밋마다, Job EC 는 스텝이 끝날 때 각자의 테이블에 저장된다](../src/test/kotlin/com/example/toybatch/context/ContextJobTest.kt#L50)
- [같은 키라도 Step EC 는 스텝마다 따로 저장되고, 다음 스텝에는 Job EC 만 넘어간다](../src/test/kotlin/com/example/toybatch/context/ContextJobTest.kt#L70)

**[ContextPromotionJobTest](../src/test/kotlin/com/example/toybatch/context/ContextPromotionJobTest.kt)** (5절)

- [잘 쓴 예 - 누적은 Step EC 에 하고 스텝이 끝나면 PromotionListener 가 Job EC 로 옮겨 다음 스텝에 넘긴다](../src/test/kotlin/com/example/toybatch/context/ContextPromotionJobTest.kt#L39)
- [잘 쓴 예 - 실패하면 Job EC 로 옮기지 않고, 재시작하면 Step EC 로 이어서 정확한 합계를 넘긴다](../src/test/kotlin/com/example/toybatch/context/ContextPromotionJobTest.kt#L49)
- [나쁜 예 - 청크마다 Job EC 에 누적하면 실패한 청크 몫이 저장되고 재시작 때 두 번 더해진다](../src/test/kotlin/com/example/toybatch/context/ContextPromotionJobTest.kt#L69)
