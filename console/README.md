# Ticket Gatling Console

기준일: 2026-05-24

로컬 브라우저에서 `gatling-test` 저장소의 Gatling 부하 테스트를 실행하고, 생성된 HTML 리포트로 이동하기 위한 개발용 콘솔이다. 운영 배포 대상이 아니며, 자동화된 테스트 러너가 아니다.

## 안전 주의

- 이 프로젝트는 실제 Gatling 부하 테스트를 실행할 수 있다.
- 사용자가 명시적으로 요청하지 않으면 `run`, Gatling simulation, 대량 사용자 테스트를 실행하지 않는다.
- 이 폴더의 `AGENTS.md` 기준으로 테스트 작성/실행을 자동으로 진행하지 않는다.
- 실행 전 대상 API, 사용자 수, 투입 시간, token mode를 반드시 확인한다.

## 빠른 맥락

- 실행 방식: Gradle `application` plugin
- 기본 콘솔 포트: `9090`
- UI 파일: `src/main/resources/static/index.html`
- 대상 API 기본값: Queue join은 Cloudflare를 거치지 않는 `https://queue.oneticket.site`, legacy 계열은 `http://52.237.82.8:18090/legacy-queue`, CDN public state는 실행 시 별도 Cloudflare state endpoint 확인 필요
- 기본 Gatling 저장소 경로: `C:\Users\mn040\IdeaProjects\ticket-workspace\gatling-test`
- 실제 Gatling 프로젝트 위치: 이 저장소의 `load-tests/gatling`

## 역할

```text
Browser
  -> Ticket Gatling Console localhost:9090
  -> gatling-test 저장소의 Gradle wrapper 실행
  -> load-tests/gatling simulation 실행
  -> build/reports/gatling HTML report 노출
```

이 콘솔은 부하 테스트 설정 UI와 리포트 브라우징만 담당한다. Ticket Server 기능이나 Queue Server 기능을 직접 구현하지 않는다.

## 실행

전제:

- JDK 25
- 대상 API 서버가 선택한 시뮬레이션의 기본 URL에서 실행 중
- `gatling-test` 저장소에 `gradlew.bat`과 `load-tests/gatling`이 존재
- 자동 로그인 모드를 쓰는 경우 seed 테스트 회원이 존재

콘솔 실행:

```powershell
.\gradlew.bat run
```

브라우저 접속:

```text
http://localhost:9090
```

## 화면에서 설정하는 값

| 영역 | 설명 |
| --- | --- |
| 테스트 종류 | 대기열 진입, 예매 오픈 흐름, hold 경합, 티켓 서버 용량 |
| 대상 | Gatling 저장소 경로, 대상 API URL, 회차 ID, 좌석 ID |
| 부하 | 사용자 수, 투입 시간, 주입 방식 |
| 프로토콜 | Queue Join의 HTTP/2 사용 여부 |
| 인증 | 자동 로그인, 직접 token 입력, 테스트 JWT 생성 |
| 자동 로그인 | 계정 prefix, domain, password, start index, timeout |
| 테스트 JWT | issuer, member 시작 ID, role, TTL, secret |
| Admission Token | 합성 생성 또는 직접 입력, issuer, audience, secret, TTL |
| polling | 대기열 상태 조회 횟수와 간격 |

## 시뮬레이션별 대상 API 자동 설정

콘솔에서 테스트 종류를 바꾸면 대상 API 입력값이 아래 기본값으로 자동 변경된다.

| 테스트 종류 | 대상 API 기본값 |
| --- | --- |
| `queue-join-only` | `https://queue.oneticket.site` |
| `queue-enter` | `http://52.237.82.8:18090/legacy-queue` |
| `legacy-queue-status` | `http://52.237.82.8:18090/legacy-queue` |
| `cdn-public-state` | `https://queue.oneticket.site` |
| `booking-capacity` | 사용자가 입력한 Ticket/Core URL |
| `ticket-open-end-to-end` | 사용자가 입력한 Ticket/Core URL + Queue URL |
| `seat-contention` | 사용자가 입력한 Ticket/Core URL |
| `smoke` | `https://api.oneticket.site` |
| `hot-seat-concurrency` | `https://api.oneticket.site` |
| `core-admission-capacity` | `https://api.oneticket.site` |
| `core-active-users-closed` | `https://api.oneticket.site` |
| `core-spike` | `https://api.oneticket.site` |
| `queue-protects-core` | Core `https://api.oneticket.site` + Queue `https://queue.oneticket.site` |

