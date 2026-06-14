# Ticket Gatling Load Tests

Queue CDN public state 전환과 legacy queue status 비교를 위한 Gatling 부하테스트 저장소이다.

## 구성

- `load-tests/gatling`: 실제 Gatling simulation 프로젝트
- `console`: 로컬 브라우저에서 Gatling 실행을 도와주는 개발용 콘솔

## 정적 비교 대상

Legacy queue status:

```text
GET /api/v1/queue/performances/{performanceId}/status
Header: X-Queue-Session
```

CDN public state:

```text
GET /queue-state/performances/{performanceId}.json
Header/cookie/auth 없음
```

## 테스트

```powershell
.\gradlew.bat -p load-tests/gatling test
.\console\gradlew.bat -p console test
```

실제 부하 실행은 대상 서버, 사용자 수, 투입 시간, 테스트 전용 `performanceId`를 확인한 뒤 수행한다.

