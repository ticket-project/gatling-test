# 2026-10-17 운영 첫 측정: 초당 60명에서 운영 호스트 CPU가 찬다

## 요약

운영 Core(`https://oneticket.site`, `bca715fe`)를 처음으로 03 고정 조건 사다리로 쟀다. 부하 발생기는 같은 리전의 EC2다.
5~50 users/sec는 모두 PASS했고, **60 users/sec에서 FAIL**했다(세 번 중 FAIL 2, PASS 1).

막힌 곳은 운영 호스트의 CPU다. 호스트는 2 vCPU이고 Core, nginx, Redis, FE, Datadog Agent가 함께 돈다.
50 u/s부터 호스트 CPU가 97%에 붙었다. CPU 차례를 기다리는 동안 요청 스레드가 DB 연결을 쥐고 있어서,
연결 1회 점유가 3.5ms(10 u/s)에서 14.9ms(60 u/s)로 늘었다. 그 결과 풀 10이 차고 연결 대기가 평균 240ms가 됐다(P-017).

다음 수는 사용자가 정한다. 60 u/s를 한계로 보고 50~60 사이를 좁힐지, 호스트 CPU를 늘리거나 Core의 사용자당 CPU를 줄인 뒤 다시 잴지다.
측정 준비 중에 운영 JWT secret이 저장소에 공개된 로컬 기본값과 같다는 것도 발견했다(P-018).

## 목적

Queue 입장률 C를 정할 운영 한계를 처음으로 잰다(core-capacity.md 측정 순서 4~5단계). 로컬에서 고친 병목(P-001·P-010·P-011·P-014)이
운영에서는 어디까지 버티는지 본다.

## 조건

| 항목 | 값 |
| --- | --- |
| 대상 | 운영 Core `https://oneticket.site`. 이미지 `leehusung/ticket-be:bca715fe…`(13:11 KST 배포). 예매 경로는 로컬 마지막 측정 `a5ec3fab`와 같다(차이는 주석·Oracle migration 삭제·Flyway 위치) |
| 호스트 | EC2 `i-09739eeb559db0d1d`. 2 vCPU(Xeon 8175M, 물리 1코어), 메모리 1,905MB, swap 2GB(측정 전 203MB 사용). 인스턴스 유형은 Datadog에 없어 미확인(t3.small로 추정) |
| 같은 호스트의 컨테이너 | `ticket-be`, `ticket-nginx`(1.27), `ticket-redis`(7.4), `ticket-fe`, `ticket-certbot`, `datadog-agent` |
| Core JVM | `JAVA_TOOL_OPTIONS=-javaagent:dd-java-agent.jar`뿐. `-Xmx` 없음 → 힙 최대 약 478MiB(메모리의 1/4). Datadog 트레이서 켜짐 |
| 풀·스레드 | Hikari 10, Tomcat 200(기본값) |
| nginx | `worker_connections 1024`, `limit_req`·`limit_conn` 없음, gzip 켬(JSON, 1,000바이트 이상). `worker_rlimit_nofile` 설정 없음 |
| DB | RDS PostgreSQL `ticket-core-db`(ap-northeast-2), DB `ticket_core_db`, `max_connections` 79. 인스턴스 클래스는 미확인 |
| 데이터 | 회원 100만, 주문 500만(사용자가 IntelliJ에서 확인), 대형 회차(15,000석) 45개. 실행 전 주문 수는 실행마다 재지 않았다 |
| 부하 발생기 | EC2 `3.37.15.209`(c6i.xlarge, 4 vCPU·7.6GB, ap-northeast-2d), OpenJDK 21. `run-step.ps1 -LoadHost`로 Gatling만 원격 실행 |
| 지표 | 이 PC → 운영 호스트 SSH 터널(`127.0.0.1:18080` → `172.18.0.2:8080`). 1초 수집은 터널 때문에 약 1.2초 간격(30초에 24~28개) |
| 회원 ID | `loadtest%@test.com` 10,000명(ID 1~10000) |

