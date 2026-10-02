# 2026-10-02 운영 규모 데이터 첫 측정

## 요약

회원 100만·주문 500만·15,000석 회차로 데이터를 바꾼 뒤 첫 측정이다. 예열(10 u/s)부터 사용자 95% 이상이 실패했다.
주문 생성이 "이 회원에게 진행 중 주문이 있나"를 확인할 때 주문 500만 행을 처음부터 끝까지 읽는다. 한 번에 10.5초가 걸린다.
DB 연결 10개가 이 쿼리에 모두 묶이고, 연결이 필요한 다른 요청(공연 요약, 좌석 선택)이 30초 기다리다 실패한다.
10월 1일에 "영향이 작다"고 적은 P-001이 데이터가 커지자 주원인이 됐다. 사다리는 시작하지 못했고, P-001과 새로 찾은 P-007의
결정을 기다린다.

## 목적

`core-capacity.md`의 가정 규모(회원 100만, 주문 500만, 15,000석 회차)로 03 고정 조건 사다리를 잰다.

## 조건

| 항목 | 값 |
| --- | --- |
| Core commit | `3ebaa4d5`(ticket `feat/order`, 미커밋 변경 없음) |
| 실행 방식 | `reset-local.ps1`, `java -jar`(JDK 25), p6spy 꺼짐. 09:56 기동 |
| 연결 풀 | Hikari 10(기본값), Tomcat 200 |
| DB | 로컬 H2 파일. `ORDERS` 5,000,000행(CANCELED 164만, CONFIRMED 31만, EXPIRED 305만, PENDING 56). `ORDERS`에 PK와 `order_key`·`hold_key` 유니크 말고 인덱스 없음 |
| 데이터 | 회원 1,000,000, 대형 회차 30개(`920000001~`, 회차당 15,000석) |
| 부하 발생기 | 같은 PC의 Gatling(logback WARN) |

## 실행 표

결과 폴더는 `distributed-results-join/capacity/<시각>-<라벨>-10ups-30s`이다. Core 쪽 `-1`은 히스토그램의 가장 큰 구간을 넘었다는 뜻이다.

| 시각 | 라벨 | 부하 | 회차 | 성공/시작 | Gatling p95/p99 체류 | Core 쪽 p95 | 연결·스레드 최대 | 유효 | 판정 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10:08 | warmup | 10 × 30초 | 920000001 | 7/300 | 90,504 / 95,784 | 요약 -1, 좌석 2000, 선택 -1, 주문 -1, 조회 -1 | 10/10 대기 188, 200/200 | 유효 | FAIL |
| 10:10 | repro | 10 × 30초 | 920000002 | 16/300 | 60,551 / 67,888 | 요약 -1, 좌석 1432, 선택 -1, 주문 -1, 조회 447 | 10/10 대기 192, 200/200 | 유효 | FAIL |
| 10:12 | diag(덤프 3) | 10 × 30초 | 920000003 | 20/300 | 60,273 / 63,245 | 요약 -1, 좌석 2863, 선택 -1, 주문 -1, 조회 626 | 10/10 대기 189, 200/200 | 유효 | FAIL(판정에 안 씀) |

- 예열이 FAIL이라 사다리(5 u/s~)는 재지 않았다. 아래 계산으로 5 u/s도 FAIL이 확실하다고 봤다.
- PC CPU(`system_cpu`) 최대는 0.97·0.82·0.96이다. PC CPU가 찼으므로 Core만 돌 때보다 느리게 나왔을 수 있다.
  다만 아래 쿼리 하나의 비용(10.5초)은 한가할 때 잰 값이라 이 영향을 받지 않는다.

## 발견한 문제

### P-001 추가 근거: 주문 500만 건에서 존재 확인 한 번이 10.5초

- **증상:** 10 u/s에서 사용자 93~98%가 실패했다. 실패의 대부분은 연결 대기 30초 초과다
  (`Connection is not available, request timed out after 30000ms (total=10, active=10, idle=0, waiting=190)`, Core 로그 859건).
- **근거:**
  - 전후 지표(warmup): `existsByMemberIdAndPerformanceIdAndStatus` 27번, 평균 41.3초. 연결을 한 번 쥔 평균 시간 0.83초,
    연결을 얻기까지 평균 8.5초. 연결을 기다리다 끝난 `findByPerformanceIdAndSeatId`(좌석 선택)는 평균 30.0초로, 시간 초과 값과 같다.
  - 스레드 덤프(diag, 3초 간격 3장): 요청 스레드 중 DB 작업 중 10·10·10, 연결 대기 5·42·80. DB 작업 중인 10개는 모두
    `BookingAvailabilityChecker.ensureNoPendingOrder(BookingAvailabilityChecker.java:48)` → `existsByMemberIdAndPerformanceIdAndStatus`였다.
    연결 대기 쪽 대표 스택은 `PerformanceController.getPerformanceSummary(PerformanceController.java:30)`다. 나머지는 쉬는 스레드(`TaskQueue.poll`)다.
  - 한가한 Core에서 H2 `EXPLAIN ANALYZE`(진행 중 주문이 없는 회원): `ORDERS.tableScan`, `scanCount` 5,000,057, **10,526ms**.
    진행 중 주문이 있는 회원은 그 행을 만나면 멈춘다(member 1은 44행, 18ms). 처음 예매하는 사용자는 늘 끝까지 읽는다.
