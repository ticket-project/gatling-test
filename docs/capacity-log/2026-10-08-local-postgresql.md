# 2026-10-08 로컬 PostgreSQL 부하 측정

## 요약

로컬 Core와 부하 실행기를 PostgreSQL로 맞춘 뒤 회원 100만·배경 주문 500만·15,000석 회차로 03 고정 조건을 실행했다.
오전 첫 측정(`72450b3e`)은 초당 10명 통과·15명 실패였다. 이후 `core-capacity` 절차를 적용한
`6cc73867` 측정은 5명 통과·10명 실패였으며, 같은 10명 부하의 재현과 스레드 덤프 3개 진단까지 완료했다.
진행 중 주문 확인은 PostgreSQL에서도 주문 전체를 읽는다. 연결 풀이 이 쿼리에 묶이면 다른 API도 연결을 기다린다.
두 측정은 Core 버전과 PC 상태가 달라 직접적인 성능 회귀 비교가 아니다. 최신 결과도 운영 입장률이나 최종 수용량 C로 확정하지 않는다.

## 목적

H2에 남아 있던 로컬 측정 경로를 PostgreSQL로 전환하고, 실제 예매 흐름과 병목을 확인한다.
이전 H2 기록과 데이터 규모를 맞췄지만 Core commit과 DB 실행 환경이 달라 DB 종류만의 성능 차이를 뜻하지 않는다.

## 조건

| 항목 | 값 |
| --- | --- |
| Core | `72450b3e`, 소스 변경 없음, JDK 25.0.2 `java -jar`, p6spy 꺼짐, worker 켜짐 |
| 대상 | `http://localhost:8083`, 별도 DB `ticket_loadtest_20261008`, Redis 논리 DB 15 |
| DB | 기존 Docker PostgreSQL 16.13 컨테이너, 시작 시 회원 1,000,000명·주문 5,000,000건 |
| 회차 | 대형 15개 `920000001~920000015`, 각 15,000석. 실행마다 새 회차 |
| 풀·스레드 | Hikari 10, Tomcat 200 |
| PostgreSQL | `shared_buffers=128MB`, `work_mem=4MB`, `max_connections=100`, 동기 커밋 켜짐, 컨테이너 CPU·메모리 제한 없음 |
| PC | Ryzen 7 8700G, 8코어·16스레드, 메모리 약 64GB. Docker VM은 16 CPU·메모리 약 32GB |
| 부하 발생기 | 같은 PC의 Gatling, Open Model, 사용자마다 고유 회원·좌석, 생각 시간 없음 |
| 준비 | `seedLocal` 적재 중 측정용 Core worker 끔, 완료 후 재기동. `ANALYZE orders, order_seats, members, performance_seats` 실행 |

공연 요약 → 좌석 상태 → 좌석 선택 → 주문 생성 → 주문 상태 조회를 한 번씩 실행한다.
아래 시간은 다섯 요청을 완료하는 사용자 체류 시간이다. API별 Core 히스토그램은 각 결과 폴더에 있다.

## 실행 표

| 시각 | 용도 | 사용자/초 × 시간 | 회차 끝자리 | 성공/시작 | 체류 p95 / p99(ms) | 연결 대기 최대 | Tomcat busy 최대 | 유효성·판정 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 09:36 | 최초 확인, 측정 제외 | 1 × 5초 | 001 | 5/5 | 1,073 / 1,073 | 0 | 2 | 유효·PASS |
| 09:37 | 예열, 측정 제외 | 5 × 30초 | 002 | 150/150 | 399 / 445 | 0 | 3 | 유효·PASS |
| 09:38 | 본 측정 | 5 × 30초 | 003 | 150/150 | 583 / 722 | 0 | 4 | 유효·PASS |
| 09:39 | 본 측정 | 10 × 30초 | 004 | 300/300 | 2,395 / 3,000 | 19 | 25 | 유효·PASS |
| 09:40 | 본 측정 | 15 × 30초 | 005 | 450/450 | 23,071 / 25,075 | 237 | 200 | 유효·FAIL |
| 09:42 | 재측정 | 10 × 30초 | 006 | 300/300 | 1,368 / 1,562 | 2 | 13 | 유효·PASS |

모든 실행에서 기술 실패·E6003은 0이었다. 15명 단계는 HTTP 실패가 아니라 체류 p99 기준 5,000ms를 넘어서 실패했다.
실제 입장은 초당 14~16명, Gatling GC 최대 멈춤은 29.517ms로 유효성 기준 안이었다. 첫 실패 뒤 높은 부하로 올리지 않았다.

