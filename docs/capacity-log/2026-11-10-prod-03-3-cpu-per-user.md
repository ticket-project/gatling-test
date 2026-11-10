# 2026-11-10 운영: 사용자당 CPU 줄이기 배포 뒤 03-3 사다리

## 요약

- ticket-core PR #305(`323c26b1`, 사용자당 CPU 줄이기 + 스케일 아웃 준비)를 운영에 배포하고 03-3 사다리를 다시 쟀다. 첫 배포는 새 필수 환경변수 `OAUTH2_REQUEST_COOKIE_KEY`가 없어 기동에 실패해 직전 이미지로 되돌아갔고, 서버에 키를 넣은 뒤 다시 배포했다.
- **첫 FAIL 90 → 110 u/s.** 100 u/s까지 PASS다. 110 u/s는 기술 실패 2.1%(응답 없음 69건)로 FAIL이다.
- **사용자당 Core CPU 16~17 → 11~13.6ms**(60~100 u/s). 11-09의 같은 부하보다 약 20% 적다.
- **110 u/s 응답 없음은 nginx FD 상한(1,024) 때문이었다(P-028).** 상한을 올리자 기술 실패는 0%가 됐고, 남은 FAIL은 응답 시간이다.
- **최종: 첫 FAIL 90 → 130 u/s(120 PASS).** nginx FD 상한·upstream keepalive와 Datadog `docker` 수집 끄기를 서버에 적용한 뒤 에이전트를 켠 채 쟀다. 130에서도 기술 실패는 0이고 호스트 CPU가 막는다.
- **Datadog 에이전트를 끄면 첫 FAIL 130 u/s다(P-030).** 15초마다 에이전트 수집이 dockerd·containerd를 1코어 가까이 1~2초 깨워 호스트가 멈췄다. 켠 상태의 100~110 u/s 판정은 이 멈춤에 따라 흔들린다.
- **100 u/s 위에서는 호스트 CPU가 꽉 찬다(P-029).** Core 프로세스 CPU는 0.4~0.67이지만 nginx가 Core의 절반만큼 CPU를 써 2 vCPU 호스트가 1.00에 붙는다. Hikari 대기는 80 u/s부터 생겼다(24).
- WebSocket 압축을 꺼도 서버 송신은 30초 평균 최대 약 33Mbps로 여유가 크다(Datadog `system.net.bytes_sent`).
- 처음 사다리(40 → 60 → 70)는 재시작 직후 JIT가 30초에 18~23초 돌아 70 u/s에서 FAIL했다. 40 u/s로 JIT가 3초 아래로 내려갈 때까지 데운 뒤 다시 쟀다. 판정은 두 번째 사다리로 한다.

## 조건

| 항목 | 값 |
| --- | --- |
| Core | `323c26b1`, c6i.large(2 vCPU, 메모리 3.8GB), Hikari 10, Datadog 트레이서 켬·프로파일러 끔 |
| nginx | `gzip on`, json, `gzip_min_length 1000`, `gzip_comp_level` 기본(1). Core 압축은 꺼짐 |
| 부하 발생기 | 3.35.158.179 한 대 |
| 시나리오 | 03-3, 좌석 상태 `occupancy`, 알림 `/seats/batch`(gatling 기본값) |
| DB | 회원 약 100만, 주문 약 503만, 대형 회차 920000185~200 사용 |

## 실행 표(데운 뒤)

