# 콘솔 대상 환경 선택과 로컬 부하테스트 데이터 설계

기준일: 2026-08-21

## 1. 목표

Gatling Console에서 Core 부하 테스트의 대상 서버를 로컬과 운영 중에서 고르고, 필요하면 임의의 URL을 직접 넣어 실행할 수 있게 한다. 그리고 로컬 서버를 대상으로 했을 때 Core API별 독립 성능 시나리오 5개가 실제로 끝까지 동작하도록 로컬 전용 부하테스트 데이터를 만든다.

대상 시나리오는 다음 다섯 가지다.

```text
GET  /api/v1/performances/{performanceId}/summary
GET  /api/v1/performances/{performanceId}/seats/status
POST /api/v1/performances/{performanceId}/seats/{seatId}/select
POST /api/v1/orders
GET  /api/v1/orders/{orderKey}
```

## 2. 현재 상태와 문제

### 2.1 콘솔이 로컬 대상을 거부한다

`LoadTestService.validateBookingExecution`이 Core booking flow 시나리오에 대해 `validateRemoteUrl`을 호출한다. 이 검증은 `localhost`, `127.*`, `::1`, `0.0.0.0`을 모두 거부한다. 다섯 시나리오는 전부 `usesCoreBookingFlow()`가 참이므로 인증조차 필요 없는 공연 요약 조회도 로컬 대상으로 실행할 수 없다.

프런트엔드에도 같은 취지의 검증이 `index.html`에 있다.

### 2.2 대상 URL이 프리셋에 흩어져 있다

`proofSimulationDefaults`의 각 시나리오 항목이 `coreBaseUrl`을 `https://oneticket.site`로 하드코딩한다. 대상 서버를 바꾸려면 시나리오를 고를 때마다 입력칸을 다시 고쳐야 하고, 새 대상을 추가하려면 프런트엔드 파일을 수정해야 한다.

### 2.3 로컬에 전용 부하테스트 데이터가 없다

운영 Oracle에는 `scripts/core-capacity/create-core-capacity-data.sql`이 만든 전용 데이터가 있다.

```text
showId          910000001
performanceId   910000001..910000030
seatId          910000001..910002000
```

로컬 H2에는 이 대역이 비어 있다. 확인 결과 해당 범위의 회차 0건, 좌석 0건이다. 반면 콘솔은 자동 feeder를 만들 때 `bookingSeatStartId`를 `910000001`로 고정해서 넘긴다. 따라서 로컬을 대상으로 좌석 선점과 주문 생성을 실행하면 존재하지 않는 좌석을 요청한다.

### 2.4 회원 풀이 대상별로 다르다

운영은 `scripts/core-capacity/member-ids.txt`에서 실제 회원 ID를 읽는다. 이 파일은 운영 Oracle에서 내보낸 값이며 Git에서 제외된다. 로컬은 `SeedDataLoader`가 `loadtest{n}@test.com` 회원을 IDENTITY로 넣으므로 ID가 1부터 연속이다. 두 대상이 같은 파일을 쓸 이유가 없다.

### 2.5 Datadog 캡처가 항상 운영을 가정한다

`RunEnvironmentInput.automatic`이 `datadogEnv`를 `prod`로 고정한다. 로컬 대상 실행에서도 운영 태그로 환경 메타데이터를 조회하려 한다.

## 3. 설계

### 3.1 대상 환경 목록 파일

콘솔 저장소에 `console/environments.properties`를 둔다.

```properties
targets=local,prod

local.label=로컬 (H2)
local.coreBaseUrl=http://localhost:8080
local.queueBaseUrl=http://localhost:8081

prod.label=운영 (oneticket.site)
prod.coreBaseUrl=https://oneticket.site
prod.queueBaseUrl=https://queue.oneticket.site
```

JSON이 아니라 properties를 쓰는 이유는 콘솔에 JSON 파싱 라이브러리가 없기 때문이다. `console/build.gradle`의 의존성은 JUnit뿐이고, 응답 JSON은 `Json` 클래스가 문자열로 직접 만든다. 대상 목록을 위해 파서를 새로 만들거나 의존성을 더하는 것은 이 기능에 비해 과하다. `targets` 키가 표시 순서를 정하므로 `Properties`의 순서 비보장 문제도 없다.

