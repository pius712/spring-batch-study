-- restart 예제의 writer 결과가 쌓이는 테이블.
-- 중복 적재를 눈으로 보려고 일부러 PK/unique 를 걸지 않았다.
CREATE TABLE IF NOT EXISTS restart_demo_result (
    job_name   VARCHAR(100) NOT NULL,
    item_value INT          NOT NULL
);

-- restart.jdbc 예제(01-2): reader 가 읽는 원본 테이블.
-- order_date = "범위 고정" 조건, status = "처리 상태 플래그" 조건으로 쓴다.
CREATE TABLE IF NOT EXISTS rj_order (
    id         BIGINT      PRIMARY KEY,
    order_date VARCHAR(10) NOT NULL,
    status     VARCHAR(10) NOT NULL
);

-- restart.jdbc 예제의 writer 결과. 누락/중복을 보려고 PK 없음.
CREATE TABLE IF NOT EXISTS rj_result (
    job_name VARCHAR(100) NOT NULL,
    order_id BIGINT       NOT NULL
);

-- faulttolerance 예제(02): writer 결과. 재시도 때 중복 insert 를 보려고 PK 없음.
CREATE TABLE IF NOT EXISTS ft_result (
    job_name   VARCHAR(100) NOT NULL,
    item_value INT          NOT NULL
);

-- faulttolerance 예제: SkipListener 가 청크 트랜잭션 안에서 남기는 스킵 기록
CREATE TABLE IF NOT EXISTS ft_skip_log (
    job_name   VARCHAR(100) NOT NULL,
    stage      VARCHAR(10)  NOT NULL,
    item_value INT          NOT NULL
);

-- jpa 예제(03): 주문. processor 가 fee 를 계산하고 status 를 DONE 으로 바꾼다
CREATE TABLE IF NOT EXISTS jpa_order (
    id     BIGINT      PRIMARY KEY,
    amount INT         NOT NULL,
    status VARCHAR(10) NOT NULL,
    fee    INT
);

-- jpa 예제: 정산 결과. fee 는 음수가 될 수 없다 (잘못된 데이터 → 쓰기 실패를 만들기 위한 제약)
CREATE TABLE IF NOT EXISTS jpa_settlement (
    order_id BIGINT PRIMARY KEY,
    fee      INT    NOT NULL CHECK (fee >= 0)
);
