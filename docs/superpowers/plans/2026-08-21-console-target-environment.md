# 콘솔 대상 환경 선택과 로컬 부하테스트 데이터 구현 계획

> **작업자 안내:** 각 단계는 `- [ ]` 체크박스로 추적한다. 설계 근거는 [2026-08-21-console-target-environment-design.md](../specs/2026-08-21-console-target-environment-design.md)를 본다.
>
> **커밋 정책:** 이 저장소의 `AGENTS.md`는 사용자가 명시적으로 요청하지 않으면 커밋하지 않는다고 정한다. 따라서 각 Task의 마지막 단계는 커밋이 아니라 테스트 실행이다. 커밋은 전체 작업이 끝난 뒤 사용자 요청이 있을 때 한다.

**목표:** Gatling Console에서 Core 부하 테스트 대상을 로컬과 운영 중에 고르거나 임의 URL로 직접 지정할 수 있게 하고, 로컬 서버 대상으로 Core API별 5개 시나리오가 끝까지 동작하도록 로컬 전용 데이터를 만든다.

**접근:** 콘솔은 대상 URL 프리셋을 `environments.properties`로 외부화하고 입력칸을 채워주는 방식으로만 쓴다. localhost 차단은 제거하되 실행 확인 체크 규칙으로 대체한다. ticket 저장소에는 기존 시드와 분리된 `LoadTestFixtureSeeder`를 추가하고 운영과 같은 고정 ID 대역을 쓴다.

**기술 스택:** Java 21 릴리스 / JDK 25 toolchain, `com.sun.net.httpserver`, 순수 JDK(의존성 없음), Spring Boot `JdbcTemplate`, H2 (MODE=Oracle), JUnit 5

**저장소 두 곳을 건드린다.**

```text
C:\Users\mn040\IdeaProjects\ticket-workspace\gatling-test    Task 1~7
C:\Users\mn040\IdeaProjects\ticket-workspace\ticket          Task 8~9
```

---

## 파일 구조

### gatling-test

| 파일 | 책임 |
| --- | --- |
| `console/environments.properties` | 대상 환경 목록. 운영자가 직접 편집하는 유일한 대상 설정 |
| `console/src/main/java/com/ticket/gatling/console/TargetEnvironment.java` | 대상 하나를 담는 record |
| `console/src/main/java/com/ticket/gatling/console/TargetEnvironmentCatalog.java` | properties 파일을 읽어 순서 있는 목록으로 바꾼다 |
| `console/src/main/java/com/ticket/gatling/console/ConsoleServer.java` | `GET /api/environments` 추가 |
| `console/src/main/java/com/ticket/gatling/console/LoadTestService.java` | localhost 허용, 로컬 대상 Member ID 파일 면제 |
| `console/src/main/java/com/ticket/gatling/console/RunEnvironmentInput.java` | 로컬 대상 Datadog 캡처 비활성 |
| `console/src/main/resources/static/index.html` | 대상 환경 드롭다운과 연동 |

### ticket

| 파일 | 책임 |
| --- | --- |
| `core/core-api/src/main/java/com/ticket/core/config/seed/LoadTestFixtureSeeder.java` | 로컬 전용 회차·좌석 데이터 적재 |
| `core/core-api/src/main/resources/application.yml` | 기능 플래그 기본값 false |
| `core/core-api/src/main/resources/application-local.yml` | 로컬에서만 true, 회원 2,000명 |

---

## Task 1: 대상 환경 카탈로그

**Files:**
- Create: `console/src/main/java/com/ticket/gatling/console/TargetEnvironment.java`
- Create: `console/src/main/java/com/ticket/gatling/console/TargetEnvironmentCatalog.java`
- Create: `console/src/test/java/com/ticket/gatling/console/TargetEnvironmentCatalogTest.java`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`console/src/test/java/com/ticket/gatling/console/TargetEnvironmentCatalogTest.java`:

```java
package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetEnvironmentCatalogTest {
    @TempDir
    Path tempDir;

    @Test
    void readsTargetsInDeclaredOrder() throws IOException {
        final Path file = tempDir.resolve("environments.properties");
        Files.writeString(file, """
                targets=local,prod

                local.label=로컬 (H2)
                local.coreBaseUrl=http://localhost:8080
                local.queueBaseUrl=http://localhost:8081

                prod.label=운영 (oneticket.site)
                prod.coreBaseUrl=https://oneticket.site
                prod.queueBaseUrl=https://queue.oneticket.site
                """, StandardCharsets.UTF_8);

        final List<TargetEnvironment> targets = TargetEnvironmentCatalog.load(file);

        assertEquals(2, targets.size());
        assertEquals("local", targets.get(0).key());
        assertEquals("로컬 (H2)", targets.get(0).label());
        assertEquals("http://localhost:8080", targets.get(0).coreBaseUrl());
        assertEquals("http://localhost:8081", targets.get(0).queueBaseUrl());
        assertEquals("prod", targets.get(1).key());
        assertEquals("https://oneticket.site", targets.get(1).coreBaseUrl());
    }

    @Test
    void returnsEmptyListWhenFileIsMissing() {
        final List<TargetEnvironment> targets =
                TargetEnvironmentCatalog.load(tempDir.resolve("absent.properties"));

        assertTrue(targets.isEmpty());
    }

    @Test
    void skipsTargetsWithoutCoreBaseUrl() throws IOException {
        final Path file = tempDir.resolve("environments.properties");
        Files.writeString(file, """
                targets=broken,prod
                broken.label=설정이 빠진 대상
                prod.label=운영
                prod.coreBaseUrl=https://oneticket.site
                """, StandardCharsets.UTF_8);

        final List<TargetEnvironment> targets = TargetEnvironmentCatalog.load(file);

        assertEquals(1, targets.size());
        assertEquals("prod", targets.get(0).key());
        assertEquals("", targets.get(0).queueBaseUrl());
    }

    @Test
    void fallsBackToKeyWhenLabelIsMissing() throws IOException {
        final Path file = tempDir.resolve("environments.properties");
        Files.writeString(file, """
                targets=staging
                staging.coreBaseUrl=https://staging.example.com
                """, StandardCharsets.UTF_8);

        final List<TargetEnvironment> targets = TargetEnvironmentCatalog.load(file);

        assertEquals("staging", targets.get(0).label());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```powershell
.\gradlew.bat -p console test --tests "*TargetEnvironmentCatalogTest*"
```

기대: 컴파일 실패. `TargetEnvironment`와 `TargetEnvironmentCatalog`가 없다.

- [ ] **Step 3: 최소 구현을 쓴다**

`console/src/main/java/com/ticket/gatling/console/TargetEnvironment.java`:

```java
package com.ticket.gatling.console;