콘솔 서버가 시작할 때 이 파일을 UTF-8로 읽고 `GET /api/environments`로 목록을 내려준다. 파일이 없거나 깨지면 대상 목록을 비우고 콘솔은 계속 동작한다. 대상 선택은 편의 기능이므로 파일 문제로 콘솔 전체를 막지 않는다.

새 대상을 추가할 때는 이 파일에 항목 하나만 넣으면 된다. 콘솔 코드와 재빌드는 필요 없다.

### 3.2 UI 동작

`테스트 종류` 아래에 `대상 환경` 드롭다운을 추가한다. 항목은 `environments.json`의 대상들과 마지막의 `직접 입력`이다.

- 프리셋을 고르면 `Ticket/Core URL`과 `Queue URL` 입력칸을 그 값으로 채운다.
- 채운 뒤에도 입력칸은 그대로 편집할 수 있다. 사용자가 값을 고치면 드롭다운은 `직접 입력`으로 바뀐다.
- `직접 입력`을 고르면 입력칸을 건드리지 않는다.

즉 프리셋은 입력칸을 채워주는 역할만 하고, 실제로 요청을 보내는 값은 언제나 텍스트 입력칸이다. 이 구조로 "미리 두 개 중 선택", "임의의 다른 URL 입력" 두 요구를 함께 만족한다.

시나리오를 바꿔도 현재 선택한 대상 환경은 유지한다. `proofSimulationDefaults`에서 `coreBaseUrl`과 `queueBaseUrl`을 제거하고, 대상 URL의 단일 출처를 대상 환경 선택으로 옮긴다.

### 3.3 localhost 허용과 실행 확인

`validateBookingExecution`의 `validateRemoteUrl("Core URL", ...)`을 `validateHttpUrl`로 바꾼다. Queue URL도 같다. localhost 여부는 거부 사유가 아니라 `운영 실행 확인` 체크박스를 요구할지 판단하는 기준으로만 쓴다.

```text
대상이 localhost 계열   → 실행 확인 체크 불필요
그 외 원격 대상         → 실행 확인 체크 필수 (기존과 동일)
```

이는 비-booking 시나리오가 이미 쓰고 있는 규칙과 같다. 운영에 실수로 부하를 보내는 것을 막는 보호는 그대로 유지된다.

분산 실행은 예외다. VM에서 localhost는 대상 서버를 가리키지 않으므로 분산 실행에서는 지금처럼 원격 URL을 강제한다.

### 3.4 회원 ID 파일의 대상별 분기

`AccessTokenFileGenerator`는 `memberIdsFile`이 비어 있으면 `syntheticMemberStartId`부터 연속 ID를 쓴다. 로컬 시드가 회원 ID를 1부터 연속으로 만들므로 로컬 대상에서는 파일이 필요 없다.

`LoadTestService.requiresExistingMemberIds`의 판단에 대상 URL 조건을 더한다.

```text
시나리오가 실제 회원을 요구  AND  대상이 localhost가 아님  → Member ID 파일 필수
그 외                                                      → 파일 없이 연속 ID 사용
```

UI에서도 로컬 대상을 고르면 `Member ID File` 입력칸을 숨긴다. 운영 대상 동작은 지금과 같다.

### 3.5 Datadog 캡처

`RunEnvironmentInput.automatic`에서 Core 대상이 localhost 계열이면 `captureEnabled`를 거짓으로 만든다. 로컬에는 Datadog 에이전트가 없으므로 캡처를 시도해도 실패 로그만 남는다. 실행 자체는 지금도 캡처 실패로 중단되지 않는다.

### 3.6 로컬 전용 부하테스트 시드 (ticket 저장소)

`SeedDataLoader`와 `seed/kopis-curated.sql`은 로컬과 운영 양쪽 데이터 적재에 쓰이므로 건드리지 않는다. 부하테스트 전용 데이터는 새 컴포넌트로 분리한다.

`LoadTestFixtureSeeder`를 새로 만들고 다음 조건에서만 동작시킨다.

```text
@ConditionalOnProperty(prefix = "app.seed.load-test-fixture", name = "enabled", havingValue = "true")
```

`application-local.yml`에서만 참으로 둔다. `application.yml`, `application-dev.yml`, `application-prod.yml`의 기본값은 거짓이므로 운영에는 어떤 경로로도 적재되지 않는다.

만드는 데이터는 운영 전용 데이터와 **같은 고정 식별자**를 쓴다.

```text
showId          910000001
performanceId   910000001부터 performance-count개
seatId          910000001..910002000  (2,000석)
회차별 좌석      회차 수 × 2,000
Queue 정책       회차마다 FORCE_OFF
```