## 발견한 문제

### P-001: 진행 중 주문 확인이 PostgreSQL에서도 전체 스캔

다음 조회를 한가할 때 실행한 `EXPLAIN (ANALYZE, BUFFERS)`는 `Gather → Parallel Seq Scan`이었다.

```sql
SELECT id FROM orders
WHERE member_id = 1 AND performance_id = 920000006 AND status = 'PENDING'
FETCH FIRST 1 ROWS ONLY;
```

- 실행 시간 216.715ms, 필터에서 제외한 행 약 5,001,054개(`Rows Removed by Filter=1,667,018`, `Actual Loops=3`).
- 공유 버퍼 hit 15,923블록·read 151,883블록. read는 PostgreSQL 버퍼에 없었다는 뜻이며 물리 디스크 읽기 횟수와 같다고 단정하지 않는다.
- 15명 단계에서는 같은 저장소 조회 450번의 평균이 약 791ms였다. Core 주문 생성 p95는 히스토그램 상한 10초를 넘었다.
- 10명 재측정 중 DB 활성 연결 표본에서 client backend 7개가 모두 같은 존재 확인 쿼리를 실행했다. 병렬 worker 7개도 같은 쿼리였다.
  `DataFileRead`와 `BufferMapping` 대기도 관찰했다.
- `orders`에는 PK와 `order_key`·`hold_key` 유니크 인덱스만 있다. 회원·회차·상태를 좁히는 인덱스가 없다.

**원인 판단:** 주문 존재 확인이 많은 행을 반복해서 읽고 DB 연결을 오래 점유한다. 부하가 늘면 공용 Hikari 풀이 차서
요약·좌석 선택·주문 조회까지 연결을 기다린다. 15명 단계의 PC 전체 CPU 표본 최대는 약 99.9%, Core CPU는 약 12.7%였다.
DB 활성 쿼리·실행 계획·연결 대기가 같은 방향을 가리킨다. 부하 발생기 GC·주입 속도는 유효성 검사를 통과했다.

다음 변경 후보는 `(member_id, performance_id, status)` 복합 인덱스다. PENDING 전용 부분 인덱스도 대안이지만
필터·유니크 정책과 실제 매개변수 실행 계획을 함께 검토해야 한다. 이번 작업에서는 Core migration이나 연결 풀을 바꾸지 않았다.

### P-007: 주문 만료 조회도 전체 스캔

`status='PENDING' AND expires_at<=CURRENT_TIMESTAMP AND id>-1 ORDER BY id FETCH FIRST 100 ROWS ONLY`도
전체 스캔을 포함했고 한가할 때 292.191ms였다. 이번 15명 실행 구간에서 이 저장소 메서드의 추가 호출은 없었으므로
해당 실패의 직접 원인으로 보지는 않는다. 주기 작업을 포함한 장시간 측정 전에 인덱스를 별도로 검토한다.

## PostgreSQL 전환 내용과 검증

- `reset-local.ps1`: H2 파일 이동 제거, 로컬 PostgreSQL 확인, 별도 포트 지원 수정, 시드 중 worker 중지 후 재기동,
  DB·Redis·환경 파일 조건 기록. PostgreSQL 데이터는 초기화하지 않는다.
- `run-step.ps1`과 `LocalPostgresSnapshot`: Core 기동 환경으로 실제 PostgreSQL 회원 ID·회원 수·주문 수·버전을 읽는다.
  접속 대상이 기동 기록과 다르면 중단한다. H2 주문 수 조회를 제거했다.
- 토큰 생성기는 `LOADTEST_JWT_SECRET`을 읽을 수 있고 실행기는 secret을 명령행에 전달하지 않는다.
- Gatling 프로젝트에 Core에서 사용하는 버전과 같은 PostgreSQL JDBC 42.7.13을 추가했다.
- `gradlew.bat -p load-tests/gatling test`: 11개 suite, 40개 테스트 통과.
- PowerShell 두 실행기의 구문 검사와 `git diff --check` 통과.
- 새 스냅샷 경로의 실제 PostgreSQL 접속·회원 내보내기 확인. 비로컬 URL은 접속 전에 거절하는 것도 확인했다(예상한 실패).
- 마지막 10명 실행의 `auditBookingDatabase`: 클라이언트 성공 300건과 DB 주문 300건 일치,
  중복 주문 좌석·활성 중복 선점·좌석 없는 주문·생성 선점 이력 없는 주문·중복 회차 좌석 모두 0.