| 시각 | 부하 | 회차 | 판정 | 사용자당 CPU | CPU 최대 | JIT | Hikari 사용/대기 | 바쁜 스레드 | 주문 p50/p95 | 점유 좌석 p95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 07:56 | 40 × 30s | 192 | PASS | 13.7ms | 0.60 | 4.0초 | 1/0 | 5 | 6/14ms | 2ms |
| 07:57 | 40 × 30s | 193 | PASS | 12.6ms | 0.45 | 3.3초 | 2/0 | 4 | 6/15ms | 1ms |
| 07:58 | 40 × 30s | 194 | PASS | 11.5ms | 0.51 | 1.9초 | 1/0 | 4 | 6/11ms | 1ms |
| 07:59 | 60 × 30s | 195 | PASS | 12.2ms | 0.64 | 2.3초 | 6/0 | 8 | 7/50ms | 6ms |
| 08:01 | 70 × 30s | 196 | PASS | 13.0ms | 0.67 | 4.0초 | 10/0 | 30 | 8/157ms | 22ms |
| 08:02 | 80 × 30s | 197 | PASS | 13.6ms | 0.74 | 6.9초 | 10/24 | 88 | 11/224ms | 28ms |
| 08:03 | 90 × 30s | 198 | PASS | 12.9ms | 0.78 | 8.9초 | 9/17 | 123 | 14/246ms | 34ms |
| 08:04 | 100 × 30s | 199 | PASS | 11.0ms | 0.70 | 5.3초 | 10/24 | 132 | 28/300ms | 157ms |
| 08:05 | 110 × 30s | 200 | FAIL | 10.8ms | 0.66 | 11.7초 | 10/77 | 135 | 89/626ms | 358ms |

### 원인 확인 재측정(08:51~09:15)

| 시각 | 부하 | 회차 | nginx 상태 | 판정 | 기술 실패 | 사용자당 CPU | Hikari 대기 | Tomcat 최대 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 08:51 | 60 | 201 | 기본 | PASS | 0% | 13.9ms | - | - |
| 08:54 | 110 | 202 | 기본 | FAIL | 3.3% | 10.8ms | 70 | 186 |
| 09:07 | 60 | 203 | `keepalive_requests 10000` | PASS | 0% | 10.0ms | - | - |
| 09:08 | 110 | 204 | `keepalive_requests 10000` | FAIL | 8.4% | 10.3ms | 21 | 180 |
| 09:13 | 60 | 205 | FD 65535 | FAIL(2초 멈춤) | 0% | 11.5ms | 0 | 184 |
| 09:14 | 110 | 206 | FD 65535 | FAIL(응답 시간) | **0%** | 11.0ms | 59 | 200 |

회차 201~220은 08:47에 seedProd로 보충했다(`large-performance-count=220`).

데우기 전 사다리(07:50~07:53, 회차 189~191): 40 PASS(29.6ms, JIT 23.3초), 60 PASS(17.9ms, JIT 18.0초), 70 FAIL(15.4ms, JIT 18.5초, 점유 좌석 p95 539ms·주문 p95 1,100ms).

11-09 같은 부하의 사용자당 CPU: 60 u/s 16.1ms, 70 u/s 16.9ms, 80 u/s 16.8ms([11-09 일지](2026-11-09-prod-03-3-fanout.md)).

## 발견한 문제

### P-028: 110 u/s 응답 없음은 nginx 작업 프로세스의 FD 상한(1,024)이었다

- **증상**: 기술 실패(응답 없음, `ClosedChannelException`)가 여러 단계에 고르게 나왔지만, 시각으로 보면 1초 안에 수십 건씩 몰렸다. 08:05 실행은 23:06:19에 26건·23:06:33 전후 43건, 08:52 재측정은 23:54:49에 64건·23:55:07에 44건이다. 그 사이 시간은 0건이다.
- **헛짚은 것**: 연결 대기열 넘침(nginx netns `ListenOverflows`·`ListenDrops` 0), `keepalive_requests` 1,000 상한(10,000으로 올리자 오히려 8.4%로 늘었다), `TCPAbortOnData`(사용자당 약 0.9건이라 평소 WebSocket 종료와 섞여 구분이 안 된다).
- **원인**: 09:08 실행 시간대 nginx 로그에 `accept4() failed (24: No file descriptors available)`와 `socket() failed (24: …) while connecting to upstream`(주문 500 1건과 같은 줄)이 있었다. 작업 프로세스 2개의 `open files` 상한이 1,024였다. 110 u/s에서 동시 사용자 약 970명이 WebSocket 하나씩 쥐고, nginx는 WebSocket마다 FD 2개(사용자 쪽·Core 쪽)를 써서 작업 프로세스당 약 970개가 된다. 2026-08-04 1,500명 실패와 같은 원인이고, 그때 짚은 `worker_rlimit_nofile`이 Core 앞 nginx에는 들어가 있지 않았다. 앞서 `not enough`만 검색해 0건으로 잘못 판단했다.
- **조치(사용자, 09:12)**: 서버 nginx.conf에 `worker_rlimit_nofile 65535;`, `worker_connections 8192;`, `keepalive_requests 10000;`을 넣고 reload했다. 작업 프로세스 상한 65535를 확인했다. 이 설정 파일은 저장소 밖에 있다.
- **결과**: 09:14 110 u/s(회차 206)는 기술 실패 0%다. 다만 응답 시간 기준을 넘어 FAIL이다(좌석 상태 p95 1,002ms, 주문 p95 1,596ms, Tomcat 200/200, Hikari 대기 59).

