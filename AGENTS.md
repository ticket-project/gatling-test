# Ticket Gatling AI 작업 규칙

이 파일은 AI 에이전트가 `gatling-test` 저장소에서 반드시 지킬 루트 규칙이다.

## 기본 원칙

- 모든 응답, 문서, 작업 로그는 한국어로 작성한다.
- 파일은 UTF-8, BOM 없이 유지한다.
- 기존 미커밋 파일과 추적되지 않은 파일은 사용자 작업으로 보고 되돌리거나 포함하지 않는다.
- 요구 범위 밖의 기능 추가, 대규모 리팩터링, 새 추상화는 하지 않는다.
- 사용자가 커밋을 명시적으로 요청하지 않으면 커밋하지 않는다.
- 실제 Gatling simulation이나 원격 부하 테스트는 대상 URL, 사용자 수, 투입 시간과 실행 승인을 확인하기 전에는 실행하지 않는다.
- 운영 secret, access token, 사용자 정보를 문서, 명령행 또는 결과 파일에 남기지 않는다.

## 먼저 읽을 순서

1. `README.md`
2. `docs/development.md`
3. `console` 변경이면 `console/AGENTS.md`와 `console/README.md`
4. 관련 Gradle 설정과 simulation, runner, 테스트 코드

## 저장소 경계

- `load-tests/gatling`은 실제 Gatling simulation과 검증·증거 수집 코드를 담당한다.
- `console`은 로컬 UI와 Gatling 실행 명령 생성을 담당한다.
- 분산 실행 스크립트는 노드별 실행, feeder 분할, 결과 수집과 합산을 담당한다.
- Ticket/Core 또는 Queue 서버 기능을 이 저장소에 구현하지 않는다.

## 검증

문서만 변경하면 정적 검증을 우선한다.

```powershell
rg -n "확인할_문구" .
git diff --check
```

실제 simulation 실행은 사용자가 대상과 규모를 명시적으로 승인한 경우에만 수행한다.

## 커밋 및 PR

- 커밋 메시지와 PR 제목은 Conventional Commits 기반 `<type>(<scope>): <한국어 설명>` 형식을 따른다.
- `scope`는 선택 사항이며 기존 하위 프로젝트 또는 책임 이름을 우선 사용한다.
- 허용 `type`은 `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `chore`, `build`, `ci`, `security`, `revert`다.
- 설명은 한국어로 작성하고 마침표를 붙이지 않는다. 기술 고유명사는 원문 표기를 허용한다.
- 하나의 커밋에는 하나의 목적만 포함하며 기존 사용자 변경과 섞지 않는다.
- 커밋 또는 PR을 만들기 전에 [커밋과 PR 컨벤션](docs/development.md#커밋과-pr-컨벤션)을 확인한다.
- 이미 원격에 올라간 커밋 이력을 변경하려면 먼저 사용자 승인을 받는다.

## 보고

마무리 보고에는 변경 파일, 핵심 변경점, 검증 결과, 실제 부하 실행 여부와 남은 리스크를 포함한다.
