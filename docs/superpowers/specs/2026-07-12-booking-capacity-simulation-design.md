# 예매 용량 및 전체 흐름 부하 테스트 재구축 설계

## 1. 목표

운영 오픈 전 초기화 가능한 환경에서 3대의 Gatling VM이 실제 사용자 흐름을 발생시키고, 다음 흐름의 안전한 처리량을 측정한다.

```text
Queue join
→ public state polling
→ Queue enter
→ 좌석 상태 조회
→ 좌석 선택
→ PENDING 주문 생성
→ 주문 조회
```

최종 산출물은 단일 사용자 수가 아니라 다음 수치의 조합이다.

- Queue join 안전 처리량
- Queue admission 안전 속도
- Ticket Server의 사용자당 예매 완료 처리량
- 전체 흐름의 joined/admitted/order-pending 퍼널
- 좌석 경합 시 중복 성공 여부
- Queue 인스턴스 수별 처리량 변화

결제 승인과 `CONFIRMED` 전이는 현재 구현 범위에 없으므로 포함하지 않는다. 예매 완료의 기준은 주문 생성 응답 `201`과 주문 조회 상태 `PENDING`이다.

## 2. 현재 구현의 문제와 교체 범위

다음 기존 시나리오는 제거하고 새 계약으로 재구축한다.

- `TicketOpenFlowSimulation`
- `HoldRaceSimulation`
- `TicketServerCapacitySimulation`

콘솔의 해당 `SimulationType` 항목, API 미리보기 분기, 기존 전용 검증, 기존 전용 테스트도 함께 교체한다. 기존 클래스명과 실행 키의 하위 호환은 제공하지 않는다.

다음 기존 시나리오는 이번 작업에서 유지한다.

- `QueueJoinOnlySimulation`
- `QueueEnterSimulation`
- `LegacyQueueStatusSimulation`
- `CdnPublicStateSimulation`

기존 구현에서 확인된 교체 이유는 다음과 같다.

- 전체 흐름이 신규 Queue의 `state`와 `serving[shardId] >= localSeq` 입장 기준을 사용하지 않는다.
- 좌석 `select`와 주문 조회 및 `PENDING` 검증이 빠져 있다.
- 콘솔의 API 미리보기와 실제 시나리오 요청이 일치하지 않는다.
- 기존 분산 실행기는 Queue Join/CDN/Legacy만 지원한다.
- 사용자별 고유 좌석 feeder가 없어 Ticket 용량과 좌석 충돌을 구분할 수 없다.
- 여러 비즈니스 오류 상태를 무조건 성공으로 취급해 기술 오류를 가릴 수 있다.

## 3. 새 시나리오

### 3.1 `BookingCapacitySimulation`

Ticket Server만 측정한다. Queue를 거치지 않고 사전에 준비한 access token과 admission token을 사용한다. 사용자별 좌석은 중복되지 않는다.

```text
feeder row 선택
→ GET /api/v1/performances/{performanceId}/seats/status
→ POST /api/v1/performances/{performanceId}/seats/{seatId}/select
→ POST /api/v1/orders
→ GET /api/v1/orders/{orderKey}
→ status=PENDING 확인
```

각 HTTP 단계는 별도 요청 이름을 사용해 Gatling 결과에서 p95/p99와 오류율을 분리한다. 정상 처리에서는 좌석 충돌을 허용하지 않는다.

성공 계약:

- seat status: `200`
- select seat: `200`
- create order: `201`, `X-Order-Key` 저장
- get order: `200`, `data.status=PENDING`
- 기술 오류율: 1% 미만
- Ticket API p99: 3초 미만

### 3.2 `TicketOpenEndToEndSimulation`

Queue부터 주문 조회까지 실제 오픈 흐름을 수행한다. feeder에는 access token과 좌석 ID를 두며 admission token은 Queue enter 응답에서 받는다.

```text
feeder row 선택
→ Queue join
→ queueToken, shardId, localSeq 저장
→ public state polling
→ serving[shardId] >= localSeq 확인
→ Queue enter
→ admissionToken 저장
→ seat status
→ select seat
→ create order
→ get order
→ status=PENDING 확인
```

`state` 응답의 `refreshAfterMs`를 기본 polling 간격으로 사용하고, 설정된 jitter 범위에서 사용자별 지연을 적용한다. `statusPolls`를 초과하면 해당 사용자를 Queue timeout으로 실패 처리하고 Ticket API를 호출하지 않는다.

퍼널은 다음 요청 이름과 사용자 상태로 집계한다.

```text
joined → admitted → seatViewed → seatSelected → orderCreated → orderPending
```