public record TargetEnvironment(
        String key,
        String label,
        String coreBaseUrl,
        String queueBaseUrl
) {
    public TargetEnvironment {
        key = key == null ? "" : key.trim();
        label = label == null || label.isBlank() ? key : label.trim();
        coreBaseUrl = coreBaseUrl == null ? "" : coreBaseUrl.trim();
        queueBaseUrl = queueBaseUrl == null ? "" : queueBaseUrl.trim();
    }
}
```

`console/src/main/java/com/ticket/gatling/console/TargetEnvironmentCatalog.java`:

```java
package com.ticket.gatling.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class TargetEnvironmentCatalog {

    private TargetEnvironmentCatalog() {
    }

    public static List<TargetEnvironment> load(final Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        final Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            return List.of();
        }

        final String declared = properties.getProperty("targets", "").trim();
        if (declared.isEmpty()) {
            return List.of();
        }

        final List<TargetEnvironment> targets = new ArrayList<>();
        for (String rawKey : declared.split(",")) {
            final String key = rawKey.trim();
            if (key.isEmpty()) {
                continue;
            }
            final String coreBaseUrl = properties.getProperty(key + ".coreBaseUrl", "").trim();
            if (coreBaseUrl.isEmpty()) {
                continue;
            }
            targets.add(new TargetEnvironment(
                    key,
                    properties.getProperty(key + ".label", ""),
                    coreBaseUrl,
                    properties.getProperty(key + ".queueBaseUrl", "")
            ));
        }
        return List.copyOf(targets);
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

```powershell
.\gradlew.bat -p console test --tests "*TargetEnvironmentCatalogTest*"
```

기대: PASS 4건.

---

## Task 2: 대상 환경 목록 파일과 API

**Files:**
- Create: `console/environments.properties`
- Modify: `console/src/main/java/com/ticket/gatling/console/ConsoleServer.java`
- Create: `console/src/test/java/com/ticket/gatling/console/ConsoleEnvironmentsApiTest.java`

- [ ] **Step 1: 대상 목록 파일을 만든다**

`console/environments.properties`:

```properties
# Gatling Console 대상 환경 목록
# targets 값이 화면에 나오는 순서를 정한다. 대상을 추가하려면 키를 여기에 넣고 아래에 항목을 쓴다.
targets=local,prod

local.label=로컬 (H2)
local.coreBaseUrl=http://localhost:8080
local.queueBaseUrl=http://localhost:8081

prod.label=운영 (oneticket.site)
prod.coreBaseUrl=https://oneticket.site
prod.queueBaseUrl=https://queue.oneticket.site
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`console/src/test/java/com/ticket/gatling/console/ConsoleEnvironmentsApiTest.java`:

```java
package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleEnvironmentsApiTest {

    @Test
    void bundledEnvironmentsFileDeclaresLocalAndProd() {
        final List<TargetEnvironment> targets =
                TargetEnvironmentCatalog.load(Path.of("environments.properties"));

        assertEquals(2, targets.size());
        assertEquals("local", targets.get(0).key());
        assertEquals("http://localhost:8080", targets.get(0).coreBaseUrl());
        assertEquals("prod", targets.get(1).key());
        assertEquals("https://oneticket.site", targets.get(1).coreBaseUrl());
    }

    @Test
    void serializesTargetsAsJsonArray() {
        final String json = ConsoleServer.environmentsJson(List.of(
                new TargetEnvironment("local", "로컬 (H2)", "http://localhost:8080", "http://localhost:8081")
        ));

        assertTrue(json.startsWith("["));
        assertTrue(json.contains("\"key\":\"local\""));
        assertTrue(json.contains("\"coreBaseUrl\":\"http://localhost:8080\""));
        assertTrue(json.contains("\"queueBaseUrl\":\"http://localhost:8081\""));
    }

    @Test
    void serializesEmptyListAsEmptyJsonArray() {
        assertEquals("[]", ConsoleServer.environmentsJson(List.of()));
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

```powershell
.\gradlew.bat -p console test --tests "*ConsoleEnvironmentsApiTest*"
```

기대: 컴파일 실패. `ConsoleServer.environmentsJson`이 없다.

- [ ] **Step 4: ConsoleServer에 라우트와 직렬화를 더한다**

`ConsoleServer.java`의 `handle` 메서드에서 `/api/simulations` 분기 바로 위에 다음을 넣는다.

```java
            if (path.equals("/api/environments")) {
                handleEnvironments(exchange);
                return;
            }
```

`handleSimulations` 메서드 바로 위에 다음 두 메서드를 넣는다.

```java
    private void handleEnvironments(final HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        writeJson(exchange, 200, environmentsJson(
                TargetEnvironmentCatalog.load(Path.of("environments.properties"))
        ));
    }

    static String environmentsJson(final java.util.List<TargetEnvironment> targets) {
        return targets.stream()
                .map(target -> "{"
                        + "\"key\":\"" + Json.escape(target.key()) + "\","
                        + "\"label\":\"" + Json.escape(target.label()) + "\","
                        + "\"coreBaseUrl\":\"" + Json.escape(target.coreBaseUrl()) + "\","
                        + "\"queueBaseUrl\":\"" + Json.escape(target.queueBaseUrl()) + "\""
                        + "}")
                .reduce((left, right) -> left + "," + right)
                .map(value -> "[" + value + "]")
                .orElse("[]");
    }
```

`Path`는 `ConsoleServer.java`가 이미 `java.nio.file.Path`를 import하고 있으므로 추가 import는 필요 없다.

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

```powershell
.\gradlew.bat -p console test --tests "*ConsoleEnvironmentsApiTest*"
```

기대: PASS 3건.

> 주의: `environments.properties`는 Gradle 작업 디렉터리 기준 상대 경로로 읽는다. `gradlew -p console run`과 `gradlew -p console test`는 모두 `console` 디렉터리를 작업 디렉터리로 쓰므로 그대로 동작한다.

---

## Task 3: localhost 대상 허용

**Files:**
- Modify: `console/src/main/java/com/ticket/gatling/console/LoadTestService.java`
- Create: `console/src/test/java/com/ticket/gatling/console/LoadTestServiceLocalTargetTest.java`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`console/src/test/java/com/ticket/gatling/console/LoadTestServiceLocalTargetTest.java`:

```java
package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadTestServiceLocalTargetTest {
    @TempDir
    Path tempDir;

    private LoadTestRequest summaryRequest(final String coreBaseUrl, final boolean confirmed) {
        return LoadTestRequest.fromForm(Map.ofEntries(
                Map.entry("ticketProjectPath", List.of(tempDir.toString())),
                Map.entry("simulation", List.of("core-performance-summary-api")),
                Map.entry("coreBaseUrl", List.of(coreBaseUrl)),
                Map.entry("performanceId", List.of("910000001")),
                Map.entry("injectionMode", List.of("constant-users-per-sec")),
                Map.entry("usersPerSecond", List.of("1")),
                Map.entry("durationSeconds", List.of("1")),
                Map.entry("operationalConfirmation", List.of(confirmed ? "on" : "off"))
        ));
    }

    @Test
    void acceptsLocalhostCoreUrlWithoutOperationalConfirmation() {
        assertDoesNotThrow(() ->
                LoadTestService.validateTargetForTest(summaryRequest("http://localhost:8080", false)));
    }

    @Test
    void acceptsLoopbackAddressCoreUrl() {
        assertDoesNotThrow(() ->
                LoadTestService.validateTargetForTest(summaryRequest("http://127.0.0.1:8080", false)));
    }

    @Test
    void stillRequiresOperationalConfirmationForRemoteCoreUrl() {
        final IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> LoadTestService.validateTargetForTest(summaryRequest("https://oneticket.site", false))
        );

        assertTrue(exception.getMessage().contains("confirmation"));
    }

    @Test
    void acceptsRemoteCoreUrlWithOperationalConfirmation() {
        assertDoesNotThrow(() ->
                LoadTestService.validateTargetForTest(summaryRequest("https://oneticket.site", true)));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```powershell
.\gradlew.bat -p console test --tests "*LoadTestServiceLocalTargetTest*"
```

기대: 컴파일 실패. `validateTargetForTest`가 없다.

- [ ] **Step 3: LoadTestService의 검증을 바꾼다**

`validateBookingExecution` 안의 두 줄을 바꾼다. 기존:

```java
        validateRemoteUrl("Core URL", request.coreBaseUrl());
        if (request.simulationType().usesQueueBaseUrl()) {
            validateRemoteUrl("Queue URL", request.queueBaseUrl());
        }
```

변경 후:

```java
        if (request.distributedExecution()) {
            validateRemoteUrl("Core URL", request.coreBaseUrl());
        } else {
            validateHttpUrl("Core URL", request.coreBaseUrl());
        }
        if (request.simulationType().usesQueueBaseUrl()) {
            if (request.distributedExecution()) {
                validateRemoteUrl("Queue URL", request.queueBaseUrl());
            } else {
                validateHttpUrl("Queue URL", request.queueBaseUrl());
            }
        }
        if (!coreTargetIsLocal(request) && !request.operationalConfirmation()) {
            throw new IllegalArgumentException(
                    "Operational confirmation is required for a non-local load-test target"
            );
        }
```

`validateBookingExecution` 메서드 안에 이미 있는 아래 블록은 위 조건이 대신하므로 삭제한다.

```java
        if (request.dbAuditEnabled()) {
```

위 블록은 남기고, 그 앞에 있던 무조건 `operationalConfirmation` 요구 검증만 제거한다. 현재 파일에서 제거 대상은 다음 문장이다.

```java
        if (!request.operationalConfirmation()) {
            throw new IllegalArgumentException(
                    "Operational confirmation is required for a booking load test"
            );
        }
```

이 문장이 파일에 없으면 이미 프런트엔드에서만 강제하던 것이므로 추가 삭제는 하지 않는다.

같은 클래스에 헬퍼와 테스트용 진입점을 더한다. `isLocalTarget(URI)` 메서드 바로 아래에 넣는다.

```java
    boolean coreTargetIsLocal(final LoadTestRequest request) {
        try {
            return isLocalTarget(new URI(request.coreBaseUrl()));
        } catch (URISyntaxException | NullPointerException exception) {
            return false;
        }
    }

    static void validateTargetForTest(final LoadTestRequest request) {
        new LoadTestService(new ReportRegistry()).validateBookingExecution(request);
    }
```

`validateTargetForTest`가 쓰는 생성자 인자는 현재 `LoadTestService`의 생성자 시그니처에 맞춘다. 생성자가 다르면 그 시그니처를 그대로 쓴다.

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

```powershell
.\gradlew.bat -p console test --tests "*LoadTestServiceLocalTargetTest*"
```

기대: PASS 4건.

- [ ] **Step 5: 기존 테스트가 깨지지 않았는지 확인한다**

```powershell
.\gradlew.bat -p console test
```

기대: 전체 PASS.

---

## Task 4: 로컬 대상에서 Member ID 파일 면제

**Files:**
- Modify: `console/src/main/java/com/ticket/gatling/console/LoadTestService.java`
- Modify: `console/src/test/java/com/ticket/gatling/console/LoadTestServiceLocalTargetTest.java`

- [ ] **Step 1: 실패하는 테스트를 더한다**

`LoadTestServiceLocalTargetTest`에 다음 두 테스트를 더한다.

```java
    private LoadTestRequest seatStatusRequest(final String coreBaseUrl) {
        return LoadTestRequest.fromForm(Map.ofEntries(
                Map.entry("ticketProjectPath", List.of(tempDir.toString())),
                Map.entry("simulation", List.of("core-seat-status-api")),
                Map.entry("coreBaseUrl", List.of(coreBaseUrl)),
                Map.entry("performanceId", List.of("910000001")),
                Map.entry("accessTokenMode", List.of("tokens")),
                Map.entry("accessTokenSource", List.of("generate-file")),
                Map.entry("jwtSecret", List.of("0123456789abcdef0123456789abcdef")),
                Map.entry("memberIdsFile", List.of("")),
                Map.entry("injectionMode", List.of("constant-users-per-sec")),
                Map.entry("usersPerSecond", List.of("1")),
                Map.entry("durationSeconds", List.of("1")),
                Map.entry("operationalConfirmation", List.of("on"))
        ));
    }

    @Test
    void localTargetDoesNotRequireMemberIdFile() {
        assertDoesNotThrow(() ->
                LoadTestService.validateJwtForTest(seatStatusRequest("http://localhost:8080")));
    }

    @Test
    void remoteTargetStillRequiresMemberIdFile() {
        final IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> LoadTestService.validateJwtForTest(seatStatusRequest("https://oneticket.site"))
        );

        assertTrue(exception.getMessage().contains("Member ID"));
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```powershell
.\gradlew.bat -p console test --tests "*LoadTestServiceLocalTargetTest*"
```

기대: 컴파일 실패. `validateJwtForTest`가 없다.

- [ ] **Step 3: 판단 기준에 대상 조건을 더한다**

`requiresExistingMemberIds`의 시그니처를 바꾼다. 기존:

```java
    private boolean requiresExistingMemberIds(final SimulationType simulationType) {
        return simulationType == SimulationType.CORE_SEAT_STATUS_API
                || simulationType == SimulationType.CORE_SEAT_SELECT_API
                || simulationType == SimulationType.CORE_ORDER_CREATE_API
                || simulationType == SimulationType.CORE_ADMISSION_CAPACITY
                || simulationType == SimulationType.CORE_REALISTIC_CONTENTION;
    }
```

변경 후:

```java
    private boolean requiresExistingMemberIds(final LoadTestRequest request) {
        if (coreTargetIsLocal(request)) {
            return false;
        }
        final SimulationType simulationType = request.simulationType();
        return simulationType == SimulationType.CORE_SEAT_STATUS_API
                || simulationType == SimulationType.CORE_SEAT_SELECT_API
                || simulationType == SimulationType.CORE_ORDER_CREATE_API
                || simulationType == SimulationType.CORE_ADMISSION_CAPACITY
                || simulationType == SimulationType.CORE_REALISTIC_CONTENTION;
    }
```

호출부 세 곳을 고친다.

1. `validateSyntheticJwt` 안의 `if (requiresExistingMemberIds(request.simulationType()))` → `if (requiresExistingMemberIds(request))`
2. `validateGeneratedMemberSeatFeeder` 안의 무조건 `validateMemberIdsFile(request);` → `if (requiresExistingMemberIds(request)) { validateMemberIdsFile(request); }`
3. Gatling 명령을 만드는 곳의 `if (requiresExistingMemberIds(request.simulationType()))` → `if (requiresExistingMemberIds(request))`

3번이 중요하다. 로컬 대상에서는 `-DmemberIdsFile`을 넘기지 않으므로 `AccessTokenFileGenerator`가 `syntheticMemberStartId`부터 연속 회원 ID를 쓴다.

같은 클래스에 테스트용 진입점을 더한다.

```java
    static void validateJwtForTest(final LoadTestRequest request) {
        new LoadTestService(new ReportRegistry()).validateSyntheticJwt(request);
    }
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

```powershell
.\gradlew.bat -p console test --tests "*LoadTestServiceLocalTargetTest*"
```

기대: PASS 6건.

---

## Task 5: 로컬 대상 Datadog 캡처 비활성

**Files:**
- Modify: `console/src/main/java/com/ticket/gatling/console/RunEnvironmentInput.java`
- Create: `console/src/test/java/com/ticket/gatling/console/RunEnvironmentInputLocalTargetTest.java`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`console/src/test/java/com/ticket/gatling/console/RunEnvironmentInputLocalTargetTest.java`:

```java
package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEnvironmentInputLocalTargetTest {

    @Test
    void disablesCaptureForLocalCoreTarget() {
        final RunEnvironmentInput input = RunEnvironmentInput.automatic(
                SimulationType.CORE_PERFORMANCE_SUMMARY_API,
                "",
                "http://localhost:8080",
                ""
        );

        assertFalse(input.captureEnabled());
    }

    @Test
    void keepsCaptureForRemoteCoreTarget() {
        final RunEnvironmentInput input = RunEnvironmentInput.automatic(
                SimulationType.CORE_PERFORMANCE_SUMMARY_API,
                "",
                "https://oneticket.site",
                ""
        );

        assertTrue(input.captureEnabled());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```powershell
.\gradlew.bat -p console test --tests "*RunEnvironmentInputLocalTargetTest*"
```

기대: FAIL. 첫 번째 테스트가 `captureEnabled`를 참으로 받는다.

- [ ] **Step 3: 최소 구현을 쓴다**

`RunEnvironmentInput.automatic`의 마지막 반환문을 바꾼다. 기존:

```java
        return new RunEnvironmentInput(true, targets);
```

변경 후:

```java
        return new RunEnvironmentInput(!isLocalHost(coreBaseUrl), targets);
```

같은 파일의 record 본문 안, `automatic` 메서드 아래에 헬퍼를 더한다.

```java
    private static boolean isLocalHost(final String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            final String host = new java.net.URI(url.trim()).getHost();
            if (host == null) {
                return false;
            }
            final String normalized = host.toLowerCase(java.util.Locale.ROOT);
            return normalized.equals("localhost")
                    || normalized.endsWith(".localhost")
                    || normalized.startsWith("127.")
                    || normalized.equals("::1")
                    || normalized.equals("0:0:0:0:0:0:0:1")
                    || normalized.equals("0.0.0.0");
        } catch (java.net.URISyntaxException exception) {
            return false;
        }
    }
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

```powershell
.\gradlew.bat -p console test --tests "*RunEnvironmentInputLocalTargetTest*"
```

기대: PASS 2건.

---

## Task 6: 대상 환경 드롭다운 UI

**Files:**
- Modify: `console/src/main/resources/static/index.html`
- Create: `console/src/test/java/com/ticket/gatling/console/ConsoleTargetEnvironmentHtmlTest.java`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`console/src/test/java/com/ticket/gatling/console/ConsoleTargetEnvironmentHtmlTest.java`:

```java
package com.ticket.gatling.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleTargetEnvironmentHtmlTest {

    private String html() throws IOException {
        return Files.readString(
                Path.of("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
    }

    @Test
    void rendersTargetEnvironmentSelect() throws IOException {
        final String html = html();

        assertTrue(html.contains("id=\"targetEnvironment\""));
        assertTrue(html.contains("value=\"__custom__\""));
    }

    @Test
    void loadsEnvironmentsFromApi() throws IOException {
        assertTrue(html().contains("/api/environments"));
    }

    @Test
    void proofDefaultsNoLongerPinCoreBaseUrl() throws IOException {
        final String html = html();
        final int start = html.indexOf("const proofSimulationDefaults");
        final int end = html.indexOf("const coreApiSimulationKeys");

        assertTrue(start > 0 && end > start);
        assertFalse(html.substring(start, end).contains("coreBaseUrl"));
        assertFalse(html.substring(start, end).contains("queueBaseUrl"));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```powershell
.\gradlew.bat -p console test --tests "*ConsoleTargetEnvironmentHtmlTest*"
```

기대: FAIL 3건.

- [ ] **Step 3: 대상 환경 select를 넣는다**

`index.html`에서 `<legend>대상</legend>` 바로 다음 줄의 `ticketProjectPath` hidden input 아래에 넣는다.

```html
        <label for="targetEnvironment">대상 환경 <span class="help-chip" tabindex="0" data-help="미리 등록한 대상을 고르면 아래 URL 입력칸을 채웁니다. 채운 뒤에도 직접 고칠 수 있고, 고치면 직접 입력으로 바뀝니다. 목록은 console/environments.properties에서 관리합니다.">?</span></label>
        <select id="targetEnvironment" name="targetEnvironment">
          <option value="__custom__">직접 입력</option>
        </select>
```

- [ ] **Step 4: coreBaseUrl 기본값을 비우고 프리셋에서 URL을 뺀다**

`coreBaseUrl`과 `queueBaseUrl` input의 하드코딩 값을 지운다.

```html
              <input id="coreBaseUrl" name="coreBaseUrl" value="" placeholder="대상 환경을 고르거나 직접 입력">
```

```html
              <input id="queueBaseUrl" name="queueBaseUrl" value="" placeholder="대상 환경을 고르거나 직접 입력">
```

`proofSimulationDefaults`의 모든 항목에서 `coreBaseUrl: 'https://oneticket.site', `와 `queueBaseUrl: 'https://queue.oneticket.site', ` 조각을 지운다. 대상 URL의 단일 출처를 대상 환경 선택으로 옮기는 것이 목적이다. 다른 키(`users`, `durationSeconds`, `resultFile` 등)는 그대로 둔다.

- [ ] **Step 5: 스크립트를 연결한다**

`const coreBaseUrlInput = document.querySelector('#coreBaseUrl');` 아래에 참조를 더한다.

```javascript
const queueBaseUrlInput = document.querySelector('#queueBaseUrl');
const targetEnvironmentSelect = document.querySelector('#targetEnvironment');
let targetEnvironments = [];
```

`loadSimulations` 함수 정의 바로 위에 다음 함수들을 더한다.

```javascript
async function loadEnvironments() {
  try {
    const response = await fetch('/api/environments');
    targetEnvironments = await response.json();
  } catch (error) {
    targetEnvironments = [];
  }
  targetEnvironmentSelect.innerHTML = targetEnvironments
    .map(item => `<option value="${item.key}">${item.label}</option>`)
    .join('') + '<option value="__custom__">직접 입력</option>';
  if (targetEnvironments.length > 0) {
    targetEnvironmentSelect.value = targetEnvironments[0].key;
    applyTargetEnvironment();
  } else {
    targetEnvironmentSelect.value = '__custom__';
  }
}

function applyTargetEnvironment() {
  const selected = targetEnvironments.find(item => item.key === targetEnvironmentSelect.value);
  if (!selected) return;
  coreBaseUrlInput.value = selected.coreBaseUrl;
  if (selected.queueBaseUrl) queueBaseUrlInput.value = selected.queueBaseUrl;
  updateVisibility();
  updateSummary();
}

function markCustomTargetEnvironment() {
  const selected = targetEnvironments.find(item => item.key === targetEnvironmentSelect.value);
  if (!selected) return;
  const sameCore = coreBaseUrlInput.value.trim() === selected.coreBaseUrl;
  const sameQueue = !selected.queueBaseUrl
          || queueBaseUrlInput.value.trim() === selected.queueBaseUrl;
  if (!sameCore || !sameQueue) targetEnvironmentSelect.value = '__custom__';
}

function coreTargetIsLocal() {
  return isLocalhostUrl(coreBaseUrlInput.value.trim());
}
```

이벤트를 연결한다. 파일 끝의 다른 `addEventListener` 호출 옆에 넣는다.

```javascript
targetEnvironmentSelect.addEventListener('change', applyTargetEnvironment);
coreBaseUrlInput.addEventListener('input', markCustomTargetEnvironment);
queueBaseUrlInput.addEventListener('input', markCustomTargetEnvironment);
```

초기화 호출을 더한다. `loadSimulations()`를 호출하는 곳 바로 앞에 `await loadEnvironments();`를 넣는다. 최상위에서 `await`을 쓸 수 없는 위치라면 `loadEnvironments().then(loadSimulations);` 형태로 바꾼다.

- [ ] **Step 6: 로컬 대상일 때 UI 규칙을 맞춘다**

`updateVisibility` 안의 Member ID 파일 표시 줄을 바꾼다. 기존:

```javascript
  setVisible('[data-option="member-ids-file"]', existingMemberIdSimulationKeys.has(selected?.key));
```

변경 후:

```javascript
  setVisible('[data-option="member-ids-file"]',
          existingMemberIdSimulationKeys.has(selected?.key) && !coreTargetIsLocal());
```

`validateBeforeRun` 안의 booking 분기에서 localhost 거부를 실행 확인 규칙으로 바꾼다. 기존:

```javascript
    if (isLocalhostUrl(coreBaseUrl)) return 'Booking 운영 부하 테스트의 Ticket/Core URL은 localhost를 사용할 수 없습니다.';
```

변경 후:

```javascript
    if (distributed && isLocalhostUrl(coreBaseUrl)) return 'EC2 분산 실행의 Ticket/Core URL은 localhost를 사용할 수 없습니다.';
```

같은 분기의 Queue URL 검증도 같게 바꾼다. 기존:

```javascript
    if (selected.usesQueueBaseUrl && isLocalhostUrl(queueBaseUrl)) return 'Booking 운영 부하 테스트의 Queue URL은 localhost를 사용할 수 없습니다.';
```

변경 후:

```javascript
    if (distributed && selected.usesQueueBaseUrl && isLocalhostUrl(queueBaseUrl)) return 'EC2 분산 실행의 Queue URL은 localhost를 사용할 수 없습니다.';
```

같은 분기의 무조건 실행 확인 요구를 로컬 예외로 바꾼다. 기존:

```javascript
    if (body.get('operationalConfirmation') !== 'on') return '운영 API에 실제 좌석 선점·주문 요청을 보내는 실행 확인이 필요합니다.';
```

변경 후:

```javascript
    if (!isLocalhostUrl(coreBaseUrl) && body.get('operationalConfirmation') !== 'on') return '운영 API에 실제 좌석 선점·주문 요청을 보내는 실행 확인이 필요합니다.';
```

`core-admission-capacity` 분기의 Member ID 파일 요구도 로컬을 면제한다. 기존:

```javascript
        if (!String(body.get('memberIdsFile') || '').trim()) return '기존 회원 ID 파일 경로를 입력해야 합니다.';
```

변경 후:

```javascript
        if (!isLocalhostUrl(coreBaseUrl) && !String(body.get('memberIdsFile') || '').trim()) return '기존 회원 ID 파일 경로를 입력해야 합니다.';
```

- [ ] **Step 7: 테스트가 통과하는지 확인한다**

```powershell
.\gradlew.bat -p console test
```

기대: 전체 PASS.

---

## Task 7: 콘솔 문서 갱신

**Files:**
- Modify: `console/README.md`
- Modify: `README.md`

- [ ] **Step 1: console/README.md의 대상 정책을 고친다**

`## 안전 주의` 목록의 마지막 항목을 바꾼다. 기존:

```markdown
- Queue·legacy·CDN 대상 URL은 기본값이 없으며 직접 입력한다. localhost가 아닌 대상은 실행 확인 체크 없이는 서버가 거부한다.
```

변경 후:

```markdown
- Queue·legacy·CDN 대상 URL은 기본값이 없으며 직접 입력한다. localhost가 아닌 대상은 실행 확인 체크 없이는 서버가 거부한다.
- Core 대상 URL은 `대상 환경` 드롭다운에서 고르거나 직접 입력한다. 목록은 `console/environments.properties`에서 관리하며 로컬 대상은 실행 확인 체크를 요구하지 않는다.
```

`## 화면에서 설정하는 값` 표의 `대상` 행을 바꾼다. 기존:

```markdown
| 대상 | Gatling 저장소 경로, 대상 API URL, 회차 ID, 좌석 ID |
```

변경 후:

```markdown
| 대상 | 대상 환경(로컬·운영·직접 입력), 대상 API URL, 회차 ID, 좌석 ID |
```

- [ ] **Step 2: 대상 환경 절을 더한다**

`console/README.md`의 `## 시뮬레이션별 대상 API 정책` 바로 앞에 다음을 넣는다.

```markdown
## 대상 환경

Core 시나리오의 대상 URL은 `console/environments.properties`에서 관리한다.

```properties
targets=local,prod

local.label=로컬 (H2)
local.coreBaseUrl=http://localhost:8080
local.queueBaseUrl=http://localhost:8081

prod.label=운영 (oneticket.site)
prod.coreBaseUrl=https://oneticket.site
prod.queueBaseUrl=https://queue.oneticket.site
```

`targets`가 화면 표시 순서를 정한다. 대상을 추가하려면 키를 `targets`에 넣고 아래에 `label`, `coreBaseUrl`, `queueBaseUrl`을 쓴다. 콘솔 코드를 고치거나 다시 빌드할 필요는 없고 콘솔만 다시 시작하면 된다.

드롭다운에서 대상을 고르면 URL 입력칸이 그 값으로 채워진다. 채운 뒤에도 입력칸은 그대로 고칠 수 있으며, 고치면 드롭다운이 `직접 입력`으로 바뀐다. 실제로 요청을 보내는 값은 언제나 입력칸의 값이다.

로컬 대상은 실행 확인 체크와 Member ID 파일을 요구하지 않는다. 로컬 시드가 회원 ID를 1부터 연속으로 만들기 때문에 회원 ID 파일 없이 연속 ID를 그대로 쓴다. 로컬 대상에서는 Datadog 환경 메타데이터 수집도 하지 않는다.

EC2 분산 실행은 로컬 대상을 지원하지 않는다. VM에서 localhost는 대상 서버를 가리키지 않는다.
```

- [ ] **Step 3: 루트 README.md에 로컬 실행을 적는다**

`## Core API별 독립 성능 시나리오` 절의 마지막 문단을 바꾼다. 기존:

```markdown
단일 API 테스트 안에서는 준비용 API를 호출하지 않는다. 예를 들어 주문 생성 테스트가 좌석 선점까지 호출하면 Redis·락·좌석 검증 부하가 주문 생성의 DB 비용에 섞이기 때문이다. 따라서 상태 변경 API는 실행 전에 데이터를 준비하고 실행 후 원상 복구한다. 현재 분산 Booking 실행기는 전체 예매 증거 파일을 전제로 하므로 이 다섯 테스트는 로컬 Gatling 실행만 지원한다.
```

변경 후:

```markdown
단일 API 테스트 안에서는 준비용 API를 호출하지 않는다. 예를 들어 주문 생성 테스트가 좌석 선점까지 호출하면 Redis·락·좌석 검증 부하가 주문 생성의 DB 비용에 섞이기 때문이다. 따라서 상태 변경 API는 실행 전에 데이터를 준비하고 실행 후 원상 복구한다. 현재 분산 Booking 실행기는 전체 예매 증거 파일을 전제로 하므로 이 다섯 테스트는 로컬 Gatling 실행만 지원한다.

대상 서버는 콘솔의 `대상 환경`에서 고른다. 로컬 Ticket/Core를 대상으로 하려면 ticket 저장소를 `local` 프로파일로 실행해 `app.seed.load-test-fixture.enabled=true`가 만든 전용 회차·좌석과 부하테스트 회원을 준비한다. 로컬 전용 데이터는 운영과 같은 고정 ID 대역을 쓰므로 회차 배정표를 그대로 쓸 수 있다.
```

- [ ] **Step 4: 문서에 깨진 부분이 없는지 확인한다**

```powershell
git diff --check
rg -n "environments.properties" README.md console/README.md
```

기대: `git diff --check` 출력 없음, `rg` 결과에 `console/README.md` 항목이 나온다.

---

## Task 8: ticket 로컬 설정

작업 디렉터리를 바꾼다.

```powershell
cd C:\Users\mn040\IdeaProjects\ticket-workspace\ticket
```

**Files:**
- Modify: `core/core-api/src/main/resources/application.yml`
- Modify: `core/core-api/src/main/resources/application-local.yml`

- [ ] **Step 1: 기본값을 끈다**

`application.yml`의 `app.seed` 블록을 바꾼다. 기존:

```yaml
  seed:
    enabled: false
    batch-size: 500
```

변경 후:

```yaml
  seed:
    enabled: false
    batch-size: 500
    load-test-fixture:
      enabled: false
      performance-count: 8
```

- [ ] **Step 2: 로컬에서만 켠다**

`application-local.yml`의 `app.seed` 블록을 바꾼다. 기존:

```yaml
app:
  seed:
    enabled: true
```

변경 후:

```yaml
app:
  seed:
    enabled: true
    load-test-members:
      count: 2000
    load-test-fixture:
      enabled: true
      performance-count: 8
```

- [ ] **Step 3: 설정이 읽히는지 확인한다**

```powershell
rg -n "load-test-fixture" core/core-api/src/main/resources
```

기대: `application.yml`과 `application-local.yml` 두 파일이 나온다.

---

## Task 9: 로컬 전용 부하테스트 시더

**Files:**
- Create: `core/core-api/src/main/java/com/ticket/core/config/seed/LoadTestFixtureSeeder.java`
- Create: `core/core-api/src/test/java/com/ticket/core/config/seed/LoadTestFixtureSeederTest.java`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`core/core-api/src/test/java/com/ticket/core/config/seed/LoadTestFixtureSeederTest.java`:

```java
package com.ticket.core.config.seed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoadTestFixtureSeederTest {

    private static final long ID_BASE = 910000000L;

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        jdbcTemplate.execute("""
                CREATE TABLE venues (
                  id BIGINT PRIMARY KEY, name VARCHAR(255), address VARCHAR(255), region VARCHAR(50),
                  address_detail VARCHAR(255), zip_code VARCHAR(20), latitude DOUBLE, longitude DOUBLE,
                  phone VARCHAR(50), image_url VARCHAR(500), view_box_width INT NOT NULL,
                  view_box_height INT NOT NULL, seat_diameter DOUBLE NOT NULL, gap_x DOUBLE, gap_y DOUBLE,
                  created_at TIMESTAMP NOT NULL, created_by VARCHAR(255) NOT NULL)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE shows (
                  id BIGINT PRIMARY KEY, title VARCHAR(255), sub_title VARCHAR(255), info VARCHAR(1000),
                  start_date DATE, end_date DATE, view_count BIGINT NOT NULL, sale_type VARCHAR(50),
                  sale_start_date TIMESTAMP, sale_end_date TIMESTAMP, image VARCHAR(500),
                  venue_id BIGINT, running_minutes INT, performer_id BIGINT,
                  created_at TIMESTAMP NOT NULL, created_by VARCHAR(255) NOT NULL)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE seats (
                  id BIGINT PRIMARY KEY, section VARCHAR(50) NOT NULL, row_no VARCHAR(50) NOT NULL,
                  seat_no VARCHAR(50) NOT NULL, floor INT NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL,
                  created_at TIMESTAMP NOT NULL, created_by VARCHAR(255) NOT NULL)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE show_grades (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, show_id BIGINT NOT NULL,
                  grade_code VARCHAR(50) NOT NULL, grade_name VARCHAR(50) NOT NULL,
                  price NUMERIC(19,2) NOT NULL, sort_order INT NOT NULL,
                  created_at TIMESTAMP NOT NULL, created_by VARCHAR(255) NOT NULL)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE show_seats (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, show_id BIGINT NOT NULL,
                  seat_id BIGINT NOT NULL, show_grade_id BIGINT NOT NULL,
                  created_at TIMESTAMP NOT NULL, created_by VARCHAR(255) NOT NULL)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE performances (
                  id BIGINT PRIMARY KEY, show_id BIGINT, performance_no INT,
                  start_time TIMESTAMP, end_time TIMESTAMP, order_open_time TIMESTAMP,
                  order_close_time TIMESTAMP, max_can_hold_count INT, hold_time INT,
                  created_at TIMESTAMP NOT NULL, created_by VARCHAR(255) NOT NULL)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE performance_queue_policies (
                  performance_id BIGINT PRIMARY KEY, queue_mode VARCHAR(50), queue_level VARCHAR(50),
                  preopen_queue_start_at TIMESTAMP, waiting_room_message VARCHAR(500),
                  reason VARCHAR(500), created_at TIMESTAMP NOT NULL, created_by VARCHAR(255) NOT NULL)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE performance_seats (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, performance_id BIGINT NOT NULL,
                  seat_id BIGINT NOT NULL, state VARCHAR(50), price NUMERIC(19,2),
                  created_at TIMESTAMP NOT NULL, created_by VARCHAR(255) NOT NULL)
                """);
    }

    private LoadTestFixtureSeeder seeder(final boolean enabled, final int performanceCount) {
        return new LoadTestFixtureSeeder(jdbcTemplate, enabled, performanceCount);
    }

    private int count(final String sql) {
        final Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    @Test
    void createsDedicatedShowSeatsAndPerformances() {
        seeder(true, 3).seedLoadTestFixture();

        assertEquals(1, count("SELECT COUNT(*) FROM venues WHERE id = " + (ID_BASE + 1)));
        assertEquals(1, count("SELECT COUNT(*) FROM shows WHERE id = " + (ID_BASE + 1)));
        assertEquals(2000, count("SELECT COUNT(*) FROM seats"));
        assertEquals(4, count("SELECT COUNT(*) FROM show_grades"));
        assertEquals(2000, count("SELECT COUNT(*) FROM show_seats"));
        assertEquals(3, count("SELECT COUNT(*) FROM performances"));
        assertEquals(6000, count("SELECT COUNT(*) FROM performance_seats"));
    }

    @Test
    void usesFixedIdRangeStartingAt910000001() {
        seeder(true, 2).seedLoadTestFixture();

        assertEquals(2000, count(
                "SELECT COUNT(*) FROM seats WHERE id BETWEEN 910000001 AND 910002000"));
        assertEquals(2, count(
                "SELECT COUNT(*) FROM performances WHERE id BETWEEN 910000001 AND 910000002"));
    }

    @Test
    void marksEveryPerformanceQueuePolicyAsForceOff() {
        seeder(true, 4).seedLoadTestFixture();

        assertEquals(4, count("SELECT COUNT(*) FROM performance_queue_policies"));
        assertEquals(4, count(
                "SELECT COUNT(*) FROM performance_queue_policies WHERE queue_mode = 'FORCE_OFF'"));
    }

    @Test
    void createsEverySeatAsAvailable() {
        seeder(true, 1).seedLoadTestFixture();

        assertEquals(2000, count(
                "SELECT COUNT(*) FROM performance_seats WHERE state = 'AVAILABLE'"));
    }

    @Test
    void doesNothingWhenDisabled() {
        seeder(false, 8).seedLoadTestFixture();

        assertEquals(0, count("SELECT COUNT(*) FROM seats"));
        assertEquals(0, count("SELECT COUNT(*) FROM performances"));
    }

    @Test
    void isIdempotentWhenRunTwice() {
        seeder(true, 2).seedLoadTestFixture();
        seeder(true, 2).seedLoadTestFixture();

        assertEquals(2000, count("SELECT COUNT(*) FROM seats"));
        assertEquals(2, count("SELECT COUNT(*) FROM performances"));
        assertEquals(4000, count("SELECT COUNT(*) FROM performance_seats"));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```powershell
.\gradlew.bat :core:core-api:test --tests "*LoadTestFixtureSeederTest*"
```

기대: 컴파일 실패. `LoadTestFixtureSeeder`가 없다.

- [ ] **Step 3: 시더를 구현한다**

`core/core-api/src/main/java/com/ticket/core/config/seed/LoadTestFixtureSeeder.java`:

```java
package com.ticket.core.config.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 로컬 부하 테스트 전용 회차·좌석 데이터를 만든다.
 *
 * <p>운영 Oracle의 {@code create-core-capacity-data.sql}과 같은 고정 ID 대역을 쓴다.
 * 그래야 Gatling Console의 좌석 시작 ID와 회차 배정표를 대상만 바꿔 그대로 쓸 수 있다.
 * 공용 시드인 {@code SeedDataLoader}와 {@code seed/kopis-curated.sql}은 건드리지 않는다.
 */
@Slf4j
@Component
@Order(100)
public class LoadTestFixtureSeeder implements ApplicationRunner {

    static final long ID_BASE = 910000000L;
    static final int SEAT_COUNT = 2000;
    private static final long VENUE_ID = ID_BASE + 1;
    private static final long SHOW_ID = ID_BASE + 1;
    private static final String CREATED_BY = "LOAD_TEST_FIXTURE";
    private static final int SEATS_PER_SECTION = 200;
    private static final int SEATS_PER_ROW = 10;
    private static final int SEATS_PER_VIEW_ROW = 50;
    private static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final int performanceCount;

    public LoadTestFixtureSeeder(
            final JdbcTemplate jdbcTemplate,
            @Value("${app.seed.load-test-fixture.enabled:false}") final boolean enabled,
            @Value("${app.seed.load-test-fixture.performance-count:8}") final int performanceCount
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.performanceCount = performanceCount;
    }

    @Override
    public void run(final ApplicationArguments args) {
        seedLoadTestFixture();
    }

    @Transactional
    public void seedLoadTestFixture() {
        if (!enabled) {
            log.info("부하 테스트 전용 데이터 시드를 건너뜁니다. app.seed.load-test-fixture.enabled=false");
            return;
        }
        if (performanceCount <= 0) {
            log.info("부하 테스트 전용 데이터 시드를 건너뜁니다. performance-count={}", performanceCount);
            return;
        }
        if (alreadySeeded()) {
            log.info("부하 테스트 전용 데이터가 이미 있습니다. 적재를 건너뜁니다. showId={}", SHOW_ID);
            return;
        }

        final LocalDateTime now = LocalDateTime.now();
        seedVenue(now);
        seedShow(now);
        seedSeats(now);
        final List<Long> gradeIds = seedShowGrades(now);
        seedShowSeats(now, gradeIds);
        seedPerformances(now);
        seedQueuePolicies(now);
        seedPerformanceSeats(now);

        log.info(
                "부하 테스트 전용 데이터 시드를 완료했습니다. showId={}, 회차={}, 좌석={}, 회차좌석={}",
                SHOW_ID, performanceCount, SEAT_COUNT, performanceCount * SEAT_COUNT
        );
    }

    private boolean alreadySeeded() {
        final Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shows WHERE id = ?", Integer.class, SHOW_ID);
        return count != null && count > 0;
    }

    private void seedVenue(final LocalDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO venues (
                  id, name, address, region, address_detail, zip_code,
                  latitude, longitude, phone, image_url,
                  view_box_width, view_box_height, seat_diameter, gap_x, gap_y,
                  created_at, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                VENUE_ID, "[LOAD TEST] Core Capacity Venue", "부하테스트 전용", "SEOUL",
                "로컬 Core 부하 측정 전용 데이터", "00000",
                37.5, 127.0, "000-0000-0000", null,
                500, 356, 6.0, 9.0, 8.0,
                Timestamp.valueOf(now), CREATED_BY);
    }

    private void seedShow(final LocalDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO shows (
                  id, title, sub_title, info,
                  start_date, end_date, view_count, sale_type,
                  sale_start_date, sale_end_date, image,
                  venue_id, running_minutes, performer_id,
                  created_at, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                SHOW_ID,
                "[LOAD TEST] Core Admission Capacity 2000석",
                "로컬 부하테스트 전용 공연",
                "실제 사용자에게 노출하거나 판매하지 않는 부하 측정 전용 데이터",
                java.sql.Date.valueOf(now.toLocalDate().plusDays(60)),
                java.sql.Date.valueOf(now.toLocalDate().plusDays(90)),
                0L, "GENERAL",
                Timestamp.valueOf(now.minusDays(1)),
                Timestamp.valueOf(now.plusDays(30)),
                null, VENUE_ID, 120, null,
                Timestamp.valueOf(now), CREATED_BY);
    }

    private void seedSeats(final LocalDateTime now) {
        final Timestamp createdAt = Timestamp.valueOf(now);
        final List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        for (int index = 1; index <= SEAT_COUNT; index++) {
            final int section = (index - 1) / SEATS_PER_SECTION + 1;
            final int rowNo = ((index - 1) % SEATS_PER_SECTION) / SEATS_PER_ROW + 1;
            final int seatNo = (index - 1) % SEATS_PER_ROW + 1;
            batch.add(new Object[]{
                    ID_BASE + index,
                    String.format("SEC-%02d", section),
                    String.format("ROW-%02d", rowNo),
                    String.format("%02d", seatNo),
                    section <= 6 ? 1 : 2,
                    20.0 + (index - 1) % SEATS_PER_VIEW_ROW * 9,
                    20.0 + (double) ((index - 1) / SEATS_PER_VIEW_ROW) * 8,
                    createdAt,
                    CREATED_BY
            });
            if (batch.size() == BATCH_SIZE || index == SEAT_COUNT) {
                jdbcTemplate.batchUpdate("""
                        INSERT INTO seats (id, section, row_no, seat_no, floor, x, y, created_at, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, batch);
                batch.clear();
            }
        }
    }

    private List<Long> seedShowGrades(final LocalDateTime now) {
        final Timestamp createdAt = Timestamp.valueOf(now);
        final String[][] grades = {
                {"VIP", "VIP석", "150000", "1"},
                {"R", "R석", "120000", "2"},
                {"S", "S석", "90000", "3"},
                {"A", "A석", "60000", "4"}
        };
        for (String[] grade : grades) {
            jdbcTemplate.update("""
                    INSERT INTO show_grades (
                      show_id, grade_code, grade_name, price, sort_order, created_at, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    SHOW_ID, grade[0], grade[1], new BigDecimal(grade[2]),
                    Integer.parseInt(grade[3]), createdAt, CREATED_BY);
        }
        return jdbcTemplate.queryForList(
                "SELECT id FROM show_grades WHERE show_id = ? ORDER BY sort_order",
                Long.class, SHOW_ID);
    }

    private void seedShowSeats(final LocalDateTime now, final List<Long> gradeIds) {
        final Timestamp createdAt = Timestamp.valueOf(now);
        final List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        for (int index = 1; index <= SEAT_COUNT; index++) {
            batch.add(new Object[]{
                    SHOW_ID, ID_BASE + index, gradeIds.get(gradeIndex(index)), createdAt, CREATED_BY
            });
            if (batch.size() == BATCH_SIZE || index == SEAT_COUNT) {
                jdbcTemplate.batchUpdate("""
                        INSERT INTO show_seats (show_id, seat_id, show_grade_id, created_at, created_by)
                        VALUES (?, ?, ?, ?, ?)
                        """, batch);
                batch.clear();
            }
        }
    }

    /** 구역 1~2는 VIP, 3~4는 R, 5~7은 S, 나머지는 A로 나눈다. 운영 데이터와 같은 비율이다. */
    private int gradeIndex(final int seatIndex) {
        final int section = (seatIndex - 1) / SEATS_PER_SECTION + 1;
        if (section <= 2) {
            return 0;
        }
        if (section <= 4) {
            return 1;
        }
        if (section <= 7) {
            return 2;
        }
        return 3;
    }

    private void seedPerformances(final LocalDateTime now) {
        final Timestamp createdAt = Timestamp.valueOf(now);
        final List<Object[]> batch = new ArrayList<>(performanceCount);
        for (int index = 1; index <= performanceCount; index++) {
            final LocalDateTime startTime = now.plusDays(60L + index);
            batch.add(new Object[]{
                    ID_BASE + index, SHOW_ID, index,
                    Timestamp.valueOf(startTime),
                    Timestamp.valueOf(startTime.plusMinutes(120)),
                    Timestamp.valueOf(now.minusDays(1)),
                    Timestamp.valueOf(now.plusDays(30)),
                    2, 600, createdAt, CREATED_BY
            });
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO performances (
                  id, show_id, performance_no, start_time, end_time,
                  order_open_time, order_close_time, max_can_hold_count, hold_time,
                  created_at, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
    }

    private void seedQueuePolicies(final LocalDateTime now) {
        final Timestamp createdAt = Timestamp.valueOf(now);
        final List<Object[]> batch = new ArrayList<>(performanceCount);
        for (int index = 1; index <= performanceCount; index++) {
            batch.add(new Object[]{
                    ID_BASE + index, "FORCE_OFF", "LEVEL_1", null,
                    "로컬 Core 부하 측정 전용", "Queue 없이 Core를 직접 호출하는 전용 회차",
                    createdAt, CREATED_BY
            });
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO performance_queue_policies (
                  performance_id, queue_mode, queue_level, preopen_queue_start_at,
                  waiting_room_message, reason, created_at, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
    }

    private void seedPerformanceSeats(final LocalDateTime now) {
        final Timestamp createdAt = Timestamp.valueOf(now);
        final List<BigDecimal> prices = jdbcTemplate.queryForList(
                "SELECT price FROM show_grades WHERE show_id = ? ORDER BY sort_order",
                BigDecimal.class, SHOW_ID);
        for (int performance = 1; performance <= performanceCount; performance++) {
            final long performanceId = ID_BASE + performance;
            final List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
            for (int index = 1; index <= SEAT_COUNT; index++) {
                batch.add(new Object[]{
                        performanceId, ID_BASE + index, "AVAILABLE",
                        prices.get(gradeIndex(index)), createdAt, CREATED_BY
                });
                if (batch.size() == BATCH_SIZE || index == SEAT_COUNT) {
                    jdbcTemplate.batchUpdate("""
                            INSERT INTO performance_seats (
                              performance_id, seat_id, state, price, created_at, created_by
                            ) VALUES (?, ?, ?, ?, ?, ?)
                            """, batch);
                    batch.clear();
                }
            }
        }
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

```powershell
.\gradlew.bat :core:core-api:test --tests "*LoadTestFixtureSeederTest*"
```

기대: PASS 6건.

- [ ] **Step 5: 기존 테스트가 깨지지 않았는지 확인한다**

```powershell
.\gradlew.bat :core:core-api:test
```

기대: 전체 PASS.

---

## Task 10: 통합 수동 확인

- [ ] **Step 1: 로컬 Core를 다시 시작한다**

`local` 프로파일로 ticket 애플리케이션을 실행한다. 기동 로그에서 다음 두 줄을 확인한다.

```text
부하 테스트 회원 시드를 완료했습니다. requested=2000, created=...
부하 테스트 전용 데이터 시드를 완료했습니다. showId=910000001, 회차=8, 좌석=2000, 회차좌석=16000
```

- [ ] **Step 2: 데이터가 실제로 들어갔는지 확인한다**

```powershell
java -cp "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.h2database\h2\2.3.232\4fcc05d966ccdb2812ae8b9a718f69226c0cf4e2\h2-2.3.232.jar" org.h2.tools.Shell -url "jdbc:h2:file:~/ticket-local;MODE=Oracle;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1" -user sa -password "" -sql "SELECT (SELECT COUNT(*) FROM members WHERE deleted_at IS NULL AND role='MEMBER') AS members, (SELECT COUNT(*) FROM performances WHERE id BETWEEN 910000001 AND 910000030) AS perfs, (SELECT COUNT(*) FROM performance_seats WHERE seat_id BETWEEN 910000001 AND 910002000) AS seats;"
```

기대: `members=2000`, `perfs=8`, `seats=16000`.

- [ ] **Step 3: 콘솔을 다시 시작하고 대상 환경을 확인한다**

```powershell
.\gradlew.bat -p console run
```

브라우저에서 `http://localhost:9090`을 열고 `대상 환경`에 `로컬 (H2)`, `운영 (oneticket.site)`, `직접 입력`이 나오는지 본다. `로컬 (H2)`를 고르면 Ticket/Core URL이 `http://localhost:8080`으로 채워지고, 값을 손으로 고치면 드롭다운이 `직접 입력`으로 바뀌는지 확인한다.

- [ ] **Step 4: 다섯 시나리오를 순서대로 실행한다**

대상 환경은 `로컬 (H2)`, 부하는 `10 users/sec × 30초`로 고정한다.

```text
GET  summary          performanceId 910000001
GET  seats/status     performanceId 910000001
POST seats/select     performanceId 910000022
POST orders           performanceId 910000022  (좌석 선점 후 10분 안)
GET  orders/{orderKey} 주문 생성이 만든 결과 CSV 사용
```

기대: 다섯 실행 모두 시작되고 Gatling 리포트가 생성된다. 좌석 선점과 주문 생성은 성공 건수가 예상 사용자 수와 같아야 한다.

- [ ] **Step 5: 운영 대상이 그대로인지 확인한다**

`대상 환경`을 `운영 (oneticket.site)`으로 바꾼다. 실행 확인 체크박스를 켜지 않은 채 실행을 누르면 실행 확인을 요구하는 메시지가 나오고, `Member ID File` 입력칸이 다시 보이는지 확인한다.

기대: 운영 대상 동작이 이번 변경 전과 같다.

---

## 구현하며 계획과 달라진 점

계획을 그대로 따르지 않은 부분만 적는다. 코드가 기준이다.

1. **`queueBaseUrlInput`은 이미 선언돼 있었다.** Task 6 Step 5에서 새로 선언하면 `Identifier 'queueBaseUrlInput' has already been declared`로 스크립트 전체가 죽어 화면이 빈 채로 뜬다. 기존 선언을 그대로 쓴다. 같은 사고를 막으려고 `ConsoleTargetEnvironmentHtmlTest.declaresEachTargetInputReferenceExactlyOnce`를 더했다.

2. **미리보기의 운영 URL 대체값을 지웠다.** `updateTargetRouteSummary`와 `updateApiPreview`가 입력칸이 비면 `https://oneticket.site`를 대신 보여주고 있었다. 대상 URL이 더 이상 하드코딩되지 않으므로 이 대체값은 실제로 보낼 대상과 다른 주소를 화면에 띄운다. `(대상 미지정)`으로 바꿨다.

3. **`@Transactional`을 `run()`으로 옮겼다.** Task 9의 원래 코드는 `seedLoadTestFixture()`에 트랜잭션을 걸고 `run()`에서 자기 호출했다. 자기 호출은 프록시를 지나지 않아 트랜잭션이 아예 적용되지 않는다. 중간에 실패하면 절반만 적재된 상태로 남고, 다음 기동에서 `alreadySeeded()`가 그 데이터를 보고 건너뛴다. 경계를 `run()`에 둔다.

4. **기존 `ConsoleIndexHtmlTest` 두 곳을 갱신했다.** 하드코딩된 `coreBaseUrl`·`queueBaseUrl` 값과 `'hot-seat-concurrency': { coreBaseUrl: ... }`를 고정하고 있었다. 이번 설계가 그 하드코딩을 없애는 것이므로 새 형태로 바꿨다.

## 검증 결과

```text
gradlew -p console test          BUILD SUCCESSFUL (106건)
gradlew test (ticket)            BUILD SUCCESSFUL
```

브라우저 확인(Playwright)으로 아래를 실제 화면에서 확인했다.

```text
대상 환경 목록          local, prod, 직접 입력
최초 선택               local, Core=http://localhost:8080, Queue=http://localhost:8081
운영으로 전환           Core=https://oneticket.site, Queue=https://queue.oneticket.site
URL 직접 수정           드롭다운이 __custom__으로 전환
운영 + 좌석 상태 API    Member ID 파일 입력칸 표시
로컬 + 좌석 상태 API    Member ID 파일 입력칸 숨김
```

Task 10의 로컬 Core 재시작과 다섯 시나리오 실제 실행은 아직 하지 않았다.

5. **`ApplicationRunner` 실행 순서를 명시했다.** 첫 로컬 기동에서 `SeedDataLoader`가 다음 오류로 실패해 앱이 뜨지 않았다.

   ```text
   Scalar subquery contains more than one row
   INSERT INTO SHOW_SEATS ... FROM SHOWS s CROSS JOIN SEATS st
   ```

   `seed/kopis-curated.sql`의 SHOW_GRADES·SHOW_SEATS·PERFORMANCE_SEATS는 집합 기반이다.
   `FROM SHOWS s CROSS JOIN SEATS st` 형태라 실행 시점에 존재하는 모든 행을 대상으로 삼는다.
   `LoadTestFixtureSeeder`가 `@Order(100)`으로 먼저 돌면서 전용 show 910000001과 등급 4개를 만들었고,
   그 뒤 공용 시드가 같은 show에 등급 4개를 또 붙여 `grade_code`가 중복됐다.
   SHOW_SEATS의 스칼라 서브쿼리가 2행을 반환해 기동이 실패한다.

   `SeedDataLoader.ORDER = 0`을 명시하고 `LoadTestFixtureSeeder.ORDER = SeedDataLoader.ORDER + 100`으로
   묶어 공용 시드가 항상 먼저 끝나게 했다. `LoadTestFixtureSeederTest.runsAfterTheSharedSeedLoader`가 이 순서를 고정한다.
   공용 시드 클래스 변경은 `@Order` 애노테이션 하나뿐이며 시드 데이터는 건드리지 않았다.
