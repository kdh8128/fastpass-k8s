# FastPass API MVP

## 1. 구현 목표

FastPass의 1차 MVP에서는 Redis Queue를 적용하기 전, H2 DB 기반으로 선착순 이벤트 신청 흐름을 먼저 구현했다.

이번 단계의 목표는 다음과 같다.

* 이벤트 생성
* 이벤트 목록 조회
* 이벤트 상세 조회
* 이벤트 신청
* 신청 결과 조회
* 정원 초과 시 실패 처리
* 중복 신청 방지
* 공통 예외 응답 처리

---

## 2. 구현 API

| Method | Endpoint                            | Description |
| ------ | ----------------------------------- | ----------- |
| POST   | `/api/events`                       | 이벤트 생성      |
| GET    | `/api/events`                       | 이벤트 목록 조회   |
| GET    | `/api/events/{eventId}`             | 이벤트 상세 조회   |
| POST   | `/api/events/{eventId}/apply`       | 이벤트 신청      |
| GET    | `/api/applications/{applicationId}` | 신청 결과 조회    |

---

## 3. 검증 환경

* Runtime: Java 21
* Framework: Spring Boot 3.5.x
* Database: H2 In-Memory DB
* Test Tool: curl
* API Server: `localhost:8080`

---

## 4. 검증 결과

### 4.1 이벤트 생성

요청:

```bash
curl -X POST http://localhost:8080/api/events \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"title\":\"FastPass Open Event\",\"description\":\"first come event test\",\"capacity\":3,\"eventStartAt\":\"2026-07-20T10:00:00\"}"
```

응답 예시:

```json
{
  "id": 1,
  "title": "FastPass Open Event",
  "description": "first come event test",
  "capacity": 3,
  "appliedCount": 0,
  "eventStartAt": "2026-07-20T10:00:00",
  "createdAt": "2026-07-15T19:03:25.7121932"
}
```

검증 결과:

* 이벤트 생성 성공
* 정원 `capacity=3` 정상 저장
* 신청 인원 `appliedCount=0`으로 초기화 확인

---

### 4.2 이벤트 목록 조회

요청:

```bash
curl http://localhost:8080/api/events
```

응답 예시:

```json
[
  {
    "id": 1,
    "title": "FastPass Open Event",
    "description": "first come event test",
    "capacity": 3,
    "appliedCount": 0,
    "eventStartAt": "2026-07-20T10:00:00",
    "createdAt": "2026-07-15T19:03:25.7121932"
  }
]
```

검증 결과:

* 생성된 이벤트가 목록 조회 API에서 정상 반환됨

---

### 4.3 이벤트 신청

요청:

```bash
curl -X POST http://localhost:8080/api/events/1/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"donghwan\"}"
```

응답 예시:

```json
{
  "applicationId": 1,
  "eventId": 1,
  "applicantName": "donghwan",
  "status": "SUCCESS",
  "createdAt": "2026-07-15T19:03:32.8028669"
}
```

검증 결과:

* 신청 성공 시 `SUCCESS` 상태 반환
* 신청 결과가 DB에 저장됨
* 이벤트의 `appliedCount`가 증가함

---

### 4.4 정원 초과 처리

이벤트 정원을 `3`으로 설정한 뒤 4번째 신청을 수행했다.

응답 예시:

```json
{
  "applicationId": 4,
  "eventId": 1,
  "applicantName": "user4",
  "status": "FAILED",
  "createdAt": "2026-07-13T23:32:18.598585"
}
```

검증 결과:

* 정원 내 신청은 `SUCCESS`
* 정원 초과 신청은 `FAILED`
* 이벤트의 `appliedCount`는 정원을 초과하지 않음

---

### 4.5 신청 결과 조회

요청:

```bash
curl http://localhost:8080/api/applications/1
```

응답 예시:

```json
{
  "applicationId": 1,
  "eventId": 1,
  "applicantName": "donghwan",
  "status": "SUCCESS",
  "createdAt": "2026-07-13T23:32:04.934322"
}
```

검증 결과:

