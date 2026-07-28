# Ticket Gatling Load Tests

Ticket/Queue 부하 테스트와 Queue CDN public state 전환, legacy queue status 비교를 위한 Gatling 부하테스트 저장소이다.

## 구성

- `load-tests/gatling`: 실제 Gatling simulation 프로젝트
- `console`: 로컬 브라우저에서 Gatling 실행을 도와주는 개발용 콘솔

`ticket` 저장소 안에 있던 `load-tests/gatling`은 이 저장소로 통합한다.

## 비교 대상 API

Legacy queue status:

```text
GET /api/v1/queue/performances/{performanceId}/status
Header: X-Queue-Session
```

CDN public state 확인용 경로:

```text
Base URL: Cloudflare가 프록시하는 state endpoint
GET /api/v1/queue/performances/{performanceId}/state
Header/cookie/auth 없음
```

`/join` 부하 테스트의 `https://queue.oneticket.site`는 Cloudflare를 거치지 않고 Queue origin Nginx로 직접 요청한다. `cdn-public-state` 테스트는 별도의 Cloudflare state endpoint가 구성된 경우에만 CDN 캐시를 검증하며, 실행 전 `CF-Ray`와 `CF-Cache-Status` 응답 헤더를 확인한다.

## `/join` 예매 오픈 패턴

`QueueJoinOnlySimulation`은 `-DinjectionMode=ticket-open`에서 실제 예매 오픈에 가까운 형태로 사용자를 투입한다.

- 예매 오픈 시각부터 최고 RPS로 10초 유지
- 20초 동안 50%, 다음 60초 동안 20%, 다음 180초 동안 10%까지 감소
- 본 부하 종료 30초 후 60초 동안 1 RPS로 회복 상태 확인(총 360초)

분산 실행은 모든 노드에 동일한 UTC 예매 오픈 시각을 전달하고 그 시각부터 최고 RPS로 시작한다. 준비가 늦어 시작 시각을 놓친 노드는 늦게 합류하지 않고 실패한다. 토큰 파일은 본 부하와 회복 확인에 필요한 사용자 수 이상이어야 하며, member subject가 중복되면 실행을 거부한다.

성공 조건은 전체 실패율 1% 미만, 본 부하와 회복 구간 각각의 `/join` p99 2초 미만이다. 응답의 `queueToken`, `shardId`, `localSeq`, `pollAfterMs`도 함께 검증한다.

## Access token 파일 생성

큰 `/join` 부하 테스트에서는 실행 중 synthetic JWT를 만들지 말고 토큰 파일을 미리 만들어 `-DaccessTokenMode=tokens -DaccessTokensFile=...`로 사용한다.

```powershell
.\gradlew.bat -p load-tests/gatling generateAccessTokens `
  -Doutput=C:\Users\mn040\IdeaProjects\ticket-workspace\.tmp\access-tokens.txt `
  -DjwtSecret=0123456789abcdef0123456789abcdef `
  -DjwtIssuer=ticket `
  -DsyntheticMemberStartId=1 `
  -DsyntheticJwtRole=MEMBER `
  -DsyntheticTokenTtlSeconds=3600 `
  -DtokenCount=60000
