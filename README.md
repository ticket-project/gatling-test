# Ticket 부하 테스트

[ticket-core](https://github.com/ticket-project/ticket-core)(예매)와 [ticket-queue](https://github.com/ticket-project/ticket-queue)(대기열)에
부하를 거는 Gatling 저장소다. 대기열이 Core에 1초에 몇 명을 들여보내도 되는지 재고, 대기열을 켰을 때 Core가 실제로
보호되는지 확인한다. 판정은 Gatling 응답 시간이 아니라 Core 쪽 지표와 증거 파일로 한다.

```text
gatling-test ─▶ ticket-queue (join·state·enter)
             └▶ ticket-core  (공연 요약 → 좌석 상태 → 좌석 선택 → 주문 생성 → 주문 조회)
```

## 다루는 문제

- **Core가 버티는 초당 입장 인원을 찾는다.** 한 사용자의 예매 흐름(요청 다섯 개)을 초당 N명씩 넣고 N을 올려 첫 FAIL을
  찾는다. 측정 순서와 판정 기준의 원본은 ticket-core
  [Core 수용량](https://github.com/ticket-project/ticket-core/blob/master/docs/core-capacity.md)이다.
- **부하 발생기가 멈춘 것을 Core의 한계로 착각하지 않는다.** 같은 PC의 Gatling은 실행 중 1~2초씩 멈출 수 있다. 그래서
  Core의 `/actuator/prometheus` 히스토그램과 1초 단위 연결 풀·스레드 지표로 판정하고, 초당 실제 입장 수가 목표에서
  벗어난 실행은 버린다([scripts/capacity](scripts/capacity/README.md)).
- **중복 판매는 응답이 아니라 DB로 확인한다.** 사용자마다 최종 결과를 한 줄씩 남기고, 실행 뒤 DB를 조회해 성공 주문 수와
  중복 좌석을 대조한다([증거 파일과 합격 조건](docs/scenarios.md#booking-증거-파일과-합격-조건)).

측정하며 찾은 병목과 고친 결과는 [측정 일지](docs/capacity-log/README.md)에 쌓는다.

## 빠른 시작

필요한 것: JDK 25, Docker, Windows PowerShell. 스크립트는 아래 배치를 전제한다.

```text
<작업 폴더>/
├─ ticket/                      ticket-core 클론
├─ gatling-test/                이 저장소
└─ local-env/ticket-core.env    Core 환경 변수. 저장소 밖에 둔다
```

1. 서버 없이 도는 단위 테스트로 빌드를 확인한다.

   ```powershell
   .\gradlew.bat -p load-tests\gatling test
   ```

2. 로컬 Core를 띄운다. 먼저 ticket-core README 빠른 시작의 1번(PostgreSQL·Redis)을 하고, 2번의 환경 변수를
   `..\local-env\ticket-core.env`에 `KEY=VALUE`로 한 줄씩 적는다(`$env:`와 따옴표는 뺀다). 그다음 아래를 실행하면 Core jar를
   만들고, 부하 회차 30개와 회원 2,000명을 넣고, 8080에 Core를 띄운다. 끝나면 화면에 나온 PID로 `Stop-Process`한다.

   ```powershell
   .\scripts\capacity\reset-local.ps1
   ```

3. 초당 1명 × 5초를 한 번 잰다. 종료 코드 0이 PASS이고, 결과는 `distributed-results-join\capacity\` 아래 새 폴더의
   `summary.md`에 있다. 회차는 한 번 쓰면 다시 쓸 수 없으므로 다음 실행은 `910000002`처럼 다음 회차를 쓴다.

   ```powershell
   .\scripts\capacity\run-step.ps1 -UsersPerSecond 1 -DurationSeconds 5 -PerformanceId 910000001 -Label first
   ```

localhost가 아닌 대상은 실행마다 대상 URL·부하·시간을 확인한 뒤에만 돌린다. 운영 측정은
[운영 Core 안전 입장률 측정 가이드](docs/core-capacity-production.md)를 따른다.

## 설정

- **로컬 Core 접속**: `reset-local.ps1`이 `..\local-env\ticket-core.env`를 읽고, `run-step.ps1`은 같은 파일의 `JWT_SECRET`으로
  부하용 토큰을 만든다. 다른 위치의 파일은 `-EnvFile`로 넘긴다.
- **운영 대상**: JWT secret은 명령행이 아니라 `LOADTEST_JWT_SECRET` 환경 변수로 넘긴다. 값은 저장소에 없다.
- **DB 감사**(`auditBookingDatabase`): `BOOKING_AUDIT_DB_URL`, `BOOKING_AUDIT_DB_USERNAME`, `BOOKING_AUDIT_DB_PASSWORD`.
- **콘솔 대상 환경**: [console/environments.properties](console/environments.properties)에서 고를 대상 URL을 관리한다.
- secret·토큰·회원 정보는 문서, 명령행, 결과 파일, 커밋에 남기지 않는다.

## 구조

```text
load-tests/gatling/       Gatling 시뮬레이션, feeder 검증, 증거 파일 수집
console/                  로컬 브라우저에서 시뮬레이션을 고르고 실행하는 콘솔(포트 9090)
scripts/capacity/         콘솔 없이 Core 수용량을 한 단계씩 재고 판정하는 스크립트
run-distributed-*.ps1     여러 VM에 feeder를 나눠 실행하고 결과를 합치는 스크립트
distributed-results-join/ 실행 결과. git이 추적하지 않는다
docs/                     시나리오·판정, 운영 측정 절차, 측정 일지
```

| 알고 싶은 것 | 문서 |
| --- | --- |
| 시뮬레이션마다 무엇을 재고, feeder와 합격 조건이 무엇인가 | [시나리오와 판정](docs/scenarios.md) |
| 로컬에서 Core 수용량을 한 단계씩 재는 법 | [scripts/capacity](scripts/capacity/README.md) |
| 운영 Core 입장률을 재는 절차 | [운영 측정 가이드](docs/core-capacity-production.md) |
| 지금까지 찾은 병목과 성능 변화 | [측정 일지](docs/capacity-log/README.md) |
| 콘솔 사용법 | [console](console/README.md) |

## 자주 하는 작업

```powershell
.\gradlew.bat -p load-tests\gatling test   # 시뮬레이션 쪽 단위 테스트
.\gradlew.bat -p console test              # 콘솔 단위 테스트
.\gradlew.bat -p console run               # 콘솔 띄우기. http://localhost:9090
```

- **결과 폴더 정리**: `distributed-results-join`은 git이 추적하지 않고 경로가 260자를 넘기 쉽다. 지우거나 옮기기 전에
  `tar`로 백업한다.
- **측정 기록**: 측정한 날마다 [측정 일지](docs/capacity-log/README.md)의 틀로 한 편을 쓰고, 문제 번호(P-xxx) 표를 고친다.

## 모르고 바꾸면 사고가 나는 것

- **Gatling 로그 수준을 DEBUG로 올리지 않는다.** 부하 발생기가 출력을 기다리느라 멈춰 결과가 무효가 된다(P-002).
- **같은 회차를 다시 쓰지 않는다.** 앞 실행의 선점·주문이 남아 비즈니스 거절이 기술 실패처럼 섞인다.
- **Gatling assertion에 응답 시간을 넣지 않는다.** assertion은 기술 실패율만 본다(느린 응답을 KO로 세지 않는다).
  Core 수용량 측정의 응답 시간·밀림 판정은 `scripts/capacity/run-step.ps1`이 따로 한다(기준은 ticket-core `docs/core-capacity.md`).
- **409를 한데 세지 않는다.** 비즈니스 거절·과부하(E6003)·기술 실패로 나눠 센다. 분류 원본은 `CoreRejections`다.

## 기여

커밋·PR 규칙은 [개발 기준](docs/development.md)에 있다. 커밋 제목은 `<type>(<scope>): <한국어 설명>`이다.
AI 코딩 에이전트용 지침은 [AGENTS.md](AGENTS.md)다.
