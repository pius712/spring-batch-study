# 02-2. writer 에서의 retry / skip — 질문별 정리

선행: [02-1. Fault tolerance](02-1-fault-tolerance.md)
코드: 아래 [코드 지도](#코드-지도) 참고. 문서 안의 잡·빈·클래스 이름을 누르면 정의된 줄로 이동한다.
실행: `./gradlew test --tests '*WriterFaultToleranceTest'`

모든 답은 Spring Batch 6.0.5 `ChunkOrientedStep` 소스와 위 테스트로 확인했다.

기본 구성은 reader 1~20, chunk 5 → **청크 하나 = 트랜잭션 하나.** (Q4-2, Q4-3 만 100건 / chunk 100)

```
청크1 [1..5]   청크2 [6..10]   청크3 [11..15]   청크4 [16..20]
```

실패 item 이 든 청크만 롤백되고 앞 청크는 이미 커밋돼서 남는다. 뒤 청크는 실행되지 않는다.
예: 13 에서 FAILED → 청크3 [11..15] 롤백 → 1~10 만 남음.


## 코드 지도

```
faulttolerance/
├── basic/    02-1 문서의 잡 (Q1, Q4-1 에서 같이 씀)
├── writer/   ← 이 문서: WriterRetryJobConfig(Q1, Q2), WriterSkipJobConfig(Q3, Q4), 해결 writer
├── fault/    실패 주입: Fault, 예외, FaultInjectingReader/Processor/Writer
└── support/  공통 뼈대·기록: FaultToleranceSteps, AttemptRecorder, FakeExternalApi
```

| 파일 | 역할 |
|---|---|
| [WriterRetryJobConfig.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterRetryJobConfig.kt) | Q1, Q2 의 잡 (write 재시도 중복, retry 한도 단위) |
| [WriterSkipJobConfig.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterSkipJobConfig.kt) | Q3, Q4 의 잡 (skip 한도 단위, scan) |
| [SavepointItemWriter.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/SavepointItemWriter.kt) | Q1 해결: savepoint 로 write 한 번을 원자적으로 |
| [ExternalSendWriter.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/ExternalSendWriter.kt) | Q1, Q4-5: 외부 전송 시점 3가지 (즉시 / item 마다 커밋 후 / 성공 후 1번) |
| [FakeExternalApi.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/support/FakeExternalApi.kt) | 트랜잭션 밖 외부 시스템 흉내 |
| [FaultToleranceSteps.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/support/FaultToleranceSteps.kt) | 공통 뼈대 (건수, 청크 크기, writer 교체 옵션) |
| [RetryJobConfig.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobConfig.kt) | Q1 에서 같이 쓰는 잡 (중복 / 멱등) |
| [SkipJobConfig.kt](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobConfig.kt) | Q4-1 에서 같이 쓰는 잡 |
| [WriterFaultToleranceTest.kt](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |
| [FaultToleranceTestSupport.kt](../src/test/kotlin/com/example/toybatch/faulttolerance/support/FaultToleranceTestSupport.kt) | 테스트 공통 설정·헬퍼 |

## 요약

| 질문 | 답 |
|---|---|
| Q1. write 재시도 때 중복? | **생긴다.** 롤백 없이 같은 트랜잭션에서 `write(chunk)` 를 다시 부른다. 해결: 멱등 writer / savepoint / (외부 전송은) 성공 후 1회 예약 |
| Q2. retry 한도 단위? | read/process 는 **item 마다**, write 는 **write 호출(= 청크) 마다** |
| Q3. skip 한도 단위? | **item 단위, 스텝 전체 누적** |
| Q4-1. write skip 때 롤백 안 됨? | **롤백된다.** (롤백이 안 되는 건 retry). 롤백 후 1건씩 scan |
| Q4-2. 100건 중 4건 skip | write 가 **101번** 불린다 (청크 1 + 1건씩 100) |
| Q4-3. 그런데 skipLimit=3 이면? | scan 도중 4번째에서 FAILED. 앞 66건은 이미 커밋. ❗ **재시작해도 70~100 은 처리되지 않는다** |
| Q4-4. skip 대상이 아닌 예외? | scan 없이 **청크 전체 롤백** → FAILED |
| Q4-5. scan 때 중복? | DB 는 롤백되니 중복 없음. **외부 전송은 중복.** 해결: 커밋 후 전송. Q1 의 "성공 후 1번 예약" writer 는 scan 에서도, retry + skip 에서도 한 번씩 |

---

## Q1. retry 정책이 writer 에서 문제가 되는 경우

### 재현

Batch 6 의 write 재시도는 **롤백 없이** 같은 트랜잭션에서 `writer.write(chunk)` 를 다시 호출한다 (`ChunkOrientedStep.doWrite` → `RetryTemplate.execute`).
그래서 **DB 쓰기**가 중복된다 (Batch 5 는 롤백 후 다시 써서 DB 중복은 없었다). **외부 전송** 중복은 Batch 5 도 같다. 트랜잭션 밖이라 롤백 여부와 관계없고, 5 도 재시도 때 writer 에 청크 전체를 다시 넘긴다. 버전별 비교는 [02-1 의 표](02-1-fault-tolerance.md#write-재시도-한-번에-무엇이-다시-도나-batch-5-vs-6).

```
청크 [11..15], 13 이 1번 Transient 실패
  write 1차 : INSERT 11 ✔, INSERT 12 ✔, 13 ✘
  write 2차 : INSERT 11, 12, 13, 14, 15 ✔   ← 11, 12 가 또 들어감. 롤백 없음
  커밋      → 11, 12 두 줄씩
```

| 테스트 | writer | 결과 |
|---|---|---|
| [`ftRetryWriteJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobConfig.kt#L40) | INSERT | ❗ 11, 12 두 줄씩 (22줄). `writeCount` 는 20 으로 정상처럼 보임 |
| [`ftRetryExternalJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterRetryJobConfig.kt#L57) | 외부 전송 | ❗ 11, 12 두 번 전송 |

### 해결

| 테스트 | 방법 | 결과 |
|---|---|---|
| [`ftRetryWriteIdempotentJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/RetryJobConfig.kt#L44) | **멱등 writer** (`MERGE ... KEY(...)`, upsert) | ✅ 20줄 |
| [`ftRetryWriteSavepointJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterRetryJobConfig.kt#L45) | **savepoint 로 write 한 번을 원자적으로** ([`SavepointItemWriter`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/SavepointItemWriter.kt#L16)) | ✅ INSERT writer 인데도 20줄 |
| [`ftRetryExternalAfterCommitJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterRetryJobConfig.kt#L60) | item 마다 afterCommit 예약 | ❗ **그래도 두 번** |
| [`ftRetryExternalOnSuccessJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterRetryJobConfig.kt#L64) | `write()` 가 끝까지 성공했을 때만 청크 전체 전송을 afterCommit 예약 | ✅ 한 번씩 |

```kotlin
// SavepointItemWriter: 실패하면 이번 write() 가 한 일만 되돌리고 예외를 다시 던진다
val connection = DataSourceUtils.getConnection(dataSource)   // 청크 트랜잭션의 커넥션
val savepoint = connection.setSavepoint()
try { delegate.write(chunk); connection.releaseSavepoint(savepoint) }
catch (e: Exception) { connection.rollback(savepoint); throw e }
```

**afterCommit 함정:** 재시도는 같은 트랜잭션이라, 실패한 시도에서 등록한 afterCommit 콜백도 **그대로 남아 있다가** 커밋 때 같이 실행된다.
→ 외부 전송은 "item 마다 바로 예약"이 아니라 **`write()` 가 예외 없이 끝난 뒤 한 번만** 예약해야 한다.
(scan 은 롤백되면서 콜백이 버려지므로 item 마다 예약해도 된다 → Q4-5)

---

## Q2. retry 한도는 item 단위? chunk 단위?

`RetryTemplate.execute()` 한 번이 재시도 한도의 단위다. 무엇을 감싸는지가 단계마다 다르다.

| 단계 | 감싸는 것 | 한도 단위 |
|---|---|---|
| read | `reader.read()` 1번 | item |
| process | `processor.process(item)` 1번 | item |
| write | `writer.write(chunk)` 1번 | **청크** |

같은 조건(청크 [11..15] 에서 12 가 1번, 14 가 2번 실패, `maxRetries=2`)으로 비교:

| 테스트 | 실패 위치 | 결과 |
|---|---|---|
| [`ftRetryScopeProcessJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterRetryJobConfig.kt#L69) | process | ✅ 12 는 2번, 14 는 3번 시도. 각자 한도 안 |
| [`ftRetryScopeWriteJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterRetryJobConfig.kt#L76) | write | ❌ **FAILED** |
| [`ftRetryScopeWrite3Job`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterRetryJobConfig.kt#L83) | write, `maxRetries=3` | ✅ |

```
ftRetryScopeWriteJob, 청크 [11..15] 의 write 호출
  1번째 : 11 ✔, 12 ✘                    ← 재시도 1 사용
  2번째 : 11 ✔, 12 ✔, 13 ✔, 14 ✘        ← 재시도 2 사용
  3번째 : 11 ✔, 12 ✔, 13 ✔, 14 ✘        → 한도 소진, FAILED
```

item 별로는 12 가 1번, 14 가 2번 실패했을 뿐인데 **청크 안의 실패를 전부 합쳐서** 센다. 청크가 클수록 write 재시도 한도가 빨리 바닥난다.

---

## Q3. skip 한도는 item 단위? chunk 단위?

**item 단위, 스텝 전체 누적.** `skipPolicy.shouldSkip(e, contribution.getStepSkipCount())` 에 스텝 전체 skip 수가 넘어간다.

[`ftSkipScopeJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterSkipJobConfig.kt#L42) ([테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L91 "Q3 - skip 한도는 item 단위이고 스텝 전체에서 누적된다")): process 에서 2, 4 (청크 1) / 7, 9 (청크 2) 가 잘못된 데이터, `skipLimit=3`

```
청크 [1..5]  : 2 skip(누적 1), 4 skip(누적 2) → 커밋 → 1, 3, 5 저장
청크 [6..10] : 7 skip(누적 3), 9 → 누적 4 > 3 → 청크 롤백, FAILED
```

한 청크에 2건씩이라 청크 단위로 세면 통과했어야 하지만, 누적 4건째에서 실패한다.

---

## Q4. writer 에서 skip 이 발생하면

### Q4-1. 청크 트랜잭션인데 예외가 나도 롤백이 안 된다?

**skip 은 롤백된다.** 롤백 없이 다시 호출하는 건 retry 다 (Q1).

```
write 실패 → 재시도 없음/소진 → skip 가능? → 예
  → scan 모드 진입 표시 → 예외를 던짐 → 청크 트랜잭션 롤백 (writer 가 넣은 11, 12 도 취소)
  → 다음 트랜잭션부터 1건씩 scan
```

[`ftSkipJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/basic/SkipJobConfig.kt#L24) ([테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L102 "Q4-1 write 에서 skip 이 나면 청크는 롤백된다 (재시도와 다르다)")) (INSERT writer, write 13 Invalid): 11 은 write 가 2번 호출됐는데 DB 에는 1줄 → 첫 시도가 롤백됐다는 증거.

### Q4-2. 100건 청크에서 4건 skip → scan 으로 I/O 가 100번?

[`ftScanIoJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterSkipJobConfig.kt#L53) ([테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L110 "Q4-2 100건 청크에서 4건이 잘못되면 scan 으로 write 가 101번 불린다")): 100건, chunk 100, write 10·30·50·70 이 잘못된 데이터.

```
write([1..100])  → 10 에서 실패 → 청크 롤백                    (1번)
scan : write([1]) 커밋, write([2]) 커밋, ..., write([10]) 실패 → 롤백, skip
       ... write([100]) 커밋                                  (100번)
```

- write 호출 **101번**, 트랜잭션 **101번** (청크 1 + scan 100). 4건 찾자고 100건을 전부 1건씩 다시 쓴다.
- `commitCount=1` (scan 전체를 1로 센다), `rollbackCount=5` (청크 1 + skip 4). (Batch 5 는 scan 트랜잭션마다 세서 `commitCount=97`. write 호출 101번은 5 도 같다)
- scan 중에는 retry 도 없다. 처음 실패한 item 이후도 끝까지 1건씩 간다.
- 청크가 크면 scan 비용이 그대로 커진다. 잘못된 데이터가 자주 섞이면 **processor 에서 검증해서 거르는 것**(process skip 은 scan 이 없다)이 훨씬 싸다.

### Q4-3. 같은 상황에서 skipLimit 이 3 이라면?

[`ftScanSkipLimitJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterSkipJobConfig.kt#L61) (테스트 [1](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L125 "Q4-3 같은 청크에서 skipLimit 3 이면 scan 도중 4번째에서 멈추고, 그 앞은 이미 커밋돼 있다") · [2](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L135 "Q4-3 함정 - 그 상태로 재시작하면 70 ~ 100 은 영영 처리되지 않는다")): 위와 같은데 `skipLimit=3`, reader 가 위치를 저장(`ftReader.read.count`).

```
scan : 1~9 커밋, 10 skip(1), 11~29 커밋, 30 skip(2), 31~49 커밋, 50 skip(3), 51~69 커밋
       70 → skip 누적 4 > 3 → FAILED
결과 : 66건 저장 (1~69 중 10, 30, 50 제외). 71~100 은 시도도 안 함
```

**❗ 그리고 재시작하면:**

```
1차 FAILED 시점의 step context : ftReader.read.count = 100
  ← scan 트랜잭션도 커밋할 때마다 reader.update() 로 위치를 저장한다.
    reader 는 청크를 만들 때 이미 100 까지 읽어둔 상태
2차(재시작) : reader 가 100 에서 시작 → 읽을 게 없음 → COMPLETED, readCount=0
결과 : 70 ~ 100 (31건) 은 영영 처리되지 않는다. 에러도 없다
```

scan 중간에 실패하면 **"reader 위치 = 청크 끝"** 인데 **"실제로 커밋된 것 = 청크 중간까지"** 가 되어 01 에서 본 "데이터와 위치가 항상 같이 커밋된다" 는 전제가 깨진다.

**Batch 5 에서는 이 문제가 없다.** 같은 시나리오를 Batch 5.2.4 로 돌리면 ([`compare/batch5`](../compare/batch5/src/test/java/demo/Batch5CompareTest.java) 의 C):

```
1차 FAILED 시점의 step context : r.count = 0, ChunkMonitor.OFFSET = 66
  ← ChunkMonitor 가 "청크를 시작할 때의 reader 위치" + "scan 으로 처리한 개수" 를 따로 저장한다
2차(재시작) : 청크 시작 위치로 돌아가 66 개를 건너뛰고 67 부터 → 70 skip (새 StepExecution 이라 skip 수는 0 부터)
결과 : 99건 저장, 누락은 잘못된 데이터 10, 30, 50, 70 뿐
```

Batch 6 의 `ChunkOrientedStep` 에는 이 `ChunkMonitor` 가 없다. **Batch 5 → 6 퇴행**이라, 6 에서 scan 이 걸칠 수 있는 잡은 아래 대응이 필요하다.

대응 (테스트로 검증한 것은 아님, 설계 방향):
- skip 한도 초과로 실패했다면 **재시작에 기대지 말고**, 처리 여부를 데이터로 판단하게 만든다. (01-2 의 status 플래그 + `saveState(false)`, 또는 멱등 writer + 처음부터 다시)
- `skipLimit` 을 충분히 크게 두고, 한도는 "이 이상이면 데이터가 이상하다" 는 경보 용도로 쓴다.
- 청크 크기를 줄여 scan 이 걸치는 범위 자체를 줄인다.

### Q4-4. skip 대상이 아닌 예외가 나면 청크 단위 롤백인가?

**그렇다.** [`ftWriteNonSkippableJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterSkipJobConfig.kt#L69) ([테스트](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L151 "Q4-4 skip 대상이 아닌 예외는 scan 없이 청크 롤백 후 FAILED")): `skip(InvalidItemException)` 인데 write 13 에서 [`TransientException`](../src/main/kotlin/com/example/toybatch/faulttolerance/fault/FaultExceptions.kt#L4) (retry 도 없음).

```
write([11..15]) : 11 ✔, 12 ✔, 13 ✘ Transient → skip 대상 아님 → scan 안 함 → 청크 롤백 → FAILED
결과 : 1~10 만 남음 (11, 12 도 롤백). 11 의 write 시도는 1번뿐
```

scan 도중에 skip 대상이 아닌 예외가 나면, 그 1건의 트랜잭션만 롤백되고 **앞서 scan 으로 커밋된 건 남는다** (Q4-3 과 같은 구조).

### Q4-5. scan 으로 처리할 때 앞 item 이 중복 처리될 수 있나?

| 대상 | 중복? | 이유 |
|---|---|---|
| DB (청크 트랜잭션 안) | 없음 | 첫 write 가 롤백된 뒤 scan 이 다시 쓴다 (Q4-1) |
| processor | 없음 | Batch 6 scan 은 가공 결과를 재사용하고 processor 를 다시 부르지 않는다 |
| **외부 전송 (트랜잭션 밖)** | **있음** | 첫 write 에서 이미 보낸 건 롤백이 안 된다 |

| 테스트 | writer | 결과 |
|---|---|---|
| [`ftScanExternalJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterSkipJobConfig.kt#L76) | write 안에서 바로 전송 | ❗ 11, 12 두 번 (첫 write + scan), 14, 15 한 번, 13 없음 |
| [`ftScanExternalAfterCommitJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterSkipJobConfig.kt#L79) | item 마다 커밋 후 전송 예약 | ✅ 전부 한 번. 롤백된 첫 write 의 예약은 버려진다 |
| [`ftScanExternalOnSuccessJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterSkipJobConfig.kt#L82) | `write()` 가 성공했을 때만 청크 전체 전송 예약 (Q1 의 해결 writer) | ✅ 전부 한 번. 13 은 없음 |
| [`ftRetryScanExternalOnSuccessJob`](../src/main/kotlin/com/example/toybatch/faulttolerance/writer/WriterSkipJobConfig.kt#L86) | 위 writer + **retry 2번 + skip**, 13 이 계속 실패 | ✅ 전부 한 번. 13 은 없음 |

#### Q1 의 "성공 후 1번 예약" writer 를 skip(scan) 과 같이 쓰면

```
청크 [11..15], 13 이 계속 실패, retry 2번 + skip  (ftRetryScanExternalOnSuccessJob)

청크 트랜잭션 T1
  write([11..15]) 1차 : 11 ✔ 12 ✔ 13 ✘   → write() 가 끝까지 못 가서 예약 안 함
  write([11..15]) 2차 : 11 ✔ 12 ✔ 13 ✘   → 예약 안 함          (retry: 같은 트랜잭션)
  write([11..15]) 3차 : 11 ✔ 12 ✔ 13 ✘   → 예약 안 함, retry 소진 → skip 가능 → T1 롤백
scan (1건씩 각자 트랜잭션)
  write([11]) ✔ → 예약 → 커밋 → 11 전송
  write([12]) ✔ → 예약 → 커밋 → 12 전송
  write([13]) ✘ → 예약 안 함 → 롤백 → skip        (scan 안에서는 retry 하지 않는다: 13 의 write 시도 = 청크 3 + scan 1 = 4번)
  write([14]) ✔ → 예약 → 커밋 → 14 전송
  write([15]) ✔ → 예약 → 커밋 → 15 전송

writeCalls = [5, 5, 5, 5, 5, 1, 1, 1, 1, 1, 5]   (청크 1, 2 / 청크 3 × 3번 / scan 5번 / 청크 4)
rollbackCount = 2 (청크 롤백 1 + scan 13 롤백 1), writeSkipCount = 1
```

- **scan 은 1건짜리 `write()` 를 부르는 것**이라, "write() 가 성공하면 그 청크 전체를 예약" 이 곧 "그 1건을 예약" 이 된다. 그래서 따로 처리할 게 없다.
- 청크 단계의 실패한 시도들은 `write()` 가 예외로 끝나서 **애초에 예약이 없다.** (item 마다 예약하는 writer 는 예약이 쌓이지만 T1 롤백 때 같이 버려진다)
- 결론: **retry 만 / skip(scan) 만 / 둘 다** 어느 경우든 "성공 후 1번 예약" 이면 외부 전송이 한 번씩이다. 외부 전송 writer 는 이 방식으로 두면 된다.
- 남는 위험 (테스트하지 않은 설계상 한계): 전송은 **DB 커밋 뒤**라, `afterCommit` 안에서 전송이 실패하면 DB 는 이미 커밋돼 있고 reader 위치도 넘어가 있다. 재시작해도 다시 보내지 않는다. 전송 누락까지 막아야 하면 아래 아웃박스 패턴을 쓴다.

해결 방법:
- **커밋 후 전송** (`TransactionSynchronization.afterCommit`). retry 도 쓴다면 Q1 처럼 `write()` 성공 후 한 번만 예약.
- 받는 쪽이 **멱등 키**(item id 등)로 중복을 걸러낸다.
- **아웃박스 패턴**: 청크 트랜잭션에서는 발송할 내용을 테이블에만 쓰고, 실제 발송은 별도 스텝/프로세스가 한다.

## 체크리스트

- [ ] write 재시도를 건다면 writer 가 **멱등하거나 원자적**인가? (MERGE, savepoint)
- [ ] writer 에 **트랜잭션 밖 부수효과**(외부 API, 메시지, 파일)가 있는가? → 커밋 후 전송 / 멱등 키 / 아웃박스
- [ ] write 재시도 한도는 **청크 전체**로 센다는 걸 감안했는가?
- [ ] 청크 크기 × write skip 가능성 = scan 비용. 잘못된 데이터는 **processor 에서 거를 수 있는가?**
- [ ] skip 한도 초과로 실패했을 때 **재시작하면 안 되는 경우**(Q4-3)를 알고 있는가?

## 테스트로 따라가기

테스트 이름이 곧 시나리오다. 위 표·설명과 같은 순서로 보면 된다.

**[WriterFaultToleranceTest](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt)**

- [Q1 재현 - write 재시도는 롤백 없이 write 를 다시 불러서 앞 item 이 중복 insert 된다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L16)
- [Q1 재현 - 외부 전송도 재시도 횟수만큼 중복된다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L23)
- [Q1 해결 - 멱등 writer(MERGE)](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L31)
- [Q1 해결 - savepoint 로 write 한 번을 원자적으로 만들면 재시도 전에 흔적이 지워진다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L37)
- [Q1 함정 - item 마다 커밋 후 전송을 예약해도 재시도는 같은 트랜잭션이라 실패한 시도의 예약까지 나간다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L45)
- [Q1 해결 - write 가 끝까지 성공했을 때만 전송을 예약하면 한 번씩만 나간다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L52)
- [Q2 - process 재시도 한도는 item 마다 따로 센다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L61)
- [Q2 - write 재시도 한도는 write 호출, 즉 청크 하나 단위로 센다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L70)
- [Q2 - 같은 상황에서 maxRetries 를 3 으로 올리면 성공한다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L83)
- [Q3 - skip 한도는 item 단위이고 스텝 전체에서 누적된다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L91)
- [Q4-1 write 에서 skip 이 나면 청크는 롤백된다 (재시도와 다르다)](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L102)
- [Q4-2 100건 청크에서 4건이 잘못되면 scan 으로 write 가 101번 불린다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L110)
- [Q4-3 같은 청크에서 skipLimit 3 이면 scan 도중 4번째에서 멈추고, 그 앞은 이미 커밋돼 있다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L125)
- [Q4-3 함정 - 그 상태로 재시작하면 70 ~ 100 은 영영 처리되지 않는다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L135)
- [Q4-4 skip 대상이 아닌 예외는 scan 없이 청크 롤백 후 FAILED](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L151)
- [Q4-5 재현 - scan 은 DB 는 롤백해주지만 외부 전송은 중복된다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L160)
- [Q4-5 해결 - 커밋 후 전송하면 롤백된 시도는 전송되지 않는다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L169)
- [Q4-5 성공 후 1번 예약 writer 도 scan 에서 건별로 한 번씩 전송된다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L177)
- [Q4-5 retry 와 skip 을 같이 걸어도 성공 후 1번 예약이면 한 번씩 전송된다](../src/test/kotlin/com/example/toybatch/faulttolerance/writer/WriterFaultToleranceTest.kt#L187)
