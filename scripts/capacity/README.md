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
.\scripts\capacity\reset-local.ps1                                # 로컬 PostgreSQL에 표준 회차 30개, 회원 2,000명 적재
.\scripts\capacity\reset-local.ps1 -LargePerformanceCount 20 -Members 1000000 -BackgroundOrders 5000000
.\scripts\capacity\reset-local.ps1 -KeepData -PoolSize 20         # 데이터는 그대로 두고 풀만 바꿔 재기동
```

`-Compose`는 Core를 Windows가 아니라 ticket `docker-compose-local.yml`의 컨테이너(`ticket-core`)로 띄운다.

```powershell
.\scripts\capacity\reset-local.ps1 -Compose -LargePerformanceCount 45 -Members 1000000 -BackgroundOrders 5000000
.\scripts\capacity\reset-local.ps1 -Compose -KeepData             # 이미 적재된 DB로 Core만 새로 띄움
.\scripts\capacity\reset-local.ps1 -Compose -KeepData -PoolSize 20 # 풀만 바꿔 재기동. 빼고 다시 띄우면 기본값(10)
.\scripts\capacity\reset-local.ps1 -Compose -KeepData -Datadog    # Datadog Agent와 Core 트레이서를 켜고 재기동
```

- Core가 PostgreSQL·Redis와 같은 Docker 네트워크(`ticket`)에서 이름으로 접속한다. Windows의 Core는 쿼리마다 Docker
  포트 중계를 지나는데, 그 중계가 측정 천장이었다([capacity-log P-008](../../docs/capacity-log/README.md)).
- 포트(127.0.0.1:8083), 환경 파일(`local-env/ticket-core.env`), DB(compose의 PostgreSQL)는 compose가 정한다. `-PoolSize`는 셸 환경변수로 compose에 넘긴다.
- `-Datadog`은 compose의 `datadog-agent`(profile `datadog`)를 함께 띄우고 Core 트레이서·프로파일러를 켠다.
  `local-env/datadog.env`에 `DD_API_KEY`·`DD_SITE`가 있어야 한다. 트레이서가 Core CPU를 쓰므로 켠 실행과 끈 실행의 숫자를 섞어 비교하지 않는다.
  빼고 다시 띄우면 Agent 컨테이너를 지우고 트레이서도 끈다.
  `-Compose` 없이(`java -jar`) 쓰면 운영과 같은 버전의 dd-java-agent를 `~/.datadog`에 받아 붙인다. compose의 Agent는
  8126을 호스트에 열지 않으므로 Agent는 직접 띄워야 하고, `localhost:8126`에서 기다리지 않으면 멈춘다.
- `-Datadog`이면 Gatling이 보내는 `X-Load-Test-Run-Id`를 span 태그 `load_test.run_id`로 남긴다. Datadog에서
  `@load_test.run_id:<runId>`로 한 실행만 찾는다. `java -jar`면 Agent가 읽을 JSON 로그를 `distributed-results-join/capacity/core-logs`에 따로 쓴다.
- 로컬 compose면 실행 전후 PostgreSQL WAL 통계를 `postgres-wal.txt`에 남기고 `summary.json`의 `server.postgresWal`에 차이를 적는다.
  fsync 시간은 PostgreSQL `track_wal_io_timing`이 켜져 있어야 쌓인다(capacity-log P-016).
- 부하는 여전히 Windows의 Gatling이 `http://localhost:8083`으로 보내므로 HTTP 요청은 포트 중계를 지난다. 판정은 Core 쪽 숫자로 한다.
- `-ThreadDumps`는 jcmd 대신 `docker exec ticket-core kill -3 1`로 뜨고, `docker logs`에서 덤프 부분을 잘라 저장한다.

- 지정 포트(기본 8080)가 쓰이고 있으면 멈춘다. 다른 Core가 떠 있으면 `-Port 8083`처럼 빈 포트를 사용한다.
- PostgreSQL 데이터를 삭제하거나 초기화하지 않는다. `-KeepData`는 시드 적재도 건너뛴다. 새 측정 환경이 필요하면 별도 DB를 만들고 `-EnvFile`로 접속 설정을 전달한다.
- `bootJar`로 만든 jar를 `java -jar`로 띄운다. 운영과 같은 실행 방식이다.
  - `bootRun`과 IDE 실행은 `-XX:TieredStopAtLevel=1`(C2 컴파일러 꺼짐)을 붙여서 측정에 쓰지 않는다.
  - `local-env/ticket-core.env`를 읽고, SQL 로그(p6spy)는 끈다.
