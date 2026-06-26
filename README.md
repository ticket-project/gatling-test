# Ticket Gatling Load Tests

Ticket/Queue 부하 테스트와 Queue CDN public state 전환, legacy queue status 비교를 위한 Gatling 부하테스트 저장소이다.

## 구성

- `load-tests/gatling`: 실제 Gatling simulation 프로젝트
- `console`: 로컬 브라우저에서 Gatling 실행을 도와주는 개발용 콘솔

`ticket` 저장소 안에 있던 `load-tests/gatling`은 이 저장소로 통합한다.

## 비교 대상 API

Legacy queue status:

```text
GET /api/v1/queue/performances/{performanceId}/status
Header: X-Queue-Session
```

CDN public state:

```text
Base URL: https://queue.oneticket.site
GET /api/v1/queue/performances/{performanceId}/state
Header/cookie/auth 없음
```

## Access token 파일 생성

큰 `/join` 부하 테스트에서는 실행 중 synthetic JWT를 만들지 말고 토큰 파일을 미리 만들어 `-DaccessTokenMode=tokens -DaccessTokensFile=...`로 사용한다.

```powershell
.\gradlew.bat -p load-tests/gatling generateAccessTokens `
  -Doutput=C:\Users\mn040\IdeaProjects\ticket-workspace\.tmp\access-tokens.txt `
  -DjwtSecret=0123456789abcdef0123456789abcdef `
  -DjwtIssuer=ticket `
  -DsyntheticMemberStartId=1 `
  -DsyntheticJwtRole=MEMBER `
  -DsyntheticTokenTtlSeconds=3600 `
  -DtokenCount=60000
```

생성된 파일은 UTF-8 텍스트이며 JWT가 한 줄에 하나씩 들어간다. 콘솔에서는 `Token mode=직접 입력`을 선택하고 `Access Token 파일 경로`에 위 파일 경로를 넣는다.

## 테스트

```powershell
.\gradlew.bat -p load-tests/gatling test
.\console\gradlew.bat -p console test
```

실제 부하 실행은 대상 서버, 사용자 수, 투입 시간, 테스트 전용 `performanceId`를 확인한 뒤 수행한다.
