# 2026-10-10 로컬 PostgreSQL 측정(노트북, master `c9dbb328`)

## 요약

PostgreSQL 전환이 master에 다시 머지된 뒤(`c9dbb328`), 다른 PC(노트북)에서 측정 환경을 처음부터 만들고 03 고정 조건을 쟀다.
회원 100만·주문 500만·15,000석 회차 조건에서 사다리 첫 단계인 초당 5명부터 FAIL이었고, 재현과 진단에서도 FAIL이었다.
원인은 10-08과 같은 P-001이다. 진행 중 주문 확인 쿼리가 주문 500만 행을 매번 병렬 전체 스캔하고, 덤프의 DB 실행 스레드 9~10개가
거의 모두 이 쿼리였다. 이 PC는 8코어라 그 스캔만으로 PC CPU가 100%에 붙었고, 연결 풀 10개가 이 쿼리에 묶여 다른 API도 연결을 기다렸다.
마지막 PASS가 없으므로 후보값 C는 없다. 사용자의 결정(인덱스 추가 또는 다른 선택)을 기다린다.

## 목적

PostgreSQL로 돌아온 master로 로컬 수용량 사다리를 다시 시작하고, 첫 FAIL의 원인을 근거와 함께 보고한다.

## 조건

| 항목 | 값 |
| --- | --- |
| Core | `c9dbb3280`, `sourceDirty=false`, JDK 25.0.1 `java -jar`, p6spy 꺼짐, worker 켜짐, 포트 8080, PID 36068 |
| DB | Docker `postgres:18-alpine`(PostgreSQL 18.6) `ticket-postgres`, DB `ticket`, `shared_buffers=128MB`. 사용자가 이날 새로 띄웠다 |
| Redis | Docker `redis:7.4-alpine` `ticket-redis`, 논리 DB 기본(0) |
| 데이터 | `reset-local.ps1 -LargePerformanceCount 30 -Members 1000000 -BackgroundOrders 5000000`. 빈 DB에 새로 적재(약 10분). 적재 후 `ANALYZE orders, order_seats, members, performance_seats` |
| 실행 전 행 수 | 회원 1,000,000, 주문 5,000,000(첫 실행 전), 대형 회차 30개 × 15,000석. `orders` 테이블 1,806MB |
| 풀·스레드 | Hikari 10(기본), Tomcat 200 |
| PC | Intel Core Ultra 5 228V, 8 논리 코어, 메모리 약 32GB(Docker 할당 15.4GB). **10-08(Ryzen 7 8700G, 16스레드)과 다른 PC다** |
| 부하 발생기 | 같은 PC의 Gatling, Open Model, 사용자마다 고유 회원·좌석, 생각 시간 없음 |
| 환경 파일 | 이 PC에는 `local-env/ticket-core.env`가 없어서 새로 만들었다. JWT secret은 무작위 생성한 로컬 전용 값, OAuth 키는 더미 값 |

## 실행 표

steps.md의 줄을 옮겼다. 회차는 `920000001~004`를 썼고 남은 대형 회차는 `005~030`이다.

| 시각 | 용도 | 부하 | 회차 | 성공/시작 | 체류 p95 / p99(ms) | Core 쪽 p95(요청별, ms) | 연결·스레드 최대 | 유효 | 판정 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 18:04 | 예열(판정 제외) | 10 × 30초 | 001 | 300/300 | 59,257 / 61,478 | 요약 5,727, 좌석 상태·선택·주문 상한 초과, 주문 상태 8,590 | 10/10, pending 243, busy 200/200 | 유효 | FAIL |
| 18:06 | 사다리 | 5 × 30초 | 002 | 150/150 | 7,704 / 8,353 | 요약 895, 좌석 상태 1,432, 선택 1,432, 주문 5,727, 주문 상태 805 | 10/10, pending 32, busy 37 | 유효 | FAIL |
| 18:07 | 재현 | 5 × 30초 | 003 | 150/150 | 22,370 / 23,991 | 요약 2,863, 좌석 상태 5,000, 선택 5,000, 주문 상한 초과, 주문 상태 3,000 | 10/10, pending 100, busy 93 | 유효 | FAIL |
| 18:09 | 진단(덤프 3개, 판정 제외) | 5 × 30초 | 004 | 150/150 | 24,813 / 26,683 | 요약 3,579, 좌석 상태 5,727, 선택 7,158, 주문 상한 초과, 주문 상태 3,221 | 10/10, pending 89, busy 88 | 유효 | FAIL |

