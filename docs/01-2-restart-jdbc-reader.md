# 01-2. DB reader 에서 재시작이 안전한가?

선행: [01-1. 재시작과 ExecutionContext](01-1-restart-execution-context.md)
코드: 아래 [코드 지도](#코드-지도) 참고. 문서 안의 잡·빈·클래스 이름을 누르면 정의된 줄로 이동한다.
실행: `./gradlew test --tests '*RestartJdbcJobTest'`


## 코드 지도

| 파일 | 역할 |
|---|---|
| [RestartJdbcJobConfig.kt](../src/main/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobConfig.kt) | 잡 정의 6개 |
| [RestartJdbcReaderConfig.kt](../src/main/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcReaderConfig.kt) | reader 빈: 개수 기반 cursor, 키 기반 paging, 상태 플래그용 reader |
| [OffsetPagingOrderReader.kt](../src/main/kotlin/com/example/toybatch/restart/jdbc/OffsetPagingOrderReader.kt) | JpaPagingItemReader 의 LIMIT/OFFSET 동작을 JDBC 로 흉내 |
| [OrderWriter.kt](../src/main/kotlin/com/example/toybatch/restart/jdbc/OrderWriter.kt) | 처리 기록 insert (+ status=DONE) |
| [OrderRow.kt](../src/main/kotlin/com/example/toybatch/restart/jdbc/OrderRow.kt) | rj_order 행 |
| [schema.sql](../src/main/resources/schema.sql) | 테이블 (rj_order, rj_result) |
| [RestartJdbcJobTest.kt](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt) | 테스트 (아래 [테스트로 따라가기](#테스트로-따라가기)) |

## 한 줄 요약

**ExecutionContext 에 남는 건 "몇 번째까지 읽었는지"라는 위치뿐이다.**
파일은 내용이 안 바뀌니 "50번째"가 항상 같은 줄이지만, 테이블은 1차·2차 실행 사이에 데이터가 바뀔 수 있어서
**"50번째"가 다른 행을 가리킬 수 있다.**
그래서 DB 배치에서는 먼저 **"재시작했을 때 내 쿼리가 같은 결과를 같은 순서로 주는가?"** 를 따져야 한다.

## reader 별로 context 에 저장하는 것

| Reader | 저장 방식 | context 에 남는 값 | 재시작할 때 |
|---|---|---|---|
| `JdbcCursorItemReader` | 개수 기반 | `{name}.read.count` = 50 | 쿼리를 다시 실행하고 앞의 50행을 건너뜀 |
| `JpaCursorItemReader` | 개수 기반 | `{name}.read.count` | 위와 같음 |
| `JpaPagingItemReader` / `RepositoryItemReader` | 개수 기반 (offset) | `{name}.read.count` | `OFFSET 50` 부터 조회 |
| `JdbcPagingItemReader` | **키 기반** | `{name}.read.count` + **`{name}.start.after`** (마지막으로 읽은 정렬 키 값, 예: `id=1234`) | `WHERE id > 1234 ORDER BY id` 로 조회 |

- **개수 기반:** 그 사이 앞쪽 행이 추가·삭제되면 위치가 밀려서 **일부를 빼먹거나 중복 처리**한다.
  `ORDER BY` 가 없으면 DB 가 순서를 보장하지 않으니 더 위험하다.
- **키 기반:** "id 1234 다음부터"라서 데이터가 조금 바뀌어도 정확하게 이어간다.
  대신 **정렬 키가 유일해야 한다** → `sortKeys` 에 PK 를 넣는다 (복합 정렬이면 마지막에 PK 추가).

## 안전하게 만드는 두 가지 방법

### 방법 1. 조회 대상을 고정하기 → context 를 믿는다

JobParameter 로 범위를 박아서, 1차·2차 실행의 **쿼리 결과가 같아지게** 만든다.

```sql
SELECT * FROM orders
 WHERE created_at >= :startAt AND created_at < :endAt   -- JobParameter 로 고정
 ORDER BY id
```

- 재시작은 **같은 파라미터**로만 가능하므로 2차 실행도 같은 범위를 조회한다.
- 그 사이 새 주문이 들어와도 범위 밖이라 결과가 안 바뀐다 → 파일처럼 정적인 데이터가 된다.
- 그래서 context 의 위치를 믿을 수 있다 (`saveState(true)`, 기본값).
- 범위 안의 행이 수정·삭제될 수 있다면 여전히 흔들리므로, **`JdbcPagingItemReader` + PK 정렬(키 기반)** 을 쓰는 게 가장 안전하다.

### 방법 2. 처리 상태 플래그 + `saveState(false)` → 테이블이 기억한다

처리한 행을 표시해서 **다음 조회에서 빠지게** 만든다.

```sql
-- reader
SELECT * FROM orders WHERE status = 'READY' ORDER BY id
-- writer (비즈니스 처리와 같은 청크 트랜잭션)
UPDATE orders SET status = 'DONE' WHERE id = ?
```

- 재시작하면 이미 커밋된 건은 `DONE` 이라 조회되지 않고 남은 것만 나온다.
- "어디까지 했는지"를 **비즈니스 테이블의 status 컬럼이 기억**하므로 context 가 필요 없다.
- **반드시 `saveState(false)`.** 켜 두면 아래 함정에 빠진다.

#### 함정: status 플래그인데 saveState 를 켜 두면

```
1차: 1~50 처리(DONE) 후 실패. context: read.count=50
2차: READY 로 조회되는 건 51~100 (50건)
     reader: "read.count=50 이니까 앞의 50개 건너뛰자"
     → 51~100 을 전부 건너뜀 → 아무것도 처리 안 하고 COMPLETED  ❗
```

에러도 안 나고 COMPLETED 로 끝나서 **발견하기 어렵다.**

## 한눈에 비교

| | 어디까지 했는지를 누가 기억하나 | saveState | 추천 reader |
|---|---|---|---|
| 방법 1. 범위 고정 | ExecutionContext (Spring Batch) | `true` (기본) | `JdbcPagingItemReader` + PK 정렬 |
| 방법 2. 상태 플래그 | 비즈니스 테이블 status 컬럼 | **`false`** | 아무거나 (단, 아래 페이징 주의) |
| ❌ 둘 다 아님 | 개수 기반 위치를 믿음 → 엉뚱한 곳에서 재시작 | | |

## 덤: 재시작이 아니어도 터지는 오프셋 페이징 버그

방법 2 를 **오프셋 기반 페이징 reader**(`JpaPagingItemReader` 등)로 구현하면, 재시작과 상관없이 **정상 실행 중에도** 누락이 생긴다.

```
page 0: OFFSET 0  LIMIT 10 → 1~10 읽고 DONE 처리
page 1: OFFSET 10 LIMIT 10 → 그런데 1~10 이 빠져서 READY 목록이 당겨짐
        → 21~30 을 읽음. 11~20 은 건너뜀 ❗
```

해결책:
- 키 기반인 `JdbcPagingItemReader` 를 쓴다 (`id > 마지막키` 라서 목록이 당겨져도 상관없음)
- 또는 cursor 기반 reader 를 쓴다 (쿼리를 한 번만 실행)
- 또는 JPA 페이징을 꼭 써야 하면 항상 page 0 만 읽도록 `getPage()` 를 0 으로 고정하는 방식으로 커스텀

## 재현 결과

전부 [`RestartJdbcJobTest`](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L28) 에 테스트로 있다. 공통 준비:

- `rj_order` 에 id 1~100 (`order_date=2026-09-25`, `status=READY`), chunk=10 (페이징 reader 는 pageSize=10)
- 재시작 케이스: 1차에서 id 57 에서 일부러 실패 → **1~50 커밋**, 51~60 롤백 → **(데이터 변경)** → 같은 파라미터로 2차 실행(재시작)

| # | 잡 | reader / 방식 | 1차·2차 사이 변경 | 결과 | 테스트 |
|---|---|---|---|---|---|
| 1 | [`rjCursorJob`](../src/main/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobConfig.kt#L41) | `JdbcCursorItemReader` (개수), 범위 고정 | 범위 **밖**에 101~120 추가 | ✅ 51~100 처리, 총 100건 | [테스트](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L51 "개수 기반 cursor - 범위 밖에 데이터가 추가되면 재시작해도 안전하다") |
| 2 | [`rjCursorJob`](../src/main/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobConfig.kt#L41) | 〃 | 범위 **안** 처리 끝난 1~5 삭제 | ❌ **51~55 누락**, COMPLETED | [테스트](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L66 "개수 기반 cursor - 범위 안의 처리된 행이 삭제되면 재시작 때 누락된다") |
| 3 | [`rjPagingJob`](../src/main/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobConfig.kt#L44) | `JdbcPagingItemReader` (키), 범위 고정 | 범위 안 1~5 삭제 | ✅ 51~100 처리, 총 100건 | [테스트](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L85 "키 기반 paging - 범위 안의 처리된 행이 삭제되어도 재시작은 정확하다") |
| 4 | [`rjStatusStateJob`](../src/main/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobConfig.kt#L49) | cursor + status 플래그, `saveState=true` | (writer 가 DONE 처리) | ❌ **0건 처리**, COMPLETED | [테스트](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L103 "상태 플래그인데 saveState가 true면 재시작 때 전부 건너뛰고 COMPLETED 된다") |
| 5 | [`rjStatusNoStateJob`](../src/main/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobConfig.kt#L52) | cursor + status 플래그, `saveState=false` | 〃 | ✅ 51~100 처리 | [테스트](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L119 "상태 플래그 + saveState false면 재시작 때 남은 것만 처리한다") |
| 6 | [`rjOffsetStatusJob`](../src/main/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobConfig.kt#L55) | 오프셋 페이징 + status 플래그 | 재시작 없음 (실패 없이 1회) | ❌ **50건 누락**, COMPLETED | [테스트](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L134 "오프셋 페이징 + 상태 플래그는 재시작 없이 정상 실행해도 절반이 누락된다") |
| 7 | [`rjKeyStatusJob`](../src/main/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobConfig.kt#L58) | `JdbcPagingItemReader` + status 플래그 | 재시작 없음 | ✅ 100건 | [테스트](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L147 "키 기반 페이징 + 상태 플래그는 목록이 당겨져도 전부 처리한다") |

❌ 세 개가 **전부 에러 없이 COMPLETED** 로 끝난다는 게 핵심이다. 모니터링에 안 잡힌다.

### 1 vs 2 — 개수 기반은 "범위 안" 데이터가 바뀌면 틀어진다

```
1차 : SELECT ... WHERE order_date='2026-09-25' ORDER BY id  → [1..100]
      1~50 커밋 후 실패.  context = {rangeCursorReader.read.count=50}

(케이스 1) 다음 날짜 주문 101~120 추가 → 범위 밖이라 조회 결과는 그대로 [1..100]
2차 : 같은 쿼리 → [1..100], 앞의 50개 건너뜀 → 51 부터  ✅

(케이스 2) 주문 1~5 삭제 → 조회 결과가 [6..100] (95건) 으로 당겨짐
2차 : 같은 쿼리 → [6..100], 앞의 50개 건너뜀 → 6~55 를 건너뛰고 56 부터  ❌
      ❗ 처리 안 된 id (5건) = [51, 52, 53, 54, 55]
```

범위 고정(방법 1)은 "범위 밖" 변화만 막아준다. 범위 안의 행이 삭제·수정될 수 있으면 개수 기반 reader 로는 부족하다.

### 3 — 키 기반은 같은 삭제에도 정확하다

```
1차 context = {rangePagingReader.read.count=50, rangePagingReader.start.after={id=50}}
2차 : SELECT ... WHERE order_date='2026-09-25' AND id > 50 ORDER BY id LIMIT 10  → 51 부터  ✅
```

"몇 번째"가 아니라 "어떤 키 다음"을 기억하므로 앞쪽 행이 사라져도 상관없다.

### 4 vs 5 — 상태 플래그인데 saveState 를 켜 두면

```
1차 : READY [1..100] 조회. 1~50 DONE 커밋 후 실패.  context = {read.count=50}

(케이스 4, saveState=true)
2차 : READY 조회 → [51..100] (50건). "read.count=50 이니까 앞의 50개 건너뛰자"
      → 50건 전부 건너뜀 → writeCount=0, COMPLETED  ❌
      ❗ COMPLETED 인데 READY 로 남은 주문 = 50건

(케이스 5, saveState=false)
2차 : READY 조회 → [51..100], 건너뛰기 없음 → 51~100 처리  ✅
```

### 6 vs 7 — 오프셋 페이징은 재시작이 아니어도 누락된다

```
(케이스 6) 실제 로그
[offset paging] page=0 OFFSET 0  → ids [1..10]    → DONE 처리
[offset paging] page=1 OFFSET 10 → ids [21..30]   ← READY 목록이 [11..100] 으로 당겨져서 11~20 을 건너뜀
[offset paging] page=2 OFFSET 20 → ids [41..50]
[offset paging] page=3 OFFSET 30 → ids [61..70]
[offset paging] page=4 OFFSET 40 → ids [81..90]
[offset paging] page=5 OFFSET 50 → ids []         → 끝
❗ 처리 안 된 id (50건) = [11..20, 31..40, 51..60, 71..80, 91..100]

(케이스 7) JdbcPagingItemReader: WHERE status='READY' AND id > {마지막 id} → 목록이 당겨져도 다음 키부터  ✅
```

> 여기서는 [`OffsetPagingOrderReader`](../src/main/kotlin/com/example/toybatch/restart/jdbc/OffsetPagingOrderReader.kt#L16) 로 `JpaPagingItemReader` 의 `LIMIT/OFFSET` 동작을 JDBC 로 흉내냈다.
> 진짜 `JpaPagingItemReader` 로 같은 결과가 나오는 건 [03-1. JPA reader / writer](03-1-jpa-reader-writer.md) 의 케이스 2 에서 볼 수 있다.

## 체크리스트

DB reader 로 재시작 가능한 잡을 만들 때:

- [ ] 쿼리에 **유일한** `ORDER BY` 가 있는가? (PK 포함)
- [ ] 재시작 사이에 조회 결과가 바뀔 수 있는가?
  - 안 바뀜 (범위 고정) → `saveState(true)`, 가능하면 `JdbcPagingItemReader`
  - 처리하면 빠짐 (status 플래그) → **`saveState(false)`**
  - 그냥 계속 바뀜 → 위 둘 중 하나로 설계를 바꾼다
- [ ] writer 가 reader 와 같은 DB·같은 트랜잭션인가? (아니면 멱등성 고민 필요)
- [ ] 멀티스레드 스텝인가? → 순서가 보장되지 않으므로 `saveState(false)` + 방법 2

## 테스트로 따라가기

테스트 이름이 곧 시나리오다. 위 표·설명과 같은 순서로 보면 된다.

**[RestartJdbcJobTest](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt)**

- [개수 기반 cursor - 범위 밖에 데이터가 추가되면 재시작해도 안전하다](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L51)
- [개수 기반 cursor - 범위 안의 처리된 행이 삭제되면 재시작 때 누락된다](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L66)
- [키 기반 paging - 범위 안의 처리된 행이 삭제되어도 재시작은 정확하다](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L85)
- [상태 플래그인데 saveState가 true면 재시작 때 전부 건너뛰고 COMPLETED 된다](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L103)
- [상태 플래그 + saveState false면 재시작 때 남은 것만 처리한다](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L119)
- [오프셋 페이징 + 상태 플래그는 재시작 없이 정상 실행해도 절반이 누락된다](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L134)
- [키 기반 페이징 + 상태 플래그는 목록이 당겨져도 전부 처리한다](../src/test/kotlin/com/example/toybatch/restart/jdbc/RestartJdbcJobTest.kt#L147)
