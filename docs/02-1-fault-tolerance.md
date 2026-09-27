# 02-1. Fault tolerance — retry 와 skip

코드: 아래 [코드 지도](#코드-지도) 참고. 문서 안의 잡·빈·클래스 이름을 누르면 정의된 줄로 이동한다.
실행: `./gradlew test --tests '*faulttolerance*'`
이어서: writer 에서의 retry/skip 을 질문별로 파고든 [02-2](02-2-writer-fault-tolerance.md)


## 코드 지도

```
faulttolerance/
├── basic/    ← 이 문서의 잡: RetryJobConfig, SkipJobConfig, RetrySkipJobConfig
├── writer/   02-2 문서의 잡과 해결 writer
├── fault/    실패 주입: Fault, 예외, FaultInjectingReader/Processor/Writer
└── support/  공통 뼈대·기록: FaultToleranceSteps, AttemptRecorder, 리스너
```

| 파일 | 역할 |
|---|---|
| [FaultToleranceSteps.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/support/FaultToleranceSteps.kt) | 공통 뼈대: reader → processor → writer, retryPolicy 헬퍼 |
| [RetryJobConfig.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobConfig.kt) | retry 예제 잡 5개 |
| [SkipJobConfig.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobConfig.kt) | skip 예제 잡 2개 |
| [RetrySkipJobConfig.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetrySkipJobConfig.kt) | retry + skip 조합 잡 |
| [Fault.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/fault/Fault.kt) | 실패 규칙 `Fault`, 단계별 묶음 `Faults` |
| [FaultExceptions.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/fault/FaultExceptions.kt) | `TransientException`(retry 대상), `InvalidItemException`(skip 대상) |
| [FaultInjectingReader.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/fault/FaultInjectingReader.kt) | 규칙대로 실패하는 reader |
| [FaultInjectingProcessor.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/fault/FaultInjectingProcessor.kt) | 규칙대로 실패하는 processor |
| [FaultInjectingWriter.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/fault/FaultInjectingWriter.kt) | 규칙대로 실패하는 writer (INSERT / MERGE) |
| [AttemptRecorder.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/support/AttemptRecorder.kt) | item 별 시도 횟수, skip 기록 (메모리) |
| [RecordingSkipListener.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/support/RecordingSkipListener.kt) | skip 된 item 을 메모리 + 테이블에 기록 |
| [LoggingRetryListener.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/support/LoggingRetryListener.kt) | 재시도 로그 |
| [RetryJobTest.kt](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |
| [SkipJobTest.kt](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |
| [RetrySkipJobTest.kt](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetrySkipJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |
| [FaultToleranceTestSupport.kt](../src/test/kotlin/com/example/toybatch/faulttolerance/support/FaultToleranceTestSupport.kt) | 테스트 공통 설정·헬퍼 |

## 한 줄 요약

**retry = "다시 하면 될 것 같은 오류"를 그 자리에서 다시 시도. skip = "다시 해도 안 될 오류"가 난 item 을 버리고 계속 진행.**
보통 둘을 섞어서 *일시적 오류는 몇 번 재시도하고, 그래도 안 되거나 데이터 자체가 잘못됐으면 skip* 으로 쓴다.

## 예제 구성

모든 잡이 같은 뼈대([`FaultToleranceSteps`](../src/main/kotlin/com/example/toybatch/faulttolerance/support/FaultToleranceSteps.kt#L26))를 쓰고, **어디서 몇 번 실패하는지**만 다르다.

```
reader (1~20) → processor → writer (ft_result 에 한 건씩 insert)     chunk = 5
```

item 20개가 청크 5개씩 4개로 나뉜다. **청크 하나 = 트랜잭션 하나**다.

```
청크1 [1..5]   청크2 [6..10]   청크3 [11..15]   청크4 [16..20]
  커밋           커밋             커밋              커밋
```

이 문서의 결과 숫자는 전부 이 규칙으로 읽으면 된다.

- 어떤 청크에서 스텝이 실패하면 **그 청크만 롤백**되고, **앞 청크들은 이미 커밋돼서 남는다.** 뒤 청크는 실행되지 않는다.
- 예: item 7 에서 실패 → 7 이 든 청크2 [6..10] 롤백 → 청크1 의 **1~5 만 저장**, 청크3·4 는 실행 안 됨.

| 예외 | 의미 | 보통의 처리 |
|---|---|---|
| [`TransientException`](../src/main/kotlin/com/example/toybatch/faulttolerance/fault/FaultExceptions.kt#L4) | 일시적 오류 (API 타임아웃, 락 경합) | retry |
| [`InvalidItemException`](../src/main/kotlin/com/example/toybatch/faulttolerance/fault/FaultExceptions.kt#L7) | 데이터 자체가 잘못됨 | retry 하지 말고 skip |

실패 규칙은 [`Fault`](../src/main/kotlin/com/example/toybatch/faulttolerance/fault/Fault.kt#L10) 로 만든다.

```kotlin
Fault.transientTimes(7, 2)   // 7 은 1·2번째 시도에서 실패, 3번째 성공
Fault.transientAlways(7)     // 7 은 항상 실패
Fault.invalid(7)             // 7 은 항상 InvalidItemException
Faults(read = ..., process = ..., write = ...)   // 단계별로 지정
```

[`AttemptRecorder`](../src/main/kotlin/com/example/toybatch/faulttolerance/support/AttemptRecorder.kt#L12) 는 "어떤 item 이 어느 단계에서 몇 번 시도됐는지"를 **메모리**에 기록한다.
DB 에 적으면 청크가 롤백될 때 기록도 같이 사라지기 때문이다.

## Batch 6 설정 방법

```kotlin
StepBuilder("step", jobRepository)
    .chunk<Int, Int>(5)                      // Batch 6: chunk(size, tm) 은 deprecated
    .transactionManager(transactionManager)
    .reader(...).processor(...).writer(...)
    .faultTolerant()                         // ❗ 빠지면 아래 설정이 전부 무시된다
    .retryPolicy(                            // Spring Framework 7 의 org.springframework.core.retry.RetryPolicy
        RetryPolicy.builder()
            .includes(TransientException::class.java)
            .maxRetries(2)                   // 최초 1 + 재시도 2 = 총 3번 시도
            .delay(Duration.ofMillis(10))
            .build()
    )
    .retryListener(LoggingRetryListener())   // org.springframework.core.retry.RetryListener
    .skip(TransientException::class.java, InvalidItemException::class.java)
    .skipLimit(10)                           // 스텝 전체 skip 한도 (기본 10)
    .skipListener(...)
    .build()                                 // → ChunkOrientedStep
```

- Batch 5 까지는 retry 에 **spring-retry** 를 썼지만, Batch 6 의 새 `ChunkOrientedStep` 은 **Spring Framework 7 core retry**(`RetryTemplate`, `RetryPolicy`)를 쓴다.
- `.retry(X::class.java).retryLimit(n)` 도 되지만, 이렇게 만든 정책은 재시도 간격이 **기본 1초**(`RetryPolicy.Builder.DEFAULT_DELAY = 1000`)다. 간격을 조절하려면 `retryPolicy()` 로 직접 만든다.

## Batch 6 에서 실제로 어떻게 동작하나 (`ChunkOrientedStep` 소스 기준)

| 단계 | retry | retry 소진 후 skip 가능하면 |
|---|---|---|
| read | `reader.read()` 를 그 자리에서 다시 호출 | 그 item 은 없던 걸로 하고 다음 read |
| process | `processor.process(item)` 을 그 자리에서 다시 호출 | 그 item 만 빼고 계속 |
| write | `writer.write(chunk)` 를 **롤백 없이 같은 트랜잭션에서** 다시 호출 | 청크 롤백 → **scan 모드**: 한 건씩 각자 트랜잭션으로 다시 write, 실패한 건만 skip |

- retry 대상이 아닌 예외도 곧바로 "재시도 소진"으로 취급해서 skip 정책을 본다. 그래서 **retry 설정 없이 skip 만 써도 된다.**
- skip 도 안 되면 스텝이 FAILED 된다. 원인은 `FatalStepExecutionException: Unable to process chunk` 로 감싸져 있으니 `cause` 를 봐야 한다.

## 1. Retry (`RetryJobConfig`, `RetryJobTest`)

전부 "TransientException 만 최대 2번 재시도", skip 없음. 실패 item 7 은 청크2 [6..10], 13 은 청크3 [11..15] 에 있다.

| 잡 | 실패 규칙 | 결과 | item 7 의 process 호출 횟수 | 테스트 |
|---|---|---|---|---|
| [`ftRetryProcessJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobConfig.kt#L25) | process 7: Transient 2번 | ✅ COMPLETED, 20건, rollback=0 | 3번 (최초 1 + 재시도 2 → 3번째에 성공) | [테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt#L11 "일시적 오류는 재시도해서 성공하면 아무 일 없던 것처럼 끝난다") |
| [`ftRetryExhaustedJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobConfig.kt#L30) | process 7: Transient 계속 | ❌ FAILED, 1~5 만 저장 (청크2 [6..10] 롤백) | 3번 (최초 1 + 재시도 2, 전부 실패) | [테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt#L22 "재시도를 다 써도 실패하면 skip 설정이 없으므로 스텝이 FAILED 된다") |
| [`ftRetryNotRetryableJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobConfig.kt#L36) | process 7: Invalid **1번만** | ❌ FAILED, 1~5 만 저장 (청크2 [6..10] 롤백) | **1번** (한 번 더 하면 성공할 상황이지만 retry 대상이 아님) | [테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt#L32 "retry 대상이 아닌 예외는 한 번만 시도하고 바로 실패한다") |
| [`ftRetryWriteJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobConfig.kt#L40) | write 13: Transient 1번 | ❗ COMPLETED 인데 **22줄** (20 + 11, 12 한 번씩 더) | | [테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt#L42 "writer 재시도는 롤백 없이 청크 전체를 다시 쓰므로 멱등하지 않으면 중복이 생긴다") |
| [`ftRetryWriteIdempotentJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobConfig.kt#L44) | 위와 같음, writer 가 `MERGE` | ✅ 20줄 | (item 11 은 writer 에 2번 넘어갔지만 DB 에는 1줄) | [테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt#L55 "writer가 멱등하면 재시도해도 중복이 없다") |

[`ftRetryExhaustedJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobConfig.kt#L30) ([테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt#L22 "재시도를 다 써도 실패하면 skip 설정이 없으므로 스텝이 FAILED 된다")) 을 청크 단위로 따라가면:

```
청크1 [1..5]  : process 1~5 성공 → write → 커밋          → 1~5 저장
청크2 [6..10] : process 6 성공, process 7 ✘ ✘ ✘ (3번 다 실패, 재시도 소진)
                skip 설정 없음 → 청크2 롤백 (6 도 저장 안 됨) → 스텝 FAILED
청크3, 청크4  : 실행 안 됨
```

[`ftRetryNotRetryableJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobConfig.kt#L36) ([테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt#L32 "retry 대상이 아닌 예외는 한 번만 시도하고 바로 실패한다")) 도 같은 모양이고, process 7 이 1번 만에 실패한다는 것만 다르다.

### ❗ writer 재시도는 롤백 없이 청크 전체를 다시 쓴다

```
청크 [11..15]
  write 1차 : INSERT 11 ✔, INSERT 12 ✔, 13 ✘ Transient
  write 재시도 (같은 트랜잭션, 롤백 없음) : INSERT 11, 12, 13, 14, 15 ✔
  커밋 → 11, 12 가 두 줄씩
```

그런데 `writeCount` 는 **20** 으로 정상처럼 찍힌다. 통계로는 알 수 없다.
→ **retry 를 거는 writer 는 멱등해야 한다.** (upsert/`MERGE`, 유니크 키, "이미 처리됐으면 무시" 등)
Batch 5 는 청크를 롤백한 다음 다시 처리했기 때문에 **DB 중복**은 없었다. **Batch 5 → 6 으로 올릴 때 조심할 부분.**
단, 트랜잭션 밖의 외부 호출은 5 에서도 중복이다. 아래 표.

#### write 재시도 한 번에 무엇이 다시 도나 (Batch 5 vs 6)

청크 [11..15], write 13 이 1번 Transient 실패 → 재시도 1번.

| | Batch 5.2.4 | Batch 6.0.5 | 확인 |
|---|---|---|---|
| 흐름 | 예외를 밖으로 던져 **청크 롤백** → 읽어둔 아이템으로 process 부터 다시 → 새 트랜잭션에서 write | **롤백 없이** 같은 트랜잭션에서 `write(chunk)` 만 다시 | ✅ D / `ftRetryWriteJob` |
| reader | 다시 안 부름. 읽은 아이템을 `ChunkContext` 에 보관해 둔다 (`ChunkOrientedTasklet` 의 `INPUTS`) | 다시 안 부름. 메모리의 청크를 그대로 쓴다 | ✅ D: `read=20` (다시 읽었다면 25) / `ftRetryWriteJob`: `readCount=20` |
| processor | **다시 부름** (11~15 가 2번씩). `processorNonTransactional()` 이면 캐시한 결과를 재사용 | 다시 안 부름 | Batch 5 ✅ D: `process(11..15)=[2,2,2,2,2]`. `processorNonTransactional` 과 Batch 6 쪽은 소스 기준 |
| writer 호출 | 청크 **전체**로 다시 (11~15) | 청크 **전체**로 다시 (11~15) | ✅ D: `writeCalls=[5,5,5,5,5]` / `ftRetryWriteJob` |
| writer 안의 DB 쓰기 | 1차 시도분이 롤백됐으니 **중복 없음** | 1차 시도분(11, 12)이 남은 채로 다시 씀 → **중복** | ✅ D: `distinct=20` / `ftRetryWriteJob`: 22줄 |
| writer 안의 외부 호출 | **중복** (11, 12 재전송. 롤백해도 외부는 안 돌아온다) | **중복** | Batch 5 는 D 의 writeCalls 로 추론 / ✅ `ftRetryExternalJob` |
| processor 안의 외부 호출 | **중복** (processor 를 다시 부르므로) | 중복 없음 | Batch 5 는 D 의 process 횟수로 추론 |
| `rollbackCount` | 1 | 0 | ✅ D / `ftRetryWriteJob` |

→ **DB 쓰기**는 5 에서 안전했고 6 에서 멱등이 필요해졌다. **외부 호출**은 원래부터 둘 다 멱등(또는 커밋 후 전송)이 필요했다. 해결 방법은 [02-2 Q1](02-2-writer-fault-tolerance.md#q1-retry-정책이-writer-에서-문제가-되는-경우).

## 2. Skip (`SkipJobConfig`, `SkipJobTest`)

전부 "InvalidItemException 은 skip", retry 없음. (청크 [1..5] [6..10] [11..15] [16..20])

| 잡 | 실패 규칙 | 결과 | 테스트 |
|---|---|---|---|
| [`ftSkipJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobConfig.kt#L24) | read 3, process 8, write 13 이 Invalid | ✅ COMPLETED, 17건 (20 − {3, 8, 13}). skip(read/process/write) = 1/1/1 | [1](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L11 "read, process, write 에서 난 잘못된 데이터를 각각 건너뛰고 COMPLETED 된다") · [2](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L22 "write 스킵은 청크를 롤백하고 한 건씩 다시 쓰는 scan 으로 범인을 찾는다") · [3](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L33 "write 스킵 때 SkipListener 가 DB 에 남긴 기록은 롤백되어 사라진다") |
| [`ftSkipLimitJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobConfig.kt#L39) | process 3, 8, 13 이 Invalid, `skipLimit(2)` | ❌ FAILED, 8건 (아래 흐름 참고) | [테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L44 "skipLimit 을 넘으면 스텝이 FAILED 된다") |

[`ftSkipLimitJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobConfig.kt#L39) ([테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L44 "skipLimit 을 넘으면 스텝이 FAILED 된다")) 을 청크 단위로 따라가면 (skip 한도 2):

```
청크1 [1..5]   : 3 skip (누적 1) → 1, 2, 4, 5 커밋
청크2 [6..10]  : 8 skip (누적 2) → 6, 7, 9, 10 커밋
청크3 [11..15] : 13 → 누적 3 > 한도 2 → 청크3 롤백 (11, 12 도 저장 안 됨) → 스텝 FAILED
청크4          : 실행 안 됨
결과 : 1~10 − {3, 8} = 8건
```

### write skip 은 scan 으로 범인을 찾는다

writer 는 청크를 통째로 받기 때문에 **몇 번째 item 이 문제인지 모른다.** 그래서:

```
청크 [11..15] write → 13 에서 실패 → 청크 롤백 (11, 12 insert 취소)
scan : [11] 트랜잭션 → 커밋
       [12] 트랜잭션 → 커밋
       [13] 트랜잭션 → 실패 → skip, 이 트랜잭션은 롤백
       [14] 트랜잭션 → 커밋
       [15] 트랜잭션 → 커밋
```

[`ftSkipJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobConfig.kt#L24) (테스트 [1](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L11 "read, process, write 에서 난 잘못된 데이터를 각각 건너뛰고 COMPLETED 된다") · [2](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L22 "write 스킵은 청크를 롤백하고 한 건씩 다시 쓰는 scan 으로 범인을 찾는다") · [3](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L33 "write 스킵 때 SkipListener 가 DB 에 남긴 기록은 롤백되어 사라진다")) 에서 확인되는 것:

- 11 은 write 가 **2번** 호출됐다 (청크 1번 + scan 1번). 15 는 **1번** (청크 write 가 13 에서 멈춰서 15 까지 못 감).
- **processor 는 다시 안 불린다.** Batch 6 scan 은 이미 가공된 결과를 그대로 다시 쓴다. (Batch 5 는 scan 때 processor 를 다시 부른다 → 11~15 가 2번씩)
- scan 트랜잭션이 5번 돌았어도 `commitCount` 는 1 만 오른다. `rollbackCount` 는 청크 롤백 1 + 13 롤백 1 = 2. (Batch 5 는 scan 트랜잭션마다 세서 `commitCount=8`)

> **Batch 5 와 비교:** "청크 롤백 → 1건씩 각자 트랜잭션으로 다시 write → 실패한 건만 skip" 이라는 **scan 의 뼈대는 같다.**
> 달라진 건 processor 재호출, 카운트, 아래의 SkipListener 기록, 그리고 scan 도중 실패했을 때의 재시작이다 ([맨 아래 표](#batch-5--6-에서-바뀐-것)).
> 전부 [`compare/batch5`](../compare/batch5/src/test/java/demo/Batch5CompareTest.java) 에서 같은 시나리오를 Batch 5.2.4 로 돌려서 확인했다.

### ❗ SkipListener 가 DB 에 쓴 기록은 롤백될 수 있다

[`RecordingSkipListener`](../src/main/kotlin/com/example/toybatch/faulttolerance/support/RecordingSkipListener.kt#L14) 는 skip 된 item 을 메모리와 `ft_skip_log` 테이블 두 군데에 남긴다. 결과:

| | 메모리 (`AttemptRecorder`) | `ft_skip_log` 테이블 |
|---|---|---|
| read 3 | ✅ | ✅ |
| process 8 | ✅ | ✅ |
| write 13 | ✅ | ❌ **없음** |

`onSkipInWrite` 는 13 의 scan 트랜잭션, 즉 **롤백될 트랜잭션** 안에서 불린다. 그래서 같은 트랜잭션으로 넣은 스킵 로그도 같이 사라진다.
**Batch 5 에서는 남는다** (skip 리스너를 롤백 이후, 다음에 커밋되는 트랜잭션에서 부른다). 5 에서 잘 되던 스킵 로그가 6 으로 올리면 조용히 빠지는 부분이다.

write skip 이 난 청크에서는 **process skip 기록도 같이 사라진다.** [`ftSkipListenerContractJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobConfig.kt#L50) ([테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L54 "같은 청크에서 write skip 이 나면 앞서 호출된 process skip 리스너의 DB 기록도 같이 롤백된다")) (같은 청크 [6..10] 에서 process 8 skip + write 9 skip):

| | 메모리 | `ft_skip_log` (Batch 6.0.5) | `ft_skip_log` (Batch 5.2.4) |
|---|---|---|---|
| process 8 | ✅ | ❌ 없음 | ✅ |
| write 9 | ✅ | ❌ 없음 | ✅ |

```
청크 [6..10]
  process 8 ✘ → 즉시 onSkipInProcess(8) 호출 (청크 트랜잭션 안에서 INSERT)
  write [6, 7, 9, 10] → 9 ✘ → 청크 롤백 → process 8 의 skip 로그도 같이 롤백
  scan : 6 ✔, 7 ✔, 9 ✘ → onSkipInWrite(9) 호출 후 이 트랜잭션도 롤백, 10 ✔
         (processor 도 process skip 리스너도 다시 안 불린다)
```

#### 의도된 동작인가, 버그인가

**의도된 동작이다.** 업스트림이 이렇게 결론내리고 계약을 이 동작에 맞게 고쳤다.

- 6.0.3 까지는 `onSkipInWrite` 의 DB 쓰기가 남았고, 6.0.4 부터 롤백된다. 이걸 회귀로 제보한 [#5436](https://github.com/spring-projects/spring-batch/issues/5436) 에 메인테이너가 "버그가 아니라 문서 문제" 라고 답했다. `ItemWriteListener#onWriteError`, `ChunkListener#onChunkError` 처럼 skip 리스너도 롤백될 트랜잭션에서 도는 게 맞고, 트랜잭션 작업은 별도 트랜잭션으로 하라는 것이다.
- 그 결과 [#5494](https://github.com/spring-projects/spring-batch/issues/5494) (6.0.6 마일스톤) 에서 `SkipListener` javadoc 과 레퍼런스 문서가 다시 쓰였다. 6.0.5 까지의 javadoc 에 있던 *"not called until just before committing"* 은 *"skip 이나 스텝 실패가 결정되기 직전에 호출"* 로 바뀌었고, 새 javadoc 은 다음을 명시한다.
  - `onSkipInRead` / `onSkipInProcess` 는 진행 중인 청크 트랜잭션 안에서 불리고, 그 트랜잭션이 커밋될지는 나머지 item 에 달렸다.
  - `onSkipInWrite` 는 scan 의 1건짜리 트랜잭션에서 불리고, 그 트랜잭션은 **항상 롤백된다.**
  - 그래서 리스너 안의 트랜잭션 작업은 `PROPAGATION_REQUIRES_NEW` 로 한다.
- 즉 이 프로젝트가 쓰는 6.0.5 의 javadoc 문구와는 어긋나지만, 그건 문서가 늦게 따라온 것이고 동작은 의도된 것이다. **Batch 5 와 다른 것도 사실**이라 5 → 6 이관 시 주의할 점이다.

그래서 Batch 6 에서는 **skip 리스너에서 청크 트랜잭션으로 DB 에 쓰지 않는다** (공식 권장 사항이다). 별도 트랜잭션(`REQUIRES_NEW`)으로 쓰거나, 메모리에 모았다가 `afterStep` 에서 저장한다.
→ 스킵 이력을 DB 에 남겨야 하면 별도 트랜잭션(`REQUIRES_NEW`)으로 쓰거나, 메모리에 모았다가 `afterStep` 에서 저장한다.

## 3. Retry + Skip 조합 (`RetrySkipJobConfig`, `RetrySkipJobTest`)

```
TransientException   : 2번 재시도, 그래도 안 되면 skip
InvalidItemException : 재시도 없이 바로 skip
writer               : MERGE (멱등)
```

| item | 규칙 | 시도 | 결과 |
|---|---|---|---|
| process 4 | Transient 2번 | 3번 | 3번째 성공 → 저장 |
| process 7 | Transient 계속 | 3번 | 재시도 소진 → skip |
| process 9 | Invalid | **1번** | retry 대상 아님 → 바로 skip |
| write 12 | Transient 1번 | | writer 재호출로 성공 |
| write 14 | Invalid | | retry 없이 청크 롤백 → scan → 14 만 skip |

결과: 20 − {7, 9, 14} = **17건, COMPLETED**. skip(read/process/write) = 0/2/1.
(4, 7, 9 는 청크2 [6..10], 12, 14 는 청크3 [11..15] 에 있다. 모든 청크가 결국 커밋된다)

write 청크 [11..15] 를 따라가 보면:

```
write 1차  : 11 ✔, 12 ✘ Transient
write 재시도 : 11 ✔, 12 ✔, 13 ✔, 14 ✘ Invalid (retry 대상 아님) → 청크 롤백 → scan
scan       : 11 ✔, 12 ✔, 13 ✔, 14 ✘ skip, 15 ✔     (한 건씩 각자 트랜잭션, scan 중에는 retry 없음)

item 별 writer 호출 횟수: item 11 = 3번, item 12 = 3번, item 14 = 2번, item 15 = 1번
```

- **skip 목록에 `TransientException` 도 넣어야** "재시도해도 안 되면 skip" 이 된다. 안 넣으면 재시도 소진 시 스텝이 FAILED.
- 여기서는 14 때문에 청크가 롤백되어 retry 때의 중복 insert 도 같이 취소된다. 하지만 14 가 없었다면 그대로 커밋되어 11 이 두 줄 남는다 ([`ftRetryWriteJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobConfig.kt#L40) 과 같은 상황). 롤백 여부에 기대지 말고 writer 를 멱등하게 만드는 게 답이다.

## Batch 5 → 6 에서 바뀐 것

Batch 6 쪽은 이 프로젝트 테스트로, Batch 5 쪽은 [`compare/batch5`](../compare/batch5/src/test/java/demo/Batch5CompareTest.java) (Boot 3.5.7 / Batch 5.2.4, 같은 시나리오) 로 확인했다.
"확인" 열이 비어 있는 줄은 소스를 읽고 적은 것이고 Batch 5 로 돌려보지는 않았다.

| | Batch 5 (`FaultTolerantStepBuilder`) | Batch 6 (`ChunkOrientedStep`) | 확인 |
|---|---|---|---|
| retry 구현 | spring-retry | Spring Framework 7 core retry | |
| write retry | 청크 **롤백 후** processor 부터 다시 → DB 중복 없음 (외부 호출은 중복) | **롤백 없이** 같은 트랜잭션에서 `write()` 재호출 → DB 도 중복, 멱등 필수 ([상세](#write-재시도-한-번에-무엇이-다시-도나-batch-5-vs-6)) | ✅ D |
| scan 방식 | 청크 롤백 → 1건씩 각자 트랜잭션 | 같음 | ✅ A, B |
| scan 때 processor | 다시 호출 | 다시 호출 안 함 (가공 결과 재사용) | ✅ A |
| scan 의 `commitCount` | scan 트랜잭션마다 +1 (100건 케이스 97) | scan 전체에 +1 (100건 케이스 1) | ✅ B |
| `onSkipInWrite` 에서 DB 에 쓴 것 | 남는다 | **롤백된다** | ✅ A |
| scan 도중 실패 후 재시작 | ✅ `ChunkMonitor` 가 "청크 시작 위치 + scan 오프셋" 을 저장 → 정확히 이어감 | ❌ reader 끝 위치가 저장 → **나머지 item 누락** ([02-2 Q4-3](02-2-writer-fault-tolerance.md#q4-3-같은-상황에서-skiplimit-이-3-이라면)) | ✅ C |
| `ChunkListener.afterChunk` | 커밋 후 | 커밋 전 | |
| `retryLimit()` 기본 간격 | 없음 | 1초 | |

(A~D 는 `Batch5CompareTest` 의 테스트 이름. 실행: `./gradlew -p compare/batch5 test`, 로그의 `RESULT` 줄)

## 체크리스트

- [ ] `.faultTolerant()` 를 붙였는가? (Batch 6 는 빠지면 retry/skip 이 조용히 무시된다)
- [ ] retry 는 **일시적 오류만** 대상으로 했는가? (데이터 오류를 retry 하면 시간만 버린다)
- [ ] retry 를 거는 writer 가 **멱등**한가?
- [ ] "재시도해도 안 되면 skip" 이 필요하면 그 예외를 **skip 목록에도** 넣었는가?
- [ ] `skipLimit` 이 적절한가? (기본 10. 너무 크면 대량 데이터 오류를 못 알아챈다)
- [ ] skip 이력을 DB 에 남긴다면, 롤백되는 트랜잭션 밖에서 쓰는가?
- [ ] 재시도 간격(`delay`)이 외부 시스템 사정에 맞는가?

## 테스트로 따라가기

테스트 이름이 곧 시나리오다. 위 표·설명과 같은 순서로 보면 된다.

**[RetryJobTest](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt)**

- [일시적 오류는 재시도해서 성공하면 아무 일 없던 것처럼 끝난다](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt#L11)
- [재시도를 다 써도 실패하면 skip 설정이 없으므로 스텝이 FAILED 된다](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt#L22)
- [retry 대상이 아닌 예외는 한 번만 시도하고 바로 실패한다](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt#L32)
- [writer 재시도는 롤백 없이 청크 전체를 다시 쓰므로 멱등하지 않으면 중복이 생긴다](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt#L42)
- [writer가 멱등하면 재시도해도 중복이 없다](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobTest.kt#L55)

**[SkipJobTest](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt)**

- [read, process, write 에서 난 잘못된 데이터를 각각 건너뛰고 COMPLETED 된다](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L11)
- [write 스킵은 청크를 롤백하고 한 건씩 다시 쓰는 scan 으로 범인을 찾는다](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L22)
- [write 스킵 때 SkipListener 가 DB 에 남긴 기록은 롤백되어 사라진다](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L33)
- [skipLimit 을 넘으면 스텝이 FAILED 된다](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L44)
- [같은 청크에서 write skip 이 나면 앞서 호출된 process skip 리스너의 DB 기록도 같이 롤백된다](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobTest.kt#L54)

**[RetrySkipJobTest](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetrySkipJobTest.kt)**

- [일시적 오류는 재시도하고, 재시도로도 안 되거나 잘못된 데이터면 건너뛴다](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetrySkipJobTest.kt#L13)
- [예외 종류와 단계에 따라 몇 번 시도하는지가 다르다](../src/test/kotlin/com/example/toybatch/faulttolerance/basic/RetrySkipJobTest.kt#L22)
