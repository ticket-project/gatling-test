# 2026-11-09 로컬 Core 두 대

## 요약

로컬 PC에 Core 두 대를 띄우고 그 앞에 nginx를 둬서, 요청이 두 서버로 번갈아 가도 실패가 나지 않는지 봤다.
03은 20·60·100 u/s 모두 PASS였다. 03-3(좌석 알림 WebSocket 포함)은 30 u/s 유효 실행 한 번이 PASS였다.
네 번 모두 기술 실패와 중복 판매는 0건이었다. 두 대는 부하를 거의 반씩 받았고(데운 실행의 CPU 차이 2~6%),
`run-step.ps1`의 서버별 수집(`bf2c75f`)도 실제 Core 두 대에서 동작했다. 한 PC에서 CPU를 나눠 쓴 실행이라
"두 대면 몇 배 버티나"는 이 숫자로 말하지 않는다. 그 숫자는 운영에서 따로 잰다(ticket 스케일 아웃 결정 D3-3).

## 목적

- 스케일 아웃 결정의 "2노드 검증 테스트와 로컬 2노드 부하 측정"(D3-3 전에 할 일)이다. ticket 자동 테스트
  `TwoInstanceE2ETest`(`722315e3`)는 로그인·좌석 알림·예매·주문 만료를 기능 단위로 봤다. 여기서는 실제 부하에서 실패가 없는지 본다.
- 측정 도구가 Core 여러 대의 지표를 서버마다 읽어 합치는지 실제로 확인한다(`bf2c75f`는 가짜 지표 서버로만 시험했다).

## 조건

- Core: ticket `feat/scale-out-prep` `722315e3`, compose 컨테이너 두 대(`ticket-core`, `ticket-core-2`), 대당 Hikari 기본 10, worker 두 대 모두 켬.
- 앞단: nginx 1.27(`ticket-core-lb`, 127.0.0.1:8085), 라운드 로빈, sticky 없음. 구성은 ticket `docker-compose-local-2nodes.yml`.
- 지표: `-MetricsUrl "http://127.0.0.1:8083,http://127.0.0.1:8084"`(서버마다 직접), 부하: `-CoreUrl http://127.0.0.1:8085`.
- DB: compose PostgreSQL 18(회원 100만, 실행 전 주문 571만), Redis 7.4 한 대. 이 날 호스트 5432를 다른 프로젝트 컨테이너가 써서
  PostgreSQL을 15432로 띄웠다(작업용 override, `SPRING_DATASOURCE_URL`도 15432).
- `reset-local.ps1 -Compose`는 Core 한 대만 띄우므로 `core-local.json`을 손으로 썼다(container `ticket-core-lb`, port 8085, commit `722315e3`).
- 부하 발생기: 같은 PC의 Gatling. IntelliJ가 켜진 채였다(P-002 조건).

## 실행 표

| 시각 | 시나리오 | 부하 | 회차 | 성공/시작 | Gatling p95/p99 체류 | Core 쪽 p95(요청별) | 연결·스레드 최대 | 유효 | 판정 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 13:16 | 03 warmup | 20 × 30s | 920000120 | 600/600 | 72 / 942 | seats 10, select 11, order 34 | 9/10, busy 10 | 유효 | PASS |
| 13:17 | 03 warmup | 20 × 30s | 920000121 | 600/600 | 28 / 32 | seats 6, select 4, order 13 | 2/10, busy 2 | 유효 | PASS |
| 13:18 | 03 warmup | 20 × 30s | 920000122 | 600/600 | 26 / 30 | seats 6, select 4, order 11 | 1/10, busy 2 | 유효 | PASS |
| 13:19 | 03 | 60 × 30s | 920000123 | 1800/1800 | 20 / 41 | seats 2, select 3, order 10 | 2/10, busy 2 | 유효 | PASS |
| 13:19 | 03 | 100 × 30s | 920000124 | 3000/3000 | 33 / 373 | seats 2, select 3, order 17 | 2/10, busy 4 | 유효 | PASS |
| 13:20 | 03-3 | 30 × 30s | 920000125 | 0/900 | — | — | 1/10 | 유효 | FAIL(WebSocket 403, 아래) |
| 13:24 | 03-3 | 30 × 30s | 920000126 | 0/900 | — | — | 1/10 | 유효 | FAIL(같은 403, nginx 설정이 반영되지 않음) |
| 13:28 | 03-3 | 30 × 30s | 920000127 | 736/900 | 9,714 / 10,666 | seats 6, select 6, order 28 | 10/10 pend 3, busy 20 | INVALID(입장 30~51) | — |
| 13:29 | 03-3 | 30 × 30s | 920000128 | 727/900 | 9,542 / 10,383 | seats 4, select 3, order 22 | 5/10, busy 2 | 유효 | PASS |
| 13:30 | 03-3 | 30 × 30s | 920000129 | 727/900 | 9,759 / 10,477 | seats 6, select 3, order 56 | 4/10, busy 5 | INVALID(입장 29~46) | — |

