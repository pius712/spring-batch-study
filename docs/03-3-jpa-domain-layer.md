# 03-3. 도메인 계층의 reader / writer (JpaRepository 사용) 를 배치에서 재사용하기

선행: [03-2. Spring Data JPA Repository](03-2-jpa-repository.md)
코드: 아래 [코드 지도](#코드-지도) 참고.
실행: `./gradlew test --tests '*DomainLayerJobTest'`

## 코드 지도

| 파일 | 역할 |
|---|---|
| [OrderReader.kt](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/domain/OrderReader.kt) | 도메인 조회 컴포넌트. [`readAfter`](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/domain/OrderReader.kt#L20) = `@Transactional(readOnly = true)` + 키 기반 repository 메서드 |
| [SettlementWriter.kt](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/domain/SettlementWriter.kt) | 도메인 저장 컴포넌트. [`saveAll`](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/domain/SettlementWriter.kt#L15) (REQUIRED) / [`saveAllInNewTransaction`](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/domain/SettlementWriter.kt#L21) (REQUIRES_NEW) |
| [SettlementCalculator.kt](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/domain/SettlementCalculator.kt) | 도메인 계산 컴포넌트. [`calculate`](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/domain/SettlementCalculator.kt#L17) / [`calculateTransactional`](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/domain/SettlementCalculator.kt#L24) (같은 로직, `@Transactional` 유무만 다름) |
| [DomainLayerReaderConfig.kt](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerReaderConfig.kt) | [`domainOrderReader`](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerReaderConfig.kt#L19): 03-2 의 `KeysetRepositoryItemReader` 람다에서 도메인 reader 를 부른다 |
| [DomainLayerJobConfig.kt](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobConfig.kt) | 잡 정의 4개 |
| [DomainLayerJobTest.kt](../src/test/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |

`domain` 패키지는 "서비스/API 에서도 쓰는 도메인 코드" 라고 가정한다. 배치 코드는 그걸 가져다 쓰기만 한다.

## 한 줄 요약

**재활용된다. 03-2 에서 문제였던 건 "누가 repository 를 부르냐" 가 아니라 "어떤 쿼리 / 어느 트랜잭션 / SQL 이 언제 나가냐" 라서, 도메인 reader/writer 를 거쳐도 결과가 같다.**
**대신 도메인 코드에 붙은 `@Transactional` 이 새 함정을 만든다. 특히 참여 중인 `@Transactional` 메서드의 예외를 skip 하면 청크가 에러 없이 통째로 사라진다.**

## 끼우는 방법

```kotlin
// reader: 03-2 의 키 기반 reader 에 람다만 바꿔 넣는다
KeysetRepositoryItemReader("domainOrderReader", 10, keyOf = { it.id }) { lastId, limit ->
    orderReader.readAfter(lastId, limit.max())   // 도메인 reader (안에서 JpaRepository)
}

// processor / writer: 도메인 메서드를 그대로 부른다
ItemProcessor<JpaOrder, JpaSettlement> { calculator.calculate(it) }
ItemWriter<JpaSettlement> { chunk -> settlementWriter.saveAll(chunk.items) }  // 안에서 saveAllAndFlush
```

## 재현 결과

공통: `jpa_order` 에 id 1~100 (`amount = id*100`), chunk = pageSize = 10.
writer 는 "도메인 writer 로 저장 → 같은 청크에서 이어지는 작업" 순서이고, 이어지는 작업에서 실패를 주입한다 ([`writer`](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobConfig.kt#L59)).

| # | 잡 | 구성 | 결과 | 테스트 |
|---|---|---|---|---|
| 1 | [`domainJob`](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobConfig.kt#L35) | 도메인 reader(키 기반) → 도메인 writer(REQUIRED), 57 에서 실패 후 재시작 | ✅ 1차 50건, 재시작 후 100건 | [테스트](../src/test/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobTest.kt#L44 "도메인 reader writer 를 03-2 방식으로 끼우면 그대로 동작하고 재시작도 이어간다") |
| 2 | [`domainNewTxWriterJob`](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobConfig.kt#L39) | 도메인 writer 가 **REQUIRES_NEW**, 57 에서 실패 | ❌ 배치는 50 까지라고 기록, DB 엔 **60건** | [테스트](../src/test/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobTest.kt#L61 "도메인 writer 가 REQUIRES_NEW 면 청크가 롤백돼도 정산이 먼저 커밋돼서 남는다") |
| 3 | [`domainSkipJob`](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobConfig.kt#L43) | processor 가 도메인 계산(`@Transactional` 없음), 13 검증 실패 → skip | ✅ 13 만 skip, 99건 | [테스트](../src/test/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobTest.kt#L72 "@Transactional 없는 도메인 계산에서 검증 예외가 나면 그 건만 skip 된다") |
| 4 | [`domainTxSkipJob`](../src/main/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobConfig.kt#L51) | processor 가 도메인 계산(**`@Transactional`**), 13 검증 실패 → skip | ❌ 잡 COMPLETED, writeCount 99 인데 DB 엔 **90건**. 11~20 이 없다 | [테스트](../src/test/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobTest.kt#L83 "@Transactional 도메인 계산에서 검증 예외를 skip 하면 그 청크 전체가 조용히 롤백돼서 사라진다") |

### 1 — 03-2 의 결론이 그대로 적용된다

- 도메인 reader 가 **"마지막 키 다음 N개"** 를 제공하니까 03-2 케이스 3, 5 처럼 재시작과 status 변경에 안전하다. `Page`/`Pageable`(offset) 만 제공하면 03-2 케이스 2, 4 의 누락이 그대로 생긴다.
- 도메인 writer 가 `saveAllAndFlush` 라서 SQL 오류가 write 안에서 난다. `save`/`saveAll` 만 하면 03-2 케이스 8 (커밋 때 터져서 UNKNOWN) 과 같다.
- 도메인 reader 의 `@Transactional(readOnly = true)` 는 청크 트랜잭션에 **참여**할 뿐이라 이후 저장에 영향이 없었다 (케이스 1 에서 100건 저장).

### 2 — REQUIRES_NEW 는 청크 롤백에 묶이지 않는다

```
청크 [51..60]  (청크 트랜잭션 T1)
  write : settlementWriter.saveAllInNewTransaction(51~60)  → 새 트랜잭션 T2 에서 INSERT 후 즉시 커밋 ❗
          이어지는 작업에서 57 실패
  → T1 롤백 (reader 위치 last.key 는 50 으로 남음)
  → 하지만 T2 는 이미 커밋 → DB 에 정산 51~60 이 있다

결과: 배치 메타데이터 "50 까지 처리" ↔ 실제 DB "60 까지 저장"
```

- 이 예제는 정산 PK 가 order_id 라 재시작하면 merge 로 덮어써서 겉보기엔 복구된다. INSERT 만 하는 로그 테이블이나 외부 발송이면 **중복**이 된다 (02-2 의 외부 발송 중복과 같은 모양).
- 도메인 코드에서 감사 로그 등에 REQUIRES_NEW 를 쓰고 있다면, 배치 경로에서 그 메서드를 부르는지 확인해야 한다.

### 3 vs 4 — 같은 로직인데 `@Transactional` 하나로 청크가 사라진다

```
청크 [11..20]  (청크 트랜잭션 T1)
  process 13 : calculator.calculateTransactional(13)
               → @Transactional(REQUIRED) 로 T1 에 참여 → InvalidOrderException
               → Spring 이 "참여 중인 트랜잭션에서 RuntimeException" 이라 T1 을 rollback-only 로 표시 ❗
               → 배치는 예외를 받아 skip (processSkipCount=1)
  write      : 11, 12, 14 ~ 20 저장 (writeCount += 9)
  커밋 시점  : ChunkOrientedStep 이 status.isRollbackOnly() 를 보고 에러 없이 롤백하고 다음 청크로 넘어간다
               (건수 / commitCount 는 이미 올라간 뒤)

결과: 잡 COMPLETED, rollbackCount 0, writeCount 99 인데 DB 에는 11~20 이 없다. ERROR 로그도 없다.
```

- 케이스 3 (`@Transactional` 없음) 에서는 예외가 트랜잭션 인터셉터를 거치지 않으니 rollback-only 가 안 되고 13 만 skip 된다.
- 03-2 케이스 9 (`saveAllAndFlush` writer + skip) 가 괜찮았던 건 **write skip 은 원래 청크를 롤백하고 scan 으로 한 건씩 다시 쓰기** 때문이다. 이 문제는 롤백 없이 넘어가는 **process 단계의 skip** 에서 생긴다. (read 단계 skip 은 이 예제에서 확인하지 않았다)
- Spring Data 의 repository 구현(`SimpleJpaRepository`) 메서드에도 `@Transactional` 이 붙어 있다. processor 에서 부른 repository 메서드가 예외를 던져도 같은 구조가 될 수 있다 (따로 재현하지는 않았다).
- 메타데이터가 정상이라 모니터링으로도 안 잡힌다. 건수 검증(읽은 수 = 저장 수 + skip 수)을 따로 하지 않으면 모른다.

## 도메인 코드를 배치에서 쓸 때 체크리스트

- [ ] 도메인 reader 에 **키 기반 조회**(마지막 키 다음 N개)가 있는가? 없으면 배치용으로 하나 추가한다
- [ ] 도메인 writer 가 **write 안에서 flush** 하는가? (`saveAllAndFlush` 또는 호출 뒤 `flush()`)
- [ ] 배치 경로에서 부르는 도메인 메서드에 **REQUIRES_NEW** 가 없는가?
- [ ] **skip 대상 예외를 던지는 도메인 메서드에 `@Transactional`** 이 붙어 있지 않은가? 붙어 있다면
  - 검증을 트랜잭션 없는 메서드로 분리하거나 (케이스 3 처럼), 트랜잭션 메서드를 부르기 **전에** 검증해서 processor 에서 `null` 로 filter 한다
  - `@Transactional(noRollbackFor = [InvalidOrderException::class])` 로 rollback-only 표시를 막는다 (이 예제에서는 검증하지 않았다)
  - ❗ processor 에서 예외를 catch 해서 `null` 을 돌려도 소용없다. rollback-only 표시는 예외가 인터셉터를 지나갈 때 이미 남는다
- [ ] 저장할 건 writer 에서 명시적으로 저장하는가? (03-1, 03-2 의 dirty checking 함정은 도메인 메서드로 엔티티를 바꿔도 같다)

## 테스트로 따라가기

**[DomainLayerJobTest](../src/test/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobTest.kt)**

- [도메인 reader writer 를 03-2 방식으로 끼우면 그대로 동작하고 재시작도 이어간다](../src/test/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobTest.kt#L44)
- [도메인 writer 가 REQUIRES_NEW 면 청크가 롤백돼도 정산이 먼저 커밋돼서 남는다](../src/test/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobTest.kt#L61)
- [@Transactional 없는 도메인 계산에서 검증 예외가 나면 그 건만 skip 된다](../src/test/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobTest.kt#L72)
- [@Transactional 도메인 계산에서 검증 예외를 skip 하면 그 청크 전체가 조용히 롤백돼서 사라진다](../src/test/kotlin/com/example/toybatch/jpa/domainlayer/DomainLayerJobTest.kt#L83)
