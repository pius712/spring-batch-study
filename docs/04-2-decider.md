# 04-2. JobExecutionDecider 로 분기

코드: 아래 [코드 지도](#코드-지도) 참고. [04-1](04-1-flow.md) 의 `on()` / `from()` 을 전제로 한다.

## 코드 지도

| 파일 | 역할 |
|---|---|
| [DeciderJobConfig.kt](../src/main/kotlin/com/example/toybatch/flow/decider/DeciderJobConfig.kt) | 잡 정의: [`deciderJob`](../src/main/kotlin/com/example/toybatch/flow/decider/DeciderJobConfig.kt#L34), [`processStep`](../src/main/kotlin/com/example/toybatch/flow/decider/DeciderJobConfig.kt#L47) (chunk=10) |
| [SizeDecider.kt](../src/main/kotlin/com/example/toybatch/flow/decider/SizeDecider.kt) | [decide](../src/main/kotlin/com/example/toybatch/flow/decider/SizeDecider.kt#L18) 에서 앞 스텝의 writeCount 로 `LARGE` / `SMALL` 을 돌려준다 |
| [FlowReaderConfig.kt](../src/main/kotlin/com/example/toybatch/flow/support/FlowReaderConfig.kt) | `@StepScope` reader(1..inputCount). 04-1 과 같이 쓴다 |
| [DeciderJobTest.kt](../src/test/kotlin/com/example/toybatch/flow/decider/DeciderJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |

## 한 줄 요약

**decider 는 스텝 사이에 끼워서 "다음에 어디로 갈지"만 정하는 분기점이다. 스텝의 ExitStatus 를 바꾸지 않고 분기할 수 있다.**

## 흐름

```
processStep(chunk=10) → sizeDecider ──LARGE──→ reportStep
                             └───────*──────→ end
```

| inputCount | decider 판단 (writeCount ≥ 100) | 실행되는 스텝 |
|---|---|---|
| 30 | `SMALL` | processStep |
| 150 | `LARGE` | processStep → reportStep |

## 코드

```kotlin
JobBuilder(JOB_NAME, jobRepository)
    .start(processStep())
    .next(sizeDecider)
        .on(SizeDecider.LARGE).to(reportStep())
    .from(sizeDecider)
        .on("*").end()
    .end()
    .build()
```

`decide(jobExecution, stepExecution)` 는 **바로 앞 스텝의 StepExecution** 을 받는다. 여기서는 그 writeCount 로 판단한다.

## 리스너로 ExitStatus 바꾸기(04-1) vs decider

| | afterStep 에서 ExitStatus 변경 (04-1) | JobExecutionDecider (04-2) |
|---|---|---|
| 분기 코드가 남는 곳 | 스텝의 EXIT_CODE (`BATCH_STEP_EXECUTION`) | 어디에도 안 남는다 (스텝이 아님) |
| 스텝의 결과 | 바뀐다 (`NO_DATA` 등) | 그대로 `COMPLETED` |
| 재시작하면 | COMPLETED 스텝은 건너뛰고, 저장된 EXIT_CODE 로 분기 | **다시 평가된다** |
| 어울리는 경우 | "이 스텝이 어떻게 끝났나" 가 분기 조건일 때 | 분기 조건이 스텝의 성공/실패와 관계없을 때 (건수, 요일, 설정값 등) |

## 직접 확인해보기

- 테스트 로그의 `>>> [sizeDecider]` 줄에서 writeCount 와 판단 결과가 보인다.
- 테스트에서 StepExecution 목록에 sizeDecider 가 없는 걸 확인할 수 있다.
- 해볼 것: `from(sizeDecider).on("*").end()` 를 지우고 `inputCount=30` 으로 돌려보기 (`SMALL` 에 매칭되는 전이가 없어서 잡이 FAILED).

- 이어서 읽기: Flow 를 따로 만들어 재사용하거나 병렬로 돌리는 방법은 [04-3 FlowBuilder](04-3-flow-builder.md).

## 테스트로 따라가기

**[DeciderJobTest](../src/test/kotlin/com/example/toybatch/flow/decider/DeciderJobTest.kt)**

- [쓴 건수가 기준 미만이면 decider 가 SMALL 을 돌려줘서 processStep 만 실행하고 끝난다](../src/test/kotlin/com/example/toybatch/flow/decider/DeciderJobTest.kt#L27)
- [쓴 건수가 기준 이상이면 decider 가 LARGE 를 돌려줘서 reportStep 까지 실행한다](../src/test/kotlin/com/example/toybatch/flow/decider/DeciderJobTest.kt#L35)