- seed는 ticket 저장소의 `seedLocal`이다. 대형 회차(15,000석)와 배경 주문은 ticket `seed/README.md`를 따른다.
- 시드 적재 동안에는 측정용 Core의 worker를 끄고, 완료 후 원래 worker 설정으로 재기동한다.
- 다른 로컬 앱과 분리할 때는 별도 PostgreSQL DB와 비어 있는 Redis 논리 DB(`SPRING_DATA_REDIS_DATABASE`)를 함께 지정한다.
- 띄운 Core의 commit·PID·풀·seed 조건을 `distributed-results-join/capacity/core-local.json`에 남긴다.
- 끝나면 화면에 나온 PID로 `Stop-Process`해서 끈다.

## 한 단계 재기: `run-step.ps1`

```powershell
.\scripts\capacity\run-step.ps1 -UsersPerSecond 50 -PerformanceId 910000003 -Label ladder
.\scripts\capacity\run-step.ps1 -UsersPerSecond 50 -PerformanceId 920000001 -Label large
# 운영: 준비는 스크립트가 하고, 사람이 줄 것은 부하 발생기 IP뿐이다
.\scripts\capacity\run-step.ps1 -Prod -CheckOnly                                   # 부하 없이 준비·점검만
.\scripts\capacity\run-step.ps1 -Prod -LoadHost ubuntu@<EC2 IP> -UsersPerSecond 5 -Label warmup
```

`-Prod`는 운영 호스트(`-ProdHost`, 기본 `ubuntu@oneticket.site`)에 SSH로 들어가 아래를 스스로 한다(`prod-target.ps1`).
운영 호스트와 부하 발생기는 같은 키(`ticket-test-key-01.pem`)를 쓴다. `-KeyPath`를 비우면 `~/.ssh/`부터 찾는다.

1. Core 이미지 태그(commit), 컨테이너 IP, `free -m`을 읽어 `server-conditions.txt`에 남긴다. 실행마다는 DB에 묻지 않는다.
   `-CheckOnly`일 때만 DB 규모(`pg_class.reltuples` 추정치), `max_connections`, 대형 회차 수, nginx·compose·`docker stats`를 더 남긴다.
   운영 Core·DB는 사양이 낮아 점검이 측정을 흔들지 않게 한다(capacity-log P-020: 실행마다 주문 500만 건을 훑던 점검이 첫 10~20초 DB 지연과 겹쳤다).
2. 빈 로컬 포트에서 `ticket-be:8080`으로 SSH 터널을 열어 `-MetricsUrl`로 쓰고, 지표를 다 읽으면 닫는다.
   전체 `/actuator/prometheus`(약 280KB)는 실행 전후 한 번씩만 받는다. 1초 수집·회복 대기·기동 시간은 `includedNames`로 거른 지표만 받는다.
3. `ticket-be`의 `JWT_SECRET`을 메모리로만 받아 토큰을 만든다. 환경 변수·파일에 남기지 않는다.
4. `scripts/core-capacity/member-ids.txt`(git 미추적)가 없으면 운영 DB에서 부하 회원 ID를 받는다.
   DB 비밀번호는 운영 호스트 안에서 `ticket-be` 환경 변수 → `postgres:18-alpine` psql 컨테이너로만 넘긴다.
5. `-PerformanceId`를 비우면 마지막 부하 주문의 회차 + 1을 고른다(주문을 PK 역순으로 읽다 첫 부하 주문에서 멈춘다).
   사다리는 `-CheckOnly`가 알려 준 회차부터 1씩 올려 `-PerformanceId`로 넘긴다. 그러면 실행마다 DB를 건드리지 않는다.
6. 앞 실행의 부하가 빠질 때까지(health UP, Hikari pending 0, 바쁜 요청 스레드 5 이하) 5초 간격으로 최대 3분 기다린다.
7. 조건(commit, Core 기동 시간, DB 규모, 회차)을 `prod-conditions.json`에 남긴다. Core EC2를 막 켰으면(기동 시간이 짧으면) 첫 실행은 예열로 쓴다.

`-Prod` 없이 각 값을 직접 줄 수도 있다. 이때 JWT secret은 `-JwtSecret`이나 환경 변수 `LOADTEST_JWT_SECRET`으로 준다.