`queue-join-only`의 `https://queue.oneticket.site`는 Queue origin Nginx를 직접 호출하며 Cloudflare를 거치지 않는다. `cdn-public-state`의 기본 URL은 입력 편의를 위한 값일 뿐이다. 현재 hostname이 DNS-only이면 CDN 테스트가 아니므로, 실행 전에 Cloudflare가 프록시하는 state endpoint로 바꾸고 `CF-Ray`, `CF-Cache-Status` 헤더를 확인한다.

## EC2 분산 실행

EC2 분산 실행은 `queue-join-only`, `legacy-queue-status`, `cdn-public-state`에서 지원한다.

`queue-join-only` 분산 실행은 인증이 필요하므로 `Token mode=토큰 파일/목록`, `Access Token 준비 방식=파일 자동 생성`을 권장한다. 이 경우 각 EC2 노드가 실행 전에 자기 노드용 access token 파일을 만들고, 노드별 memberId 범위가 겹치지 않도록 시작 ID를 자동으로 밀어 쓴다. `테스트 JWT 생성` 모드도 사용할 수 있지만 JWT 생성 비용이 Gatling 부하 발생기 CPU에 들어간다.

join 분산 스크립트는 기본적으로 로컬 `gatling-test` 프로젝트를 각 EC2의 `~/gatling-test`로 압축 동기화한 뒤 실행한다. 따라서 콘솔에서 방금 수정한 simulation이나 token generator가 EC2에도 반영된다. 수동 실행에서 동기화를 건너뛰려면 `run-distributed-gatling-join.ps1`에 `-SkipSyncProject`를 지정한다.

`queue-join-only`에서는 `예매 오픈` 빠른 설정 또는 `예매 오픈 패턴` 주입 방식을 선택할 수 있다. 입력하는 `초당 사용자 수 / 최고 RPS`는 분산 실행 시 **노드 1대당 최고 RPS**다. 콘솔은 단계별 부하와 회복 확인까지 포함한 노드별 토큰 수를 자동 계산한다.

예를 들어 최고 RPS를 10으로 지정하면 노드마다 본 부하 730명과 회복 확인 60명, 총 790개의 고유 access token이 필요하다. 분산 실행은 프로젝트 동기화와 사전 확인을 마친 뒤 약 2분 후의 동일한 예매 오픈 시각에 모든 노드를 최고 RPS로 시작시킨다.

## Token mode

| Mode | 동작 | 사용 조건 |
| --- | --- | --- |
| 자동 로그인 | Gatling 실행 전 seed 회원으로 로그인해 access token 준비 | seed 회원이 생성되어 있어야 함 |
| 직접 입력 | 토큰 파일 자동 생성, 기존 파일 사용, token 목록 붙여넣기 중 선택 | `/join` 큰 테스트는 파일 자동 생성 권장 |
| 테스트 JWT 생성 | 로그인 API 없이 Gatling이 서로 다른 `sub=memberId` JWT 생성 | 서버 `JWT_SECRET`과 같은 secret 입력 필요 |

큰 `/join` 테스트에서는 `Token mode=직접 입력`, `Access Token 준비 방식=파일 자동 생성`을 사용한다. 콘솔이 실행 전에 access token 파일을 먼저 만들고 Gatling에는 `-DaccessTokensFile=...`만 넘긴다. 따라서 `/join` HTTP 요청 시간에는 JWT 생성이 포함되지 않는다.

토큰 파일은 UTF-8 텍스트이고 JWT를 한 줄에 하나씩 둔다. 이미 만든 파일이 있으면 `기존 파일 사용`, 소량 확인이면 `토큰 목록 붙여넣기`를 선택한다.

콘솔 밖에서 미리 생성해야 할 때는 아래 명령을 사용할 수 있다.

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

