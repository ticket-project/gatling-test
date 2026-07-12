# Booking Capacity Simulation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 예매 오픈·좌석 경합·Ticket 용량 시나리오를 제거하고, Queue부터 PENDING 주문까지 3VM에서 분산 실행할 수 있는 세 시나리오로 교체한다.

**Architecture:** 공통 feeder 검증기와 결과 기록기가 사용자별 토큰·좌석 매핑을 보장한다. 새 PowerShell 실행기가 feeder를 3개 노드에 분할하고, 콘솔이 Queue/Ticket URL과 분산 부하를 명시적으로 받아 로컬·원격 Gatling 명령을 생성한다.

**Tech Stack:** Java 21, Gatling Java DSL 3.15, Java 25 console, PowerShell, Gradle

**Spec:** `docs/superpowers/specs/2026-07-12-booking-capacity-simulation-design.md`

**Verification constraint:** `console/AGENTS.md`에 따라 실제 `test`, `run`, `gatlingRun`은 실행하지 않는다. 승인된 범위인 `testClasses`, `gatlingClasses`, `classes`, PowerShell parser, 정적 계약 검사만 수행한다. 테스트 소스는 작성하되 컴파일까지만 검증한다.

---

## Chunk 1: Gatling feeder와 새 시나리오

### Task 1: feeder와 결과 기록 공통 컴포넌트

**Files:**
- Create: `load-tests/gatling/src/gatling/java/com/ticket/loadtest/BookingFeeder.java`
- Create: `load-tests/gatling/src/gatling/java/com/ticket/loadtest/BookingResultRecorder.java`
- Modify: `load-tests/gatling/src/gatling/java/com/ticket/loadtest/LoadTestConfig.java`
- Modify: `load-tests/gatling/src/main/java/com/ticket/loadtest/LoadTestTokens.java`
- Modify: `load-tests/gatling/src/test/java/com/ticket/loadtest/LoadTestConfigTest.java`

- [ ] **Step 1: feeder 계약 테스트 소스 작성**

UTF-8 BOM 없음, 정확한 `memberId,accessToken,seatId,admissionToken` 4열, 양의 ID, 빈 token 금지, member 중복 금지, 시나리오별 seat/admission 중복 규칙, 예상 사용자보다 적은 행, 쉼표·개행 포함 값, EOF를 검증한다. access token subject와 memberId, admission token subject/performanceId 일치도 payload claim 기준으로 검사한다.

- [ ] **Step 2: `BookingFeeder` 구현**

`BookingRow(long memberId, String accessToken, long seatId, String admissionToken)` 목록을 반환하고 검증 실패 시 token 원문 없는 예외를 던진다. feeder는 queue 모드로 소비하며 EOF는 `feeder-exhausted` 실패가 된다. `LoadTestTokens`에는 서명 검증이 아닌 사전 정합성 확인용 claim reader만 추가한다.

- [ ] **Step 3: `BookingResultRecorder` 구현**

`scenario,nodeIndex,memberId,seatId,orderKey,httpStatus,result,timestamp`만 UTF-8 CSV로 기록한다. access/admission token은 저장하지 않는다. 동시 append는 JVM 내 lock으로 직렬화한다.

- [ ] **Step 4: 설정 연결 및 컴파일**

`bookingFeederFile`, `bookingScenario`, `nodeIndex`, `resultFile`, `pollingTimeoutSeconds`를 `LoadTestConfig`에 추가한다.

Run: `.\gradlew.bat -p load-tests/gatling testClasses gatlingClasses`

Expected: BUILD SUCCESSFUL, 테스트와 Gatling 소스 컴파일 완료, 테스트 실행 없음.

- [ ] **Step 5: 커밋**

Commit: `feat: add booking load test feeder`

### Task 2: `BookingCapacitySimulation`

**Files:**
- Create: `load-tests/gatling/src/gatling/java/com/ticket/loadtest/simulation/BookingCapacitySimulation.java`
- Replace: `load-tests/gatling/src/test/java/com/ticket/loadtest/simulation/TicketServerCapacitySimulationTest.java` with `BookingCapacitySimulationTest.java`

- [ ] **Step 1: 구조 테스트 소스 작성**

feeder → seat status 200 → select 200 → order 201 → orderKey 헤더/바디 동일성 → 최대 5초, 200ms 간격 order GET → PENDING 흐름을 고정한다.

- [ ] **Step 2: 최소 시나리오 구현**

feeder의 access/admission token을 헤더로 사용한다. 모든 `4xx`는 기술 오류로 처리하고 요청별 Ticket p99 3초, 전체 기술 오류율 1% 미만 assertion을 둔다.

- [ ] **Step 3: 컴파일 및 커밋**

Run: `.\gradlew.bat -p load-tests/gatling testClasses gatlingClasses`

