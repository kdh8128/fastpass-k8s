# Redis Queue 기반 비동기 신청 처리

## 1. 구현 목적

기존 FastPass 신청 API는 사용자가 신청 요청을 보내면 API 서버가 즉시 다음 작업을 모두 처리했다.

```text
신청 요청
  → Event 조회
  → 중복 신청 여부 확인
  → 정원 확인
  → SUCCESS / FAILED 저장
  → 응답 반환
```

이 구조는 단순하지만, 이벤트 오픈 시점처럼 신청 요청이 동시에 몰리는 상황에서는 API 서버가 직접 모든 정원 판단과 DB 갱신을 처리해야 하므로 부하가 커질 수 있다.

이를 개선하기 위해 신청 요청과 실제 처리 과정을 분리했다.

```text
신청 요청
  → PENDING 상태로 신청 저장
  → Redis Queue에 applicationId 적재
  → API는 PENDING 응답
  → Worker가 Redis Queue에서 applicationId 소비
  → 정원 확인
  → SUCCESS 또는 FAILED로 상태 변경
  → 사용자는 결과 조회 API로 상태 확인
```

---

## 2. 변경된 처리 구조

### 기존 동기 처리 방식

```text
Client
  → API Server
      → DB 조회
      → 중복 신청 여부 확인
      → 정원 확인
      → 신청 결과 저장
  → SUCCESS / FAILED 응답
```

### Redis Queue 기반 비동기 처리 방식

```text
Client
  → API Server
      → PENDING 신청 데이터 저장
      → Redis Queue에 applicationId 적재
  → PENDING 응답

Redis Queue
  → Worker
      → applicationId 소비
      → DB에서 신청 정보 조회
      → Event에 Lock 적용
      → 정원 확인
      → SUCCESS / FAILED 상태 갱신
```

---

## 3. 주요 구현 내용

### 3.1 ApplicationStatus 확장

신청 상태에 `PENDING`을 추가했다.

```java
public enum ApplicationStatus {
    PENDING,
    SUCCESS,
    FAILED
}
```

상태 의미는 다음과 같다.

| 상태 | 의미 |
|---|---|
| PENDING | 신청 요청이 접수되었고 Queue 처리 대기 중 |
| SUCCESS | 정원 내 신청 성공 |
| FAILED | 정원 초과 등으로 신청 실패 |

---

### 3.2 Redis Queue 서비스 추가

Redis List 자료구조를 Queue처럼 사용했다.

```text
Queue Key: fastpass:application:queue
```

처리 방식은 다음과 같다.

| 동작 | Redis 명령 개념 |
|---|---|
| Queue 적재 | rightPush |
| Queue 소비 | leftPop |
| Queue 크기 확인 | size |

---

### 3.3 신청 API 변경

신청 API는 더 이상 즉시 `SUCCESS` 또는 `FAILED`를 판단하지 않는다.

변경 후 신청 흐름은 다음과 같다.

```text
1. Event 조회
2. 중복 신청 여부 확인
3. EventApplication을 PENDING 상태로 저장
4. Redis Queue에 applicationId 적재
5. PENDING 응답 반환
```

예시 응답:

```json
{
  "applicationId": 1,
  "eventId": 1,
  "applicantName": "user1",
  "status": "PENDING",
  "createdAt": "2026-07-21T18:24:28.840514"
}
```

---

### 3.4 Worker 추가

`ApplicationQueueWorker`는 일정 주기마다 Redis Queue를 확인한다.

현재 구현에서는 다음 방식으로 동작한다.

```text
1초마다 Redis Queue 확인
  → applicationId가 없으면 return
  → applicationId가 있으면 DB에서 신청 정보 조회
  → Event를 PESSIMISTIC_WRITE Lock으로 조회
  → 정원 초과 여부 확인
  → SUCCESS 또는 FAILED로 상태 변경
```