- `-MetricsUrl`: health와 `/actuator/prometheus`를 읽을 주소다. 비우면 `-CoreUrl`이다. 운영 Core는 actuator를 밖에 열지 않는다.
- `-LoadHost`: Gatling만 그 EC2에서 SSH로 돌린다. 토큰·피더 생성, 회차 검사, 1초 지표 수집, 판정은 이 PC에서 한다.
  실행마다 이 작업 트리를 EC2의 `-RemoteProjectDir`(기본 `~/gatling-test`)에 풀고, 토큰·피더를 `/tmp/capacity-<결과 폴더 이름>`에 올린다.
  끝나면 결과를 받아 오고 EC2의 그 폴더(토큰 포함)를 지운다. EC2에는 JWT secret이 가지 않는다. `-KeyPath`를 비우면 `ticket-test-key-01.pem`을 찾는다.
  EC2에 Java 21이 없으면 설치한다(부하 발생기는 측정할 때만 켜므로 새 인스턴스일 수 있다).
- `-CoreCommit`: `reset-local.ps1`이 기록하지 않는 대상의 Core commit이다. `steps.md`의 commit 칸과 `summary.json`에 쓴다.

순서는 이렇다.

1. Core health, 회차 존재를 확인한다.
2. 로컬은 Core 기동에 쓴 환경 파일로 PostgreSQL의 실제 회원 ID·회원 수·주문 수·DB 버전을 읽고, 같은 JWT secret으로 토큰과 피더를 만든다. secret은 명령행 대신 환경변수로 전달한다.
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
| `database.properties` | 로컬 PostgreSQL의 DB 이름·버전·실제 회원 수·주문 수 |
| `prometheus-before.txt`, `prometheus-after.txt` | 실행 전후 `/actuator/prometheus` 원문. 연결 점유 시간, 저장소 메서드별 호출, Core GC를 전후 차이로 본다 |
| `thread-dump-<n>.txt` | `-ThreadDumps N`일 때만. Core가 막히기 시작한 순간(연결 대기 > 0 또는 바쁜 요청 스레드 20개 이상)부터 3초 간격 |
| `booking-*.csv`, `booking-evidence.json` | 사용자별 결과, 초당 입장·완료, 체류 시간 |
| `gatling.log`, `gatling-jvm.log` | Gatling 출력, 부하 발생기 GC 로그 |
| `coreadmissioncapacitysimulation-*/` | Gatling HTML 리포트 |

모든 실행의 한 줄 요약은 `distributed-results-join/capacity/steps.md`에도 쌓인다.

별도 포트로 띄웠다면 `run-step.ps1 -CoreUrl http://localhost:8083 ...`처럼 같은 포트를 지정한다. 회원 ID·토큰·feeder 파일은 결과 폴더 안에서만 사용하고 공유하거나 커밋하지 않는다.

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
- 비즈니스 거절이 1명 이상이다. 회차를 다시 썼거나 좌석·회원 준비가 덜 된 것이라 새 회차로 다시 잰다.
- Gatling HTML 리포트가 없거나 다섯 요청 중 빠진 것이 있어 요청별 응답을 판정할 수 없다.

### 판정

유효한 실행만 판정한다. 아래 중 하나라도 있으면 `FAIL`이다. 기준의 원본과 이유는 ticket-core
[Core 수용량](https://github.com/ticket-project/ticket-core/blob/master/docs/core-capacity.md)의 "판정 기준"이다(2026-10-17 개정).

| 조건 | 기준 |
| --- | --- |
| Gatling 실패 | 기술 실패율 assertion(아래 기준과 같은 값) |
| 기술 실패 | 0.1% 이상 |
| 과부하(E6003) | 1명 이상 |
| 조회 응답(`performance summary`, `seat status`, `get order`) | 각각 p95 > 500ms 또는 p99 > 1,000ms |
| 쓰기 응답(`select seat`, `create order`) | 각각 p95 > 1,000ms 또는 p99 > 2,000ms |
| 밀림 | 주입이 끝날 때(마지막 입장 시각) 아직 끝나지 않은 사용자 > 부하 × 1초, 또는 마지막 5초 입장 구간의 체류 중앙값 > 1,000ms |

- 응답 시간은 Gatling HTML 리포트 통계 표의 요청별 95th·99th pct다. 사용자가 실제로 기다린 시간이라 네트워크 왕복을 포함한다.
- 밀림은 `booking-results.csv`의 `coreAdmittedAt`·`flowCompletedAt`·`coreResidenceMillis`로 계산한다. 30초 안에 응답 기준을 넘지 않아도
  처리가 입장을 못 따라가면 FAIL로 잡으려는 조건이다(capacity-log 2026-10-17, 60 u/s 진단 실행).
- 체류 p95/p99와 Core 쪽 p95는 `summary.json`·`steps.md`에 기록만 하고 판정에 쓰지 않는다.
- `summary.json`의 `client.requests`(요청별 p95/p99), `client.leftAtInjectionEnd`, `client.tailResidenceMedianMs`에 판정에 쓴 값이 남는다.

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
