# 운영 Core 안전 입장률 측정 가이드

## 목적

이 테스트가 찾는 값은 최대 HTTP RPS가 아니라 **Core가 안정적으로 받아들일 수 있는 새로운 예매 사용자 수/초(users/sec)** 다.

한 명의 사용자는 항상 다음 다섯 요청을 같은 순서로 수행한다.

```text
공연 요약 조회
→ 좌석 상태 조회
→ 피더에 미리 정한 좌석 선택
→ 주문 생성
→ 주문 PENDING 상태 조회
```

따라서 `30 users/sec`는 단순히 `30 HTTP RPS`라는 뜻이 아니다. 정상적으로 끝나는 동안 각 사용자가 다섯 API를 호출하므로 정상 구간의 총 요청량은 대략 `150 requests/sec`가 된다. 다만 앞 단계가 실패하면 뒤 요청은 중단되므로, 최대 HTTP RPS만 보면 실패 때문에 요청 수가 줄어든 과부하 구간을 오히려 정상처럼 오판할 수 있다.

Queue Server가 제어할 값도 HTTP 요청 수가 아니라 Core로 입장시키는 **새 예매 사용자 수/초**다. 그래서 `CoreAdmissionCapacitySimulation`의 고정 사용자 여정을 Open Model `constant-users-per-sec`로 주입하고, 모든 사용자가 끝까지 성공하는지를 기준으로 안전 입장률을 결정한다.

## 시나리오 계약

`CoreAdmissionCapacitySimulation`은 Queue를 거치지 않고 Core를 직접 호출한다. 기존 Queue/Admission 우회 목적을 유지하며 Admission Token을 보내지 않는다. Admission Token 기능을 새로 추가하거나 변경하지 않는다. 테스트 performance는 기존 정책의 `queueMode=FORCE_OFF`로 만들어 Admission Token 없이 직접 호출할 수 있어야 한다. 전역 Admission 검증 설정을 바꾸는 것보다 테스트 회차만 명시적으로 Queue 비대상으로 만드는 편이 운영 영향 범위가 작다.

비교 가능한 결과를 위해 다음 조건은 고정한다.

- Open Model, `constant-users-per-sec`
- 한 실행에는 하나의 고정 users/sec만 사용
- 생각 시간 없음
- 무작위 좌석 선택 없음
- 좌석 충돌 재시도 없음
- 사용자별 고유 ACTIVE 회원과 access token
- 사용자별 고유 AVAILABLE 좌석
- 부하 단계가 바뀌어도 같은 다섯 요청 구조와 같은 SLO 사용

여러 부하 단계를 한 번에 연속 실행하는 기능은 없다. 각 단계가 끝날 때 운영 DB·Redis 상태를 사람이 확인하고 필요한 데이터 정리를 마친 뒤 다음 단계를 별도로 실행한다. Gatling은 운영 데이터를 자동으로 DELETE/UPDATE하지 않는다.

## 테스트 데이터

테스트 전용 performance의 좌석 수와 응답 특성은 실제 공연과 비슷해야 한다.

```text
실제 공연 약 2,000석
→ 테스트 performance도 약 2,000석 수준
```

좌석 상태 API는 좌석 수에 따라 응답 크기, JSON 직렬화 비용, 네트워크 전송량과 클라이언트 파싱량이 달라진다. 100석짜리 회차는 실제 부하를 과소평가할 수 있고, 100,000석짜리 회차는 과대평가할 수 있다. 좌석 수뿐 아니라 AVAILABLE·HELD·SOLD 비율과 Core 배포 환경도 실행마다 가능한 한 같게 유지한다.

운영 데이터 생성 SQL, 30개 독립 회차 배정, 자동 feeder 생성과 원복 절차는 [Core Admission Capacity 운영 파일](../scripts/core-capacity/README.md)을 따른다. 최초 SQL이 2,000개 전용 ACTIVE 회원과 물리 좌석, 30개 performance를 함께 만든다. 각 performance는 독립적인 `PERFORMANCE_SEATS`를 사용한다. 한 실행이 사용한 performance는 다음 실행에서 재사용하지 않고, 같은 회차를 여러 날 재사용할 때만 Core 비동기 작업 완료 후 Console의 원복 버튼을 사용한다.

전용 회원은 `id=1..2000`의 연속 범위를 사용한다. 이 범위에 기존 회원이 하나라도 있으면 SQL은 빈 ID만 채우지 않고 전체 실행을 중단한다. 기존 계정 ID로 합성 JWT를 발급하는 일을 막기 위한 안전장치다.

