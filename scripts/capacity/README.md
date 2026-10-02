# Core 수용량 측정 스크립트

03 고정 조건(`CoreAdmissionCapacitySimulation`)을 콘솔 없이 한 단계씩 실행하고 **Core 쪽 숫자**로 판정한다.
측정 순서와 판정 기준의 원본은 ticket-core
[Core 수용량](https://github.com/ticket-project/ticket-core/blob/master/docs/core-capacity.md)이다. 실행 기록은
[capacity-log](../../docs/capacity-log/README.md)에 남긴다.

콘솔로 재지 않는 이유는 [10월 1일 일지](../../docs/capacity-log/2026-10-01-local-first.md)에 있다. 요약하면 이렇다.
Gatling이 잰 응답 시간만으로는 Core의 한계인지 부하 발생기가 멈춘 것인지 구분할 수 없다. 같은 조건에서도 Gatling 쪽 p95는
45ms~5.5초였고, Core 쪽 p95는 3~28ms였다.

## 로컬 Core 다시 띄우기: `reset-local.ps1`

```powershell
.\scripts\capacity\reset-local.ps1                                # 표준 회차 30개, 회원 2,000명, 새 DB
.\scripts\capacity\reset-local.ps1 -LargePerformanceCount 20 -Members 1000000 -BackgroundOrders 5000000
.\scripts\capacity\reset-local.ps1 -KeepData -PoolSize 20         # 데이터는 그대로 두고 풀만 바꿔 재기동
```

- 8080이 쓰이고 있으면 아무것도 끄지 않고 멈춘다. IntelliJ 등에서 띄운 Core는 직접 끈다.
- `~/ticket-local.*.db`는 지우지 않고 `.bak-<시각>`으로 옮긴다.
- `bootJar`로 만든 jar를 `java -jar`로 띄운다. 운영과 같은 실행 방식이다.
  - `bootRun`과 IDE 실행은 `-XX:TieredStopAtLevel=1`(C2 컴파일러 꺼짐)을 붙여서 측정에 쓰지 않는다.
  - `local-env/ticket-core.env`를 읽고, SQL 로그(p6spy)는 끈다.
- seed는 ticket 저장소의 `seedLocal`이다. 대형 회차(15,000석)와 배경 주문은 ticket `seed/README.md`를 따른다.
- 띄운 Core의 commit·PID·풀·seed 조건을 `distributed-results-join/capacity/core-local.json`에 남긴다.
- 끝나면 화면에 나온 PID로 `Stop-Process`해서 끈다.

## 한 단계 재기: `run-step.ps1`

```powershell
.\scripts\capacity\run-step.ps1 -UsersPerSecond 50 -PerformanceId 910000003 -Label ladder
.\scripts\capacity\run-step.ps1 -UsersPerSecond 50 -PerformanceId 920000001 -Label large
# 운영
$env:LOADTEST_JWT_SECRET = "<운영 JWT secret>"
.\scripts\capacity\run-step.ps1 -CoreUrl https://oneticket.site -OperationalConfirmation `
  -MemberIdsFile scripts\core-capacity\member-ids.txt -UsersPerSecond 5 -PerformanceId 920000001 -Label prod
```

순서는 이렇다.

1. Core health, 회차 존재를 확인한다.
2. 토큰과 피더를 만든다.
3. **회차 좌석이 전부 AVAILABLE이고 좌석 ID가 연속인지** 확인한다. 한 번 쓴 회차는 여기서 멈춘다.
4. 실행 전 `/actuator/prometheus`와 (로컬이면) `ORDERS` 행 수를 기록한다.
5. 1초 단위 Hikari·Tomcat·CPU를 수집하며 Gatling을 직접 실행한다.
6. 실행 뒤 히스토그램 차이로 요청별 Core 쪽 p50/p95/p99를 계산한다.

결과는 `distributed-results-join/capacity/<시각>-<label>-<부하>/`에 남는다.

| 파일 | 내용 |
| --- | --- |
| `summary.json` | 조건, 유효성, 판정, Gatling 쪽·Core 쪽 숫자 |
| `summary.md` | 일지에 붙일 표 한 줄 |
| `core-metrics.tsv` | 1초 단위 Hikari 사용·대기, Tomcat 바쁜 스레드, CPU |
| `prometheus-before.txt`, `prometheus-after.txt` | 실행 전후 `/actuator/prometheus` 원문. 연결 점유 시간, 저장소 메서드별 호출, Core GC를 전후 차이로 본다 |
| `thread-dump-<n>.txt` | `-ThreadDumps N`일 때만. Core가 막히기 시작한 순간(연결 대기 > 0 또는 바쁜 요청 스레드 20개 이상)부터 3초 간격 |
| `booking-*.csv`, `booking-evidence.json` | 사용자별 결과, 초당 입장·완료, 체류 시간 |
| `gatling.log`, `gatling-jvm.log` | Gatling 출력, 부하 발생기 GC 로그 |
| `coreadmissioncapacitysimulation-*/` | Gatling HTML 리포트 |

모든 실행의 한 줄 요약은 `distributed-results-join/capacity/steps.md`에도 쌓인다.

`-ThreadDumps 3`은 원인을 찾는 진단 실행용이다. 덤프하는 동안 Core가 잠깐 멈추므로 그 실행의 숫자는 판정에 쓰지 않는다.
`reset-local.ps1`로 띄운 로컬 Core에서만 된다(같은 JDK의 `jcmd`를 쓴다).

### 유효성 (판정보다 먼저)

하나라도 걸리면 `INVALID`다. 숫자를 판정에 쓰지 않는다.

- **초당 실제 입장 수가 목표의 ±15%(최소 ±2명)를 벗어났다.** 부하 발생기가 멈추면 입장이 출렁이고, Core가 막히면 입장은
  일정한 채 완료만 출렁인다. 10월 1일 80 u/s에서 Core가 막혔을 때 입장은 79~81이었다. 부하 발생기가 멈췄을 때는 10~101이었다.
- 부하 발생기(Gatling JVM) GC 멈춤이 200ms를 넘었다.
- 시작 사용자 수가 목표보다 적다.
- 로컬 Core가 디버거나 `TieredStopAtLevel=1`로 떠 있다.
- 로컬 Core를 `reset-local.ps1`로 띄우지 않아 commit과 조건을 알 수 없다.

### 판정

유효한 실행만 판정한다. 아래 중 하나라도 있으면 `FAIL`이다.

- Gatling 실패
- 기술 실패 1% 이상
- 과부하(E6003) 1명 이상
- 체류 p99가 5,000ms 초과
- 비즈니스 거절 1명 이상(데이터 오염)

종료 코드는 PASS 0, FAIL 1, 중단 2, INVALID 3이다.

## 측정 규칙

- 기동 직후 첫 실행은 버린다. 예열 실행을 한 번 하고 잰다(P-005).
- 같은 단계를 2~3번 돌린다. 갈리면 숫자를 믿기 전에 원인부터 찾는다.
- 한 번에 하나만 바꾼다. 풀, 코드, 데이터량 가운데 둘이 같이 바뀐 비교는 버린다.
- 실행마다 새 회차를 쓴다. 스크립트가 다시 쓴 회차를 막는다.
- 로컬 숫자는 병목 찾기용이다. 입장률을 정하는 측정은 부하 발생기를 분리한 환경(EC2 부하 발생기 → 운영)에서 한다.

## 주의

- 스크립트 메시지는 영어다. Windows PowerShell 5.1은 BOM 없는 UTF-8 스크립트의 한글 문자열을 깨뜨린다.
- Gatling 로그는 `load-tests/gatling/src/gatling/resources/logback.xml`에서 WARN이다. DEBUG로 올리면 부하 발생기가
  출력을 기다리느라 멈춘다(P-002). 실패 요청을 자세히 보려면 잠깐만 올린다.