### P-029: 100 u/s 위에서는 호스트 CPU가 꽉 찬다. Core만 보면 여유가 있어 보인다

- **본 것**: 측정 도구의 `process_cpu`(Core)는 0.4~0.67인데 `system_cpu`(호스트 전체)는 100 u/s부터 30초 중 14~18번 표본이 1.00이다. 호스트 평균에서 Core를 빼면 0.41(2 vCPU 기준 약 0.8코어)이 다른 프로세스 몫이다.
- **누가 쓰나**: Datadog `container.cpu.usage`, 09:15:00~32(110 u/s): ticket-be 0.95코어, **ticket-nginx 0.48코어**, datadog-agent 0.08, ticket-redis 0.04. nginx가 Core의 절반만큼 CPU를 쓴다. TLS와 WebSocket 알림 중계가 큰 몫일 것으로 보지만 나눠 보지 않았다(미확인).
- **뜻**: 사용자당 CPU는 Core만 11ms이고 호스트 전체로는 약 14ms다. Hikari 대기·Tomcat 포화는 CPU를 못 받은 스레드가 연결을 쥔 채 늦어진 결과일 수 있다(추정). Core만의 CPU로 여유를 판단하면 안 된다.
- **고칠 곳 후보**: ① nginx `/api/`가 모든 요청에 `Connection "upgrade"`를 붙여 Core로 가는 연결을 재사용하지 못한다. `upstream keepalive`와 `/api/`의 `Connection ""`로 바꾸면 nginx·Core 양쪽 연결 생성 비용이 준다. ② TLS 종료를 ALB로 옮기면(스케일 아웃 결정 D0) 호스트에서 nginx 몫이 빠진다.
- **60 u/s 데우기 FAIL(09:13, 회차 205)**: 09:14:03~05 약 2초 동안 호스트 CPU 1.00, Tomcat 184로 멈춰 좌석 상태 p99 1,061ms였다. 기술 실패 0이다. reload 직후 한 번 생긴 멈춤으로 보고 판정에서 뺀다(원인 미확인).

### P-026 보완: 측정 도구가 Core 한 대일 때 사용자당 CPU를 0으로 적었다

- 인스턴스 여러 대를 읽게 바뀐 뒤(`bf2c75f`), 한 대면 PowerShell이 지표 배열을 문자열 하나로 풀어 CPU·JIT가 0, Hikari·Tomcat 최대가 빈 칸이었다. `27d3ea7`로 고쳤다. 07:39·07:41 예열은 고치기 전이라 summary의 CPU가 0이다(원문으로 다시 계산하면 172.5·73.3ms).

### P-030: 15초마다 Datadog 에이전트 수집이 호스트를 1~5초 멈춘다

- **증상**: 100 u/s가 08:04 PASS, 09:33·09:40 FAIL로 흔들렸다. 실행마다 약 15초 간격으로 호스트 CPU 1.00, Tomcat 100~160, Core 프로세스 CPU는 오히려 0.4로 내려가는 1~5초 멈춤이 있었다(60·100·110 u/s 모두). 멈춤이 짧으면 PASS, 길면 FAIL이었다. GC는 30초에 13번·최대 50ms라 아니다.
- **찾은 방법**: 사용자가 서버에서 `top -b -d 1`을 3분 기록하는 동안 100 u/s를 돌렸다(09:40, 회차 209). `dockerd`와 `containerd`가 15초마다 함께 40~68%씩(합쳐 약 1코어) 1~2초 튀었고, 부하 전후에도 같았다. 멈춘 00:41:18~19(UTC)는 java 77% + containerd 42% + dockerd 39% + nginx 약 35%였다. `agent`(Datadog)도 15초마다 20~43% 튀었다.
- **확인**: `docker stop datadog-agent` 뒤 같은 100 u/s(09:47, 회차 210)는 PASS였다. 호스트 1.00 표본 0/28, Tomcat 최대 42, Hikari 대기 0, 주문 p99 65~110ms(에이전트 켰을 때 1,400ms대).
- **후보 설정**(서버 compose, 미확인): 컨테이너 수치 수집(`docker.sock`), `DD_PROCESS_AGENT_ENABLED=true` + `pid: host`, Core 라벨의 openmetrics `".*"`(이건 Core CPU 쪽). 부하 없이 하나씩 꺼 보며 `top`으로 가를 수 있다.