## 실행 표

| 시각 | 용도 | 부하 | 회차 | 성공/시작 | 체류 p95 / p99(ms) | Core 쪽 p95(ms) | 연결·스레드 최대 | 유효 | 판정 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 13:52 | smoke(버림: 도구 오류) | 1 × 5s | 920000001 | 0/5 | - | - | - | - | 중단 |
| 13:54 | smoke | 1 × 5s | 920000001 | 5/5 | 2,045 / 2,045 | 요약 89, 좌석 89, 선택 447, 주문 1,432, 주문상태 89 | 1/10 대기 0, busy 3 | 유효 | PASS |
| 14:06 | 예열(버림) | 5 × 30s | 920000002 | 150/150 | 253 / 484 | 요약 17, 좌석 22, 선택 39, 주문 100, 주문상태 17 | 1/10 대기 0, busy 4 | 유효 | PASS |
| 14:07 | 사다리 | 5 × 30s | 920000003 | 150/150 | 123 / 160 | 요약 11, 좌석 10, 선택 15, 주문 50, 주문상태 11 | 1/10 대기 0, busy 2 | 유효 | PASS |
| 14:07 | 사다리 | 10 × 30s | 920000004 | 300/300 | 155 / 224 | 요약 15, 좌석 11, 선택 22, 주문 67, 주문상태 13 | 1/10 대기 0, busy 3 | 유효 | PASS |
| 14:08 | 사다리 | 15 × 30s | 920000005 | 450/450 | 197 / 383 | 요약 22, 좌석 13, 선택 28, 주문 89, 주문상태 14 | 4/10 대기 0, busy 7 | 유효 | PASS |
| 14:09 | 사다리 | 20 × 30s | 920000006 | 600/600 | 208 / 602 | 요약 22, 좌석 13, 선택 39, 주문 100, 주문상태 13 | 5/10 대기 0, busy 5 | 유효 | PASS |
| 14:10 | 사다리 | 30 × 30s | 920000007 | 900/900 | 392 / 639 | 요약 34, 좌석 22, 선택 50, 주문 157, 주문상태 22 | 10/10 대기 4, busy 21 | 유효 | PASS |
| 14:10 | 사다리 | 40 × 30s | 920000008 | 1200/1200 | 498 / 800 | 요약 56, 좌석 50, 선택 89, 주문 201, 주문상태 45 | 10/10 대기 17, busy 28 | 유효 | PASS |
| 14:11 | 사다리 | 50 × 30s | 920000009 | 1500/1500 | 1,336 / 1,581 | 요약 246, 좌석 268, 선택 358, 주문 537, 주문상태 268 | 10/10 대기 62, busy 59 | 유효 | PASS |
| 14:12 | 사다리 | 60 × 30s | 920000010 | 1800/1800 | 5,455 / 6,754 | 요약 1,432, 좌석 1,432, 선택 1,432, 주문 2,000, 주문상태 1,432 | 10/10 대기 253, busy 200 | 유효 | FAIL |
| 14:13 | 재현 | 60 × 30s | 920000011 | 1800/1800 | 4,998 / 5,822 | 요약 805, 좌석 984, 선택 1,432, 주문 2,000, 주문상태 805 | 10/10 대기 160, busy 200 | 유효 | FAIL |
| 14:14 | 진단 | 60 × 30s | 920000012 | 1800/1800 | 3,887 / 4,542 | 요약 447, 좌석 716, 선택 1,432, 주문 2,000, 주문상태 500 | 10/10 대기 176, busy 200 | 유효 | PASS |

