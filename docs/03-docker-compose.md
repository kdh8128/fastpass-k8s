# Docker Compose 기반 FastPass 로컬 실행 환경

## 1. 구현 목적

FastPass API를 로컬 JVM에서 직접 실행하던 방식에서 벗어나, Spring Boot API, PostgreSQL, Redis를 모두 Docker Compose로 실행할 수 있도록 구성했다.

이를 통해 로컬에서도 실제 운영 환경과 유사하게 다음 구조를 검증할 수 있다.

```text
Client
  → fastpass-api container
      → fastpass-postgres container
      → fastpass-redis container
```

---

## 2. 구성 서비스

| 서비스 | 컨테이너 이름 | 역할 | 포트 |
|---|---|---|---|
| api | fastpass-api | Spring Boot API 서버 | 8080 |
| postgres | fastpass-postgres | 이벤트 및 신청 데이터 저장 | 5432 |
| redis | fastpass-redis | 신청 Queue 저장소 | 6379 |

---

## 3. Profile 분리

로컬 직접 실행과 Docker Compose 실행 환경을 분리하기 위해 Spring profile을 나누었다.

| Profile | DB Host | Redis Host | 사용 상황 |
|---|---|---|---|
| local | localhost | localhost | 내 PC에서 bootRun으로 실행 |
| docker | postgres | redis | Docker Compose 내부 컨테이너 실행 |

Docker Compose 환경에서는 컨테이너 간 통신을 위해 `localhost`가 아니라 서비스 이름인 `postgres`, `redis`를 사용한다.

---

## 4. 실행 방법

프로젝트 루트에서 실행한다.

```bash
cd /d/coding/project/fastpass-k8s

docker compose up -d --build
```

실행 상태 확인:

```bash
docker compose ps
```

확인된 컨테이너:

```text
fastpass-api
fastpass-postgres
fastpass-redis
```

---

## 5. Health Check 검증

요청:

```bash
curl http://localhost:8080/actuator/health
```

응답:

```json
{"status":"UP"}
```

이를 통해 Docker 컨테이너로 실행된 Spring Boot API가 정상적으로 동작함을 확인했다.

---

## 6. API 동작 검증

### 6.1 이벤트 생성

요청:

```bash
curl -X POST http://localhost:8080/api/events \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"title\":\"FastPass Docker Event\",\"description\":\"docker compose api test\",\"capacity\":3,\"eventStartAt\":\"2026-07-20T10:00:00\"}"
```

응답 예시:

```json
{
  "id": 2,
  "title": "FastPass Docker Event",
  "description": "docker compose api test",
  "capacity": 3,
  "appliedCount": 0,
  "eventStartAt": "2026-07-20T10:00:00"
}
```

---

### 6.2 신청 요청

요청:

```bash
curl -X POST http://localhost:8080/api/events/2/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"docker-user1\"}"
```

기대 응답:

```json
{
  "eventId": 2,
  "applicantName": "docker-user1",
  "status": "PENDING"
}
```

---

### 6.3 신청 결과 조회

요청:

```bash
curl http://localhost:8080/api/applications/{applicationId}
```

Worker 처리 후 기대 응답:

```json
{
  "status": "SUCCESS"
}
```

이를 통해 Docker Compose 환경에서도 Redis Queue 기반 비동기 신청 처리 구조가 정상 동작함을 확인했다.

---

## 7. 현재 단계의 의미

이번 단계에서 FastPass는 단순 로컬 실행 애플리케이션이 아니라, 컨테이너 기반 실행 환경을 갖게 되었다.

이 구조는 이후 Kubernetes 배포로 확장하기 위한 전 단계이다.

```text
Docker Compose
  → API 컨테이너
  → PostgreSQL 컨테이너
  → Redis 컨테이너

Kubernetes
  → API Deployment
  → PostgreSQL Deployment/StatefulSet
  → Redis Deployment
  → Service
  → ConfigMap/Secret
```

---

## 8. 완료 기준

```text
API Docker image build 성공
fastpass-api 컨테이너 실행 성공
PostgreSQL 컨테이너 연결 성공
Redis 컨테이너 연결 성공
/actuator/health 응답 성공
이벤트 생성 API 성공
신청 API 성공
Redis Queue 기반 신청 처리 성공
```

위 기준을 만족했으므로 Docker Compose 기반 FastPass 로컬 실행 환경 구성을 완료했다.
