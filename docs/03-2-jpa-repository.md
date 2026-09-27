# 03-2. Spring Data JPA Repository 를 그대로 쓰기

선행: [03-1. JPA reader / writer](03-1-jpa-reader-writer.md)
이어서: 도메인 계층의 reader / writer 가 repository 를 쓰는 경우는 [03-3](03-3-jpa-domain-layer.md)
코드: 아래 [코드 지도](#코드-지도) 참고. 문서 안의 잡·빈·클래스 이름을 누르면 정의된 줄로 이동한다.
실행: `./gradlew test --tests '*RepositoryJobTest'`


## 코드 지도

| 파일 | 역할 |
|---|---|
| [RepositoryJobConfig.kt](../src/main/kotlin/com/example/toybatch/jpa/repository/RepositoryJobConfig.kt) | 잡 정의 8개 |
| [Repositories.kt](../src/main/kotlin/com/example/toybatch/jpa/support/Repositories.kt) | Spring Data 리포지토리 (JpaOrderRepository, JpaSettlementRepository) |
| [KeysetRepositoryItemReader.kt](../src/main/kotlin/com/example/toybatch/jpa/repository/KeysetRepositoryItemReader.kt) | 리포지토리 메서드를 람다로 부르는 키 기반 reader |
| [RepositoryReaderConfig.kt](../src/main/kotlin/com/example/toybatch/jpa/repository/RepositoryReaderConfig.kt) | reader 빈 (RepositoryItemReader, 키 기반 reader) |
| [RepositoryWriterConfig.kt](../src/main/kotlin/com/example/toybatch/jpa/repository/RepositoryWriterConfig.kt) | writer 빈 (RepositoryItemWriter, saveAllAndFlush 람다) |
| [RepositoryJobTest.kt](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |

## 한 줄 요약

**된다. `RepositoryItemReader` / `RepositoryItemWriter` 가 있다.**
하지만 `RepositoryItemReader` 는 **offset 페이징**이고, `RepositoryItemWriter` 는 **flush 를 안 한다**. 이 두 가지 때문에 함정이 생긴다.
권장하는 조합은 **reader 는 repository 메서드를 람다로 부르는 키 기반 reader, writer 는 `saveAllAndFlush` 를 부르는 람다 writer** 다.

## 세 가지 방법

```kotlin
interface JpaOrderRepository : JpaRepository<JpaOrder, Long> {
    fun findByStatus(status: String, pageable: Pageable): Page<JpaOrder>                              // RepositoryItemReader 용
    fun findByStatusAndIdGreaterThanOrderByIdAsc(status: String, id: Long, limit: Limit): List<JpaOrder> // 키 기반 reader 용
}
```

### 1. `RepositoryItemReader` (Spring Batch 제공)

```kotlin
RepositoryItemReaderBuilder<JpaOrder>()
    .name("readyOrdersRepositoryReader")
    .repository(orderRepository)
    .methodName("findByStatus")            // ❗ 메서드 이름을 문자열로 → 오타는 실행해야 안다
    .arguments(listOf("READY"))            //    마지막 인자로 Pageable 이 자동으로 붙는다
    .sorts(mapOf("id" to Sort.Direction.ASC))
    .pageSize(10)
    .build()
```

- 내부적으로 `PageRequest.of(page, pageSize, sort)` → **page 번호 = OFFSET** 방식. `JpaPagingItemReader` 와 같은 성질이다.
- 재시작 시 context 에는 `read.count` 만 남는다 (개수 기반).
- 조회가 **청크 트랜잭션 안에서** 일어난다 → 읽은 엔티티는 청크 트랜잭션의 EntityManager 소속. (`JpaPagingItemReader`/`JpaCursorItemReader` 는 자기 전용 EM)

### 2. `KeysetRepositoryItemReader` (직접 만든 것, 30줄)

```kotlin
KeysetRepositoryItemReader("readyOrdersKeysetReader", pageSize = 10, keyOf = { it.id }) { lastId, limit ->
    orderRepository.findByStatusAndIdGreaterThanOrderByIdAsc("READY", lastId, limit)
}
```

- repository 메서드를 **람다로 직접** 호출 → 컴파일 타임에 검사된다.
- "마지막 키 다음 N개" 를 조회 → **키 기반.** 마지막으로 넘긴 item 의 키를 `{name}.last.key` 로 저장해서 재시작한다.
- `Limit` 파라미터는 Spring Data 3.2+ 에서 지원한다.

### 3. writer: `RepositoryItemWriter` vs `saveAllAndFlush` 람다

```kotlin
RepositoryItemWriterBuilder<JpaSettlement>().repository(settlementRepository).build()   // repository.saveAll(chunk)
ItemWriter<JpaSettlement> { chunk -> settlementRepository.saveAllAndFlush(chunk.items) } // 권장
```

둘 다 청크 트랜잭션에 참여한다. 차이는 **SQL 이 언제 나가는가**다.
`saveAll` 은 영속성 컨텍스트에 올려두기만 해서 INSERT 가 **커밋 직전**에 나간다. `saveAllAndFlush` 는 `write()` 안에서 나간다.

## 재현 결과

공통: `jpa_order` 에 id 1~100 (`amount = id*100`, `status = READY`), chunk = pageSize = 10.

| # | 잡 | 구성 | 결과 | 테스트 |
|---|---|---|---|---|
| 1 | [`repoPagingJob`](../src/main/kotlin/com/example/toybatch/jpa/repository/RepositoryJobConfig.kt#L55) | `RepositoryItemReader` → `RepositoryItemWriter` | ✅ 100건 | [테스트](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L48 "RepositoryItemReader 와 RepositoryItemWriter 로 repository 를 그대로 쓸 수 있다") |
| 2 | [`repoPagingJob`](../src/main/kotlin/com/example/toybatch/jpa/repository/RepositoryJobConfig.kt#L55) 재시작 | 57 에서 실패 → 1~5 삭제 → 재시작 | ❌ **51~55 누락** (offset) | [테스트](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L56 "RepositoryItemReader 는 offset 방식이라 재시작 사이에 앞쪽 행이 지워지면 누락된다") |
| 3 | [`repoKeysetJob`](../src/main/kotlin/com/example/toybatch/jpa/repository/RepositoryJobConfig.kt#L62) 재시작 | 키 기반 reader, 같은 상황 | ✅ `id > 50` 부터, 100건 | [테스트](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L69 "키 기반 reader 는 마지막 키를 저장하므로 앞쪽 행이 지워져도 정확히 이어간다") |
| 4 | [`repoStatusJob`](../src/main/kotlin/com/example/toybatch/jpa/repository/RepositoryJobConfig.kt#L66) | `RepositoryItemReader(findByStatus)` + DONE 저장 | ❌ **50건 누락** | [테스트](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L84 "RepositoryItemReader 로 status 조건을 읽으면서 status 를 바꾸면 절반이 누락된다") |
| 5 | [`repoKeysetStatusJob`](../src/main/kotlin/com/example/toybatch/jpa/repository/RepositoryJobConfig.kt#L70) | 키 기반 reader(`status='READY' AND id > ?`) + DONE 저장 | ✅ 100건 | [테스트](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L92 "키 기반 reader 는 status 를 바꿔서 목록이 당겨져도 전부 처리한다") |
| 6 | [`repoDirtyJob`](../src/main/kotlin/com/example/toybatch/jpa/repository/RepositoryJobConfig.kt#L74) | processor 에서 `order.settle()`, writer 는 정산만 | ⚠️ DONE 100건 (청크 트랜잭션의 dirty checking) | [테스트](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L102 "RepositoryItemReader 는 청크 트랜잭션 안에서 읽으므로 processor 에서 바꾼 엔티티가 dirty checking 으로 저장된다") |
| 7 | [`repoDirtySkipJob`](../src/main/kotlin/com/example/toybatch/jpa/repository/RepositoryJobConfig.kt#L79) | 6 + 정산 13 쓰기 실패 → skip | ❌ **정산 11~20(13 제외)은 있는데 주문 11~20 은 READY** | [테스트](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L110 "하지만 청크가 롤백되고 scan 으로 넘어가면 엔티티 변경은 사라지고 정산만 다시 쓰인다") |
| 8 | [`repoWriterSkipJob`](../src/main/kotlin/com/example/toybatch/jpa/repository/RepositoryJobConfig.kt#L88) | `RepositoryItemWriter` + 정산 13 실패 → skip 기대 | ❌ **skip 안 됨, 잡 상태 UNKNOWN, 재시작 거부** | [테스트](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L124 "RepositoryItemWriter 는 flush 를 안 해서 커밋 때 터지고, skip 이 동작하지 않고 잡이 UNKNOWN 이 된다") |
| 9 | [`repoFlushWriterSkipJob`](../src/main/kotlin/com/example/toybatch/jpa/repository/RepositoryJobConfig.kt#L96) | `saveAllAndFlush` writer + skip | ✅ 13 만 skip, 99건 | [테스트](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L144 "saveAllAndFlush 로 write 안에서 SQL 을 내보내면 skip 이 정상 동작한다") |

### 2 vs 3 — offset 이냐 키냐

01-2, 03 에서 본 것과 같다. `RepositoryItemReader` 는 재시작 때 `page = read.count / pageSize` 로 이어가기 때문에 앞쪽 행이 지워지면 밀린다.
키 기반 reader 는 `last.key=50` 을 저장해 두고 `id > 50` 부터 조회한다.

### 4 vs 5 — status 플래그

```
RepositoryItemReader : findByStatus("READY", PageRequest.of(1, 10)) → 이미 1~10 이 DONE 이라 21~30 을 줌 ❗
키 기반 reader        : findByStatusAndIdGreaterThan("READY", 10, Limit.of(10)) → 11~20  ✅
```

### 6, 7 — dirty checking 은 되지만 믿으면 안 된다

`RepositoryItemReader` 와 키 기반 reader 는 청크 트랜잭션 안에서 조회한다. 그래서 processor 에서 바꾼 엔티티가 **커밋 때 dirty checking 으로 저장된다** (케이스 6).
03 의 `JpaCursorItemReader`(안 저장됨), `JpaPagingItemReader`(reader 트랜잭션으로 저장됨)와도 또 다르다.

그런데 청크가 롤백되고 scan 으로 넘어가면:

```
청크 [11..20]
  processor : 주문 11~20 settle()   (청크 트랜잭션 EM 에 변경이 쌓임)
  write     : 정산 13 CHECK 위반 → 청크 롤백 → 주문 변경도 같이 롤백, 엔티티는 detach
  scan      : writer 만 한 건씩 다시 호출 → 정산 11, 12, 14 ~ 20 저장 (13 skip)
              processor 는 다시 안 불림 → 주문 변경을 다시 적용할 기회가 없음

결과: 정산은 있는데 주문 11~20 은 READY  ❗ → 다음에 또 정산 대상으로 잡힌다 (중복 정산)
```

03 의 `JpaPagingItemReader` 케이스(정산 없음 + DONE)와 **반대 방향**의 불일치다. 결론은 같다.
→ **저장할 건 writer 에서 명시적으로 저장한다.** (03 케이스 7 의 [`SettleAndMarkDoneWriter`](../src/main/kotlin/com/example/toybatch/jpa/basic/SettleAndMarkDoneWriter.kt#L14) 처럼)

### 8 — `RepositoryItemWriter` + skip = 커밋 때 터져서 UNKNOWN

```
청크 [11..20]
  write()  : settlementRepository.saveAll(chunk) → 영속성 컨텍스트에만 올림. 예외 없음 → "성공"
  (write 가 성공했으니 skip/scan 대상 아님)
  커밋     : flush → INSERT 13 → CHECK 위반 → 커밋 실패
  → 스텝 메타데이터 버전까지 어긋나서 OptimisticLockingFailureException
  → 잡 상태 UNKNOWN
```

- skip 은 read/process/write **안에서** 난 예외만 다룬다. 커밋 때 난 예외는 대상이 아니다.
- **UNKNOWN** 은 "어디까지 커밋됐는지 프레임워크도 모른다" 는 뜻이라, 같은 파라미터로 다시 돌리면 `JobRestartException` 으로 **거부된다.** 사람이 상태를 확인하고 메타데이터를 고쳐야 한다.
- 해결: SQL 을 `write()` 안에서 내보낸다 → `saveAllAndFlush` (케이스 9). `JpaItemWriter` 는 원래 `write()` 끝에서 flush 하므로 이 문제가 없다.

## 어떤 걸 쓸까

| 상황 | reader | writer |
|---|---|---|
| 조회 대상이 안 바뀜, 간단히 | `RepositoryItemReader` 도 OK | `saveAllAndFlush` 람다 또는 `JpaItemWriter` |
| 조회 조건 컬럼을 같은 잡에서 바꿈 (status 플래그) | **키 기반 reader** | 〃 |
| 재시작 사이에 데이터가 바뀔 수 있음 | **키 기반 reader** | 〃 |
| skip/retry 를 씀 | 〃 | **write 안에서 flush 되는 것** (`RepositoryItemWriter` 기본값은 ❌) |

## 체크리스트

- [ ] `RepositoryItemReader` 에 **유일한 정렬 키**(`sorts` 에 PK)를 줬는가?
- [ ] 조회 조건 컬럼을 같은 잡에서 바꾸고 있다면 **키 기반**으로 읽는가?
- [ ] writer 가 **`write()` 안에서 flush** 하는가? (`RepositoryItemWriter` 는 안 한다)
- [ ] processor 에서 바꾼 엔티티를 **writer 가 명시적으로 저장**하는가?
- [ ] 키 기반 reader 의 `pageSize` 가 chunk 크기와 같은가? (다르면 이전 트랜잭션에서 조회한 detach 된 엔티티를 받게 된다)

## 테스트로 따라가기

테스트 이름이 곧 시나리오다. 위 표·설명과 같은 순서로 보면 된다.

**[RepositoryJobTest](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt)**

- [RepositoryItemReader 와 RepositoryItemWriter 로 repository 를 그대로 쓸 수 있다](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L48)
- [RepositoryItemReader 는 offset 방식이라 재시작 사이에 앞쪽 행이 지워지면 누락된다](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L56)
- [키 기반 reader 는 마지막 키를 저장하므로 앞쪽 행이 지워져도 정확히 이어간다](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L69)
- [RepositoryItemReader 로 status 조건을 읽으면서 status 를 바꾸면 절반이 누락된다](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L84)
- [키 기반 reader 는 status 를 바꿔서 목록이 당겨져도 전부 처리한다](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L92)
- [RepositoryItemReader 는 청크 트랜잭션 안에서 읽으므로 processor 에서 바꾼 엔티티가 dirty checking 으로 저장된다](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L102)
- [하지만 청크가 롤백되고 scan 으로 넘어가면 엔티티 변경은 사라지고 정산만 다시 쓰인다](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L110)
- [RepositoryItemWriter 는 flush 를 안 해서 커밋 때 터지고, skip 이 동작하지 않고 잡이 UNKNOWN 이 된다](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L124)
- [saveAllAndFlush 로 write 안에서 SQL 을 내보내면 skip 이 정상 동작한다](../src/test/kotlin/com/example/toybatch/jpa/repository/RepositoryJobTest.kt#L144)