모든 실행에서 기술 실패 0%, 과부하(E6003) 0, 비즈니스 거절 0이었다. 실제 입장은 초당 4~6명(예열 9~11명),
Gatling GC 최대 멈춤은 9.458ms로 유효성 기준 안이다. FAIL 이유는 모두 체류 p99 5,000ms 초과다.
예열이 300명 모두 성공했으므로 준비 단계의 "기술 실패·비즈니스 거절 0" 조건은 만족했다. 5명이 첫 단계라 직전 PASS가 없다.

## 발견한 문제

### P-001 (추가 근거) 초당 5명에서 진행 중 주문 확인이 주문 전체를 스캔하고 연결 풀을 묶는다

**메트릭 전후 차이** (`prometheus-before/after.txt`)

| 항목 | 5명 사다리 | 5명 재현 | 5명 진단 |
| --- | --- | --- | --- |
| 연결 획득 평균(ms, 2,105회) | 221.9 | 1,168.7 | 1,104.0 |
| 연결 사용 평균(ms) | 156.0 | 208.0 | 225.2 |
| `OrderRepository.existsByMemberIdAndPerformanceIdAndStatus` 평균(ms, 150회) | 1,996.9 | 2,446.4 | 2,663.8 |
| `PerformanceSeatRepository.findByPerformanceIdAndSeatId` 평균(ms, 150회) | 199.9 | 1,160.5 | 1,281.8 |
| Core GC 멈춤 합계(ms) | 80 | 95 | 100 |
| Core 프로세스 CPU 최대 | 11.5% | 14.8% | 14.7% |
| PC 전체 CPU 최대(`system_cpu`) | 100% | 100% | 100% |

존재 확인은 사용자당 한 번(150회/150명)이다. 존재 확인의 총 시간이 사다리 299.5초·재현 367.0초·진단 399.6초로,
30초 실행 동안 연결 10개 중 평균 10~13개 분량을 이 쿼리 하나가 쓴 셈이다(대기 포함일 수 있다).
`findByPerformanceIdAndSeatId`는 유니크 인덱스 조회다. 트랜잭션 밖에서 호출되어 저장소 시간에 연결 획득 대기가 들어간다.
덤프에서도 `findSeatState`가 Hikari 대기 쪽에 있었으므로 원인이 아니라 피해 쪽으로 본다.

**스레드 덤프** (진단 실행, 요청 스레드 200개, 약 3초 간격)

| 분류 | 덤프 1 | 덤프 2 | 덤프 3 |
| --- | --- | --- | --- |
| PostgreSQL 응답 대기(`NioSocketImpl` ← `QueryExecutorImpl.execute`) | 10 | 10 | 9 |
| 그중 `OrderRepository.existsPendingByMemberIdAndPerformanceId:60` | 9 | 10 | 8 |
| Hikari 연결 획득 대기 | 3 | 13 | 19 |
| Redisson 락 | 0 | 0 | 2 |
| 쉬는 스레드 | 187 | 177 | 170 |

Hikari 대기 스택의 호출자는 판매 정책(`PerformanceSaleFinder.requirePolicy:36`), 공연 요약, 좌석 상태(`GetSeatStatusUseCase:61`),
주문 상태, 회원 확인, 주문 생성(`PendingOrderCreator`)으로 여러 API에 걸쳐 있다. 연결 10개 중 9~10개가 존재 확인 쿼리를 실행하고,
나머지 요청이 그 연결을 기다리며 대기 수가 3 → 13 → 19로 늘었다.

