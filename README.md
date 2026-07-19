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

스크립트는 `ceil(RpsPerNode * DurationSeconds)` 행을 VM별로 연속 분할하고 `manifest.csv`에 `nodeIndex,totalNodes,globalRps,nodeRps,rowStart,rowEnd`를 기록한다. URL/feeder/manifest 오류는 exit 2, 원격 노드 실패나 SLO/중복 성공 검출은 exit 1이다.

결과는 `distributed-results-booking` 아래에 모이며, `booking-summary.json`과 `booking-results-merged.csv`를 확인한다. 실제 smoke와 단계별 증분 부하는 운영 URL, performanceId, feeder, VM별 RPS를 사람이 확인한 뒤 별도로 실행한다.