- 13:52 smoke는 Gatling이 시작하지 못했다. 이 PC의 `gradlew`가 CRLF 줄끝이라 EC2에서 `/bin/sh^M`을 찾지 못했다(측정 도구 문제). 회차는 쓰이지 않아 13:54에 같은 회차로 다시 쟀다.
- 14:0x에 한 번 사다리를 시작했지만 SSH 터널이 끊겨 있어 실행 없이 멈췄다. 회차는 쓰이지 않았다.
- 진단 실행은 운영 이미지에 `jcmd`가 없고 덤프를 사용자가 떠야 해서 덤프 없이 돌렸다. 대신 Datadog 트레이스와 컨테이너 CPU를 봤다.
- 60 u/s는 FAIL 2·PASS 1이다. 같은 부하에서 FAIL이 한 번이라도 나오면 FAIL로 본다.
- 모든 실행에서 기술 실패·과부하(E6003)·비즈니스 거절은 0이었다. FAIL 이유는 셋 다 체류 p99 초과다.

## 발견한 문제

### P-017 운영 호스트(2 vCPU)의 CPU가 초당 50~60명에서 차고, 연결 점유가 늘어 풀 10이 막힌다

**증상**: 60 u/s에서 체류 p99가 6,754·5,822·4,542ms(직전 PASS 50 u/s는 1,581ms)다. 다섯 요청이 함께 느려졌다.
Core 쪽 p95는 요약·점유 좌석·주문 조회가 268ms 이하에서 1,432ms로, 주문 생성이 537ms에서 2,000ms로 늘었다.

**근거**

| 부하 | 호스트 CPU 평균(최대) | Core CPU 평균 | Core CPU 시간/사용자 | 연결 1회 점유 | 사용자당 빌림 | 풀 예산(부하 × 빌림 × 점유) | 연결 대기 평균 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 10 u/s | 측정 표본 없음 | - | 87.4ms(JIT 데우는 중) | 3.48ms | 10.11 | 0.35(풀의 4%) | 0.0ms |
| 30 u/s | 0.85(1.00) | 0.54 | 41.1ms | 4.69ms | 10.03 | 1.41(14%) | 0.3ms |
| 40 u/s | 0.94(0.99) | 0.73 | 34.9ms | 6.34ms | 10.03 | 2.54(25%) | 1.5ms |
| 50 u/s | 0.97(1.00) | 0.75 | 30.1ms | 12.68ms | 10.02 | 6.35(63%) | 32.4ms |
| 60 u/s | 0.97(1.00) | 0.75 | 28.5ms | 14.89ms | 10.02 | 8.95(89%) | 240.5ms |
| 60 재현 | 0.97(1.00) | 0.74 | 27.9ms | 14.58ms | 10.02 | 8.77(88%) | 141.1ms |
| 60 진단 | 0.98(1.00) | 0.75 | 27.9ms | 13.08ms | 10.02 | 7.86(79%) | 77.9ms |

- 호스트·Core CPU는 `core-metrics.tsv`의 `system_cpu`·`process_cpu`에서 바쁜 표본(`tomcat_busy` > 3)의 평균이다. 1.0이 2 vCPU 전체다.
- Datadog `docker.cpu.usage`(100% = 1 vCPU, 14:06~14:14): `ticket-be` 최대 164%, `ticket-nginx` 최대 약 12%, `ticket-redis` 최대 약 11%, `datadog-agent` 4~13%.
  `system.cpu.iowait`는 거의 0, `system.cpu.stolen`은 평균 1.6%·최대 9.9%다.
- CPU 계산: 60 u/s × 28.5ms = 1.71 CPU초/초로 2 vCPU의 86%다. nginx·Redis·Agent의 약 0.3 vCPU를 더하면 2 vCPU가 다 찬다.
  50 u/s는 50 × 30.1ms = 1.5 + 0.3 = 1.8(90%)이라 겨우 버텼다.
- 저장소 호출 평균(쿼리 포함)은 10 u/s 0.8~2.6ms, 60 u/s 3~7.5ms다. 연결 점유는 그보다 훨씬 많이 늘었다(3.5 → 14.9ms).
  스레드가 연결을 쥔 채 CPU 차례를 기다린 시간으로 본다.
- Datadog 트레이스 `6ad303c800000000698adf7bf3803812`(60 u/s, 14:12:40 `POST /api/v1/orders` 1,814ms): DB·Redis 자식 span은 하나에 1~13ms다. 나머지는 span 밖의 대기다.
  같은 트레이스의 비동기 후처리 `BookingEventListeners.on`은 2,322ms였다(P-015).