```

생성된 파일은 UTF-8 텍스트이며 JWT가 한 줄에 하나씩 들어간다. 콘솔에서는 `Token mode=직접 입력`을 선택하고 `Access Token 파일 경로`에 위 파일 경로를 넣는다.

## 테스트

```powershell
.\gradlew.bat -p load-tests/gatling test
.\console\gradlew.bat -p console test
```

실제 부하 실행은 대상 서버, 사용자 수, 투입 시간, 테스트 전용 `performanceId`를 확인한 뒤 수행한다.

## Booking capacity 시나리오

새 예매 부하 테스트는 구형 예매 오픈/홀드 경합/티켓 서버 용량 시나리오를 대체한다.

| key | Simulation | 측정 대상 |
| --- | --- | --- |
| `booking-capacity` | `BookingCapacitySimulation` | Ticket/Core의 좌석 조회, 선택, 주문 생성, 주문 PENDING 전환 |
| `ticket-open-end-to-end` | `TicketOpenEndToEndSimulation` | Queue join/state/enter부터 Ticket/Core 예매까지 전체 흐름 |
| `seat-contention` | `SeatContentionSimulation` | 같은 좌석에 대한 경합 정합성, 비즈니스 거절과 기술 오류 분리 |

### Booking feeder CSV

모든 booking 시나리오는 순환하지 않는 feeder 파일을 사용한다. 파일은 UTF-8 without BOM이어야 하고 첫 줄은 정확히 아래와 같아야 한다.

```csv
memberId,accessToken,seatId,admissionToken
1,access-jwt-1,101,admission-jwt-1
2,access-jwt-2,102,admission-jwt-2
```

규칙:

- `memberId`는 중복되면 안 된다.
- `booking-capacity`, `ticket-open-end-to-end`는 기본적으로 좌석이 중복되면 안 된다.
- `seat-contention`은 의도적으로 같은 `seatId`를 여러 행에 넣을 수 있다.
- `ticket-open-end-to-end`는 admission token을 Queue `enter` 응답에서 받으므로 feeder의 `admissionToken`은 비워도 된다.
- feeder가 부족하면 실행 전 검증 또는 Gatling feeder exhaustion으로 실패한다.

### 3 VM 분산 실행

Booking 전용 실행기는 `run-distributed-booking.ps1`이다. 원격 Gradle 명령에는 운영 URL, performanceId, feeder 경로, injection mode, polling timeout, node/result 경로만 전달한다. JWT secret이나 admission secret은 명령행에 전달하지 않는다.

```powershell
.\run-distributed-booking.ps1 `
  -Hosts ubuntu@43.203.155.15,ubuntu@15.165.40.25,ubuntu@43.203.136.184 `
  -KeyPath C:\path\ticket-test-key-01.pem `
  -RemoteProjectDir ~/gatling-test `
  -Simulation com.ticket.loadtest.simulation.TicketOpenEndToEndSimulation `
  -CoreBaseUrl https://api.example.com `
  -QueueBaseUrl https://queue.example.com `
  -PerformanceId 1 `
  -FeederFile C:\path\booking-feeder.csv `
  -RpsPerNode 100 `
  -DurationSeconds 300 `
  -InjectionMode constant-users-per-sec `
  -PollingTimeoutSeconds 300 `
  -CollectReports
```

스크립트는 주입 방식별 예상 사용자 수만큼 feeder 행을 VM별로 연속 분할하고 `manifest.csv`에 기준·목표 RPS, 주입 방식, 행 범위를 기록한다. URL/feeder/manifest 오류는 exit 2, 원격 노드 실패나 SLO/중복 성공 검출은 exit 1이다.

결과는 `distributed-results-booking` 아래에 모이며, `booking-summary.json`과 `booking-results-merged.csv`를 확인한다. 실제 smoke와 단계별 증분 부하는 운영 URL, performanceId, feeder, VM별 RPS를 사람이 확인한 뒤 별도로 실행한다.

## 정합성·Core 보호 증명 시나리오

신규 시나리오는 아래 순서로 실행한다. Java 클래스명은 숫자로 시작할 수 없으므로 실행 클래스에는 숫자를 붙이지 않고, Gatling 시나리오 이름에 `01`~`06` 순서를 유지한다.

| 순서 | Simulation | 증명 대상 |
| --- | --- | --- |
| 01 | `SmokeSimulation` | 좌석 조회부터 주문 조회까지 실제 예매 계약이 정상인지 확인 |
| 02 | `HotSeatConcurrencySimulation` | 같은 좌석에 동시 요청해 선택 성공 1건, 주문 성공 1건, 나머지 정상 거부인지 확인 |
| 03 | `CoreAdmissionCapacitySimulation` | Queue 없이 Core의 안전한 초당 입장 사용자 수 측정 |
| 04 | `CoreActiveUsersClosedSimulation` | Closed Model로 Core가 안정적으로 유지할 수 있는 동시 활성 사용자 상한 측정 |
| 05 | `CoreSpikeSimulation` | 기준 부하에서 5초 안에 최고 부하로 상승한 뒤 유지·회복하는지 측정 |
| 06 | `QueueProtectsCoreSimulation` | 외부 유입과 Queue 통과 후 Core 실제 진입을 분리해 보호 효과 측정 |

Smoke, Core 용량, Core Active Users Closed, Spike, Queue 보호 시나리오는 회원마다 서로 다른 `seatId`가 필요하다. Hot Seat는 반대로 모든 feeder 행이 같은 `seatId`를 사용해야 한다. 모든 시나리오는 회원별로 고유한 access token을 사용한다. Queue 보호 시나리오는 `enter` 응답으로 admission token을 받으므로 feeder의 `admissionToken`을 비워 둔다.

### Core 안전 입장률 측정

`CoreAdmissionCapacitySimulation`은 Open Model로 한 실행에 하나의 고정 입장률만 사용한다. 10, 25, 50, 100, 200, 300 users/sec를 각각 별도 실행해야 앞 단계의 주문 데이터, GC, DB lock이 다음 단계 결과를 오염시키지 않는다.

```powershell
.\gradlew.bat -p load-tests\gatling gatlingRun `
  --simulation com.ticket.loadtest.simulation.CoreAdmissionCapacitySimulation `
  -DcoreBaseUrl=https://api.example.com `
  -DperformanceId=1 `
  -DbookingFeederFile=C:\path\booking-feeder.csv `
  -DbookingScenario=CORE_ADMISSION_CAPACITY `
  -DinjectionMode=constant-users-per-sec `
  -DusersPerSecond=300 `
  -DdurationSeconds=300 `
  -DresultFile=build\reports\core-capacity-300.csv