테스트 JWT 생성 모드는 로컬 검증용이다. 운영 secret이나 실제 사용자 token을 문서나 로그에 남기지 않는다.

## Admission Token mode

티켓 서버 보호 API는 `X-Admission-Token`을 요구한다. 콘솔은 두 방식을 지원한다.

| Mode | 동작 | 사용 조건 |
| --- | --- | --- |
| 합성 생성 | Gatling이 `memberId + performanceId`로 admission token을 생성 | Ticket Server의 `ADMISSION_TOKEN_SECRET_KEY`, issuer, audience와 같아야 함 |
| 직접 입력 | Queue Server가 발급한 admission token 목록 사용 | 같은 순서의 access token과 member/performance가 일치해야 함 |

티켓 서버 용량 테스트는 Queue Server를 우회한다. 따라서 일반적으로 `Admission Token mode=합성 생성`을 사용하고, Ticket Server 설정과 같은 `Admission Secret`을 입력한다.

주문 생성까지 측정하려면 member가 DB에 존재해야 한다. 이 경우 `Access Token mode=자동 로그인` 또는 실제 seed 회원의 access token 직접 입력을 사용한다. `테스트 JWT 생성`은 DB 회원이 없어도 통과하는 조회/좌석 선택 범위 확인에는 쓸 수 있지만, 주문 생성 기준 TPS 측정에는 적합하지 않다.

## 구조

```text
gatling-test/console
├── src/main/java/com/ticket/gatling/console
│   ├── ConsoleApplication.java      # main, consolePort 처리
│   ├── ConsoleServer.java           # HTTP server, UI/report endpoint
│   ├── LoadTestRequest.java         # form 입력 파싱 후 요청 모델화
│   ├── LoadTestService.java         # Gatling 저장소 검증, Gatling 실행
│   ├── GatlingCommandBuilder.java   # Gradle/Gatling command 생성
│   ├── ReportRegistry.java          # 실행 결과 report directory 매핑
│   └── SimulationType.java          # 지원 simulation 목록
└── src/main/resources/static
    └── index.html                   # 로컬 콘솔 UI
```

## 대상 Gatling simulation

이 저장소의 `load-tests/gatling` 아래 simulation을 실행한다.

```text
com.ticket.loadtest.simulation.QueueJoinOnlySimulation
com.ticket.loadtest.simulation.QueueEnterSimulation
com.ticket.loadtest.simulation.LegacyQueueStatusSimulation
com.ticket.loadtest.simulation.CdnPublicStateSimulation
com.ticket.loadtest.simulation.BookingCapacitySimulation
com.ticket.loadtest.simulation.TicketOpenEndToEndSimulation
com.ticket.loadtest.simulation.SeatContentionSimulation
```

`CdnPublicStateSimulation`은 `https://queue.oneticket.site`를 기본 입력값으로 사용하고, 아래 public state API만 반복 조회한다. 기본 hostname이 DNS-only인 현재 구성에서는 origin 조회가 되므로, CDN 캐시를 검증할 때는 Cloudflare가 프록시하는 state endpoint를 명시해야 한다.

```text
GET /api/v1/queue/performances/{performanceId}/state
```

## 정적 확인

사용자가 부하 테스트 실행을 명시하지 않은 문서/코드 확인 작업에서는 아래처럼 정적 확인만 수행한다.

```powershell
rg -n "찾을_문구" .
```

실제 실행 검증은 사용자가 테스트 규모와 대상 서버를 확인한 뒤에만 수행한다.

## AI 작업 메모

- 이 프로젝트에서 `test` 또는 `run`을 자동으로 실행하지 않는다.
- 부하 테스트 관련 변경은 이 저장소의 `load-tests/gatling` simulation과 함께 읽는다.
- 기본 Gatling 저장소 경로가 현재 작업 공간과 다를 수 있으므로 실행 전 UI 입력값을 확인한다.
- 리포트는 이 저장소의 `load-tests/gatling/build/reports/gatling` 아래에 생성된다.

## Booking 예매 부하 콘솔 사용

Console의 `테스트 종류`에서 다음 여섯 시나리오를 직접 선택할 수 있다.

