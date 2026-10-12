# Core 수용량 측정 일지

Core 수용량을 재면서 생긴 문제, 찾은 원인, 고친 방법, 성능 변화를 남긴다. 측정 순서와 판정 기준의 원본은
ticket-core [Core 수용량](https://github.com/ticket-project/ticket-core/blob/master/docs/core-capacity.md)이다.
이 폴더는 그 방법을 실제로 해 본 기록이다.

- 측정한 날마다 `YYYY-MM-DD-주제.md`를 하나 쓴다. 틀은 맨 아래에 있다.
- 문제는 번호(`P-001`…)를 붙여 아래 표에 올리고, 상태가 바뀌면 표를 고친다. 자세한 내용은 그 문제를 처음 적은 일지에 있다.
- 시도와 틀렸던 가설도 지우지 않는다. 확정된 측정 규칙만 ticket-core 문서에 옮긴다.
- 원자료(Gatling 리포트, 증거 CSV)는 `distributed-results-join/`에 있다. git이 추적하지 않으므로 일지에서는 폴더 이름으로 가리킨다.

## 문제 목록

| 번호 | 문제 | 종류 | 발견 | 상태 | 영향과 수치 |
| --- | --- | --- | --- | --- | --- |
| P-001 | 주문 생성마다 같은 회원의 진행 중 주문을 인덱스 없이 전체 스캔한다 | Core·DB | [10-01](2026-10-01-local-first.md#p-001) | 해결 확인(PENDING 부분 인덱스 `57f4db85`) | [10-12](2026-10-12-pending-index-remeasure.md#p-001-결과-부분-인덱스로-해결-확인) 같은 10 u/s에서 체류 p99 14.69초 → 0.11초, 존재 확인 922ms → 0.6ms. 인덱스 사용 +7,389회, 전체 스캔 0회 |
| P-002 | 같은 PC의 Gatling이 실행 중 1~2초씩 멈춰 응답 시간이 부풀려진다 | 측정 도구 | [10-01](2026-10-01-local-first.md#p-002) | 해결: 결과 파일을 사용자마다 여닫던 코드 수정(`8e8d61e`), 측정 중 IntelliJ 종료 | [10-12](2026-10-12-pending-index-remeasure.md#p-002-원인과-해결-결과-파일을-사용자마다-여닫았다) 수정 전 80 u/s 두 번 INVALID(입장 30~138) → 수정 후 80·100 u/s 네 번 모두 유효(입장 ±1~2명) |
| P-003 | 콘솔이 지난 실행의 증거 파일을 다른 실행 폴더로 복사했다 | 측정 도구 | [10-01](2026-10-01-local-first.md#p-003) | 수정함(`6c3c689`) | 운영 대상 결과 폴더 35개 중 20개가 사본이었다 |
| P-004 | IntelliJ 실행 설정과 `local-env/ticket-core.env`의 JWT secret이 다르다 | 로컬 환경 | [10-01](2026-10-01-local-first.md#p-004) | 결정 대기 | 설정 파일을 바꿔 띄우면 부하 토큰이 전부 401 |
| P-005 | 재기동 직후 첫 부하가 크게 느리다 | Core JVM | [10-01](2026-10-01-local-first.md#p-005) | 측정 규칙으로 우회 | 같은 조건에서 첫 실행 체류 p95 7,975ms, 다음 실행 1,103ms(Gatling 쪽) |
| P-006 | IntelliJ 실행 설정이 측정에 맞지 않는다(디버거, 최적화 컴파일러 꺼짐, SQL 로그) | 로컬 환경 | [10-01](2026-10-01-local-first.md#p-006) | 측정 규칙으로 우회 | 측정용 Core는 `bootRun`으로 띄운다 |
| P-007 | 주문 만료 worker가 주문 전체를 스캔한다 | Core·DB | [10-02](2026-10-02-large-data-first.md#p-007) | 개선 확인, 장시간 측정에서 재확인 | [10-12](2026-10-12-pending-index-remeasure.md#p-007-결과-같은-인덱스로-만료-조회도-개선) P-001의 부분 인덱스를 타서 292ms → 2.3ms. 비용은 PENDING 수에 비례 |
| P-008 | 연결이 차고 다섯 API가 함께 연결을 기다린다(DB는 연결 시간의 대부분을 Core를 기다림) | 로컬 환경 | [10-12](2026-10-12-pending-index-remeasure.md#p-008-초당-40명에서-db-연결-10개가-차고-다섯-api가-함께-연결을-기다린다) | 원인 확인: Windows ↔ Rancher Desktop 포트 중계(DB 쪽). Core를 compose 컨테이너로 옮겨 해소 | [컨테이너 측정](2026-10-12-pending-index-remeasure.md#p-008-결과-통로가-원인이었다db-쪽) 100 u/s에서 연결 1회 사용 8~9ms → 1.6~1.7ms, 사용자당 연결 시간 110~131ms → 22~24ms, 연결 대기 0 |
| P-009 | Core가 막히면 1초 지표 수집(`/actuator/prometheus`)이 같은 Tomcat 스레드를 기다리다 타임아웃된다 | 측정 도구 | [10-12](2026-10-12-pending-index-remeasure.md#좌석-상태-projection-재측정--812fd552) | 우회(판정은 전후 히스토그램이라 영향 없음), 결정 대기 | 100 u/s에서 Core ERROR 12·16건(`ClientAbortException`), `core-metrics.tsv` 표본 33개 중 9~23개. 연결 대기 최대값이 낮게 잡힐 수 있다. 근본 해결은 Core 관리 포트 분리(Core 설정) |
| P-010 | 좌석 상태 응답(1MB, 압축 없음)이 100 u/s에서 Gatling에 늦게 닿는다 | 측정 환경·Core | [10-12](2026-10-12-pending-index-remeasure.md#p-010-좌석-상태-응답1mb-압축-없음이-초당-100명에서-gatling에-늦게-닿는다) | 해결: 응답 압축(Core `21de0a3c`), Gatling Accept-Encoding(`01b58d1`) | [10-12](2026-10-12-pending-index-remeasure.md#p-010-결과-응답-압축으로-해결) 100 u/s Gatling 쪽 좌석 상태 p95 4,576ms → 23~24ms, 응답 1,050KB → 78KB |

## 성능 변화

같은 조건(대상, 데이터량, 부하)으로 잰 대표 숫자만 시간순으로 적는다. 판정 기준은 Core 쪽 숫자다.

| 날짜 | 환경 | Core commit | 조건 | 결과 | 비고 |
| --- | --- | --- | --- | --- | --- |
| 10-01 | 로컬(같은 PC) | `c4ee7fcb` | 03, 50 u/s × 30초, 주문 5→1.35만 건 | Core 쪽 p95: 주문 생성 ≤28ms, 좌석 상태 ≤28ms, 선택 ≤14ms | 기준선 후보. 도구 검증(A/A) 전이라 확정하지 않는다 |
| 10-02 | 로컬(같은 PC) | `3ebaa4d5` | 03, 10 u/s × 30초, 회원 100만·주문 500만·15,000석 | FAIL. 성공 7~20/300, 주문 생성 Core 쪽 p95 측정 상한 초과 | P-001이 주원인. 데이터 조건이 바뀌어 10-01과 직접 비교하지 않는다 |
| 10-08 | 로컬 PostgreSQL(같은 PC) | `72450b3e` | 03, 회원 100만·주문 500만·15,000석 | 10 u/s 두 번 PASS(p99 체류 3.00초·1.56초), 15 u/s FAIL(p99 25.1초) | [측정 기록](2026-10-08-local-postgresql.md). 전체 스캔 병목 유지. 이전 기록과 commit·DB가 달라 DB 종류만의 성능 비교는 아니다 |
| 10-08 | 로컬 PostgreSQL(같은 PC) | `6cc73867` | 03, 회원 100만·주문 약 500만·15,000석, 각 30초 | 5 u/s PASS(p99 1.01초), 10 u/s FAIL·재현(p99 14.69초·11.37초) | [core-capacity 후속 기록](2026-10-08-local-postgresql.md#core-capacity-후속-측정--6cc73867). 덤프 3개로 P-001·연결 대기 확인. 버전·PC 상태 차이로 이전 실행과 직접 비교 불가. C 미확정 |
| 10-12 | 로컬 PostgreSQL(같은 PC) | `57f4db85` | 03, 회원 100만·주문 약 500만·15,000석, 각 30초. 6cc73867과 `src/main` 차이는 부분 인덱스뿐, 좌석 행만 49만 늘어남 | 10 u/s PASS(p99 0.11초, 이전 14.69초), 15·20·30 PASS(30은 p99 4.57초), 40 FAIL·재현(p99 11.08초·10.29초) | [PENDING 부분 인덱스 재측정](2026-10-12-pending-index-remeasure.md). P-001 해결로 첫 FAIL이 10 → 40 u/s. 다음 병목 P-008. C 미확정 |
| 10-12 | 로컬 PostgreSQL(같은 PC) | `812fd552` | 위와 같은 데이터(주문 +7,350), 각 30초. `57f4db85`와 차이는 좌석 상태 projection뿐 | 40 u/s PASS(p99 0.17초, 이전 11.08초), 50·60·80 PASS(50·60은 첫 실행 INVALID 뒤 다시 잼), 100 FAIL·재현(p99 21.08초·10.26초) | [좌석 상태 projection 재측정](2026-10-12-pending-index-remeasure.md#좌석-상태-projection-재측정--812fd552). 첫 FAIL 40 → 100 u/s. P-008 같은 모양 재발. C 미확정 |
| 10-12 | 로컬 PostgreSQL(같은 PC) | `812fd552` | 위와 같고 Hikari 풀만 20(`-PoolSize 20`) | 100 u/s FAIL·재현(p99 11.66초·15.13초). 연결 1회 사용 8 → 18ms로 늘어 처리량 같음 | [풀 20 실험](2026-10-12-pending-index-remeasure.md#hikari-풀-20-실험--812fd552). 풀은 한계 아님 |
| 10-12 | 로컬 compose(Core 컨테이너, PG 18) | `76316283` | 새 seed(회원 100만·주문 500만·15,000석), Core·DB 같은 Docker 네트워크, Gatling은 Windows | 80 u/s PASS(p99 2.36초), 100 FAIL·재현(p99 19.2초·8.3초). Core 쪽 p95는 100 u/s에서도 39ms 이하 | [컨테이너 측정](2026-10-12-pending-index-remeasure.md#core-컨테이너-측정통로-확인--76316283). DB·Core 환경이 함께 바뀌어 앞 줄과 직접 비교하지 않는다. 남은 한계는 P-010 |
| 10-12 | 로컬 compose(Core 컨테이너, PG 18) | `fcb103bd`(src `21de0a3c`) | 위와 같고 응답 압축 켬, Gatling Accept-Encoding·결과 파일 쓰기 수정, IntelliJ 종료 | 80 u/s PASS 두 번(p99 216·208ms), **100 u/s PASS 두 번(p99 200·277ms)** | [압축·도구 수정 뒤](2026-10-12-pending-index-remeasure.md#intellij를-끄고-다시-잼-결과-파일-쓰기-수정--fcb103bd--8e8d61e). 사다리 끝까지 통과, Core 쪽 p95 28ms 이하. C는 운영에서 |

## 일지 틀

```markdown
# YYYY-MM-DD 주제

## 요약
(쉬운 말로 3~5줄: 무엇을 했고, 무엇을 알았고, 다음은 무엇인지)

## 목적

## 조건
- 대상, Core commit, 실행 방식(bootRun/IDE, 디버거·SQL 로그), 연결 풀, DB와 실행 전 주요 테이블 행 수, 부하 발생기 위치

## 실행 표
| 시각 | 시나리오 | 부하 | 회차 | 성공/시작 | Gatling p95/p99 체류 | Core 쪽 p95(요청별) | 연결·스레드 최대 | 유효 | 판정 |

## 발견한 문제
### P-NNN 제목
- 증상
- 근거(수치, 실행 계획, 로그)
- 원인 판단
- 틀렸던 가설
- 해결 선택지와 장단점
- 결정(누가, 무엇을, 왜)
- 고친 뒤 결과

## 다음에 할 일
```