식별자를 운영과 맞추는 이유는 콘솔이 `bookingSeatStartId`를 `910000001`로 고정해서 넘기고, `docs/core-capacity-production.md`의 회차 배정표와 `scripts/core-capacity/README.md`가 이미 이 대역을 전제하기 때문이다. 같은 값을 쓰면 콘솔·문서·회차 배정을 그대로 재사용할 수 있고 대상만 바꿔 같은 절차를 반복할 수 있다.

회차 수는 `app.seed.load-test-fixture.performance-count`로 조정하고 기본값은 8로 둔다. 로컬은 `ddl-auto: create`라 재시작할 때마다 데이터를 다시 넣으므로, 운영과 같은 30회차를 그대로 쓰면 좌석이 60,000행이 되어 기동이 느려진다. 기본 8회차는 좌석 16,000행이며 API별 7단계와 예비 1회차를 덮는다. 03 경계 탐색까지 로컬에서 하려면 이 값을 올린다.

회원은 기존 `app.seed.load-test-members.count`를 2,000으로 올려서 ID 1..2,000을 만든다. 별도 컴포넌트를 만들지 않는 이유는 부하테스트 회원 시드가 이미 `SeedDataLoader`에 있고 대상 테이블도 같기 때문이다.

시더는 멱등이어야 한다. 대상 식별자가 이미 있으면 건너뛴다. `ddl-auto: create`인 local 프로파일에서는 매번 새로 만들지만, 다른 로컬 설정에서 재실행해도 중복이 생기지 않아야 한다.

## 4. 영향 범위

### gatling-test

```text
console/environments.properties                             신규
console/src/main/java/.../TargetEnvironmentCatalog.java     신규
console/src/main/java/.../ConsoleServer.java                /api/environments 추가
console/src/main/java/.../LoadTestService.java              localhost 허용, 회원 파일 분기
console/src/main/java/.../RunEnvironmentInput.java          로컬 캡처 비활성
console/src/main/resources/static/index.html                대상 환경 드롭다운
console/src/test/java/.../                                  검증 테스트
```

### ticket

```text
core/core-api/src/main/java/.../seed/LoadTestFixtureSeeder.java   신규
core/core-api/src/main/resources/application.yml                  기본값 false
core/core-api/src/main/resources/application-local.yml            enabled true, 회원 2000
core/core-api/src/test/java/.../seed/LoadTestFixtureSeederTest.java  신규
```

## 5. 검증

```powershell
.\gradlew.bat -p console test
.\gradlew.bat test          # ticket 저장소
```

콘솔 테스트가 확인할 것은 다음과 같다.

- `environments.properties` 파싱과 `/api/environments` 응답
- 파일이 없거나 깨졌을 때 콘솔이 빈 목록으로 계속 동작하는지
- localhost Core URL이 통과하고 실행 확인 체크를 요구하지 않는지
- 원격 Core URL이 여전히 실행 확인 체크를 요구하는지
- 분산 실행에서 localhost가 여전히 거부되는지
- 로컬 대상에서 Member ID 파일 없이 검증을 통과하는지
- 운영 대상에서 Member ID 파일이 여전히 필수인지

ticket 테스트가 확인할 것은 다음과 같다.

- 시더가 만드는 회차 수, 좌석 수, 식별자 범위
- 회차마다 Queue 정책이 `FORCE_OFF`인지
- 두 번 실행해도 중복이 생기지 않는지
- 플래그가 꺼져 있으면 아무것도 만들지 않는지

수동 확인은 로컬 Core를 재시작한 뒤 콘솔에서 로컬 대상을 골라 다섯 시나리오를 순서대로 실행하는 것이다.

## 6. 범위에서 제외

- 운영 `member-ids.txt` 재생성. 운영 Oracle 접근이 필요하며 이번 작업과 독립적이다.
- 03 이후 시나리오(`CoreAdmissionCapacity`, `CoreRealisticContention`, `CoreActiveUsersClosed`, `CoreSpike`, `QueueProtectsCore`)의 로컬 실행 검증. 대상 선택 자체는 같은 경로를 타므로 동작하지만, 이번 검증 범위는 API별 5개로 한정한다.
- 분산 실행에서의 로컬 대상 지원.
- 좌석 시작 ID를 UI에서 바꾸는 기능. 로컬과 운영이 같은 대역을 쓰므로 필요하지 않다.