### nginx upstream keepalive(09:32 적용)

`upstream backend`에 `keepalive 64; keepalive_timeout 15s;`, `/api/`의 `Connection ""`. 110 u/s 0.48코어였던 nginx가 100 u/s에서 0.27~0.33코어였다(부하가 달라 같은 조건 비교는 아니다). 60 u/s 사용자당 Core CPU 9.1ms.

### 에이전트를 끈 사다리(09:47~09:53)

| 시각 | 부하 | 회차 | 판정 | 사용자당 CPU | 호스트 1.00 표본 | Hikari 대기 | Tomcat 최대 | 실패 이유 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 09:47 | 100 | 210 | PASS | 9.5ms | 0/28 | 0 | 42 | |
| 09:50 | 110 | 211 | PASS | 9.7ms | 1/31 | 0 | 11 | |
| 09:51 | 120 | 212 | PASS | 10.6ms | 7/32 | 38 | 46 | |
| 09:52 | 130 | 213 | FAIL | 10.2ms | 14/29 | 50 | 200 | 좌석 상태 p95 646ms > 500 |

에이전트를 끄면 **첫 FAIL 130 u/s(120 PASS)**다. 130에서도 기술 실패는 0이고, 호스트 CPU가 1.00에 붙는 표본이 절반이라 다음 한계도 호스트 CPU다. 호스트 전체 사용자당 CPU는 약 13ms(0.87 × 2코어 ÷ 130)다. 측정 뒤 사용자가 에이전트를 다시 켠다.

### 에이전트 `docker` 수집을 끈 사다리(10:06~10:12)

무부하 확인(10:00): `agent status`에서 `docker` 수집이 한 번에 평균 779ms(다른 컨테이너 수집 7ms, Core openmetrics 199ms)였다. 사용자가 이 수집만 끄자 1분 `top`에서 dockerd·containerd 튐이 사라졌다. 사용자가 서버 compose의 datadog-agent에 빈 폴더 마운트 `./datadog/docker.d:/etc/datadog-agent/conf.d/docker.d:ro`를 넣고 에이전트만 다시 만들었다(`docker compose up -d --no-deps datadog-agent`, DOCKER_IMAGE·FRONTEND_DOCKER_IMAGE는 실행 중 컨테이너 값). `docker.*` 지표는 빠지고 `container.*`는 그대로다.

| 시각 | 부하 | 회차 | 판정 | 사용자당 CPU | 호스트 1.00 표본 | Hikari 대기 | Tomcat 최대 | 실패 이유 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10:06 | 110 | 214 | INVALID | 10.3ms | 6/32 | 5 | 179 | 입장 109~132/s(부하 발생기 흔들림) |
| 10:07 | 110 | 215 | PASS | 9.6ms | 3/33 | 0 | 25 | |
| 10:09 | 120 | 216 | PASS | 9.7ms | 9/30 | 2 | 30 | |
| 10:11 | 130 | 217 | FAIL | 10.3ms | 19/31 | 101 | 200 | 좌석 상태 p95 781ms, 주문 p95 1,056ms |

에이전트를 켠 운영 그대로의 조건에서 **첫 FAIL 130 u/s(120 PASS)**로, 에이전트를 끈 결과와 같다. 판정은 이 사다리로 한다.

## 직접 보려면

- 결과 폴더: `distributed-results-join/capacity/20261110-07*`·`20261110-08*`의 `summary.json`, `booking-results.csv`(110 u/s 기술 실패 종류).
- Datadog us5: `service:ticket-be env:prod` 2026-11-10 07:56~08:06 KST, 호스트 `system.net.bytes_sent`.