## 원자료와 재실행

결과 루트는 `distributed-results-join/capacity/`다.

- `20261008-093633-pg-smoke-1ups-5s`
- `20261008-093716-pg-warmup-5ups-30s`
- `20261008-093823-pg-ladder-5ups-30s`
- `20261008-093919-pg-ladder-10ups-30s`
- `20261008-094010-pg-ladder-15ups-30s`: PostgreSQL 두 실행 계획 JSON도 포함
- `20261008-094210-pg-repeat-10ups-30s`: DB 활성 쿼리 표본과 `booking-db-audit.json` 포함

측정용 Core PID 56704는 종료했다. 기존 8080 개발 서버는 유지했고 PostgreSQL 데이터·결과도 보존했다.
이 실행에서는 회차 001~006을 사용했다. 이후 회차 사용 현황과 환경 파일 위치는 아래 후속 측정을 따른다.

이번에는 30초 고정 조건만 확인했다. 정확한 경계 좁히기, 03-3 실제 사용자 흐름의 15분 유지,
Queue 보호 시나리오와 부하 발생기를 분리한 운영 측정은 실행하지 않았다.

## core-capacity 후속 측정 — 6cc73867

사용자 요청으로 `ticket/.agents/skills/core-capacity/SKILL.md`와 `local.md`를 적용했다.
로컬 문서의 H2 전제는 PostgreSQL을 쓰라는 사용자 지시로 대체했다.
예열 → 5·10명 단계 측정 → 첫 FAIL과 같은 부하 재현 → 같은 부하에서 덤프 3개 진단 순서다.
쿼리 분석에는 `query-performance-review`를 함께 적용했다. 첫 FAIL 후 더 높은 부하로 올리지 않았다.

### 조건과 결과

Core는 `6cc73867`, 기동 당시 `sourceDirty=false`, PID 267352, 기동 시각 10:45:57이었다.
회원 1,000,000명·시작 주문 5,001,355건을 그대로 재사용했다. 별도 PG DB·8083·Redis DB 15,
Hikari 10·Tomcat 200·worker 켜짐·회차별 15,000석·사용자당 요청 5개 조건은 위와 같다.
모든 실행은 새 회차에서 30초간 Open Model로 주입했다.

| 부하 시작 | 용도 | 사용자/초 | 회차 끝자리 | 성공/시작 | 체류 p95 / p99(ms) | 연결 대기 최대 | Tomcat busy 최대 | 판정 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10:46:29 | 예열, 수용량 판정 제외 | 10 | 007 | 300/300 | 14,782 / 16,053 | 125 | 112 | 지연 FAIL |
| 10:48:13 | 단계 측정 | 5 | 008 | 150/150 | 722 / 1,012 | 0 | 6 | PASS |
| 10:49:19 | 첫 실패 | 10 | 009 | 300/300 | 12,816 / 14,688 | 102 | 98 | FAIL |
| 10:51:12 | 동일 부하 재현 | 10 | 010 | 300/300 | 9,846 / 11,368 | 84 | 81 | FAIL |
| 10:52 | 덤프 진단, 수용량 판정 제외 | 10 | 011 | 300/300 | 9,235 / 10,473 | 86 | 77 | FAIL |

모든 실행은 주입 유효성 검사를 통과했고 기술 실패·E6003은 0이었다. 예열도 요청은 모두 성공했다.
FAIL 이유는 사용자 체류 p99가 5,000ms를 넘었기 때문이다. 5명은 관측된 PASS이며,
경계 좁히기와 후보 3회 통과 검증을 하지 않았으므로 최종 수용량 C가 아니다.

### P-001: 실행 계획·메트릭·덤프가 같은 병목을 가리킴

전후 Prometheus 누적값의 차이로 다음 평균을 계산했다. 저장소 시간에는 DB 실행 외 대기가 포함될 수 있다.

| 메트릭 | 5명 PASS | 10명 첫 FAIL | 10명 재현 | 10명 진단 |
| --- | --- | --- | --- | --- |
| 연결 획득 평균(ms) | 0.022 | 576.605 | 348.219 | 388.154 |
| 연결 사용 평균(ms) | 33.242 | 91.469 | 84.558 | 84.134 |
| 진행 중 주문 존재 확인 평균(ms) | 343.364 | 922.450 | 922.575 | 911.051 |
| 위 저장소 호출 수 | 150 | 300 | 300 | 300 |
| Core GC 전체 멈춤 합계(ms) | 40 | 176 | 94 | 101 |
| PC 전체 CPU 표본 최대 | 98.2% | 100% | 99.8% | 99.9% |