피더는 UTF-8 BOM 없는 CSV이고 헤더는 정확히 다음과 같다.

```csv
memberId,accessToken,seatId,admissionToken
1,access-token-1,910000001,
2,access-token-2,910000002,
```

`memberId`와 `seatId`는 전체 파일에서 고유해야 한다. Core Admission Capacity에서는 기존 우회 흐름에 맞춰 `admissionToken`을 비워 둔다. 피더의 회원은 Core DB에 실제 ACTIVE 회원으로 존재해야 하고, 좌석은 지정한 performance에 속한 AVAILABLE 좌석이어야 한다.

### 실행별 피더

`bookingFeederOffset`은 0부터 시작하는 행 위치다. 실행 전에 다음 조건을 검사한다.

```text
offset >= 0
offset + 예상 사용자 수 <= 피더 전체 데이터 행 수
```

조건을 만족하지 않으면 HTTP 부하를 시작하기 전에 실패한다.

Console에서 `토큰 파일/목록 → 파일 자동 생성`을 선택하면 실행 직전에 예상 사용자 수만큼 JWT와 feeder를 함께 생성한다. 기본 범위는 연속된 `memberId=1..`, `seatId=910000001..`이고, Admission Token 열은 비어 있다.

```text
5 users/sec × 30초
performanceId 910000001
build/core-capacity-booking-feeder.csv 150행 자동 생성
offset 0

10 users/sec × 30초
performanceId 910000002
build/core-capacity-booking-feeder.csv 300행 자동 재생성
offset 0
```

JWT secret과 issuer는 운영 Core 설정과 같아야 한다. 전용 회원은 최초 SQL이 이미 생성하므로 회원가입·로그인 API를 호출하는 별도 준비 스크립트는 없다. 동일한 물리 seat ID와 memberId를 다시 사용해도 performance가 다르면 좌석 상태와 주문 범위가 독립적이다. 하나의 큰 수동 feeder가 필요한 별도 환경에서는 기존 파일 입력과 offset 기능을 그대로 사용할 수 있다.

Offset은 앞 실행의 DB·Redis 정리를 대신하지 않는다. 회원과 좌석을 겹치지 않게 만드는 안전장치이며, 이전 실행에서 생성된 주문과 선점 상태의 확인·정리는 별도로 수행한다.

## 기본 SLO

Console에서 아래 값을 변경할 수 있다. 비교 세션 중에는 기준을 바꾸지 않는 것이 원칙이며, 기본값은 기존 `LoadTestConfig` 값을 그대로 사용한다.

| 기준 | p95 | p99 |
| --- | ---: | ---: |
| 공연 요약 | 300 ms | 700 ms |
| 좌석 상태 | 300 ms | 700 ms |
| 좌석 선택 | 500 ms | 1,000 ms |
| 주문 생성 | 800 ms | 1,500 ms |
| 주문 조회 | 500 ms | 1,000 ms |

Technical failure 허용률 기본값은 `1.0%`다. Gatling assertion은 실제 값이 임계값보다 **작아야** PASS로 판정한다. 어떤 기준으로 실행했는지는 기존 `booking-run-config.json`에 기록된다.

## 권장 실행 순서

먼저 작은 부하에서 요청 계약과 데이터 정합성을 확인한다.

```text
01 Smoke
02 Hot Seat 정합성 확인

03 Core Admission Capacity 최초 경계 탐색
5 users/sec  × 30초
10 users/sec × 30초
15 users/sec × 30초
20 users/sec × 30초
30 users/sec × 30초
40 users/sec × 30초
50 users/sec × 30초
```

각 줄은 독립 실행이다. 한 단계가 끝나면 리포트와 운영 상태를 확인하고 다음 독립 performance로 이동한다. Gatling이 데이터를 정리하거나 다음 부하를 자동으로 실행하지 않는다.

2,000석 회차에서 최고 단계인 `50 users/sec × 30초`는 1,500개의 고유 좌석을 사용해 500석의 여유를 남긴다. 60초로 늘리면 3,000석이 필요하므로 같은 데이터에서는 실행하지 않는다. 좌석 고갈이 Core 용량 실패로 섞이지 않도록 최초 경계 탐색은 모든 단계를 30초로 고정한다.

예를 들어 `30 PASS`, `40 FAIL`이면 `32`, `35`, `38 users/sec` 정도로 경계를 좁힌다. 최종 5~10분 유지 테스트는 후보 입장률을 찾은 뒤 필요한 좌석 수를 다시 계산한다. 현재 Simulation은 한 실행에서 performance 하나를 사용하므로 `30 users/sec × 10분 = 18,000석`이 필요하다. 실제보다 큰 좌석 상태 응답으로 측정 의미를 바꾸거나 좌석을 자동 초기화하지 말고, 장시간 검증 데이터 구조를 별도로 승인한 후 실행한다.

