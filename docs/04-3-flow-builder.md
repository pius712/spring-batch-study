# 04-3. FlowBuilder 로 Flow 조립하기 (재사용 / split)

코드: 아래 [코드 지도](#코드-지도) 참고. [04-1](04-1-flow.md) 의 `on()` / `from()` / `end()` 를 전제로 한다.

## 코드 지도

| 파일 | 역할 |
|---|---|
| [FlowBuilderJobConfig.kt](../src/main/kotlin/com/example/toybatch/flow/builder/FlowBuilderJobConfig.kt) | Flow 빈: [`usersFlow`](../src/main/kotlin/com/example/toybatch/flow/builder/FlowBuilderJobConfig.kt#L38), [`ordersFlow`](../src/main/kotlin/com/example/toybatch/flow/builder/FlowBuilderJobConfig.kt#L43). 잡: [`flowBuilderJob`](../src/main/kotlin/com/example/toybatch/flow/builder/FlowBuilderJobConfig.kt#L50) (순차), [`splitJob`](../src/main/kotlin/com/example/toybatch/flow/builder/FlowBuilderJobConfig.kt#L60) (병렬) |
| [FlowBuilderJobTest.kt](../src/test/kotlin/com/example/toybatch/flow/builder/FlowBuilderJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |

## 한 줄 요약

**`FlowBuilder` 는 스텝 묶음(Flow)을 잡과 따로 만든다. 만든 Flow 는 여러 잡에 끼워 쓸 수 있고, `split` 으로 여러 Flow 를 병렬로 돌릴 수 있다.**

## 흐름

```
usersFlow  = loadUsersStep
ordersFlow = loadOrdersStep

flowBuilderJob : usersFlow → ordersFlow → processStep        (한 스레드에서 순서대로)

splitJob       : ┌ usersFlow  ┐
                 │            ├→ processStep                 (Flow 마다 스레드 하나, 둘 다 끝나면 다음)
                 └ ordersFlow ┘
```

두 잡이 **같은 Flow 빈**(`usersFlow`, `ordersFlow`)을 같이 쓴다.
스텝은 흐름만 보려는 거라 로그만 찍는 tasklet 이고, 어느 스레드에서 돌았는지 Step ExecutionContext 에 남긴다.

## 코드

```kotlin
// Flow 만들기: JobBuilder 와 쓰는 법이 같다 (start / next / on / from ...)
@Bean
fun usersFlow(): Flow = FlowBuilder<SimpleFlow>("usersFlow")
    .start(loadUsersStep)
    .build()

// 순차로 이어 붙이기
JobBuilder("flowBuilderJob", jobRepository)
    .start(usersFlow())
    .next(ordersFlow())
    .next(processStep)
    .end()        // start(flow) 로 시작하면 JobFlowBuilder 가 되므로 end() 로 닫는다
    .build()

// 병렬로 돌리기
val parallelLoad = FlowBuilder<SimpleFlow>("parallelLoad")
    .split(SimpleAsyncTaskExecutor("split-"))
    .add(usersFlow(), ordersFlow())
    .build()

JobBuilder("splitJob", jobRepository)
    .start(parallelLoad)
    .next(processStep)   // split 안의 Flow 가 전부 끝나야 실행된다
    .end()
    .build()
```

## 핵심

| 개념 | 설명 |
|---|---|
| Flow vs Job | Flow 는 스텝과 전이의 묶음일 뿐이라 혼자 실행할 수 없다. 잡(또는 `StepBuilder.flow(flow)` 로 만든 FlowStep) 안에 넣어야 돈다 |
| 재사용 | Flow 를 빈으로 만들어 두면 여러 잡이 같이 쓴다. 공통 준비 단계처럼 여러 잡에 반복되는 스텝 묶음에 쓴다 |
| 스텝 이름 | 재사용하는 Flow 안의 스텝은 특정 잡 이름을 붙이기 애매해서 `sharedFlow.` 접두사를 붙였다. 잡마다 StepExecution 은 따로 남으니 이름이 겹쳐도 문제없다 |
| `split(executor)` | 안에 넣은 Flow 를 executor 로 동시에 실행하고, **전부 끝날 때까지 기다린 뒤** 다음으로 간다. `SimpleAsyncTaskExecutor` 는 Flow 마다 새 스레드를 만든다 |
| split 과 실패 | 하나라도 실패하면 나머지가 끝날 때까지 기다린 뒤 split 전체가 실패로 끝난다 |
| split 과 트랜잭션 | 병렬 스텝은 각자 자기 트랜잭션·커넥션을 쓴다. 같은 테이블을 동시에 쓰면 락 경합이 생길 수 있다 |

## 직접 확인해보기

- 테스트 로그의 `>>>` 줄에서 `thread=Test worker`(순차) 와 `thread=split-1`, `split-2`(병렬) 를 비교해 본다.
- 해볼 것: `split` 의 executor 를 `SyncTaskExecutor` 로 바꿔보기 (split 인데 한 스레드에서 차례로 돈다), usersFlow 안에 스텝을 하나 더 넣고 on/from 분기를 추가해 보기.

## 테스트로 따라가기

**[FlowBuilderJobTest](../src/test/kotlin/com/example/toybatch/flow/builder/FlowBuilderJobTest.kt)**

- [Flow 를 이어 붙이면 Flow 안의 스텝이 순서대로 같은 스레드에서 실행된다](../src/test/kotlin/com/example/toybatch/flow/builder/FlowBuilderJobTest.kt#L29)
- [split 으로 묶은 Flow 는 각자 다른 스레드에서 돌고, 둘 다 끝난 뒤 다음 스텝이 실행된다](../src/test/kotlin/com/example/toybatch/flow/builder/FlowBuilderJobTest.kt#L43)
