# 04-1. 조건부 Flow (chunk 스텝의 ExitStatus 로 분기)

코드: 아래 [코드 지도](#코드-지도) 참고.

## 코드 지도

| 파일 | 역할 |
|---|---|
| [FlowJobConfig.kt](../src/main/kotlin/com/example/toybatch/flow/conditional/FlowJobConfig.kt) | 잡 정의: [`flowJob`](../src/main/kotlin/com/example/toybatch/flow/conditional/FlowJobConfig.kt#L38) (분기 규칙 전부 여기), [`processStep`](../src/main/kotlin/com/example/toybatch/flow/conditional/FlowJobConfig.kt#L53) (chunk=10) |
| [FlowReaderConfig.kt](../src/main/kotlin/com/example/toybatch/flow/support/FlowReaderConfig.kt) | `@StepScope` reader(1..inputCount) / processor. JobParameter 로 건수·실패 위치를 정한다 |
| [ValidateProcessor.kt](../src/main/kotlin/com/example/toybatch/flow/support/ValidateProcessor.kt) | failAt 번호에서 예외 (실패 분기용) |
| [ExitStatusListener.kt](../src/main/kotlin/com/example/toybatch/flow/conditional/ExitStatusListener.kt) | [afterStep](../src/main/kotlin/com/example/toybatch/flow/conditional/ExitStatusListener.kt#L20) 에서 읽은 건수를 보고 ExitStatus 를 바꾼다 |
| [FlowJobTest.kt](../src/test/kotlin/com/example/toybatch/flow/conditional/FlowJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |

## 한 줄 요약

**`on("코드")` 는 스텝의 ExitStatus 를 보고 다음 스텝을 고른다. chunk 스텝은 `afterStep` 리스너에서 ExitStatus 를 바꾼다.**

## 흐름

```
processStep(chunk=10) ──FAILED──→ alertStep   (processStep 은 ABANDONED, 잡은 COMPLETED)
    │ ─────NO_DATA──→ end
    │ ─────*────────→ reportStep
```

processStep: reader(1..inputCount) → processor(×10, failAt 에서 실패) → writer(로그).
분기 뒤의 스텝(report / alert)은 흐름만 보려는 거라 로그만 찍는 tasklet 이다.

| JobParameters | processStep 결과 | 실행되는 스텝 | 잡 상태 |
|---|---|---|---|
| `inputCount=30` | write 30 → `COMPLETED` | processStep → reportStep | COMPLETED |
| `inputCount=0` | read 0 → `NO_DATA` | processStep | COMPLETED |
| `inputCount=30, failAt=15` | 1~10 커밋, 11~20 청크에서 실패 → `FAILED` | processStep → alertStep | **COMPLETED** |

## 핵심

| 개념 | 설명 |
|---|---|
| BatchStatus vs ExitStatus | BatchStatus 는 "성공했나"(Batch 가 관리), ExitStatus 는 "어떻게 끝났나"(분기용 코드, 바꿀 수 있음). `inputCount=0` 이면 스텝은 COMPLETED 인데 ExitStatus 만 `NO_DATA` |
| chunk 스텝의 ExitStatus 바꾸기 | tasklet 처럼 `contribution` 을 직접 만질 곳이 없으니 `StepExecutionListener.afterStep` 에서 새 ExitStatus 를 돌려준다. 이 시점엔 read/write 건수가 다 집계돼 있다. 실패한 스텝은 건드리지 않는다 (FAILED 그대로 둬야 FAILED 분기가 걸림) |
| `on()` 매칭 순서 | 선언 순서가 아니라 **구체적인 패턴이 먼저**. `NO_DATA` 가 `*` 보다 먼저 적용된다. `?` 는 한 글자 와일드카드 |
| `from(step)` | 앞에서 선언한 스텝에 분기를 추가한다. 스텝을 **객체로** 찾으므로 스텝을 한 번 만들어 재사용해야 한다 (`processStep()` 을 매번 호출하면 다른 상태가 됨) |
| `end()` / `fail()` / `stopAndRestart()` | 여기서 잡을 COMPLETED / FAILED / STOPPED 로 끝낸다 |
| `@StepScope` + Kotlin | 스코프 프록시가 CGLIB 서브클래스라서 반환 타입 클래스가 `open` 이어야 한다 (`ValidateProcessor`). `ListItemReader` 는 원래 open 이라 괜찮다 |

## 주의: 실패를 분기로 처리하면 잡은 성공이다

`.on("FAILED").to(alertStep)` 으로 실패를 받으면

- 실패한 processStep 은 **ABANDONED** 로 바뀐다 (재시작해도 다시 실행하지 않는다는 표시)
- alertStep 이 성공하면 잡은 **COMPLETED** → 같은 파라미터로 **재시작할 수 없다**
- 1~10 은 이미 커밋됐고 11~30 은 처리되지 않은 채로 남는다

알림만 보내고 잡은 실패로 남기려면 `.on("FAILED").to(alertStep).on("*").fail()` 처럼 끝에 `fail()` 을 붙인다. 그래야 재시작으로 11 부터 이어갈 수 있다.
재시작 개념은 [01-1](01-1-restart-execution-context.md) 참고.

## 직접 확인해보기

- 테스트 로그의 `>>>` 줄을 따라가면 청크별 write 와 리스너가 정한 exitStatus 가 순서대로 보인다.
- 해볼 것: FAILED 분기 끝에 `.on("*").fail()` 붙이고 잡 상태 보기, `ExitStatusListener` 를 빼고 `inputCount=0` 돌려보기 (`COMPLETED` 로 끝나서 reportStep 으로 간다).
- 이어서 읽기: ExitStatus 를 바꾸지 않고 분기하는 방법은 [04-2 decider](04-2-decider.md).

## 테스트로 따라가기

**[FlowJobTest](../src/test/kotlin/com/example/toybatch/flow/conditional/FlowJobTest.kt)**

- [읽은 게 있으면 processStep 이 COMPLETED 로 끝나서 reportStep 으로 간다](../src/test/kotlin/com/example/toybatch/flow/conditional/FlowJobTest.kt#L28)
- [읽은 게 없으면 리스너가 ExitStatus 를 NO_DATA 로 바꿔서 바로 끝난다](../src/test/kotlin/com/example/toybatch/flow/conditional/FlowJobTest.kt#L37)
- [chunk 스텝이 실패해도 FAILED 로 분기해서 처리하면 스텝은 ABANDONED, 잡은 COMPLETED 로 끝난다](../src/test/kotlin/com/example/toybatch/flow/conditional/FlowJobTest.kt#L48)
