# Ticket Gatling Console AI 작업 규칙

이 파일은 AI 에이전트가 `ticket-gatling-load-tests/console`에서 반드시 지킬 최소 규칙이다.

## 절대 규칙

- 사용자가 명시적으로 요청하지 않으면 `test`, `run`, Gatling simulation, 대량 사용자 테스트를 실행하지 않는다.
- 테스트를 새로 작성하지 않는다. 이 프로젝트는 의도치 않은 Gatling 실행 위험이 있다.
- 실행 전에는 대상 API URL, 사용자 수, 투입 시간, token mode를 사용자 요청 또는 화면 설정으로 확인한다.
- 운영 secret, 실제 access token, 실제 사용자 정보를 문서나 로그에 남기지 않는다.

## 기본 원칙

- 모든 답변, 문서, 작업 로그는 한국어로 작성한다.
- 파일은 UTF-8, BOM 없이 유지한다.
- 기존 미커밋 변경은 사용자 작업으로 보고 되돌리지 않는다.
- 요청 범위 밖의 기능 추가, 대규모 리팩터링, 새 추상화는 하지 않는다.
- 파괴적 작업, 대량 삭제, `git reset`, `git checkout --`는 명시 요청 없이 수행하지 않는다.

## 먼저 읽을 순서

1. `README.md`
2. `build.gradle`
3. `src/main/resources/static/index.html`
4. `src/main/java/com/ticket/gatling/console`
5. 이 저장소의 `load-tests/gatling`

## 구조 경계

- 이 프로젝트는 로컬 콘솔 UI와 Gatling 실행 command 생성만 담당한다.
- Ticket Server, Queue Server, Gateway 기능을 직접 구현하지 않는다.
- 리포트는 이 저장소의 `load-tests/gatling/build/reports/gatling` 아래 생성되는 것으로 본다.

## 검증

문서/코드 확인은 정적 명령을 우선한다.

```powershell
rg -n "확인할_문구" .
```

실제 실행 검증은 사용자가 테스트 규모와 대상 서버를 명시한 경우에만 수행한다.

## 보고

마무리 보고에는 변경 파일, 핵심 변경점, 검증 결과, 남은 리스크를 포함한다.