연결 획득·사용은 실행당 2,100~4,205회이고 존재 확인은 사용자당 한 번이다.
서로 분모가 다르므로 연결 사용 평균과 저장소 평균을 같은 트랜잭션 시간처럼 비교하지 않는다.
Core 자체 CPU 최대는 첫 FAIL에서 약 11.9%였다. DB는 별도 프로세스이므로 Core CPU가 낮아도 DB 여유를 뜻하지 않는다.

| Core API p95(ms, 히스토그램 버킷 상한) | 5명 PASS | 10명 첫 FAIL |
| --- | --- | --- |
| 공연 요약 | 22 | 1,790 |
| 좌석 상태 | 134 | 2,863 |
| 좌석 선택 | 22 | 3,221 |
| 주문 생성 | 626 | 7,158 |
| 주문 상태 | 13 | 2,000 |

덤프는 `http-nio-8083-exec-*` 요청 스레드 82개만 분류했다. idle은 병목 스레드에서 제외했다.
첫 덤프는 10:52:40이고 뒤의 덤프는 약 3.6초·7.3초 뒤다.

| 분류 | 덤프 1 | 덤프 2 | 덤프 3 |
| --- | --- | --- | --- |
| PostgreSQL JDBC 실행·응답 대기 | 10 | 10 | 9 |
| 그중 진행 중 주문 존재 확인 | 7 | 6 | 7 |
| Hikari 연결 획득 대기 | 4 | 16 | 26 |
| Redis | 0 | 1 | 0 |
| 기타 runnable | 2 | 0 | 0 |
| idle | 66 | 55 | 47 |

대표 DB 스택은 `QueryExecutorImpl.processResults/execute` →
`OrderRepository.existsPendingByMemberIdAndPerformanceId:60` →
`BookingAvailabilityChecker.ensureNoPendingOrder:46` → `CreateOrderUseCase.createOrder:106`이다.
대기 스택은 `ConcurrentBag.borrow:163` → `HikariPool.getConnection:160`이며,
공연 요약·판매 정책·주문 상태·좌석 상태 등 여러 API에서 나타났다.

코드 경로는 `OrderController:63,76` → `CreateOrderUseCase:84,106` →
`BookingAvailabilityChecker:38,41,46` → `OrderRepository:59~63`이다.
모두 측정 commit의 `src/main/java/com/ticket/booking/order/` 아래에 있다.
기존 주문이 없는 신규 회원·회차 조합에서도 존재 확인을 수행하므로 주문 규모만큼 읽는 비용이 매번 발생한다.

