# Core Admission Capacity 운영 데이터

운영자가 직접 다루는 파일은 세 종류뿐이다.

```text
create-core-capacity-data.sql   최초 1회 운영 Oracle 데이터 생성
reset-core-capacity.ps1         Console 버튼이 여는 30개 전용 회차 전체 원복 절차
tests/CoreCapacity.Tests.ps1    운영 접속 없이 위 두 파일의 안전 계약 검증
```

별도의 feeder 준비 스크립트는 없다. 생성 SQL의 마지막 조회 결과에서 실제 회원 ID를 한 번 내보내고, Gatling Console의 기존 JWT 파일 자동 생성 기능이 그 ID 순서대로 booking feeder까지 실행 직전에 함께 만든다.

## 1. 최초 1회: Oracle 데이터 생성

[create-core-capacity-data.sql](create-core-capacity-data.sql)의 전체 내용을 운영 Oracle Console에서 한 번 실행한다.

- JetBrains Database Console/DataGrip: 전체 선택 후 일반 `Execute(Ctrl+Enter)`를 사용한다. `Execute Selection as Single Statement`는 사용하지 않는다.
- SQL Developer: `Run Script(F5)`를 사용한다.
- SQL*Plus/SQLcl: `@create-core-capacity-data.sql`로 실행한다.

이 파일에는 JDBC Console이 Oracle SQL로 잘못 전송하는 `SET SERVEROUTPUT`, `WHENEVER SQLERROR` 같은 SQL*Plus 전용 명령을 넣지 않는다. 성공 메시지를 보고 싶다면 JetBrains Console의 `Enable DBMS_OUTPUT`을 켠다. 출력 여부와 무관하게 단일 PL/SQL 블록이 오류 시 직접 `ROLLBACK`하고 오류를 다시 발생시킨다.

한 트랜잭션에서 다음 전용 데이터를 만들고 건수를 검증한 뒤에만 `COMMIT`한다.

```text
공연장                         1개
공연                           1개
물리 좌석                      2,000개
좌석 등급                      4개
공연 좌석 매핑                 2,000개
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
```

`MEMBERS`는 INSERT·UPDATE·DELETE하지 않는다. 운영에 이미 존재하는 `deleted_at IS NULL`, `role=MEMBER` 회원을 사용한다. SQL의 마지막 `MEMBER_ID` 결과에는 앞에서부터 최대 10,000개의 실제 ID가 나오며, 이 열만 `scripts/core-capacity/member-ids.txt`로 내보낸다. 헤더는 있어도 되고 ID는 연속일 필요가 없다. 이 파일은 Git에서 제외되며 여러 API 테스트와 03·03-2에서 재사용한다.

JetBrains 결과 그리드에서 `MEMBER_ID` 열을 선택해 CSV 또는 텍스트로 내보내면 된다. 파일 내용은 다음 두 형식을 모두 허용한다.

```text
MEMBER_ID
1
5
11
```

```text
1,5,11
```

## 2. 매 실행: Console이 JWT와 feeder 자동 생성

Console에서 `03 고정 조건 Core 수용량`을 선택하면 다음 값은 화면에 노출하지 않고 내부 기본값으로 사용한다.

```text
Access Token Mode     토큰 파일/목록
Access Token Source   파일 자동 생성
Member ID File        scripts/core-capacity/member-ids.txt
Booking Feeder        build/core-capacity-booking-feeder.csv
Feeder Offset         0
```

운영자는 최초 한 번 내보낸 Member ID 파일 경로와 JWT Secret만 확인한다. Console이 예상 사용자 수에 맞춰 토큰 수와 내부 파일을 자동으로 결정한다.

실행 버튼을 누르면 실제 HTTP 부하 전에 Console이:

1. 입력한 운영 JWT secret과 issuer로 access token 파일을 만든다.
2. Member ID 파일의 실제 ID 순서대로 JWT `sub`와 전용 seatId를 연결해 booking feeder를 만든다.
3. Member ID가 부족하거나 feeder 행 수와 offset이 2,000개 전용 좌석 범위를 넘으면 중단한다.
4. 준비가 성공한 경우에만 Gatling을 시작한다.

feeder 형식은 다음과 같고 Admission Token은 비워 둔다.

```csv
memberId,accessToken,seatId,admissionToken
5,<generated-jwt-sub-5>,910000001,
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

03·03-2 전에 API별 테스트를 먼저 한다면 상태를 변경하는 `좌석 선점 → 주문 생성` 7단계에는 `910000022..910000028`을 한 회차씩 배정한다. 이 두 API는 같은 단계에서 같은 회차를 이어서 사용하므로 14개가 아니라 7개 회차를 소비한다. 공연 요약·좌석 상태·주문 조회는 읽기 전용이어서 별도 회차를 소비하지 않는다.

03 최초 경계 탐색에는 `910000001..910000007`, 경계 좁히기와 재검증에는 `910000008..910000021`, 03-2·Smoke·Hot Seat·예비 용도에는 `910000029..910000030`을 우선 사용한다. 03-2나 정합성 테스트를 여러 번 수행해 회차가 더 필요하면 사용 완료 회차를 안전 원복한 후 재사용한다. 새 회차를 쓰면 회원과 물리 seatId를 다시 사용해도 `PERFORMANCE_SEATS`와 주문 범위가 서로 독립적이므로 offset은 0으로 둔다.

한 실행의 `offset + 예상 사용자 수`는 2,000 이하여야 한다. 따라서 이 데이터로 `50 users/sec × 60초 = 3,000명`을 실행할 수 없다. 좌석 고갈을 Core 한계로 오판하지 않도록 최초 비교는 모든 단계를 30초로 고정한다.

## 4. 사용한 회차 원복

여러 회차를 순서대로 사용해 테스트를 마친 뒤 한꺼번에 초기 상태로 돌릴 때 Console 상단의 `테스트 데이터 전체 원복` 버튼을 누른다. 이 버튼은 선택한 Simulation과 관계없이 항상 표시된다.

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

전용 범위 `910000001..910000030`만 순회한다. 삭제를 시작하기 전에 30개 회차 모두가 사전 점검을 통과해야 한다. 공연·회차·회차 좌석·물리 좌석·Queue 정책과 기존 회원은 삭제하지 않는다. 각 회차의 DB 좌석이 모두 `AVAILABLE`, 주문이 모두 `EXPIRED`, Outbox와 hold 해제가 완료된 경우에만 진행하고 강제 상태 UPDATE는 하지 않는다. 결과는 하나의 `all-performances-<timestamp>` 폴더와 `reset-all-summary.json`에 남는다.

원복 창이 열려 있는 동안 Console은 새 부하 테스트 시작과 두 번째 원복 창 실행을 막는다. 원복 완료 후 PowerShell 창을 닫아야 다음 테스트를 시작할 수 있다.

## 5. 코드 수준 검증

다음 검증은 운영 Oracle·Redis 또는 Core에 접속하지 않는다.

```powershell
Invoke-Pester .\scripts\core-capacity\tests\CoreCapacity.Tests.ps1
```
