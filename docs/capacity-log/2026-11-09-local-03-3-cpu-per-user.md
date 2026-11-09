# 2026-11-09 로컬: 사용자당 CPU 줄이기 변경 전후 03-3 비교

## 요약

- 같은 PC·데이터에서 Core만 바꿔 03-3을 쟀다. 변경 전은 운영과 같은 master `9e65d670`(옛 점유 좌석 `occupied` + 옛 알림 주소), 변경 후는 ticket-core `feat/scale-out-prep` `261b8497`(설계 [core-cpu-per-user](https://github.com/ticket-project/ticket-core/blob/feat/scale-out-prep/docs/plans/core-cpu-per-user.md) 1~6 전부, 시나리오는 `occupancy` + `/seats/batch`)이다.
- **사용자당 Core CPU**: 40 u/s 28.0 → 25.3ms(-10%), 60 u/s 19.1 → 19.4ms(+2%), 80 u/s 20.0 → 15.7ms(-22%). 방향은 줄었지만 실행마다 흔들림이 커서 설계 추정(약 -23%)을 로컬에서 확정하지는 못했다.
- **주문 응답이 늘었다**: Core 쪽 `POST /api/v1/orders` 평균 7.2~7.6 → 10.1~10.6ms, 취소도 약 3ms 늘었다. 저장소 시간은 줄었다(`OrderRepository.save` 1.62 → 1.09ms, `hold_history`·진행 중 주문 SELECT·공연 조회가 사라짐). 늘어난 원인은 찾지 못했다(아래 P-027).
- 사용자가 측정을 여기서 멈추라고 해 사다리를 더 올리지 않았다.

## 조건

| 항목 | 값 |
| --- | --- |
| 대상 | 로컬 compose Core(127.0.0.1:8083), PostgreSQL 18(호스트 15432 override), Redis 7.4, Datadog 꺼짐, Hikari 10 |
| 데이터 | 기존 로컬 DB(주문 약 571만, 회원 100만), 대형 회차(15,000석) 920000120~131 |
| PC | Gatling도 같은 PC, IntelliJ 켜짐(약 5.8GB) |
| 시나리오 | 03-3, 예열 5 u/s × 30초 3번 뒤 40·60·80 u/s × 30초 |
| gatling | `56edd8b`, 변경 전 `-SeatStatusSource occupied -SeatTopic legacy`, 변경 후 기본값(`occupancy`, `batch`) |

로컬에는 nginx가 없다. 변경 전은 Core가 응답을 gzip했고 변경 후는 아무도 압축하지 않는다. 운영에서는 압축 CPU가 nginx로 옮겨 가므로 이 차이만큼 로컬이 변경 후에 유리하다.

## 실행 표

| 시각 | Core | 부하 | 회차 | 판정 | 사용자당 Core CPU | CPU 최대 | 주문 평균 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 16:13 | 9e65d670 | 40 × 30s | 123 | PASS | 28.0ms | 0.18 | 7.6ms | |
| 16:14 | 9e65d670 | 60 × 30s | 124 | PASS | 19.1ms | 0.10 | 6.4ms | |
| 16:15 | 9e65d670 | 80 × 30s | 125 | INVALID | 20.0ms | 0.15 | 7.2ms | 입장이 고르지 않음(부하 발생기) |
| 16:26 | 261b8497 | 40 × 30s | 129 | PASS | 25.3ms | 0.14 | 10.6ms | |
| 16:28 | 261b8497 | 60 × 30s | 130 | INVALID | 19.4ms | 0.12 | 12.2ms | 입장 59~109/초(부하 발생기) |
| 16:30 | 261b8497 | 80 × 30s | 131 | INVALID | 15.7ms | 0.10 | 10.1ms | 입장이 고르지 않음(부하 발생기) |

INVALID는 모두 이 PC의 Gatling이 고르게 넣지 못한 것이다. Core 쪽 숫자(CPU, 요청별 시간)는 그대로 읽을 수 있다.

## 발견한 문제

### P-027: 변경 후 주문·취소 응답이 약 3ms 늘었다(원인 미확인)

- **증상**: 세 부하 모두 주문 평균이 7 → 10ms, 취소 p50이 4~6 → 7~8ms다. 좌석 선택·점유 좌석·배치도는 같다.
- **본 것**: 저장소 메서드 시간은 줄었다. DB 연결을 쥔 시간의 사용자당 합은 12.9 → 20.4ms(40 u/s)로 늘었는데, 주문 만료 청소의 `findFirstByStatusAndExpiresAtLessThanEqualAndIdGreaterThanOrderByIdAsc`가 한 번에 1.4~1.7초 걸리고 실행마다 7~9번 돌아 그 차이만 사용자당 약 4ms다. 이 청소 쿼리는 변경 전에도 같았다.
- **아직 모르는 것**: 주문·취소 요청 안에서 늘어난 3ms가 어느 구간인지. 둘에 공통으로 바뀐 것은 Redis 알림 relay(옛 주소로 즉시 전송 추가)와 업무 예외·보안 계측 변경이다.
- **다음**: Datadog을 켜고(`reset-local.ps1 -Compose -KeepData -Datadog`) 주문 trace를 전후로 비교한다. 만료 청소 쿼리의 1.4초도 따로 본다.

## 직접 보려면

- 결과 폴더: `distributed-results-join/capacity/20261109-16*cmp-{before,after}-03-3-*`의 `summary.json`(`server.coreCpuMsPerUser`, `coreRequests`), `prometheus-before.txt`·`prometheus-after.txt`(`spring_data_repository_invocations_seconds`, `hikaricp_connections_usage_seconds`).