**실행 계획** (측정 후 한가할 때, 회차 920000004)

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT o.id FROM orders o
WHERE o.member_id = 1 AND o.performance_id = 920000004 AND o.status = 'PENDING'
FETCH FIRST 1 ROWS ONLY;
```

`Limit → Gather(workers 2) → Parallel Seq Scan on orders`, 실행 282.2ms, 필터 제외 1,665,755행 × 3루프 ≈ 500만 행,
shared hit 15,642·read 152,132블록. `orders`(1,806MB)가 `shared_buffers`(128MB)보다 훨씬 커서 매번 대부분을 버퍼 밖에서 읽는다.
`orders` 인덱스는 `pk_orders`, `uk_orders_order_key`, `uk_orders_hold_key`뿐이다(`c9dbb328`의
`db/migration-vendor/postgresql/booking/V1__create_booking_schema.sql`도 같다).

**원인 판단**

한 번의 존재 확인이 한가할 때도 프로세스 3개(리더 + 병렬 worker 2)로 약 0.28초를 쓴다. 초당 5건이면 이 쿼리만으로
약 4코어 분량을 요구하고, 이 PC는 8 논리 코어에서 Gatling·Core·Docker VM이 함께 돈다. 그래서 부하 시작 직후 PC CPU가 100%에 붙고,
쿼리가 한 건 2~2.7초로 늘어 연결을 오래 잡는다. 풀이 차면 인덱스로 빨리 끝나는 다른 API도 연결을 기다린다.
10-08(16스레드 PC)에서 5명 PASS·10명 FAIL이던 것이 이 PC에서 5명 FAIL로 내려간 것은 같은 병목이 CPU가 적은 PC에서 더 일찍 드러난 것으로 본다.

**틀렸던 가설·버린 해석**

- 처음 집계에서 저장소 평균이 모두 0ms로 나왔다. 집계 스크립트가 `_sum` 이름을 잘못 골라서였고, 고친 뒤 위 숫자를 얻었다.
- 덤프에서 `Unsafe.park ← awaitNanos`로 시작하는 스레드 170~187개는 처음에 "기타"로 셌다. Tomcat 작업 큐에서 쉬는 스레드라 쉬는 스레드로 옮겼다.
- 좌석 단건 조회(`findByPerformanceIdAndSeatId`) 평균 1.3초는 처음에 두 번째 병목 후보로 봤다. 트랜잭션 밖 호출이라 연결 대기가 포함되고,
  덤프에서 이 조회는 DB 실행이 아니라 Hikari 대기에만 나타나서 후보에서 뺐다.

**관찰만 한 것**

- 예열(10명) 중 18:05:49~18:06:01에 Core ERROR 4건이 있었다. `/actuator/prometheus` 응답을 쓰는 중 클라이언트(측정 스크립트의 1초 수집)가
  연결을 끊어 `AsyncRequestNotUsableException`이 났고, `GlobalExceptionHandler`가 `text/plain` 응답에 `ApiResponse`를 쓰지 못해 WARN이 이어졌다.
  부하 요청의 실패는 아니다. 사다리·재현·진단 구간에는 ERROR가 없었다.
- P-007(주문 만료 조회)은 진단 실행 중 한 번만 호출되어 이번 FAIL의 원인으로 보지 않는다.

## 해결 선택지(결정 대기)

| 선택 | 장점 | 단점·확인할 점 | 다시 잴 때 기대하는 변화 |
| --- | --- | --- | --- |
| `orders(member_id, performance_id, status)` 복합 인덱스 | 존재 확인이 인덱스 범위 조회로 바뀌어 스캔·CPU·연결 점유가 함께 준다 | 주문 쓰기마다 인덱스 갱신, 저장 공간. H2·PostgreSQL migration 짝과 테스트 필요 | 5명 PASS, 연결 획득 평균 ≈0, 존재 확인 평균 수 ms. 다음 병목까지 사다리를 더 오른다 |
| PENDING 전용 부분 인덱스 `WHERE status = 'PENDING'` | 진행 중 주문만 담아 인덱스가 작다 | PostgreSQL 전용 문법이라 H2 방언과 짝을 맞추기 어렵다. 매개변수 바인딩 쿼리에서 부분 인덱스가 쓰이는지 실행 계획 확인 필요 | 복합 인덱스와 비슷 |
| 존재 확인을 다른 저장소(Redis 등)로 옮기기 | DB 왕복 자체를 없앤다 | 주문 상태와 Redis 키의 정합성 관리가 새로 생긴다. 변경 범위가 크다 | DB 연결 사용 감소. 정합성 검증이 따로 필요 |
| 풀을 키운다 | 설정 변경만으로 끝난다 | 병목이 CPU·DB 스캔이라 이 PC에서는 동시 스캔만 늘어 더 느려질 수 있다. 권하지 않는다 | 거의 없음 또는 악화 |

## 원자료

결과 루트는 `distributed-results-join/capacity/`다.

| 폴더 | 용도 |
| --- | --- |
| `20261010-180425-warmup-10ups-30s` | 예열 |
| `20261010-180643-ladder-5ups-30s` | 첫 FAIL |
| `20261010-180742-repro-5ups-30s` | 재현 |
| `20261010-180851-diag-5ups-30s` | 진단, `thread-dump-1~3.txt` |
| `core-local-20261010-175415*.log` | Core 로그(적재용·측정용) |

## 다음에 할 일

- 사용자 결정을 기다린다. 인덱스를 넣으면 `-KeepData`로 다시 띄워(새 migration은 기동 때 적용) 예열 후 5명부터 재측정한다.
- 이 PC에서 숫자를 내면 10-08 PC 숫자와 직접 비교하지 않는다. 같은 PC·같은 데이터에서 고치기 전(오늘)과 후를 비교한다.
- 측정 Core(PID 36068)는 재측정에 쓸 수 있도록 띄워 둔다.
