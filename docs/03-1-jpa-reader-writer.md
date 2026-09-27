# 03-1. JPA reader / writer

선행: [01-2. DB reader 재시작](01-2-restart-jdbc-reader.md), [02-1. Fault tolerance](02-1-fault-tolerance.md)
코드: 아래 [코드 지도](#코드-지도) 참고. 문서 안의 잡·빈·클래스 이름을 누르면 정의된 줄로 이동한다.
이어서: Spring Data Repository 를 그대로 쓰는 방법은 [03-2](03-2-jpa-repository.md)
실행: `./gradlew test --tests '*JpaJobTest'`


## 코드 지도

| 파일 | 역할 |
|---|---|
| [JpaJobConfig.kt](../src/main/kotlin/com/example/toybatch/jpa/basic/JpaJobConfig.kt) | 잡 정의 9개 |
| [JpaReaderConfig.kt](../src/main/kotlin/com/example/toybatch/jpa/basic/JpaReaderConfig.kt) | reader 빈 (JpaPagingItemReader, JpaCursorItemReader) |
| [JpaWriterConfig.kt](../src/main/kotlin/com/example/toybatch/jpa/basic/JpaWriterConfig.kt) | writer 빈 (JpaItemWriter, SettleAndMarkDoneWriter) |
| [JpaSteps.kt](../src/main/kotlin/com/example/toybatch/jpa/support/JpaSteps.kt) | 공통 스텝 뼈대 (chunk = 10) |
| [Entities.kt](../src/main/kotlin/com/example/toybatch/jpa/support/Entities.kt) | 엔티티 (JpaOrder, JpaSettlement) |
| [Processors.kt](../src/main/kotlin/com/example/toybatch/jpa/support/Processors.kt) | processor (SettlementProcessor, SettleOrderProcessor) |
| [FlakyFlushWriter.kt](../src/main/kotlin/com/example/toybatch/jpa/basic/FlakyFlushWriter.kt) | flush 시점 일시 오류 흉내 (케이스 8, 9) |
| [SettleAndMarkDoneWriter.kt](../src/main/kotlin/com/example/toybatch/jpa/basic/SettleAndMarkDoneWriter.kt) | 케이스 7 해결: 정산 + 상태 변경을 writer 에서 같이 |
| [JpaJobTest.kt](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |

## 한 줄 요약

**JPA reader, JPA writer, 청크 트랜잭션은 각자 다른 EntityManager 를 쓴다.**
그래서 "processor 에서 엔티티 값만 바꾸면 dirty checking 으로 저장되겠지"가 **reader 에 따라 안 되기도 하고, 되는데 엉뚱한 트랜잭션으로 되기도 한다.**
저장은 항상 **writer 에서, 청크 트랜잭션으로** 하는 게 원칙이다.

## 누가 어떤 EntityManager 를 쓰나 (Batch 6.0 소스 기준)

| 컴포넌트 | EntityManager | 트랜잭션 | 특징 |
|---|---|---|---|
| `JpaPagingItemReader` (기본 `transacted=true`) | **자기 전용** | 페이지마다 **자기 트랜잭션**: `begin → flush → clear → 조회 → commit` | 다음 페이지를 읽을 때 이전 페이지 엔티티의 변경이 flush 된다 |
| `JpaPagingItemReader` (`transacted=false`) | 자기 전용 | 없음 | 읽은 엔티티를 바로 `detach` |
| `JpaCursorItemReader` | **자기 전용** | **없음** | 청크 커밋 때마다 `clear()`. 변경은 flush 될 기회가 없다 |
| `JpaItemWriter` | **청크 트랜잭션에 묶인 것** | 청크 트랜잭션 | `merge`(기본) 또는 `persist` 후 `flush()` |
| (참고) `RepositoryItemReader` | 청크 트랜잭션에 묶인 것 | 청크 트랜잭션 | 조회가 청크 트랜잭션 안에서 일어난다 → [03-2](03-2-jpa-repository.md) |

```
청크 트랜잭션 (JpaTransactionManager) ── EM-W ── JpaItemWriter: merge/persist + flush
JpaPagingItemReader ─────────────────── EM-R ── 페이지마다 자기 트랜잭션 (청크 트랜잭션과 무관)
JpaCursorItemReader ─────────────────── EM-C ── 트랜잭션 없음
```

reader 가 준 엔티티는 **EM-R / EM-C 소속**이다. EM-W 입장에서는 모르는 객체라서 `JpaItemWriter` 가 `merge` 로 붙인다.

## 재현 결과

공통: `jpa_order` 에 id 1~100 (`amount = id*100`, `status = READY`), chunk = pageSize = 10.

| # | 잡 | 구성 | 결과 | 테스트 |
|---|---|---|---|---|
| 1 | [`jpaPagingJob`](../src/main/kotlin/com/example/toybatch/jpa/basic/JpaJobConfig.kt#L48) | paging → 주문을 정산으로 변환 → `JpaItemWriter` | ✅ 100건. 57 에서 실패 후 재시작하면 `OFFSET 50` 부터 이어서 100건 | [테스트](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L46 "JpaPagingItemReader 에서 JpaItemWriter 로 저장하고, 실패 후 재시작하면 이어서 처리한다") |
| 2 | [`jpaPagingStatusJob`](../src/main/kotlin/com/example/toybatch/jpa/basic/JpaJobConfig.kt#L55) | paging(`status='READY'`) + 주문을 DONE 으로 merge | ❌ **50건 누락**, COMPLETED | [테스트](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L65 "JpaPagingItemReader 로 status 조건을 읽으면서 status 를 바꾸면 절반이 누락된다") |
| 3 | [`jpaCursorStatusJob`](../src/main/kotlin/com/example/toybatch/jpa/basic/JpaJobConfig.kt#L60) | cursor(`status='READY'`) + 주문을 DONE 으로 merge | ✅ 100건 | [테스트](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L76 "JpaCursorItemReader 는 쿼리를 한 번만 실행하므로 status 를 바꿔도 전부 처리한다") |
| 4 | [`jpaCursorDirtyJob`](../src/main/kotlin/com/example/toybatch/jpa/basic/JpaJobConfig.kt#L66) | cursor + processor 에서 `order.settle()` (writer 는 정산만) | ❌ 정산 100건인데 **주문 DONE 0건** | [테스트](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L86 "cursor reader 가 준 엔티티를 processor 에서 바꿔도 저장되지 않는다") |
| 5 | [`jpaPagingDirtyJob`](../src/main/kotlin/com/example/toybatch/jpa/basic/JpaJobConfig.kt#L71) | paging + processor 에서 `order.settle()` | ⚠️ DONE 100건. 저장은 되는데 **reader 트랜잭션**으로 | [테스트](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L96 "paging reader 가 준 엔티티는 processor 에서 바꾸면 저장되는데, 청크 트랜잭션이 아니라 reader 트랜잭션으로 저장된다") |
| 6 | [`jpaPagingDirtySkipJob`](../src/main/kotlin/com/example/toybatch/jpa/basic/JpaJobConfig.kt#L76) | 5 + 정산 13 쓰기 실패 → skip | ❌ 정산 13 은 없는데 **주문 13 은 DONE** | [테스트](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L105 "그래서 청크가 롤백되어도 processor 에서 바꾼 상태는 남는다 - skip 된 건인데 DONE") |
| 7 | [`jpaSettleAndMarkSkipJob`](../src/main/kotlin/com/example/toybatch/jpa/basic/JpaJobConfig.kt#L85) | 정산 저장 + 상태 변경을 writer 에서 같이, 13 skip | ✅ 13 은 정산도 없고 READY 로 남음 | [테스트](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L117 "정산 저장과 상태 변경을 writer 에서 같은 트랜잭션으로 하면 skip 된 건은 둘 다 안 남는다") |
| 8 | [`jpaWriteRetryJob`](../src/main/kotlin/com/example/toybatch/jpa/basic/JpaJobConfig.kt#L95) | flush 가 한 번 실패 → write 재시도 | ❌ 재시도가 **PK 중복**으로 계속 실패 → FAILED | [테스트](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L131 "JPA writer 의 write 재시도는 같은 트랜잭션에서 이미 insert 된 행과 PK 가 충돌해서 성공할 수 없다") |
| 9 | [`jpaWriteRetryScanJob`](../src/main/kotlin/com/example/toybatch/jpa/basic/JpaJobConfig.kt#L103) | 8 + skip 설정 | ✅ scan 으로 살아나서 100건 | [테스트](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L145 "retry 에 skip 을 같이 걸면 scan 이 한 건씩 새 트랜잭션으로 다시 써서 살아난다") |

### 2 vs 3 — 진짜 `JpaPagingItemReader` 의 오프셋 누락

[01-2](01-2-restart-jdbc-reader.md) 에서 JDBC 로 흉내냈던 문제를 진짜 `JpaPagingItemReader` 로 재현했다.

```
page0 : WHERE status='READY' ORDER BY id  OFFSET 0  → 1~10   → DONE 으로 merge, 커밋
page1 : WHERE status='READY' ORDER BY id  OFFSET 10 → READY 목록이 [11..100] 이라 21~30 을 읽음 (11~20 건너뜀)
...
❗ READY 로 남은 주문 = [11..20, 31..40, 51..60, 71..80, 91..100]
```

`JpaCursorItemReader` 는 쿼리를 한 번만 실행해서 결과를 흘려받으므로 목록이 당겨질 일이 없다.
(cursor 는 스텝 내내 커넥션과 결과셋을 붙잡고 있는다는 비용이 있다. 데이터가 크면 키 기반인 `JdbcPagingItemReader` 가 낫다)

### 4 — cursor reader 의 엔티티는 바꿔도 버려진다

```kotlin
override fun process(order: JpaOrder): JpaSettlement {
    order.settle()                       // status = DONE, fee = ...  ← 저장될 거라 기대
    return JpaSettlement(order.id, ...)  // writer 는 정산만 저장
}
```

`order` 는 `JpaCursorItemReader` 의 EM-C 소속이다. EM-C 는 트랜잭션도 flush 도 없고 청크마다 `clear()` 된다.
→ **정산은 100건 들어갔는데 주문은 전부 READY.** 다음 날 다시 돌리면 전부 또 정산된다.

### 5, 6 — paging reader 의 엔티티는 "저장은 되는데" 청크 트랜잭션 밖이다

같은 processor 를 `JpaPagingItemReader` 와 쓰면 DONE 이 100건 저장된다. 저장해주는 건 writer 가 아니라 **reader 가 다음 페이지를 읽을 때 자기 트랜잭션에서 한 `flush()`** 다.
청크 트랜잭션과 따로 커밋되므로, 청크가 롤백돼도 되돌려지지 않는다. 케이스 6 이 그 결과다.

```
청크 [11..20]
  processor : 11~20 전부 order.settle()  (EM-R 에 변경만 쌓임)
  write     : 정산 13 이 CHECK 위반 → 청크 롤백 → scan → 정산 13 skip
page2 읽기  : EM-R 이 자기 트랜잭션에서 flush → 주문 11~20 전부 DONE 커밋 (13 포함)

결과: 정산 13 없음, 주문 13 DONE  ❗ → 다시는 정산 대상으로 안 잡힌다
```

### 7 — 고치는 방법: 바꿀 건 writer 에서, 청크 트랜잭션으로

[`SettleAndMarkDoneWriter`](../src/main/kotlin/com/example/toybatch/jpa/basic/SettleAndMarkDoneWriter.kt#L14) 는 정산 저장과 주문 상태 변경을 **둘 다 청크 트랜잭션의 EntityManager(EM-W)로** 한다.

```kotlin
override fun write(chunk: Chunk<out JpaSettlement>) {
    settlementWriter.write(chunk)   // JpaItemWriter: merge + flush
    EntityManagerFactoryUtils.getTransactionalEntityManager(emf)!!
        .createQuery("UPDATE JpaOrder o SET o.status = 'DONE' WHERE o.id IN :ids")
        .setParameter("ids", chunk.items.map { it.orderId })
        .executeUpdate()
}
```

같이 커밋되고 같이 롤백된다. 13 은 scan 에서 skip 되면서 상태 변경도 롤백되어 READY 로 남는다 → 데이터를 고치고 다시 돌리면 정산된다.
주문 엔티티 하나만 바꾸는 경우라면 케이스 3처럼 **processor 가 바꾼 엔티티를 반환하고 `JpaItemWriter<JpaOrder>` 가 merge** 하게 하면 된다.

### 8 vs 9 — JPA writer 에 write 재시도를 걸면

[`FlakyFlushWriter`](../src/main/kotlin/com/example/toybatch/jpa/basic/FlakyFlushWriter.kt#L17) 는 13 이 든 청크를 처음 쓸 때만 flush 가 실패하도록(CHECK 위반) 만든 writer 다. "데드락처럼 다시 하면 되는 DB 오류"를 흉내낸다.

```
청크 [11..20]
  write 1차 : merge 11..20 → flush: INSERT 11 ✔, INSERT 12 ✔, INSERT 13 ✘ (CHECK)
  재시도 1  : 롤백 없이 같은 트랜잭션 → flush: INSERT 11 ✘ Unique index or primary key violation
  재시도 2  : 같음
  → 재시도 소진, skip 설정 없음 → FAILED
```

[02](02-1-fault-tolerance.md) 에서 본 것처럼 Batch 6 의 write 재시도는 **롤백 없이 같은 트랜잭션에서 `write()` 를 다시 부른다.**
JDBC 에서는 조용히 중복 insert 가 됐지만, JPA 에서는 PK 가 있어서 에러로 드러나고 **재시도는 절대 성공하지 못한다.**
(Hibernate 도 "flush 중 예외가 난 Session 은 다시 쓰지 말라"는 입장이다)

케이스 9 처럼 **skip 을 같이 걸면** 재시도 소진 후 scan 으로 넘어간다. scan 은 한 건씩 **새 트랜잭션 = 새 EntityManager** 로 쓰기 때문에 일시적 오류였다면 여기서 성공한다 (skip 0건, 100건 저장).
다른 방법은 write 재시도를 아예 걸지 않고 스텝을 실패시킨 뒤 재시작하는 것이다. 청크 단위로 롤백되고 context 가 맞춰져 있으니 재시작은 안전하다 (케이스 1).

## 그 밖에 알아둘 것

- **JPA 가 들어오면 Boot 가 `JpaTransactionManager` 를 만든다.** 배치도 이걸 쓰고, `JdbcTemplate` 도 같은 트랜잭션에 참여한다. 그래서 이 프로젝트의 JDBC 예제들도 그대로 동작한다.
- **`JpaItemWriter` 는 기본이 `merge`.** PK 를 직접 지정한 엔티티는 merge 때 `SELECT` 를 먼저 한다 (건마다 조회 1번). 새 엔티티만 쓴다면 `usePersist(true)` 가 빠르다.
  - 단, `persist` + 자동 생성 ID(IDENTITY) 조합은 롤백 후 scan/재시도 때 이미 ID 가 채워진 객체를 다시 `persist` 하게 될 수 있다. skip/retry 를 쓴다면 테스트로 꼭 확인하자.
- **Kotlin 엔티티**: `kotlin("plugin.jpa")` 로 기본 생성자를, `allOpen { annotation("jakarta.persistence.Entity") }` 로 open 을 붙인다 (지연 로딩 프록시용). `build.gradle.kts` 참고.
- **`spring.jpa.hibernate.ddl-auto: none`**: 테이블은 `schema.sql` 이 만든다. 내장 DB 에서 Boot 기본값(`create-drop`)이 켜지면 Hibernate 가 테이블을 지우고 다시 만든다.

## 체크리스트

- [ ] processor 에서 엔티티를 바꾸고 있다면, **그 엔티티를 writer 가 저장하는가?** (dirty checking 에 기대지 않는다)
- [ ] 여러 테이블을 바꾼다면 **전부 writer 에서, 청크 트랜잭션으로** 하는가?
- [ ] 조회 조건 컬럼(status 등)을 같은 잡에서 바꾼다면 **`JpaPagingItemReader` 를 쓰고 있지 않은가?** → cursor 또는 키 기반 페이징
- [ ] JPA writer 에 **write 재시도**를 걸었다면 skip 도 같이 걸었는가? 아니면 재시도 없이 실패 → 재시작으로 갈 것인가?
- [ ] `JpaItemWriter` 의 merge/persist 선택이 엔티티의 ID 전략과 맞는가?

## 테스트로 따라가기

테스트 이름이 곧 시나리오다. 위 표·설명과 같은 순서로 보면 된다.

**[JpaJobTest](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt)**

- [JpaPagingItemReader 에서 JpaItemWriter 로 저장하고, 실패 후 재시작하면 이어서 처리한다](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L46)
- [JpaPagingItemReader 로 status 조건을 읽으면서 status 를 바꾸면 절반이 누락된다](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L65)
- [JpaCursorItemReader 는 쿼리를 한 번만 실행하므로 status 를 바꿔도 전부 처리한다](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L76)
- [cursor reader 가 준 엔티티를 processor 에서 바꿔도 저장되지 않는다](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L86)
- [paging reader 가 준 엔티티는 processor 에서 바꾸면 저장되는데, 청크 트랜잭션이 아니라 reader 트랜잭션으로 저장된다](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L96)
- [그래서 청크가 롤백되어도 processor 에서 바꾼 상태는 남는다 - skip 된 건인데 DONE](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L105)
- [정산 저장과 상태 변경을 writer 에서 같은 트랜잭션으로 하면 skip 된 건은 둘 다 안 남는다](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L117)
- [JPA writer 의 write 재시도는 같은 트랜잭션에서 이미 insert 된 행과 PK 가 충돌해서 성공할 수 없다](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L131)
- [retry 에 skip 을 같이 걸면 scan 이 한 건씩 새 트랜잭션으로 다시 써서 살아난다](../src/test/kotlin/com/example/toybatch/jpa/basic/JpaJobTest.kt#L145)