```

`external arrival`과 `core admitted`는 Gatling의 별도 dummy 지표다. Queue 없는 Core 테스트에서는 두 시계열이 거의 같아야 하고, Queue 보호 테스트에서는 외부 유입과 Core 진입의 차이가 보여야 한다.

### Core 동시 사용자 상한

`CoreActiveUsersClosedSimulation`은 Closed Model이다. `users`만큼의 동시 사용자를 30초 동안 올린 뒤 `durationSeconds` 동안 유지하며, 각 사용자가 좌석 조회 → 좌석 선점 → 주문 생성을 마치면 Gatling이 즉시 새 사용자를 보충한다.

이 테스트가 필요한 이유는 Open Model의 안전 입장률과 Closed Model의 동시 사용자 상한이 서로 다른 한계이기 때문이다. Open Model은 외부 도착률을 고정해 과부하를 그대로 드러내고, Closed Model은 느려진 사용자를 새 사용자로 보충하지 않으므로 처리량 저하가 가려질 수 있다. 따라서 Closed 결과를 Queue 입장률로 사용하면 안 된다. 50, 100, 200, 300명처럼 단계별로 실행하면서 API p95·p99, 5xx·timeout, DB Pool·Lock뿐 아니라 `core flow completed` 성공 완료 흐름 수/초가 함께 유지되는지 확인한다.

Closed Model은 실행 중 사용자를 계속 교체하므로 `bookingFeederRows`가 `users`와 별도로 필요하다. 피더는 순환시키지 않으며 모든 행의 회원, access token, seatId, admission token은 고유해야 한다. 필요한 행 수는 응답 시간이 빨라질수록 증가하므로 예상 완료 흐름 수보다 여유 있게 준비하고, 부족하면 테스트를 실패시켜 정합성 오염을 막는다.

```powershell
.\run-distributed-booking.ps1 `
  -Simulation com.ticket.loadtest.simulation.CoreActiveUsersClosedSimulation `
  -CoreBaseUrl https://api.example.com `
  -PerformanceId 1 `
  -FeederFile C:\path\booking-feeder.csv `
  -ConcurrentUsersPerNode 100 `
  -FeederRowsPerNode 10000 `
  -DurationSeconds 300 `
  -CollectReports
```

위 예시는 3개 노드라면 Core 동시 사용자 300명을 유지한다. `FeederRowsPerNode`는 각 노드에 배정할 고유 사용자·좌석 행 수이고, `ConcurrentUsersPerNode`는 각 노드가 동시에 유지할 사용자 수다.

### Spike와 Queue 보호 비교

`CoreSpikeSimulation`은 30초 기준 부하 → 5초 상승 → 최고 부하 유지 → 5초 하강 → 30초 회복 순서다. `usersPerSecond`는 기준 부하, `targetUsersPerSecond`는 최고 부하, `durationSeconds`는 최고 부하 유지 시간이다.

```powershell
.\run-distributed-booking.ps1 `
  -Simulation com.ticket.loadtest.simulation.CoreSpikeSimulation `
  -CoreBaseUrl https://api.example.com `
  -PerformanceId 1 `
  -FeederFile C:\path\booking-feeder.csv `
  -RpsPerNode 34 `
  -TargetRpsPerNode 667 `
  -DurationSeconds 60 `
  -InjectionMode spike `
  -CollectReports