- Core GC는 60 u/s에서 16회 534ms로 작다. 사용자당 할당 1.4MB.
- `PerformanceSeatRepository.findByPerformanceIdAndSeatId`가 60 u/s에서 평균 83.5ms로 튀어 보인다. 이 호출은 트랜잭션 밖(`SelectSeatUseCase.java:71`)이라 저장소 시간 안에 **연결 대기가 들어 있다.**
  쿼리가 느려진 것이 아니다(재현 48.9ms, 진단 27.1ms로 연결 대기와 같이 움직인다).

**원인 후보** (가능성 높은 순)

1. 호스트 CPU 포화. 2 vCPU 하나에 Core와 nginx·Redis·FE·Datadog Agent가 함께 있다.
   지지: 호스트 CPU 0.97~1.00, 연결 점유가 쿼리 시간보다 크게 늘어남, 다섯 요청이 함께 느려짐, GC·iowait·steal은 작음.
   미확인: Core가 사용자당 약 28ms의 CPU를 어디에 쓰는지(프로파일을 보지 않음). Datadog 트레이서 몫(로컬에서는 트레이서가 한계를 낮췄다, P-016).
2. 사용자당 연결 10번 빌림과 풀 10(P-013). 지지: 60 u/s에서 풀 예산 79~89%.
   반대: CPU가 남던 30 u/s에서는 점유 4.7ms로 예산 14%였다. 로컬에서 풀만 20으로 키우면 점유가 늘어 처리량이 같았다(P-008, P-016 D).
3. 비동기 후처리가 같은 풀과 CPU를 쓴다(P-015). 사용자당 빌림 10번 중 약 2번이고, 트레이스에서 2.3초 걸렸다.
4. CPU 크레딧(t3 계열이라면, 미확인). 이번 30초 실행들에서는 steal이 작아 영향이 작았다. 15분 실행(core-capacity.md 7단계)에서는 크레딧이 바닥날 수 있다.

코드 위치(빌림 10번의 출처):

- `booking/admission/BookingEntryGuard.java:39` → `booking/salespolicy/usecase/PerformanceSaleFinder.java:34` `requirePolicy`: 판매 정책 `findById`가 사용자당 3번
- `booking/order/usecase/CreateOrderUseCase.java:75` `execute`: 회차·공연·공연장 `findById`가 각 2번, 좌석·등급 조회, 주문 저장
- `booking/selection/usecase/SelectSeatUseCase.java:71` `requireSelectableSeat`: 트랜잭션 밖 자동 커밋 조회 1번
- `booking/event/BookingEventListeners.java:60` `on(OrderCreated)`: 커밋 뒤 비동기 후처리(Modulith 기록 포함)

**어떻게 발견했나**

1. 사다리 표에서 50 → 60 u/s에 Core 쪽 p95가 다섯 요청 모두 함께 5배쯤 뛰었다. 한 API가 아니라 공용 자원 문제로 봤다.
2. `summary.json`의 `server`에서 연결 대기 최대 62 → 253, Tomcat busy 59 → 200을 보고 연결 줄을 의심했다.
3. 그런데 `prometheus-before/after.txt`로 계산한 연결 1회 점유가 3.5ms(10 u/s)에서 14.9ms(60 u/s)로 늘었고, 저장소 호출은 그만큼 늘지 않았다. 쿼리가 아니라 연결을 쥔 채 다른 것을 기다린다는 뜻이다.
4. `core-metrics.tsv`의 `system_cpu`가 40 u/s부터 0.94 이상, 50·60 u/s에 1.0에 붙어 있었다. Core만의 `process_cpu`는 0.75라 나머지 0.25는 다른 컨테이너다.
5. Datadog `docker.cpu.usage`를 컨테이너별로 나눠 Core 외에 nginx·Redis·Agent가 함께 CPU를 쓰는 것을 확인했다.
6. 느린 주문 요청 트레이스를 열어 DB·Redis span이 짧고 대부분이 span 밖 대기임을 확인했다.
7. `findByPerformanceIdAndSeatId`의 83ms는 처음엔 좌석 조회가 느려진 것처럼 보였다. 코드를 보니 트랜잭션 밖 호출이라 연결 대기가 섞인 숫자였다(틀릴 뻔한 가설).