Queue API p99 기준은 2초 미만, Ticket API p99 기준은 3초 미만이다. Queue 대기 중인 사용자의 timeout은 기술 오류와 별도 결과로 집계한다.

### 3.3 `SeatContentionSimulation`

소수 좌석에 다수 회원을 배정해 좌석 점유 정합성을 검증한다. feeder의 여러 행이 의도적으로 같은 `seatId`를 가질 수 있다.

```text
feeder row 선택
→ seat status
→ select seat
→ create order
→ 성공 주문의 seatId/orderKey 기록
→ 성공 주문 조회
```

select 결과가 비즈니스 충돌이면 주문 시도 여부를 시나리오 설정으로 결정한다. 기본 설정은 좌석 선택 UX 실패와 주문 점유 경쟁을 분리하기 위해 주문 경쟁을 계속 진행한다. 주문 생성은 `201` 또는 정의된 좌석 점유 비즈니스 오류만 허용한다.

분산 실행 후 세 노드의 성공 결과를 합쳐 다음을 검증한다.

- 성공 주문 수가 대상 좌석 수 이하
- 하나의 `seatId`에 성공 주문이 두 건 이상 없음
- `5xx`, timeout, Redis/DB 기술 오류 없음
- 성공 주문의 `orderKey` 조회 결과가 `PENDING`

## 4. 공통 feeder 계약

대량 실행에서 문자열 `seatIds`를 순환 사용하지 않고 행 단위 feeder를 사용한다.

```csv
memberId,accessToken,seatId,admissionToken
100001,access-token-1,500001,admission-token-1
100002,access-token-2,500002,admission-token-2
```

`TicketOpenEndToEndSimulation`에서는 `admissionToken` 열을 무시한다. `BookingCapacitySimulation`과 `SeatContentionSimulation`에서는 해당 열을 요구한다.

feeder 규칙:

- 행 수가 예상 가상 사용자 수보다 적으면 순환하지 않고 실행을 실패시킨다.
- `memberId`, access token subject, admission token subject가 일치해야 한다.
- admission token의 performanceId가 실행 대상 회차와 일치해야 한다.
- 최대 처리량 시나리오에서는 seatId가 중복되지 않아야 한다.
- 경합 시나리오에서만 seatId 중복을 허용한다.
- access token과 admission token 원문을 Gatling 로그와 통합 리포트에 기록하지 않는다.

feeder는 실행 전에 VM 수에 맞춰 연속된 범위로 분할한다. 각 VM의 행 순서와 토큰·좌석 매핑은 보존한다.

## 5. 분산 실행 계약

새 PowerShell 실행기는 `run-distributed-booking.ps1`로 둔다. 기존 Queue/CDN/Legacy 실행기는 변경하지 않는다.

실행기는 다음 인자를 받는다.

- `Hosts`
- `KeyPath`
- `RemoteProjectDir`
- `Simulation`
- `CoreBaseUrl`
- `QueueBaseUrl`
- `PerformanceId`
- `FeederFile`
- `RpsPerNode`
- `DurationSeconds`
- polling 설정
- 리포트 수집 여부
- 실패 응답 본문 수집 여부

실행 순서는 다음과 같다.

```text
로컬 프로젝트 압축
→ 각 VM에 프로젝트와 해당 VM feeder 전송
→ 원격 preflight
→ 세 VM 동시 Gatling 실행
→ HTML 리포트와 성공 결과 파일 회수
→ 노드별/통합 요약 생성
```

운영 JWT secret이나 admission secret을 원격 명령행으로 전달하지 않는다. 새 시나리오는 사전에 만든 토큰 feeder 파일을 사용한다. secret을 기본값으로 두지 않으며, 실제 token 원문은 콘솔 로그·리포트·파일명에 포함하지 않는다.

## 6. 콘솔 UI와 명령 생성

기존 세 시나리오를 다음 세 항목으로 교체한다.

| 실행 키 | 표시명 | 대상 |
|---|---|---|
| `booking-capacity` | 티켓 예매 용량 | Ticket Server |
| `ticket-open-end-to-end` | 전체 예매 흐름 | Queue + Ticket Server |
| `seat-contention` | 좌석 경합 정합성 | Ticket Server |

새 시나리오는 로컬과 분산 모드를 모두 지원한다. 전체 흐름에서는 `Queue Server URL`과 `Ticket Server URL`을 별도로 입력한다. Ticket 단독 시나리오에서는 Ticket URL만 요구한다.

공통 입력:

- performanceId
- feeder 파일 경로
- 노드당 users/sec
- duration
- injection mode
- VM host 목록
- SSH key 경로
- polling 횟수/간격/jitter

실행 전 요약에 다음 값을 표시한다.

- 전체 users/sec
- 전체 예상 사용자 수
- VM별 부하와 feeder 행 범위
- 대상 Queue/Ticket URL
- 대상 performanceId
- feeder 행 수와 고유 좌석 수

새 시나리오의 기본 URL은 `localhost`나 운영 주소로 채우지 않는다. 사용자가 명시한 URL이 없으면 실행 검증에서 거부한다.

기존 분산 실행 제한을 새 세 시나리오에 대해서는 해제하고, `DistributedGatlingCommandBuilder`가 `run-distributed-booking.ps1`을 사용하도록 한다. `coreBaseUrl`과 `queueBaseUrl`을 원격 Gatling 명령에 각각 전달한다.

## 7. 부하 모델과 판정

세 부하 VM은 같은 단계에 맞춰 실행하며 전체 유입률은 `노드당 RPS × 노드 수`로 계산한다. 단계별 최소 유지 시간은 5분으로 한다. 안전 용량 주변에서는 증가 폭을 줄인다.

권장 실행 순서:

```text
smoke
→ Queue 용량
→ Ticket 예매 용량
→ 전체 흐름 단계 상승
→ 좌석 경합
→ Queue 2대 이상 scale-out 비교
→ 안전 용량 70% soak
```

각 단계는 다음 기준을 모두 만족해야 통과한다.

- 기술 오류율 1% 미만
- Queue API p99 2초 미만
- Ticket API p99 3초 미만
- 중복 성공 주문 0건
- 대상 좌석 수를 초과한 성공 주문 0건
- 부하 발생기 VM CPU가 포화되지 않음

Gatling은 단계별 요청 통계를 생성하고 Datadog에서는 같은 시작·종료 시각을 기준으로 Queue/Ticket JVM, Redis, DB, Nginx 지표를 확인한다. 이 설계에는 Datadog API를 콘솔에 직접 연결하지 않는다. 콘솔이 실행 시작·종료 시각과 run id를 기록하는 것으로 충분하다.

## 8. 실패와 안전 정지

다음 조건 중 하나가 지속되면 해당 단계의 신규 주입을 중단하고 현재 실행을 종료한다.

- 기술 오류율이 1% 이상
- Queue p99가 2초 이상
- Ticket p99가 3초 이상
- 5xx 또는 timeout이 연속 증가
- DB connection pool pending 또는 Redis timeout 발생
- 중복 성공 주문 발견
- feeder 부족, token subject 불일치, 대상 URL 미설정

`409` 또는 좌석 점유 관련 `4xx`는 좌석 경합 시나리오에서만 정상 비즈니스 결과로 인정한다. 최대 처리량 시나리오에서는 같은 응답을 데이터 중복 또는 사전 정리 실패로 취급한다.

## 9. 구현 파일 범위

주요 변경 대상:

- `load-tests/gatling/src/gatling/java/.../simulation/`의 기존 세 클래스 삭제 및 새 세 클래스 추가
- 공통 feeder와 결과 기록을 위한 `LoadTestConfig` 관련 최소 변경
- `run-distributed-booking.ps1` 추가
- `console/.../SimulationType.java` 교체
- `console/.../LoadTestRequest.java`에 Queue/Ticket URL과 feeder 입력 추가
- `console/.../GatlingCommandBuilder.java` 및 `DistributedGatlingCommandBuilder.java` 교체
- `console/.../static/index.html`의 입력·미리보기·분산 검증 변경
- 기존 세 시나리오 전용 테스트 교체

Queue 단독·CDN·Legacy 실행기와 Ticket/Queue 서버 코드는 변경하지 않는다.

## 10. 검증 기준

구현 후 실제 운영 부하를 자동 실행하지 않는다. 정적·컴파일 검증으로 다음을 확인한다.

- 새 Gatling 소스가 컴파일됨
- 콘솔 소스가 컴파일됨
- 세 새 시나리오의 실행 키와 클래스명이 일치함
- 로컬/분산 명령에 Queue/Ticket URL과 feeder가 전달됨
- 기존 네 개 유지 시나리오의 명령 계약이 변하지 않음
- 운영 secret과 token 원문이 로그·리포트·기본값에 노출되지 않음
- UTF-8 BOM 없는 파일과 `git diff --check`

실제 smoke와 단계별 부하는 사용자가 대상 URL, performanceId, feeder, VM별 RPS를 확인한 뒤 별도 실행한다.