| 번호 | Console 선택값 | 부하 모델 | 검증 목적 |
| --- | --- | --- | --- |
| 01 | `01 Smoke` | 1명 즉시 실행 | 좌석 조회 → 좌석 선점 → 주문 생성 → 주문 조회 계약 확인 |
| 02 | `02 Hot Seat Concurrency` | N명 동시 시작 | 같은 좌석의 선점 성공 1건, 주문 성공 1건, 나머지 정상 거절, 중복 0건 확인 |
| 03 | `03 Core Admission Capacity (Open)` | Open Model | 초당 신규 사용자 입장률을 단계별로 올려 Core의 안전 입장률 결정 |
| 04 | `04 Core Active Users (Closed)` | Closed Model | Core 안에서 동시에 활동하는 사용자 수의 안전 상한 결정 |
| 05 | `05 Core Spike` | Open Model Spike | 기준 RPS에서 5초 동안 최고 RPS로 급증한 뒤 회복하는지 확인 |
| 06 | `06 Queue Protects Core` | Open Model | 외부 유입은 높게 유지하면서 Queue가 Core 입장률을 보호하는지 확인 |

### 실행 순서

1. `gatling-test/console`에서 `..\gradlew.bat run`을 실행한다.
2. 브라우저에서 `http://localhost:9090`을 연다.
3. `테스트 종류`에서 01~06 중 하나를 선택한다. 선택과 동시에 해당 시나리오의 권장 부하 모델과 기본값이 채워진다.
4. `Ticket/Core URL`, 필요하면 `Queue URL`, `performanceId`, `Booking Feeder CSV`를 입력한다.
5. 화면의 예상 사용자 수보다 많은 feeder 행이 준비됐는지 확인한다.
6. 대상 서버와 Datadog 대시보드를 확인한 뒤 `실행`을 누른다.
7. 완료 후 Console의 Gatling 리포트와 `booking-summary.json`을 함께 확인한다.

### 시나리오별 입력

- `01 Smoke`: 기본값은 사용자 1명, `at-once-users`다. 기능 계약을 먼저 확인하는 용도이므로 이 단계에서 부하를 높이지 않는다.
- `02 Hot Seat Concurrency`: `사용자 수`를 100 또는 1,000으로 지정하고, feeder의 모든 행에 같은 `seatId`를 넣는다. `rendezVous`가 한 JVM 안에서만 동기화되므로 반드시 로컬 실행을 사용한다. Console은 분산 실행을 거부한다.
- `03 Core Admission Capacity`: `constant-users-per-sec` 또는 `ramp-users-per-sec`를 사용한다. 10 → 25 → 50 → 100 → 200 → 300처럼 실행을 나누고, 각 실행의 API p95/p99·5xx·timeout·DB/CPU 지표를 비교한다.
- `04 Core Active Users (Closed)`: 주입 방식을 `동시 사용자 유지 (Closed Model)`로 사용한다. `사용자 수`는 동시에 유지할 Core 사용자 수이고, `Closed Model 피더 행 수`는 노드마다 소비할 수 있는 고유 CSV 행 수다. 피더는 순환하지 않으므로 이 값은 사용자 수 이상이어야 한다.
- `05 Core Spike`: `초당 사용자 수`가 기준 RPS, `최고 RPS`가 spike RPS, `투입 시간`이 최고 RPS 유지 시간이다. 실행 패턴은 기준 30초 → 5초 ramp-up → 최고 RPS 유지 → 5초 ramp-down → 기준 30초다.
- `06 Queue Protects Core`: `초당 사용자 수`는 Queue로 들어오는 외부 유입률이다. 사용자는 join → state polling → enter로 admission token을 얻은 뒤에만 Core 흐름을 실행한다. Queue/Core URL과 polling timeout을 모두 입력한다. 2,000명/초를 60초 동안 받고 Core를 300명/초로 제한하면 마지막 사용자는 약 340초 이상 기다릴 수 있으므로 기본 timeout은 600초로 설정한다.

### Feeder 규칙

`Booking Feeder CSV`는 `memberId,accessToken,seatId,admissionToken` 4컬럼의 UTF-8 no BOM 파일이다.

