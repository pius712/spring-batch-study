# 05-1. StepExecution, StepContribution, Job / Step ExecutionContext

코드: 아래 [코드 지도](#코드-지도) 참고. 재시작과 ExecutionContext 기본은 [01-1](01-1-restart-execution-context.md).

## 코드 지도

| 파일 | 역할 |
|---|---|
| [ContextJobConfig.kt](../src/main/kotlin/com/example/toybatch/context/ContextJobConfig.kt) | 잡 정의: [`contextJob`](../src/main/kotlin/com/example/toybatch/context/ContextJobConfig.kt#L33) = [`writeStep`](../src/main/kotlin/com/example/toybatch/context/ContextJobConfig.kt#L38) (chunk=10, 1..30) → [`readStep`](../src/main/kotlin/com/example/toybatch/context/ContextJobConfig.kt#L49) |
| [PositionReader.kt](../src/main/kotlin/com/example/toybatch/context/PositionReader.kt) | ItemStream 의 [open](../src/main/kotlin/com/example/toybatch/context/PositionReader.kt#L18) / [update](../src/main/kotlin/com/example/toybatch/context/PositionReader.kt#L22) 으로 **Step EC** 를 받는 reader |
| [ContextObservingWriter.kt](../src/main/kotlin/com/example/toybatch/context/ContextObservingWriter.kt) | [beforeStep](../src/main/kotlin/com/example/toybatch/context/ContextObservingWriter.kt#L22) 으로 **StepExecution** 을 받아두고, [write](../src/main/kotlin/com/example/toybatch/context/ContextObservingWriter.kt#L26) 에서 **Job EC** 에 쓴다 |
| [ContextReaderConfig.kt](../src/main/kotlin/com/example/toybatch/context/ContextReaderConfig.kt) | [`lateBindingReader`](../src/main/kotlin/com/example/toybatch/context/ContextReaderConfig.kt#L24): `@StepScope` 로 `#{jobExecutionContext}` / `#{stepExecutionContext}` 주입 |
| [PersistedContextReader.kt](../src/main/kotlin/com/example/toybatch/context/PersistedContextReader.kt) | 메모리가 아니라 **DB 에 지금 저장된** Job / Step EC 를 읽는다 (확인용) |
| [ContextRecorder.kt](../src/main/kotlin/com/example/toybatch/context/ContextRecorder.kt) | 스텝 안에서 본 값을 테스트로 넘기는 기록기 |
| [ContextJobTest.kt](../src/test/kotlin/com/example/toybatch/context/ContextJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |

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

| | readStep 에서 |
|---|---|
| `#{jobExecutionContext['job.lastItem']}` | **30** (앞 스텝이 Job EC 에 넣은 값) |
| `#{stepExecutionContext['reader.position']}` | **null** (Step EC 는 스텝마다 따로) |

스텝 사이에 값을 넘기려면 Job EC 를 쓴다. Step EC 의 값을 넘기고 싶으면 `ExecutionContextPromotionListener` 로 스텝이 끝날 때 Job EC 에 복사한다.

## 주의

- `#{stepExecutionContext}` / `#{jobExecutionContext}` 는 **값 복사가 아니라 읽기 전용 Map**(`Collections.unmodifiableMap`)에서 꺼낸다. `StepContext.getStepExecutionContext()` 에 put 하면 `UnsupportedOperationException` 이다. 쓰려면 `stepExecution.executionContext` 에 직접 쓴다.
- `@StepScope` 주입은 **빈이 만들어질 때(보통 스텝 시작 시 open) 한 번**이다. 스텝 도중에 EC 가 바뀌어도 주입된 값은 그대로다.
- EC 는 JSON(이 프로젝트 설정) 으로 직렬화돼 `SHORT_CONTEXT`(2500자) 에 들어가고, 넘치면 `SERIALIZED_CONTEXT` 에 들어간다. 큰 데이터를 넣는 곳이 아니다.

## 직접 확인해보기

- 테스트 로그의 `>>> [writeStep]` 줄에서 청크마다 누적 건수와 DB 에 저장된 값이 보인다.
- 해볼 것: writeStep 에 `ExecutionContextPromotionListener` (keys = `reader.position`) 를 붙이고 readStep 에서 `#{jobExecutionContext['reader.position']}` 가 보이는지 확인하기.

## 테스트로 따라가기

**[ContextJobTest](../src/test/kotlin/com/example/toybatch/context/ContextJobTest.kt)**

- [StepContribution 은 청크마다 따로 쌓이고, 청크가 끝나야 StepExecution 에 더해진다](../src/test/kotlin/com/example/toybatch/context/ContextJobTest.kt#L30)
- [Step EC 는 청크 커밋마다, Job EC 는 스텝이 끝날 때 각자의 테이블에 저장된다](../src/test/kotlin/com/example/toybatch/context/ContextJobTest.kt#L43)
- [다음 스텝에서는 Job EC 만 보이고, 앞 스텝의 Step EC 는 안 보인다](../src/test/kotlin/com/example/toybatch/context/ContextJobTest.kt#L61)