**직접 보려면**

1. `distributed-results-join/capacity/20261017-141208-ladder-60ups-30s/core-metrics.tsv`를 엑셀로 연다 → `system_cpu` 열 → 14:12:23부터 1.0이 이어진다 → 호스트 CPU 2개가 다 찼다는 뜻이다.
   같은 줄의 `hikari_pending`이 89~96이면 연결을 기다리는 요청 수다.
2. 같은 폴더의 `prometheus-before.txt`·`prometheus-after.txt`에서 `hikaricp_connections_usage_seconds_sum`과 `_count`를 찾는다.
   점유 합 656.778 − 388.341 = 268.437초, 횟수 71,263 − 53,234 = 18,029번, 나누면 **14.89ms**다. 50 u/s 폴더(`…-141125-ladder-50ups-30s`)는 (388.243 − 197.649) ÷ (53,227 − 38,193) = 190.594 ÷ 15,034 = **12.68ms**다.
3. 같은 파일에서 `hikaricp_connections_acquire_seconds_sum`을 찾는다. 60 u/s는 (4,845.45 − 508.62) ÷ 18,029 = **240.5ms**(연결 한 번 얻는 데 기다린 평균)다.
4. `process_cpu_time_ns_total`을 찾는다. 60 u/s는 (5.5214 − 5.0079) × 10¹¹ns = 51.35초 ÷ 1,800명 = **28.5ms/사용자**다. 60명 × 28.5ms = 1.71초/초, 2 vCPU의 86%다.
5. Datadog(us5) **Metrics → Explorer**에서 `docker.cpu.usage`, `host:i-09739eeb559db0d1d`, `by container_name`, 시간 2026-10-17 14:06~14:14 KST → `ticket-be`가 150% 안팎, 나머지가 각 10% 안팎이다.
6. Datadog **APM → Traces**에서 trace id `6ad303c800000000698adf7bf3803812`를 연다 → flame graph에서 `servlet.request` 1.8초 아래 DB·Redis 막대가 짧고 빈 구간이 길다 → 그 빈 구간이 CPU·연결 대기다.
7. 운영 서버에서 부하 중에 `sudo docker stats --no-stream`을 치면 컨테이너별 CPU%를 바로 볼 수 있다.

**해결 선택지**

- **호스트 CPU 늘리기**(예: 4 vCPU 인스턴스) — 장점: 코드 변경 없음, 원인에 바로 닿는다 / 단점: 비용, 운영 서버 교체 작업 / 기대: Core CPU 몫이 두 배가 되어 한계가 CPU 기준 약 100 u/s 근처까지 오를 것(미확인). 그 뒤에는 풀 예산(P-013)이 다음 한계가 될 수 있다.
- **Core와 다른 컨테이너 떼기**(nginx·Redis·FE·Agent를 다른 호스트로) — 장점: Core가 2 vCPU를 다 쓴다(약 0.3 vCPU 회수) / 단점: 구성이 복잡해지고 네트워크 홉이 는다 / 기대: 60 u/s 부근까지(약 15% 개선, 미확인).
- **Core의 사용자당 CPU 줄이기** — 먼저 Datadog Continuous Profiler나 JFR로 28ms가 어디에 쓰이는지 본다. 후보는 반복 조회 엔티티 로딩(P-013의 판매 정책 3번·회차·공연·공연장 2번씩), JSON 직렬화, 트레이서. 장점: 사양 그대로 한계를 올린다 / 단점: 원인을 먼저 찾아야 한다 / 기대: 줄인 비율만큼 한계가 오른다.
- **거의 바뀌지 않는 데이터 캐시**(P-013 나머지) — 판매 정책·회차·공연·공연장을 짧게 캐시해 빌림 10 → 약 4. 장점: CPU와 연결 점유가 함께 준다 / 단점: 정책 변경 반영이 늦어진다, 캐시 무효화 설계가 필요 / 기대: 풀 예산이 절반 이하로, CPU도 일부 감소.
- **풀 크기만 키우기** — 권하지 않는다. CPU가 찬 상태에서는 동시 실행만 늘어 점유가 길어진다(로컬 P-008·P-016 실험).
- **60 u/s를 한계로 보고 50~60 사이 좁히기** — 지금 사양에서 C를 정한다(core-capacity.md 5단계). 예상 C는 50 근처다.