- Smoke·Core Capacity·Closed·Spike는 사용자별로 고유한 `seatId`가 필요하다.
- Hot Seat는 모든 행에 같은 `seatId`가 필요하다.
- Queue Protects Core는 `enter` 응답에서 admission token을 받으므로 feeder의 `admissionToken`을 비워 둔다.
- Open Model의 예상 행 수는 주입 패턴의 전체 사용자 수이며, 분산 실행에서는 모든 노드의 필요량을 합산한다.
- Closed Model의 feeder 행 수는 `Closed Model 피더 행 수 × 노드 수`로 검증한다.

### 리포트 해석

분산 booking 결과는 `distributed-results-booking` 아래에 생성된다.

- `manifest.csv`: VM별 rowStart/rowEnd, nodeRps, globalRps.
- `booking-results-merged.csv`: 모든 VM의 성공/거절/타임아웃 결과 병합본.
- `booking-summary.json`: 성공 수, 비즈니스 거절 수, 기술 실패율, 중복 좌석 성공, 중복 orderKey.
- Gatling HTML report: API별 응답 시간, p95/p99, KO 비율.

Hot Seat에서 비즈니스 거절은 실패가 아니라 기대 결과다. 합격 조건은 선점 성공 1건, 주문 성공 1건, 나머지 비즈니스 거절, 기술 실패 0건, 중복 좌석 성공 0건, 중복 orderKey 0건이다.

### 운영 주의

Booking 시나리오는 실제 좌석 선택과 주문 생성을 호출한다. 운영 환경에 실사용자가 없더라도 테스트 전에는 performanceId, 좌석 범위, feeder token 만료 시간, Queue/Ticket 배포 버전을 확인한다. Datadog 대시보드나 로그 확인은 부하 실행과 별도 절차로 진행한다.
## 실행 환경 메타데이터

콘솔은 부하 테스트 시작 직전에 Datadog을 조회하고, 그 시점의 실행 대상 환경 스냅샷을 결과에 고정합니다. 메타데이터 수집 실패는 부하 테스트 자체를 중단시키지 않습니다.

- Gatling HTML run description: `runId`, Queue/Core별 활성 인스턴스 수, 커밋, CPU/RAM, Docker 제한, Xmx, admission 검증 여부의 짧은 요약
- 결과 폴더의 `run-metadata.json`: Queue/Core 대상 아래 활성 호스트별 Docker, JVM, Tomcat, Hikari, Oracle, Redis, admission 검증 여부
- 콘솔 실행 결과: 대상과 호스트별 환경정보 표, JSON 복사, JSON 다운로드

Datadog 자격증명은 다음 순서로 자동 해석합니다.

1. 콘솔 서버 프로세스의 `DATADOG_API_KEY`, `DATADOG_APP_KEY`, `DATADOG_SITE`
2. 환경변수에 없는 항목은 Codex의 `~/.codex/config.toml`에 있는 `[mcp_servers.datadog.env]`

`CODEX_HOME`이 설정돼 있으면 `~/.codex` 대신 그 디렉터리의 `config.toml`을 읽습니다. 따라서 Datadog MCP가 이미 설정된 개발 환경에서는 콘솔을 Gradle이나 IDE로 바로 실행해도 같은 API 키를 사용합니다. 명시적인 환경변수는 MCP 설정보다 우선합니다.

```text
DATADOG_API_KEY=<optional override>
DATADOG_APP_KEY=<optional override>
DATADOG_SITE=us5.datadoghq.com
```

키 값은 저장소로 복사하지 않으며 브라우저 폼, Gatling 인자, 결과 JSON에도 기록하지 않습니다. 콘솔 로그에 출력되는 `Authorization: Bearer ...` 값도 저장 전에 마스킹합니다.

선택자는 화면에서 입력받지 않습니다. 테스트 종류에 따라 콘솔이 다음 대상을 자동 선택합니다.

- Queue Join Only, Queue Enter, Legacy Queue Status, CDN Public State: Queue만 조회
- Booking Capacity, Seat Contention: Core만 조회
- Ticket Open End to End: Queue와 Core를 모두 조회

현재 Datadog에서 확인된 자동 프로필은 다음과 같습니다.