Expected: BUILD SUCCESSFUL.

Commit: `feat: add booking capacity simulation`

### Task 3: `TicketOpenEndToEndSimulation`

**Files:**
- Create: `load-tests/gatling/src/gatling/java/com/ticket/loadtest/simulation/TicketOpenEndToEndSimulation.java`
- Replace: `load-tests/gatling/src/test/java/com/ticket/loadtest/simulation/TicketOpenFlowSimulationTest.java` with `TicketOpenEndToEndSimulationTest.java`

- [ ] **Step 1: Queue polling 계약 테스트 소스 작성**

join의 queueToken/shardId/localSeq, state의 serving map, `refreshAfterMs`와 min/max+jitter, serving key 누락·값 감소 시 재polling, `pollingTimeoutSeconds=300` 기본값, timeout의 `queue-timeout` 분리와 Ticket 흐름 미호출을 고정한다.

- [ ] **Step 2: 전체 흐름 구현**

serving[shardId] >= localSeq일 때만 enter를 호출하고 `data.admissionToken`을 저장한다. 이후 Task 2와 같은 Ticket 흐름과 orderKey/PENDING 재조회 계약을 사용한다. Queue 요청별 p99 2초, Ticket 요청별 p99 3초를 둔다.

- [ ] **Step 3: 컴파일 및 커밋**

Run: `.\gradlew.bat -p load-tests/gatling testClasses gatlingClasses`

Expected: BUILD SUCCESSFUL.

Commit: `feat: add end to end booking simulation`

### Task 4: `SeatContentionSimulation`과 기존 소스 제거

**Files:**
- Create: `load-tests/gatling/src/gatling/java/com/ticket/loadtest/simulation/SeatContentionSimulation.java`
- Create: `load-tests/gatling/src/test/java/com/ticket/loadtest/simulation/SeatContentionSimulationTest.java`
- Delete: `load-tests/gatling/src/gatling/java/com/ticket/loadtest/simulation/TicketOpenFlowSimulation.java`
- Delete: `load-tests/gatling/src/gatling/java/com/ticket/loadtest/simulation/HoldRaceSimulation.java`
- Delete: `load-tests/gatling/src/gatling/java/com/ticket/loadtest/simulation/TicketServerCapacitySimulation.java`

- [ ] **Step 1: 경합 계약 테스트 소스 작성**

select `E4001`은 기록 후 주문 경쟁을 계속한다. 주문 `E5000`, `E5001`, `E6000`, `E6003`만 `business-rejected`로 허용한다. `201`은 result CSV에 기록하고 order GET/PENDING을 확인한다. 그 외 `4xx`, 모든 `5xx`, timeout은 기술 오류다.

- [ ] **Step 2: 경합 시나리오 구현**

중복 seat feeder를 사용하고 성공/비즈니스 거절/기술 오류를 분리 기록한다.

- [ ] **Step 3: 기존 세 클래스 제거 확인**

Run: `Get-ChildItem load-tests\gatling\src -Recurse -File | Select-String -Pattern 'TicketOpenFlowSimulation|HoldRaceSimulation|TicketServerCapacitySimulation'`

Expected: 실행·테스트 소스에서 결과 없음.

- [ ] **Step 4: 컴파일 및 커밋**

Run: `.\gradlew.bat -p load-tests/gatling testClasses gatlingClasses`

Expected: BUILD SUCCESSFUL.

Commit: `feat: add seat contention simulation`

## Chunk 2: 3VM 분산 실행과 결과 집계

### Task 5: `run-distributed-booking.ps1`

**Files:**
- Create: `run-distributed-booking.ps1`
- Create: `console/src/test/java/com/ticket/gatling/console/DistributedBookingScriptContractTest.java`

- [ ] **Step 1: 실행기 정적 계약 테스트 소스 작성**

필수 인자 `Hosts`, `KeyPath`, `RemoteProjectDir`, `Simulation`, `CoreBaseUrl`, `QueueBaseUrl`, `PerformanceId`, `FeederFile`, `RpsPerNode`, `DurationSeconds`, `InjectionMode`, polling 설정, report/failure collection을 고정한다. JWT/admission secret 인자가 없어야 한다.

- [ ] **Step 2: feeder 검증·분할 함수 구현**

Task 1과 동일한 CSV 계약을 PowerShell preflight에서 검사한다. 실행 전 CSV/URL/manifest 오류는 exit 2다. `ceil(RpsPerNode × DurationSeconds)` 행을 노드별로 연속 분할하고 globalRps 합과 row 범위를 검증한다. runtime feeder exhaustion은 exit 1이다.

- [ ] **Step 3: manifest와 3VM 동시 실행 구현**

