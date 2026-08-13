# Core Admission Capacity 운영 데이터

운영자가 직접 다루는 파일은 세 종류뿐이다.

```text
create-core-capacity-data.sql   최초 1회 운영 Oracle 데이터 생성
reset-core-capacity.ps1         Console 버튼이 여는 30개 전용 회차 전체 원복 절차
tests/CoreCapacity.Tests.ps1    운영 접속 없이 위 두 파일의 안전 계약 검증
```

별도의 feeder 준비 스크립트는 없다. Gatling Console의 기존 JWT 파일 자동 생성 기능이 Core Capacity 실행에 필요한 booking feeder까지 실행 직전에 함께 만든다.

## 1. 최초 1회: Oracle 데이터 생성

[create-core-capacity-data.sql](create-core-capacity-data.sql)의 전체 내용을 운영 Oracle Console에서 한 번 실행한다. SQL Developer에서는 `Run Script(F5)`, 다른 도구에서는 여러 문장을 지원하는 `Execute Script`를 사용한다.

한 트랜잭션에서 다음 전용 데이터를 만들고 건수를 검증한 뒤에만 `COMMIT`한다.

```text
공연장                         1개
공연                           1개
물리 좌석                      2,000개
좌석 등급                      4개
공연 좌석 매핑                 2,000개
ACTIVE 전용 회원               2,000명
회차                           30개
Queue FORCE_OFF 정책           30개
회차 좌석                      60,000개(30 × 2,000)
```

고정 식별자는 다음과 같다.

```text
created_by       CORE_CAPACITY_20260812
showId           910000001
performanceId    910000001..910000030
seatId           910000001..910002000
memberId         1..2000
```

전용 회원은 JWT의 `sub`와 주문 검증의 실제 회원 ID를 맞추기 위한 데이터다. 비밀번호가 없는 부하테스트 전용 회원이며 `deleted_at IS NULL`, `role=MEMBER` 상태로 만든다. 로그인 API는 사용하지 않는다.

ID `1..2000`에 기존 회원이 하나라도 있거나 전용 이메일이 이미 있으면 INSERT 전에 실패한다. 기존 회원 사이의 빈 ID만 메우면 테스트 JWT가 기존 계정을 가장할 수 있으므로 부분 보충은 하지 않는다. 생성 건수 검증이 틀리면 전체 `ROLLBACK`한다. 이 SQL은 두 번 실행하거나 매 테스트마다 실행하는 파일이 아니다.

## 2. 매 실행: Console이 JWT와 feeder 자동 생성

Console에서 `03 고정 조건 Core 수용량`을 선택하면 기본값은 다음과 같다.

```text
Access Token Mode     토큰 파일/목록
Access Token Source   파일 자동 생성
Member 시작 ID        1
Booking Feeder        build/core-capacity-booking-feeder.csv
Feeder Offset         0
```

실행 버튼을 누르면 실제 HTTP 부하 전에 Console이:

1. 입력한 운영 JWT secret과 issuer로 access token 파일을 만든다.
2. 같은 토큰과 전용 memberId·seatId로 booking feeder를 만든다.
3. feeder 행 수와 offset이 2,000개 전용 데이터 범위를 넘지 않는지 검사한다.
4. 준비가 성공한 경우에만 Gatling을 시작한다.

feeder 형식은 다음과 같고 Admission Token은 비워 둔다.

```csv
memberId,accessToken,seatId,admissionToken
1,<generated-jwt>,910000001,
```

JWT secret은 반드시 운영 Core의 `security.jwt.secret`과 같아야 한다. Console 실행 로그와 결과 JSON에는 secret이나 token 본문을 기록하지 않는다. 생성 파일은 빌드/임시 경로에 두며 Git에 추가하지 않는다.

## 3. 회차 배정

최초 경계 탐색은 같은 30초 조건과 서로 다른 performance를 사용한다.

| users/sec | 예상 사용자 | 1회차 | 2회차 | 3회차 |
| ---: | ---: | ---: | ---: | ---: |
| 5 | 150 | 910000001 | 910000008 | 910000015 |
| 10 | 300 | 910000002 | 910000009 | 910000016 |
| 15 | 450 | 910000003 | 910000010 | 910000017 |
| 20 | 600 | 910000004 | 910000011 | 910000018 |
| 30 | 900 | 910000005 | 910000012 | 910000019 |
| 40 | 1,200 | 910000006 | 910000013 | 910000020 |
| 50 | 1,500 | 910000007 | 910000014 | 910000021 |

경계 좁히기에는 `910000022..910000027`, 예비 회차에는 `910000028..910000030`을 사용한다. 새 회차를 쓰면 회원과 물리 seatId를 다시 사용해도 `PERFORMANCE_SEATS`와 주문 범위가 서로 독립적이므로 offset은 0으로 둔다.

한 실행의 `offset + 예상 사용자 수`는 2,000 이하여야 한다. 따라서 이 데이터로 `50 users/sec × 60초 = 3,000명`을 실행할 수 없다. 좌석 고갈을 Core 한계로 오판하지 않도록 최초 비교는 모든 단계를 30초로 고정한다.

## 4. 사용한 회차 원복

여러 회차를 순서대로 사용해 테스트를 마친 뒤 한꺼번에 초기 상태로 돌릴 때 Console의 `전체 테스트 회차 원복 창 열기` 버튼을 누른다.

버튼이 직접 데이터를 삭제하지는 않는다. 별도 PowerShell 창에서 다음 값을 입력하고 확인 문구를 직접 타이핑해야 [reset-core-capacity.ps1](reset-core-capacity.ps1)이 실행된다.

```text
Oracle user / data source / password
Redis host / user / password
운영 원복 확인
결과 저장 및 모든 Core 인스턴스 중지 확인
30개 전체 Oracle·Redis 삭제 대상 확인
```

원복 순서는 다음과 같다.

```text
30개 회차 Oracle 사전 점검
→ 모든 Core 인스턴스 중지 확인
→ 30개 회차 Oracle 재점검 및 holdKey 추출
→ 모든 회차 Redis 대상 키 사전 점검
→ 전체 원복 확인 문구 입력
→ 회차별 Redis 대상 키 삭제·재점검
→ 회차별 Oracle 주문 실행 이력 삭제
→ 30개 회차 Oracle·Redis 사후 검증
```

전용 범위 `910000001..910000030`만 순회한다. 삭제를 시작하기 전에 30개 회차 모두가 사전 점검을 통과해야 한다. 공연·회차·회차 좌석·물리 좌석·Queue 정책·전용 회원은 삭제하지 않는다. 각 회차의 DB 좌석이 모두 `AVAILABLE`, 주문이 모두 `EXPIRED`, Outbox와 hold 해제가 완료된 경우에만 진행하고 강제 상태 UPDATE는 하지 않는다. 결과는 하나의 `all-performances-<timestamp>` 폴더와 `reset-all-summary.json`에 남는다.

원복 창이 열려 있는 동안 Console은 새 부하 테스트 시작과 두 번째 원복 창 실행을 막는다. 원복 완료 후 PowerShell 창을 닫아야 다음 테스트를 시작할 수 있다.

## 5. 코드 수준 검증

다음 검증은 운영 Oracle·Redis 또는 Core에 접속하지 않는다.

```powershell
Invoke-Pester .\scripts\core-capacity\tests\CoreCapacity.Tests.ps1
```