```text
Queue: env=prod, service=ticket-queue, metric prefix=ticket_queue, container=ticket-queue
Core:  env=prod, service=ticket-be,    metric prefix=ticket,       container=ticket-be
```

호스트 식별에는 모든 대상 애플리케이션에 공통으로 존재하는 `<prefix>.jvm_info`를 사용합니다. 조회 범위는 최근 30분이지만, 캡처 시각에서 5분 이내이고 가장 최신 호스트와의 관측 시각 차이가 90초 이내인 호스트만 활성 대상으로 판정합니다. 이 기준 때문에 롤링 배포나 scale-in 직후의 종료된 호스트는 결과에서 빠지고, 동시에 살아 있는 scale-out 호스트는 모두 `targets[].instances[]`에 보존됩니다. 일시적인 네트워크 오류, 429/5xx, 잘못된 응답, 빈 identity 응답은 최대 3번 재시도합니다.

활성 호스트가 정해지면 아래 메트릭을 그 호스트들로 제한해 조회합니다.

```text
system.cpu.num_cores
system.mem.total
container.cpu.limit
container.memory.limit
<prefix>.jvm_gc_max_data_size_bytes
fallback: <prefix>.jvm_memory_max_bytes
<prefix>.tomcat_threads_config_max_threads
<prefix>.tomcat_connections_config_max_connections
Core only: ticket.hikaricp_connections_max
redis.mem.maxmemory
```

`run-metadata.json`의 스키마 버전은 4입니다. 각 대상은 `targets[]`, 활성 호스트는 그 아래 `instances[]`로 분리되며 `replicaCountObserved`, 관측 시각, host, container ID, 이미지 ID와 설정을 각각 기록합니다. 호스트별 커밋이나 리소스 값이 다르면 `capture.warnings`와 run description의 `mixed` 값으로 표시합니다.

값이 없는 경우 단순히 `null`로만 두지 않고 인스턴스의 `evidence`에 원인을 함께 기록합니다.

- `observed`: Datadog에서 값 확인
- `explicit_unlimited`: Redis `maxmemory=0`처럼 명시적인 무제한
- `not_reported`: 현재 시계열이나 태그에 값이 없음
- `not_explicit`: JVM Xms처럼 현재 telemetry에서 명시값을 얻을 수 없음
- `unsupported_by_datadog`: Oracle 사양처럼 현재 연동에서 수집할 수 없음

일부 필드가 제공되지 않아도 대상 host를 정상 식별했다면 상태는 `captured`입니다. Queue/Core 중 일부 대상만 실패하면 `partial`, 모든 대상의 identity 수집이 실패한 경우에만 `failed`입니다.

커밋 자동 수집에는 배포 시 `DD_VERSION=<commit>` 또는 `git.commit.sha:<commit>` 태그가 필요합니다. Java 버전은 `java_version` 태그를 우선 사용하고 `jvm_info`의 버전 태그로 보완합니다. Admission 상태는 `admission_enforcement` 태그가 있으면 기록합니다.

Oracle 인스턴스 사양과 네트워크 위치는 현재 Datadog에 식별 가능한 인프라 메트릭이 없어 `null`과 `unsupported_by_datadog`로 남깁니다. Redis 위치는 애플리케이션 host에서 private endpoint가 관측됐다는 수준으로만 기록하며, Redis가 같은 장비에 있다고 단정하지 않습니다. 비밀번호, 토큰, JDBC URL, 내부 IP는 메타데이터에서 제외합니다.

현재 애플리케이션 메트릭에는 `container_id`나 pod 태그가 없으므로 인스턴스 구분 단위는 host입니다. 서로 다른 host로 scale-out하면 모두 정확히 기록되지만, 한 host 안에 여러 JVM 컨테이너를 띄우면 개별 JVM 값을 분리할 수 없습니다. 이 제한은 JSON의 `datadog.granularity=host`와 `granularityNote`에 명시됩니다. 부하 실행 중에 새로 생성되거나 제거되는 autoscaling 인스턴스까지 추적하려면 실행 전 스냅샷만으로는 부족하므로 추후 post-run 스냅샷과 실행 구간 합집합 수집이 필요합니다.