필요하면 그 뒤에 아래 순서로 현실성·동시 사용자·회복·Queue 보호를 추가 검증한다.

```text
CoreRealisticContention
CoreActiveUsersClosed
CoreSpike
QueueProtectsCore
```

Closed Model의 동시 사용자 상한은 Queue 입장률과 같은 값이 아니다. Queue의 기준에는 반드시 Open Model Core Admission Capacity 결과를 사용한다.

## Console 실행

저장소 루트에서 Console을 실행한다.

```powershell
.\gradlew.bat -p console run
```

브라우저에서 `http://localhost:9090`을 열고 다음과 같이 입력한다.

```text
Simulation:       03 고정 조건 Core 수용량
Core URL:         실제 운영 Core URL
Performance ID:   910000001
Injection:        constant-users-per-sec
Users/sec:        5
Duration:         30
Access Token:     토큰 파일/목록 → 파일 자동 생성
JWT Secret:       운영 Core와 동일한 값
Member 시작 ID:  1
Feeder:           build/core-capacity-booking-feeder.csv
Feeder Offset:    0
Result File:      ../../distributed-results-join/_latest/core-admission-capacity.csv
Operational Confirmation: ON
```

실행 버튼을 누르면 전송 직전에 다음 내용이 강조되어 표시되고, 확인 대화상자에서도 다시 보여 준다.

```text
TARGET
https://...

PERFORMANCE ID
910000001

LOAD
5 users/sec

DURATION
30 sec

EXPECTED USERS
150

FEEDER
0 ~ 149
```

첫 실행이 끝난 뒤 `10 users/sec × 30초`를 실행할 때는 다음을 바꾼다.

```text
Performance ID: 910000002
Users/sec:      10
Feeder Offset:  0
```

Core URL, duration, JWT 설정과 SLO는 같은 비교 조건으로 유지한다. performance만 새 독립 회차로 바꾸면 Console이 300행 feeder를 다시 만든다.

여러 전용 회차를 사용한 테스트 묶음이 끝나면 모든 결과를 보존하고 Core 비동기 처리가 끝난 뒤 모든 Core 인스턴스를 중지한다. 그 다음 Console의 `전체 테스트 회차 원복 창 열기` 버튼을 누른다. 별도 PowerShell 창은 `910000001..910000030` 전체를 먼저 점검하고, 30개가 모두 안전 조건을 만족한 경우에만 전체 원복 확인 문구를 요구한다. 버튼 자체가 즉시 삭제를 수행하지는 않는다.

## 결과 판독

기존 결과 파일은 그대로 유지한다.

- `booking-evidence.json`: 사용자 시작·종료·성공·기술 실패, 초당 Core 입장/완료, 활성 사용자와 체류 시간
- `booking-results.csv`: 사용자별 최종 결과
- `booking-admissions.csv`, `booking-completions.csv`, `booking-active-users.csv`: 시간대별 증거
- `booking-run-config.json`: 실행 인자, 피더 범위, SLO, runId
- Gatling `index.html`: 요청별 p95/p99와 assertion 결과

별도 결과 체계를 추가하지 않는다. 기존 파일에서 다음 값을 확인한다.

```text
booking-run-config.json:
  usersPerSecond, durationSeconds, expectedUsers, feeder offset/range, SLO, runId

booking-evidence.json:
  startedUsers, terminalUsers, successfulUsers
  technicalFailureUsers, technicalFailurePercent
  maxObservedCoreAdmissionsPerSecond
  maxObservedSuccessfulCompletionsPerSecond
  maxObservedActiveUsers
  averageCoreResidenceMillis
  p95CoreResidenceMillis
  p99CoreResidenceMillis

Gatling index.html:
  performance summary p95/p99
  seat status p95/p99
  select seat p95/p99
  create order p95/p99
  get order p95/p99
  assertion PASS/FAIL
```

## runId와 관측 상관관계

Console은 실행마다 UUID를 생성해 Gatling에 `consoleRunId`로 전달한다. Core 요청에는 기존 correlation header를 유지한다.

```text
X-Load-Test-Run-Id
X-Load-Test-Scenario
X-Load-Test-User-Id
```

같은 runId로 Gatling 결과와 Datadog 로그를 찾을 수 있다. JWT secret, Admission secret, DB 비밀번호와 실제 token 값은 명령 로그나 요약 결과에 기록하지 않는다.