- 03-3의 성공/시작이 900보다 작은 것은 이탈(주문 전 나감, 주문 뒤 취소)이 정상 응답으로 빠진 것이다. 체류가 9초대인 것도 사람이
  고민하는 시간(1~3초, 2~6초)을 넣은 시나리오라서다. 03-3은 체류로 판정하지 않는다.
- 03-3 중복 판매 검사: 920000127~129 모두 0쌍(주문 803~828건).

서버별(서버 1 = 8083, 서버 2 = 8084):

| 실행 | 서버 1 CPU 합 | 서버 2 CPU 합 | 서버 1 연결 최대 | 서버 2 연결 최대 |
| --- | --- | --- | --- | --- |
| 03 100 u/s | 28.13초 | 26.47초 | 2 | 1 |
| 03-3 30 u/s(920000128) | 25.41초 | 24.85초 | 3 | 5 |
| 03-3 30 u/s(920000127, 덜 데움) | 33.08초 | 38.91초 | 3 | 10(대기 3) |

## 발견한 문제

### 로컬 nginx 뒤에서 좌석 알림 WebSocket이 403으로 거절됐다

- 증상: 03-3 첫 두 번에서 900명 모두 `WebSocketClientHandshakeException: Invalid handshake response getStatus: 403`.
  Core에 직접(8083) 붙으면 같은 Origin으로 101이었다.
- 원인 1: nginx가 `Host`를 `$host`로 넘겨 포트(8085)가 빠졌다. Core의 WebSocket 출처 검사는 Origin(`http://127.0.0.1:8085`)이
  요청 주소와 같으면 허용하는데, 포트가 달라 다른 출처로 봤다.
- 원인 2(1을 고친 뒤에도 403): nginx가 `X-Forwarded-Proto`만 넘기면 Core(`server.forward-headers-strategy: framework`)가
  포트를 기본값(80)으로 되돌린다. `X-Forwarded-Host`에 포트가 붙은 Host를 넘기자 101이 됐다. 13:24 실행은 compose가 설정 내용
  변경을 알아채지 못해 nginx를 다시 만들지 않은 채 돈 것이다(`--force-recreate`로 해결).
- 운영 영향: 운영 FE는 허용 출처 목록(`APP_CORS_ALLOWED_ORIGINS`)으로 통과하고, ALB는 `X-Forwarded-Proto`와 `X-Forwarded-Port`를
  함께 보낸다. 그래서 이 문제는 로컬 도구 설정에 한정된다고 본다(ALB 경로는 확인하지 않았다). ALB를 붙일 때 Gatling처럼 API와 같은
  출처로 WebSocket을 여는 클라이언트가 있으면 확인한다.

### 03-3이 같은 PC에서 고르게 주입되지 않는다(INVALID 2번)

- 입장 30~51, 29~46명/초. Core 쪽 연결·스레드는 여유였다(대기 0~3). 같은 시각 IntelliJ가 켜져 있었다(P-002와 같은 조건).
  두 대라서 생긴 문제로 보지 않는다. 03-3은 사용자마다 WebSocket을 열어 Gatling 부담이 03보다 크다.

## 다음에 할 일

- 운영 Core 전용 호스트와 ALB가 생기면 1대 기준선 → 2대 → 3대 순으로 다시 잰다(D3-3). 그때 `prod-target.ps1`을 Core 전용 호스트에 맞게 고친다.
- `reset-local.ps1 -Compose`가 두 대 구성을 띄우고 `core-local.json`을 쓰게 할지는 다시 두 대로 잴 일이 생기면 정한다.