```

위 예시는 3개 노드에서 합계 약 100 users/sec → 2,000 users/sec를 만든다. 같은 고정 외부 유입률을 비교할 때는 Queue 미적용 기준으로 `CoreAdmissionCapacitySimulation`, Queue 적용 기준으로 `QueueProtectsCoreSimulation`을 실행한다. Spike 비교는 Queue 보호 시나리오에도 `-InjectionMode spike`와 같은 기준·최고 RPS를 적용한다.

Queue 보호 시나리오의 `pollingTimeoutSeconds`는 최소한 `외부 유입 사용자 수 / Queue 입장률`보다 길어야 한다. 예를 들어 120,000명이 들어오고 Core 입장률이 300명/초이면 대기열 소진에만 약 400초가 필요하므로 300초 timeout은 정상 대기 사용자를 실패로 오판한다.

### 정합성 최종 판정

Gatling 결과만으로는 응답 timeout 직전에 DB commit된 주문을 놓칠 수 있다. 따라서 Hot Seat 실행 후에는 반드시 테스트 DB를 조회해 대상 좌석의 활성 주문이 1건인지 확인한다.

```sql
SELECT os.performance_seat_id, COUNT(DISTINCT o.id) AS active_order_count
FROM order_seats os
JOIN orders o ON o.id = os.order_id
WHERE o.performance_id = :performance_id
  AND os.performance_seat_id = :performance_seat_id
  AND o.status IN ('PENDING', 'CONFIRMED')
GROUP BY os.performance_seat_id
HAVING COUNT(DISTINCT o.id) > 1;
```

조회 결과가 0행이어야 한다. 현재 시스템의 주문 생성 결과는 `PENDING`이므로 이 테스트가 직접 증명하는 범위는 중복 선점·중복 활성 주문이 없다는 것까지다. 결제 확정 기능이 연결되기 전에는 이를 중복 판매 방지 증거로 과장하지 않는다.
## Booking 증거 파일과 합격 조건

새 Booking Proof 시나리오는 Gatling HTML의 요청 통계만으로 합격시키지 않는다. 다음 파일을 함께 만든다.

- `booking-results.csv`: 시작한 각 회원의 최종 결과를 정확히 한 줄씩 기록한다.
- `booking-evidence.json`: 시작 수, 종료 결과 수, 누락 수, Queue timeout 비율, 관측된 최대 Core 입장률을 기록한다.
- `booking-admissions.csv`: Queue 통과 후 실제 Core 좌석 상태 흐름에 진입한 수를 초 단위로 기록한다.
- `booking-db-audit.json`: 클라이언트 성공 주문 수와 DB 주문 수, 중복 좌석 주문, 활성 중복 선점, 좌석 없는 주문 등을 조회한 결과다.

Console에서는 `Queue timeout 허용률`, `Core 안전 입장률`, `입장률 측정 오차 허용`을 입력한다. Queue Protects Core의 기본 안전 입장률은 300명/초이고, 분산 실행은 노드별 값이 아니라 모든 노드의 `booking-admissions.csv`를 합산한 `booking-admissions-global.csv`로 판정한다.

DB 감사를 켜려면 Console 또는 Gatling 프로세스에 아래 환경 변수를 설정해야 한다. 비밀번호는 명령행이나 결과 파일에 기록하지 않는다.

```text
BOOKING_AUDIT_DB_URL
BOOKING_AUDIT_DB_USERNAME
BOOKING_AUDIT_DB_PASSWORD
# 선택: BOOKING_AUDIT_DB_DRIVER (기본 oracle.jdbc.OracleDriver)
```

DB 감사 대상 공연은 테스트 전에 주문 이력이 없는 전용 `performanceId`여야 한다. 감사 쿼리가 공연 전체 주문을 조회하므로 기존 주문이 섞이면 현재 실행의 성공 수와 비교할 수 없고 의도적으로 실패한다.

합격은 다음을 모두 만족할 때만 성립한다: 시작 사용자 수와 종료 결과 수가 같음, 회원별 종료 결과가 한 건임, Queue timeout 비율이 허용치 이하임, Queue 적용 시 전역 Core 입장률이 상한 이내임, 클라이언트 성공 주문 수와 DB 주문 수가 같음, DB 중복·누락 정합성 위반이 0건임. `booking-admissions.csv`는 부하 발생기에서 Core 흐름 진입 직전에 관측한 값이므로 서버 내부 수신 메트릭과는 구분해서 해석한다.