* 신청 ID 기준으로 신청 결과 조회 가능
* 신청 상태, 이벤트 ID, 신청자 정보가 정상 반환됨

---

### 4.6 중복 신청 방지

동일한 신청자가 같은 이벤트에 다시 신청하는 경우를 테스트했다.

요청:

```bash
curl -X POST http://localhost:8080/api/events/1/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"donghwan\"}"
```

응답 예시:

```json
{
  "timestamp": "2026-07-15T19:03:36.71921",
  "status": 409,
  "error": "Conflict",
  "message": "Already applied to this event. eventId=1, applicantName=donghwan",
  "path": "/api/events/1/apply"
}
```

검증 결과:

* 동일 이벤트에 동일 신청자 중복 신청 차단
* 중복 신청 시 `409 Conflict` 반환
* 공통 에러 응답 형식 적용 확인

---

### 4.7 존재하지 않는 이벤트 신청

존재하지 않는 이벤트 ID로 신청을 시도했다.

요청:

```bash
curl -i -X POST http://localhost:8080/api/events/999/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"test\"}"
```

응답 예시:

```json
{
  "timestamp": "2026-07-15T19:03:52.8117924",
  "status": 404,
  "error": "Not Found",
  "message": "Event not found. id=999",
  "path": "/api/events/999/apply"
}
```

검증 결과:

* 존재하지 않는 이벤트 신청 시 `404 Not Found` 반환
* 기존 500 Internal Server Error 문제를 공통 예외 처리로 개선

---

## 5. 현재 구조

현재 1차 MVP의 신청 처리 흐름은 다음과 같다.

```text
신청 요청
  → Event 조회
  → 중복 신청 여부 확인
  → 정원 확인
  → 신청 결과 저장
  → 응답 반환
```

현재 구조는 API 서버가 신청 요청을 동기 방식으로 직접 처리한다.

---

## 6. 현재 구조의 한계

현재 구조는 기능 검증에는 충분하지만, 이벤트 오픈 시점처럼 요청이 순간적으로 몰리는 상황에서는 한계가 있다.

주요 한계는 다음과 같다.

* API 서버가 모든 신청 요청을 즉시 처리해야 함
* 요청 증가 시 DB 접근이 집중될 수 있음
* Worker 기반 비동기 처리 구조가 아직 없음
* Queue length, Worker 처리량 등 운영 지표를 아직 수집할 수 없음
* Kubernetes 환경에서 Worker Pod 확장 시나리오를 검증하기 어려움

---

## 7. 다음 개선 방향

다음 단계에서는 Redis Queue를 적용해 신청 요청을 비동기 처리 구조로 변경한다.

목표 구조는 다음과 같다.

```text
신청 요청
  → API 서버가 Redis Queue에 신청 요청 적재
  → Worker가 Queue를 소비
  → 정원 및 중복 여부 확인
  → 신청 결과 저장
  → 신청자는 결과 조회 API로 상태 확인
```

이를 통해 다음 운영 시나리오를 검증할 수 있도록 확장한다.

* 트래픽 급증 시 API 서버의 즉시 처리 부담 완화
* Redis Queue length 기반 대기열 상태 확인
* Worker 처리량 모니터링
* Worker Pod 장애 발생 시 복구 시나리오 검증
* Kubernetes HPA를 통한 API/Worker Pod 확장 검증

---

## 8. 이번 단계에서 얻은 결과

이번 단계에서는 FastPass의 핵심 도메인인 선착순 이벤트 신청 흐름을 1차 MVP 형태로 구현했다.

구현 결과는 다음과 같다.

* 이벤트 생성 및 조회 API 구현
* 이벤트 신청 및 신청 결과 조회 API 구현
* 정원 초과 시 실패 처리 구현
* 중복 신청 방지 구현
* 공통 예외 응답 구조 적용
* 존재하지 않는 리소스 요청 시 404 반환
* 잘못된 요청 또는 중복 요청에 대한 명확한 HTTP 상태 코드 반환

이번 MVP를 기반으로 다음 단계에서는 Redis Queue, PostgreSQL, Docker Compose를 적용해 운영 환경에 가까운 구조로 확장할 예정이다.