PG 활성 세션 표본에서도 client backend 6개와 parallel worker 7개가 같은 존재 확인 쿼리였다.
`DataFileRead`, `BufferMapping`, `SpinDelay` 대기가 있었다. 애플리케이션 행 잠금이 주원인이라는 근거는 관찰되지 않았다.

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id FROM orders
WHERE member_id = 1 AND performance_id = 920000012 AND status = 'PENDING'
FETCH FIRST 1 ROWS ONLY;
```

측정 후 실행 계획은 `Limit → Gather → Parallel Seq Scan`, 실행 266.001ms였다.
필터 제외 행 1,667,568 × 3루프 = 약 5,002,704행, shared hit 15,914·read 151,960블록이다.
read 블록 수는 물리 디스크 읽기 횟수와 같다고 단정하지 않는다.
실제 DB의 orders 인덱스는 `pk_orders`, `uk_orders_order_key`, `uk_orders_hold_key`뿐이었다.
측정 commit의 PG 스키마도 이 조건을 뒷받침하며 원자료에 별도로 보존했다.

### 원인 후보와 한계

1. **P-001 전체 스캔 → 연결 점유 → 다른 API의 연결 대기: 가장 강한 원인.**
   실행 계획·활성 쿼리·덤프 3개·연결 대기 증가·주문 생성 지연이 일치한다.
   반대 근거로 볼 만한 자료는 없지만 인덱스 적용 전후 비교는 아직 없으므로 개선 폭은 미확인이다.
2. **같은 PC의 CPU·DB 버퍼 경합이 지연을 증폭: 가능성이 높음.**
   CPU 최대 100%와 PG 버퍼·읽기 대기가 지지한다. 다만 프로세스별 전체 CPU 기여와 다른 작업의 영향은
   분리 측정하지 않았다. 이것만으로 PG 또는 특정 코드 변경의 성능 회귀라고 단정할 수 없다.
3. **GC·주입 정지·Redis가 주원인: 현재 근거 약함.**
   첫 FAIL 주입은 9~11명/초, Gatling 최대 GC 멈춤은 31.408ms이며 Core GC 합계도 176ms다.
   덤프의 Redis 스레드는 한 표본의 1개뿐이다. 순간 지연 가능성까지 배제한 것은 아니지만 수초 지연의 주된 설명은 DB 쪽이다.

이전 `72450b3e`에서는 10명 PASS였다. 새 JVM에서 예열을 다시 했고 측정 Core와 PC 상태가 달라졌기 때문에,
이번 결과를 특정 소스 변경 탓으로 돌리거나 두 결과 중 유리한 수치만 골라 수용량으로 삼지 않는다.

### 해결 선택지와 결정 대기

| 선택 | 기대 효과와 이유 | 비용·확인할 점 |
| --- | --- | --- |
| `(member_id, performance_id, status)` 복합 인덱스 | 존재 확인을 좁은 범위 조회로 바꿔 연결 점유의 원인을 줄임. 우선 권장 | 쓰기·저장 공간 비용. 실제 매개변수 쿼리의 계획과 동일 부하 재측정 필요 |
| PENDING 전용 부분 인덱스 | 진행 중 주문만 포함해 인덱스를 작게 유지 | 실제 prepared query가 조건을 활용하는지 확인 필요. 유니크 정책은 별도 판단 |
| 현재 FAIL을 한계로 수용하고 5~10명 사이 경계 측정 | 수정 없이 현재 상태의 입장률 후보를 좁힘 | 전체 스캔은 유지. 새 회차 확장·후보 3회 PASS·장시간 검증 필요 |

Core 코드·인덱스·풀은 이번 측정에서 바꾸지 않았다. 스킬 지시
“보고하면 멈추고 사용자의 결정을 기다린다.”에 따라 진단 보고 단계에서 멈춘다.

### 정합성·원자료·재실행 상태

재현 실행의 DB 감사는 클라이언트 성공 300건 = DB 주문 300건이었다.
중복 주문 좌석·활성 중복 선점·좌석 없는 주문·생성 선점 이력 없는 주문·중복 회차 좌석은 모두 0이다.
Core 로그 ERROR도 0이었다. 측정용 PID 267352는 종료했고 기존 8080 개발 서버는 유지했다.

원자료 루트는 `distributed-results-join/capacity/`다.

| 디렉터리·파일 | 내용 |
| --- | --- |
| `20261008-104612-skill-warmup-10ups-30s` | 예열 |
| `20261008-104753-skill-ladder-5ups-30s` | 마지막 PASS |
| `20261008-104857-skill-ladder-10ups-30s` | 첫 FAIL |
| `20261008-105058-skill-repro-10ups-30s` | 같은 부하 재현, `booking-db-audit.json` |
| `20261008-105216-skill-diag-10ups-30s` | 덤프 3개, `postgres-active-sample.txt`, `postgres-pending-order-plan.json`, `measured-core.json`, `measured-booking-schema.sql` |
| `skill-analysis-20261008.json`, 각 실행의 `skill-analysis.json` | 메트릭 차분·저장소 집계·덤프 분류와 대표 스택 |

환경 파일은 ignored 경로 `distributed-results-join/capacity/local-pg.env`에 복원했다.
회차 001~011을 사용했고 미사용 대형 회차는 012~015 네 개다. 추가 측정 전 스킬 기준에 맞게 회차를 확장해야 한다.
덤프 저장 코드는 UTF-8 BOM 없이 쓰도록 수정했고 실제 덤프 3개 생성을 확인했다.

**현재 작업 트리와 측정 결과는 구분해야 한다.** 측정 중 다른 작업으로 Core 파일의 staged 변경이 생겼고,
확인 시점의 `application-local`은 H2였으며 PG migration 삭제도 포함했다. 이 변경들은 건드리지 않았다.
디스크의 JAR는 모든 부하 실행이 끝난 뒤인 10:55:44에 다른 빌드로 교체되었다.
따라서 현재 JAR의 해시는 측정 JAR의 해시로 사용할 수 없다. 측정 JAR의 사전 해시는 확보하지 못했다.
기동 시각·clean commit 메타데이터와 실행별 PG 스냅샷은 보존했다.
재실행할 때는 현재 작업을 덮어쓰지 않는 별도 checkout 등으로 PG 호환 Core 버전과 빌드 조건부터 맞춰야 한다.
