# Ticket Gatling 저장소 Copilot 지침

Copilot Chat, code review, coding agent는 루트 [AGENTS.md](../AGENTS.md)를 공통 기준으로 따른다.

## 문서 우선순위

1. `AGENTS.md`
2. `docs/development.md`
3. `README.md`
4. `console` 변경이면 `console/AGENTS.md`와 `console/README.md`
5. 관련 Gradle 설정, simulation, runner와 테스트 코드

## 필수 규칙

- 답변, 리뷰, 커밋 메시지와 PR 설명은 한국어로 작성한다.
- 커밋과 PR 제목은 `docs/development.md`의 Conventional Commits 규칙을 따른다.
- 실제 Gatling simulation이나 원격 부하 테스트는 사용자가 대상과 규모를 승인하기 전에는 실행하지 않는다.
- 운영 secret, access token, 사용자 정보를 제안 코드나 로그에 포함하지 않는다.