동시에 여러 Worker가 같은 이벤트의 정원을 수정하는 상황을 고려하여 `PESSIMISTIC_WRITE` Lock을 적용했다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select e from Event e where e.id = :eventId")
Optional<Event> findByIdForUpdate(@Param("eventId") Long eventId);
```

---

## 4. 테스트 환경

| 항목 | 내용 |
|---|---|
| Application | Spring Boot 3.5.16 |
| Java | Java 21 |
| Database | PostgreSQL 16 |
| Queue | Redis 7.2 |
| Local Infra | Docker Compose |
| Profile | local |

Docker Compose 실행:

```bash
cd /d/coding/project/fastpass-k8s
docker compose up -d
```

Spring Boot 실행:

```bash
cd /d/coding/project/fastpass-k8s/apps/api
./gradlew.bat bootRun --args="--spring.profiles.active=local"
```

---

## 5. 검증 결과

### 5.1 이벤트 생성

요청:

```bash
curl -X POST http://localhost:8080/api/events \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"title\":\"FastPass Queue Event\",\"description\":\"redis queue test\",\"capacity\":3,\"eventStartAt\":\"2026-07-20T10:00:00\"}"
```

응답:

```json
{
  "id": 1,
  "title": "FastPass Queue Event",
  "description": "redis queue test",
  "capacity": 3,
  "appliedCount": 0,
  "eventStartAt": "2026-07-20T10:00:00",
  "createdAt": "2026-07-21T18:24:21.4093685"
}
```

---

### 5.2 신청 직후 PENDING 응답 확인

요청:

```bash
curl -X POST http://localhost:8080/api/events/1/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"user1\"}"
```

응답:

```json
{
  "applicationId": 1,
  "eventId": 1,
  "applicantName": "user1",
  "status": "PENDING",
  "createdAt": "2026-07-21T18:24:28.840514"
}
```

신청 요청 직후 `PENDING` 상태가 반환되는 것을 확인했다.

---

### 5.3 Worker 처리 후 SUCCESS 변경 확인

요청:

```bash
curl http://localhost:8080/api/applications/1
```

응답:

```json
{
  "applicationId": 1,
  "eventId": 1,
  "applicantName": "user1",
  "status": "SUCCESS",
  "createdAt": "2026-07-21T18:24:28.840514"
}
```

Worker가 Redis Queue에서 신청 건을 소비하고, 정원 내 신청으로 판단하여 `SUCCESS`로 변경한 것을 확인했다.

---

### 5.4 Queue 크기 확인

요청:

```bash
curl http://localhost:8080/api/queue/applications/size
```

응답:

```json
{
  "size": 0
}
```

Worker 처리 후 Redis Queue가 비어 있음을 확인했다.

---

### 5.5 정원 초과 검증

이벤트 정원은 3명이다.

추가 신청 요청:

```bash
curl -X POST http://localhost:8080/api/events/1/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"user2\"}"

curl -X POST http://localhost:8080/api/events/1/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"user3\"}"

curl -X POST http://localhost:8080/api/events/1/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"user4\"}"
```

신청 직후 응답:

```json
{"applicationId":2,"eventId":1,"applicantName":"user2","status":"PENDING"}
{"applicationId":3,"eventId":1,"applicantName":"user3","status":"PENDING"}
{"applicationId":4,"eventId":1,"applicantName":"user4","status":"PENDING"}
```

처리 후 이벤트 조회:

```bash
curl http://localhost:8080/api/events/1
```

응답:

```json
{
  "id": 1,
  "title": "FastPass Queue Event",
  "description": "redis queue test",
  "capacity": 3,
  "appliedCount": 3,
  "eventStartAt": "2026-07-20T10:00:00",
  "createdAt": "2026-07-21T18:24:21.409369"
}
```

신청 결과 조회:

```bash
curl http://localhost:8080/api/applications/1
curl http://localhost:8080/api/applications/2
curl http://localhost:8080/api/applications/3
curl http://localhost:8080/api/applications/4
```

결과:

```text
applicationId=1, applicantName=user1, status=SUCCESS
applicationId=2, applicantName=user2, status=SUCCESS
applicationId=3, applicantName=user3, status=SUCCESS
applicationId=4, applicantName=user4, status=FAILED
```

정원 3명까지만 성공 처리되고, 4번째 신청자는 실패 처리되는 것을 확인했다.

---

## 6. 현재 구조의 의미

이번 구현으로 FastPass는 단순 CRUD API에서 벗어나, 트래픽 집중 상황을 고려한 Queue 기반 처리 구조를 갖게 되었다.

특히 다음과 같은 운영 관점의 확장이 가능해졌다.

```text
API Pod 확장
Worker Pod 분리
Worker Pod 수평 확장
Redis Queue 모니터링
처리 지연 시간 측정
장애 발생 시 Queue 잔여 건 확인
Kubernetes HPA 적용
```

---

## 7. 한계 및 다음 개선 방향

현재 구현은 로컬 개발 환경에서 Redis Queue 기반 비동기 처리를 검증한 단계이다.

추가 개선 방향은 다음과 같다.

| 개선 항목 | 설명 |
|---|---|
| Worker 분리 | API 서버와 Worker를 별도 프로세스 또는 별도 Pod로 분리 |
| 재시도 처리 | Worker 처리 중 실패한 신청 건에 대한 retry 구조 추가 |
| Dead Letter Queue | 반복 실패 신청 건을 별도 Queue로 이동 |
| 처리 지연 모니터링 | PENDING 상태 유지 시간 측정 |
| 부하 테스트 | k6를 이용해 동시 신청 요청 검증 |
| Kubernetes 배포 | API, Worker, PostgreSQL, Redis를 Kubernetes 환경에 배포 |
| HPA 적용 | CPU 또는 Queue 길이 기반 Auto Scaling 검토 |

---

## 8. 완료 기준

이번 단계의 완료 기준은 다음과 같다.

```text
신청 API가 PENDING 상태를 반환한다.
Redis Queue에 신청 ID가 적재된다.
Worker가 Queue를 소비한다.
정원 내 신청은 SUCCESS로 변경된다.
정원 초과 신청은 FAILED로 변경된다.
이벤트 appliedCount는 capacity를 초과하지 않는다.
```

위 기준을 모두 만족했으므로 Redis Queue 기반 비동기 신청 처리 기능 구현을 완료했다.