- **실행 계획을 다시 보는 법**(`gatling-test`에서 PowerShell, Core가 떠 있어도 `AUTO_SERVER=TRUE`로 붙는다):

  ```powershell
  $h2 = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.h2database\h2\2.4.240\686180ad33981ad943fdc0ab381e619b2c2fdfe5\h2-2.4.240.jar"
  java -cp $h2 org.h2.tools.Shell -url "jdbc:h2:file:~/ticket-local;MODE=Oracle;AUTO_SERVER=TRUE" -user sa -password '""' -sql "EXPLAIN ANALYZE SELECT 1 FROM ORDERS WHERE MEMBER_ID=987654321 AND PERFORMANCE_ID=920000004 AND STATUS='PENDING' FETCH FIRST 1 ROWS ONLY"
  ```

  - 없는 회원 번호를 쓴다. 진행 중 주문이 있는 회원은 그 행에서 멈춰 느린 경우가 가려진다.
  - P-007은 `-sql`을 `EXPLAIN ANALYZE SELECT ID FROM ORDERS WHERE STATUS='PENDING' AND EXPIRES_AT<=CURRENT_TIMESTAMP AND ID>-1 ORDER BY ID FETCH FIRST 100 ROWS ONLY`로 바꾼다.
  - 고친 뒤에는 `tableScan`·`PRIMARY_KEY` 자리에 새 인덱스 이름이 나오고 `scanCount`가 작아야 한다.
- **계산:** 연결 10개 ÷ 10.5초 ≈ 초당 주문 1건이 천장이다. 03 사용자는 한 명이 주문을 한 번 하므로 5 u/s도 버틸 수 없다.
- **10월 1일 판단과 다른 점:** 1.35만 행일 때는 한 번 9~34ms였다. 비용이 행 수에 비례해 370배 커졌다.
  "언젠가 드러난다"고 적은 것이 운영 가정 규모에서 바로 드러났다.
- **Oracle:** Oracle migration에도 `ORDERS` 보조 인덱스가 없다. Oracle의 전체 스캔이 H2보다 빠를 수는 있지만 500만 행을 매번 읽는 것은 같다(미확인).

### P-007

**주문 만료 worker가 5분마다 주문 전체를 PK 순서로 훑는다** (Core·DB)

- **증상:** warmup 실행 중 `findAllByStatusAndExpiresAtLessThanEqualAndIdGreaterThan` 1번이 65.1초 걸렸다.
- **근거:**
  - `OrderExpirationTrigger.run()`(`OrderExpirationTrigger.java:20`, 기본 5분 간격) → `OrderRepositoryAdapter.findExpirable`(`OrderRepositoryAdapter.java:66`).
  - 한가할 때 `EXPLAIN ANALYZE`: `PRIMARY_KEY_12: ID > -1`로 PK를 처음부터 읽으며 `STATUS`·`EXPIRES_AT`를 거른다. `scanCount` 5,000,057, **10,695ms**.
    PENDING은 56행뿐인데 500만 행을 읽는다.
- **영향:** 5분마다 연결 하나를 10초 이상 쥔다. 부하 중이면 연결 10개 중 하나가 빠지고, P-001이 고쳐진 뒤에도 남는다.
  만료 처리가 늦어지면 좌석이 그만큼 늦게 풀린다.

## 해결 선택지

### P-001

1. `ORDERS (member_id, performance_id, status)` 인덱스를 H2·Oracle migration에 둔다.
   - 가장 직접적이다. 기대: 존재 확인이 ms 단위로 떨어지고 연결 대기가 사라진다.
   - 주문 쓰기마다 인덱스 갱신이 붙는다. 주문은 회원당 몇 건이라 선택도가 높다.
2. Oracle은 "PENDING일 때만 값이 있는" 함수 기반 유니크 인덱스로 한다(`CASE WHEN status='PENDING' THEN member_id END, …`).
   - 인덱스가 PENDING 행만큼만 작고, "회원·회차당 진행 중 주문 하나"를 DB가 보장한다(동시 요청 두 개가 둘 다 통과하는 경우를 막는다).
   - H2는 함수 기반 인덱스가 없어 로컬과 운영의 인덱스가 달라진다. 쿼리도 같은 식을 써야 인덱스를 탄다.
3. 같은 확인을 Redis의 회원별 선점 정보로 한다.
   - DB 조회가 없어진다.
   - Redis와 DB가 어긋날 때의 규칙을 새로 정해야 한다.

### P-007

1. `ORDERS (status, expires_at)` 또는 `(status, id)` 인덱스. PENDING이 적으므로 몇 행만 읽는다. 쿼리가 `ORDER BY id`라 `(status, id)`면 정렬도 인덱스로 끝난다.
2. P-001의 1번 인덱스와 묶을 수 있는지 본다. 앞 열이 `member_id`라 이 쿼리에는 못 쓴다. 따로 필요하다.

## 다음에 할 일

- 사용자가 P-001·P-007을 정하고 고치면 `재측정`: `reset-local.ps1 -KeepData`(데이터 유지, 새 migration은 기동할 때 적용) → 예열(920000004) → 10 u/s부터.
  다음 회차는 `920000004`다. 30개 중 3개를 썼다.
- 존재 확인 쿼리의 `EXPLAIN ANALYZE`를 고친 뒤 다시 떠서 인덱스를 타는지 확인한다.
- 측정 도구: `run-step.ps1`은 Core 쪽 히스토그램이 가장 큰 구간을 넘으면 `-1`을 낸다. 표에서 읽기 어렵다(`>최대`로 적을지 다음 도구 정리 때 본다).