manifest에 `nodeIndex,totalNodes,globalRps,nodeRps,rowStart,rowEnd`를 기록한다. 기존 SSH batch/SCP/project sync/preflight 패턴을 재사용하고 모든 host를 비동기로 시작한 뒤 전부 join한다. 원격 feeder는 소유자 읽기 전용으로 설정한다.

- [ ] **Step 4: 보안·회수·정리 구현**

원격 Gradle에는 URL, performanceId, feeder, injection mode, polling, node/result 경로만 전달한다. 로그는 run id/node/range/UTC와 로컬 시각만 남긴다. HTML report와 result CSV를 회수한 뒤 원격 feeder를 삭제한다. 실패 본문은 token/Authorization을 마스킹한다.

- [ ] **Step 5: 결과 통합과 SLO 판정 구현**

노드별 CSV를 합쳐 seatId/orderKey 중복, 좌석 수 초과 성공, business rejection과 기술 오류율을 계산한다. warm-up 이후 요청별 p99, 기술 오류율 1%, Queue 2초, Ticket 3초, 성공 표본 100건을 판정한다. 노드 실패·SLO·중복 성공은 exit 1, 통과는 0이다.

- [ ] **Step 6: 정적 검증 및 커밋**

Run: `[scriptblock]::Create((Get-Content .\run-distributed-booking.ps1 -Raw)) | Out-Null`

Run: `.\console\gradlew.bat -p console testClasses`

Expected: PowerShell parser 오류 없음, BUILD SUCCESSFUL, 테스트 실행 없음.

Commit: `feat: add distributed booking load runner`

## Chunk 3: Gatling Console 재구축

### Task 6: 요청 모델과 명령 생성

**Files:**
- Modify: `console/src/main/java/com/ticket/gatling/console/SimulationType.java`
- Modify: `console/src/main/java/com/ticket/gatling/console/LoadTestRequest.java`
- Modify: `console/src/main/java/com/ticket/gatling/console/GatlingCommandBuilder.java`
- Modify: `console/src/main/java/com/ticket/gatling/console/DistributedGatlingCommandBuilder.java`
- Modify: `console/src/main/java/com/ticket/gatling/console/LoadTestService.java`
- Modify: `console/src/test/java/com/ticket/gatling/console/SimulationTypeTest.java`
- Modify: `console/src/test/java/com/ticket/gatling/console/LoadTestRequestTest.java`
- Modify: `console/src/test/java/com/ticket/gatling/console/GatlingCommandBuilderTest.java`
- Modify: `console/src/test/java/com/ticket/gatling/console/DistributedGatlingCommandBuilderTest.java`
- Modify: `console/src/test/java/com/ticket/gatling/console/LoadTestServiceTest.java`

- [ ] **Step 1: enum과 요청 계약 테스트 소스 교체**

기존 세 key가 사라지고 `booking-capacity`, `ticket-open-end-to-end`, `seat-contention`이 새 클래스에 연결되는지 고정한다. 새 시나리오는 빈 URL, localhost, 없는 feeder, 행 부족을 거부하고 유지 네 시나리오의 기본값은 보존한다.

- [ ] **Step 2: 요청 모델 구현**

`coreBaseUrl`, `queueBaseUrl`, `bookingFeederFile`, `bookingScenario`, `nodeIndex`, `resultFile`, `pollingTimeoutSeconds`, 운영 확인을 추가한다. feeder 총 행·고유 좌석·노드별 row 범위를 계산한다.

- [ ] **Step 3: 로컬·분산 명령 구현**

로컬은 두 URL, feeder, scenario, node/result, injection mode를 Gatling에 전달한다. 분산은 새 세 key를 `run-distributed-booking.ps1`로 연결하고 같은 값과 polling/report 옵션을 전달한다. token/secret 원문은 명령에 넣지 않는다.

- [ ] **Step 4: 서비스 검증과 로그 마스킹 구현**

실행 전 URL/feeder/예상 행 수/운영 확인을 검증한다. command log는 token·Authorization·민감 값이 있으면 마스킹한다.

- [ ] **Step 5: 컴파일 및 커밋**

Run: `.\console\gradlew.bat -p console classes testClasses`

Expected: BUILD SUCCESSFUL, 테스트 실행 없음.

Commit: `feat: support distributed booking simulations`

### Task 7: 콘솔 화면 교체

**Files:**
- Modify: `console/src/main/resources/static/index.html`
- Modify: `console/src/test/java/com/ticket/gatling/console/ConsoleIndexHtmlTest.java`

- [ ] **Step 1: 화면 계약 테스트 소스 교체**

두 URL, feeder, performanceId, 전체·VM별 RPS, 총 예상 사용자, feeder 행 수·고유 좌석 수, VM별 rowStart/rowEnd, 운영 확인이 존재하고 기존 세 key가 사라지는지 고정한다.

- [ ] **Step 2: 동적 필드와 API preview 구현**

