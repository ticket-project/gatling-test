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
- 대상 API 기본값: legacy 계열은 `http://52.237.82.8:18090/legacy-queue`, CDN public state는 `https://queue.oneticket.site`
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
| `ticket-open-flow` | `http://localhost:8080` |
| `hold-race` | `http://localhost:8080` |
| `ticket-server-capacity` | `http://localhost:8080` |

## EC2 분산 실행

EC2 분산 실행은 `queue-join-only`, `legacy-queue-status`, `cdn-public-state`에서 지원한다.

`queue-join-only` 분산 실행은 인증이 필요하므로 `Token mode=토큰 파일/목록`, `Access Token 준비 방식=파일 자동 생성`을 권장한다. 이 경우 각 EC2 노드가 실행 전에 자기 노드용 access token 파일을 만들고, 노드별 memberId 범위가 겹치지 않도록 시작 ID를 자동으로 밀어 쓴다. `테스트 JWT 생성` 모드도 사용할 수 있지만 JWT 생성 비용이 Gatling 부하 발생기 CPU에 들어간다.

join 분산 스크립트는 기본적으로 로컬 `gatling-test` 프로젝트를 각 EC2의 `~/gatling-test`로 압축 동기화한 뒤 실행한다. 따라서 콘솔에서 방금 수정한 simulation이나 token generator가 EC2에도 반영된다. 수동 실행에서 동기화를 건너뛰려면 `run-distributed-gatling-join.ps1`에 `-SkipSyncProject`를 지정한다.

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
com.ticket.loadtest.simulation.TicketOpenFlowSimulation
com.ticket.loadtest.simulation.HoldRaceSimulation
com.ticket.loadtest.simulation.TicketServerCapacitySimulation
```

`CdnPublicStateSimulation`은 `https://queue.oneticket.site`를 기본 대상 API로 사용하고, 아래 public state API만 반복 조회한다.

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