**다음 실행**: 한계로 보면 55 u/s → (PASS면 57~58, FAIL이면 52~53) 순으로 새 회차(`920000013`~)에서 잰다. 고친 뒤라면 같은 조건으로 50·60 u/s부터 다시 잰다.

### P-018 운영 JWT secret이 저장소에 공개된 로컬 기본값과 같다

- 증상: 운영 `ticket-be`의 `JWT_SECRET`이 `gatling-test/scripts/capacity/run-step.ps1`의 `$localSecret`과 같은 32자 개발용 값이다(사용자가 서버에서 확인).
- 영향: 저장소를 본 사람은 누구나 운영 회원 아무에게나 서명된 access token을 만들 수 있다.
- 측정 중에는 바꾸지 않기로 했다(바꾸면 기존 로그인이 모두 끊기고 측정용 환경 변수도 다시 넣어야 한다).
- 해결 선택지: 무작위 값(`openssl rand -hex 32`)으로 compose 환경 변수를 바꾸고 Core 재기동. `ADMISSION_TOKEN_SECRET_KEY`도 기본값인지 확인한다(비어 있으면 Core가 같은 공개 기본값을 쓴다).
- 결정: 사용자 대기.

### 그 밖에 남길 것

- Datadog span의 `git.commit.sha`는 5월 commit `7b71a992`로 고정돼 있다. 운영 commit은 `version` 태그로 본다(production.md에 반영).
- 운영 Core는 DB에 `postgres` 마스터 계정으로 접속한다(span의 `db.user`). 권한을 줄인 앱 전용 계정을 쓰는지 사용자가 정한다.
- 운영 호스트는 부하 전부터 사용 가능 메모리 554MB, swap 203MB 사용 중이었다. 이번 30초 실행들에서는 GC·iowait가 작아 영향이 보이지 않았다.
- SSH 터널은 한 번 조용히 끊겼다. 다시 열 때 `ServerAliveInterval=30`을 붙였다.

## 측정 도구

- `run-step.ps1`에 `-MetricsUrl`(지표 주소 분리), `-LoadHost`/`-KeyPath`(EC2 원격 Gatling), `-CoreCommit`을 더했다(gatling-test `c14069c`).
- 틀린 것 1: 이 PC의 `gradlew`가 CRLF라 EC2에서 실행되지 않았다. 원격에서 LF로 바꾼 뒤 실행한다.
- 틀린 것 2: Gatling 증거 파일이 없을 때 스크립트가 예외로 끝나 종료 코드가 1(FAIL과 같음)이었다. 중단(2)으로 끝내게 했다.
- 틀린 것 3: `run-step.ps1` 머리 주석이 "판정 근거는 Core 쪽 시간"이라고 했지만 실제 판정은 Gatling이 잰 체류 p99다. 주석을 고쳤다.

## 다음에 할 일

- 결정 대기: P-017(사양·컨테이너 분리·CPU 줄이기·캐시·한계로 보기), P-018(JWT secret 교체).
- 한계로 보기로 하면 50~60 u/s 좁히기 → C 확정 → core-capacity.md 7~9단계(15분 실행, Queue 켜고 확인).
- 운영 측정 준비(터널·JWT secret·회원 ID)를 자동화하는 작업은 다른 세션이 맡았다.