전체 흐름은 Queue/Ticket URL을 모두, 나머지는 Ticket URL만 표시한다. API preview는 각 새 시나리오의 실제 요청 순서를 표시한다.

- [ ] **Step 3: 제출 전 검증과 요약 구현**

VM 수·RPS·duration·injection mode로 총량과 행 범위를 계산한다. feeder 부족, URL 미입력/localhost, 운영 확인 누락을 거부한다.

- [ ] **Step 4: 컴파일 및 커밋**

Run: `.\console\gradlew.bat -p console classes testClasses`

Expected: BUILD SUCCESSFUL.

Commit: `feat: rebuild booking load test console`

## Chunk 4: 문서와 최종 검증

### Task 8: 문서·컴파일·정적 검증

**Files:**
- Modify: `README.md`
- Modify: `console/README.md`

- [ ] **Step 1: 실행 문서 갱신**

새 key와 흐름, feeder 4열과 비순환/고갈, 시나리오별 admission token 출처, Queue/Ticket URL, node/global RPS를 기록한다. 원격 실행은 SSH batch/SCP, feeder 소유자 읽기 전용, 회수 후 삭제 또는 지정 보관 경로 이동, 실패 응답의 Authorization/token 마스킹, 로그 허용 항목(URL/run id/node/행 범위), UTC epoch와 로컬 시작·종료 시각을 문서화한다. Datadog 확인과 실제 smoke는 사용자 확인 후 별도 실행임을 명시한다.

- [ ] **Step 2: 제거된 실행 코드 이름 검사**

Run: `Get-ChildItem load-tests\gatling\src,console\src -Recurse -File | Select-String -Pattern 'TicketOpenFlowSimulation|HoldRaceSimulation|TicketServerCapacitySimulation'`

Expected: 결과 없음.

- [ ] **Step 3: 민감정보·위험 기본값 검사**

새 booking 시나리오와 runner 범위에서 `jwtSecret`, `admissionTokenSecret`, `Authorization: Bearer` 값의 로그 출력과 booking URL localhost 기본값을 검색한다.

Run: `Get-ChildItem load-tests\gatling\src\gatling,console\src\main,run-distributed-booking.ps1 -Recurse -File | Select-String -Pattern 'jwtSecret|admissionTokenSecret|System\.out.*token|Write-(Host|Output).*token|localhost'`

Expected: 새 booking 경로에서 secret 전달·token 값 로그·localhost 기본값 결과 없음. `authAndAdmissionHeaders` 같은 고정 header template은 token 값을 출력하지 않으므로 별도 허용한다.

- [ ] **Step 4: 전체 컴파일과 PowerShell parser 검증**

Run: `.\gradlew.bat -p load-tests/gatling testClasses gatlingClasses`

Run: `.\console\gradlew.bat -p console classes testClasses`

Run: `[scriptblock]::Create((Get-Content .\run-distributed-booking.ps1 -Raw)) | Out-Null`

Expected: 두 Gradle 명령 BUILD SUCCESSFUL, parser 오류 없음, 실제 test/gatlingRun 미실행.

- [ ] **Step 5: 실행 키·인자 정적 검사**

새 세 key/class, `coreBaseUrl`, `queueBaseUrl`, `bookingFeederFile`, `InjectionMode`, `FeederFile`, polling 인자가 console builder·runner·simulation에 모두 존재하고 유지 네 key가 남아 있는지 범위를 제한해 검사한다.

Run: `Get-ChildItem load-tests\gatling\src\gatling,console\src\main,run-distributed-booking.ps1 -Recurse -File | Select-String -Pattern 'booking-capacity|BookingCapacitySimulation|ticket-open-end-to-end|TicketOpenEndToEndSimulation|seat-contention|SeatContentionSimulation|queue-join-only|QueueJoinOnlySimulation|queue-enter|QueueEnterSimulation|legacy-queue-status|LegacyQueueStatusSimulation|cdn-public-state|CdnPublicStateSimulation|coreBaseUrl|queueBaseUrl|bookingFeederFile|InjectionMode|FeederFile|pollingTimeoutSeconds'`

Expected: 새 계약과 유지 계약이 모두 검색됨.

- [ ] **Step 6: UTF-8 BOM과 diff 검사**

Run: `$bad=@(); git diff --name-only --diff-filter=ACM | ForEach-Object { if(Test-Path -LiteralPath $_ -PathType Leaf){ $b=[IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $_)); if($b.Length -ge 3 -and $b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF){ $bad += $_ } } }; if($bad.Count -gt 0){ throw ('UTF-8 BOM detected: ' + ($bad -join ', ')) }`

Run: `git diff --check`

Expected: BOM 파일 없음, diff check 출력 없음.

- [ ] **Step 7: 문서 커밋**

Commit: `docs: explain booking capacity load tests`
