# API와 Worker Deployment 분리 검증

## 1. 분리 목적

FastPass는 Redis Queue 기반 비동기 신청 처리 구조를 사용한다.

초기 구조에서는 하나의 `fastpass-api` Pod 안에서 HTTP API 서버와 Queue Worker가 함께 실행되었다.

```text
기존 구조:
fastpass-api Pod
  ├── HTTP API
  └── Queue Worker
```

이 구조에서도 기능은 정상 동작하지만, 운영 관점에서는 한계가 있다.

API Pod 수를 늘리면 Worker도 함께 늘어나고, Worker만 독립적으로 확장하거나 중지하기 어렵다.

따라서 API 요청 접수 계층과 Queue 처리 계층을 Kubernetes Deployment 단위로 분리했다.

```text
개선 구조:
fastpass-api Deployment
  └── HTTP API 요청 접수

fastpass-worker Deployment
  └── Redis Queue 소비 및 신청 처리
```

---

## 2. 목표 구조

API와 Worker는 같은 Docker image를 사용하지만, Kubernetes Deployment와 환경변수를 다르게 설정하여 역할을 분리한다.

```text
같은 Docker image:
fastpass-k8s-api:latest
```

하지만 실행 역할은 다르다.

```text
fastpass-api Pod
  FASTPASS_WORKER_ENABLED=false
  → HTTP API 요청 접수
  → 신청 정보를 PENDING 상태로 저장
  → Redis Queue에 applicationId 적재
  → Worker 비활성화

fastpass-worker Pod
  FASTPASS_WORKER_ENABLED=true
  → Redis Queue에서 applicationId 소비
  → 정원 확인
  → SUCCESS 또는 FAILED 상태로 변경
  → Worker 활성화
```

이 구조를 통해 API와 Worker를 독립적으로 배포하고 확장할 수 있다.

---

## 3. 적용 방식

Spring Boot 애플리케이션 내부의 Queue Worker Bean에 조건부 실행 설정을 추가했다.

```java
@Component
@ConditionalOnProperty(
        name = "fastpass.worker.enabled",
        havingValue = "true"
)
public class ApplicationQueueWorker {
    ...
}
```

`fastpass.worker.enabled=true`일 때만 `ApplicationQueueWorker` Bean이 생성된다.

반대로 `false`이면 Worker Bean이 생성되지 않기 때문에 해당 Pod에서는 Queue 소비가 실행되지 않는다.

---

## 4. Kubernetes 환경변수 설정

Kubernetes에서는 API Deployment와 Worker Deployment에 서로 다른 환경변수를 설정했다.

### 4.1 API Deployment

`fastpass-api` Deployment에서는 Worker를 비활성화했다.

```yaml
env:
  - name: FASTPASS_WORKER_ENABLED
    value: "false"
```

API Pod의 역할은 다음과 같다.

```text
HTTP 요청 수신
이벤트 생성
신청 요청 수신
신청 상태를 PENDING으로 저장
Redis Queue에 applicationId 적재
```

---

### 4.2 Worker Deployment

`fastpass-worker` Deployment에서는 Worker를 활성화했다.

```yaml
env:
  - name: FASTPASS_WORKER_ENABLED
    value: "true"
```

Worker Pod의 역할은 다음과 같다.

```text
Redis Queue에서 applicationId dequeue
Event 정원 확인
정원이 남아 있으면 SUCCESS 처리
정원이 가득 찼으면 FAILED 처리
```

Worker Pod도 같은 Spring Boot 애플리케이션으로 실행되므로 내부적으로 HTTP 서버는 뜬다.

하지만 `fastpass-worker`에 대한 Kubernetes Service는 생성하지 않았다.

따라서 외부 요청은 `fastpass-api` Service를 통해서만 들어오고, Worker Pod는 Queue 처리 전용으로 사용된다.

---

## 5. 변경된 Kubernetes 구성

API와 Worker 분리 후 Kubernetes 구성은 다음과 같다.

```text
Namespace: fastpass

Deployments:
  fastpass-api
  fastpass-worker
  postgres
  redis

Services:
  fastpass-api
  postgres
  redis
```

전체 흐름은 다음과 같다.

```text
Client
  → fastpass-api Service
  → fastpass-api Pod
  → PostgreSQL에 PENDING 저장
  → Redis Queue에 applicationId 적재

fastpass-worker Pod
  → Redis Queue에서 applicationId 소비
  → PostgreSQL에서 신청 상태 갱신
```

---

## 6. 배포 과정

API와 Worker 분리 코드를 적용한 뒤 애플리케이션을 다시 빌드했다.

```bash
cd /d/coding/project/fastpass-k8s/apps/api

./gradlew.bat clean build
```

Docker 이미지를 다시 빌드했다.

```bash
cd /d/coding/project/fastpass-k8s

docker compose build api
```

로컬 Docker 이미지를 Docker Desktop Kubernetes 노드 내부 이미지 저장소로 import했다.

```bash
docker save fastpass-k8s-api:latest | docker exec -i desktop-control-plane ctr -n k8s.io images import -
```

Kubernetes manifest를 적용했다.

```bash
kubectl apply -f deploy/k8s/api-deployment.yaml
kubectl apply -f deploy/k8s/worker-deployment.yaml
```

Deployment를 재시작했다.

```bash
kubectl rollout restart deployment/fastpass-api -n fastpass
kubectl rollout restart deployment/fastpass-worker -n fastpass
```

Pod 상태를 확인했다.

```bash
kubectl get pods -n fastpass
```

기대 상태는 다음과 같다.

```text
fastpass-api-xxxxx       1/1   Running
fastpass-worker-xxxxx    1/1   Running
postgres-xxxxx           1/1   Running
redis-xxxxx              1/1   Running
```

---

## 7. Worker 활성화 로그 확인

Worker가 API Pod에서는 실행되지 않고 Worker Pod에서만 실행되는지 확인하기 위해 로그를 확인했다.

API Deployment 로그 확인:

```bash
kubectl logs -n fastpass deployment/fastpass-api --tail=50
```

Worker Deployment 로그 확인:

```bash
kubectl logs -n fastpass deployment/fastpass-worker --tail=50
```

Worker가 활성화된 Pod에서는 다음 로그가 출력된다.

```text
FastPass queue worker is enabled. batchSize=50
```

검증 기준은 다음과 같다.

```text
fastpass-api 로그:
  Worker enabled 로그 없음

fastpass-worker 로그:
  FastPass queue worker is enabled. batchSize=50 로그 존재
```

이를 통해 Worker Bean이 `fastpass-worker` Pod에서만 생성되는 것을 확인할 수 있다.

---

## 8. 분리 검증 시나리오

API와 Worker가 실제로 분리되었는지 확인하기 위해 Worker Deployment를 일시적으로 중지했다.

검증 목적은 다음과 같다.

```text
Worker가 없는 상태에서도 API는 신청 요청을 받을 수 있어야 한다.
하지만 Worker가 없기 때문에 신청 상태는 PENDING으로 남아 있어야 한다.
Redis Queue에는 신청 ID가 적재되어 있어야 한다.
Worker를 다시 실행하면 Queue를 소비하고 SUCCESS로 변경되어야 한다.
```

---

## 9. Worker 중지

Worker Deployment의 replica 수를 0으로 변경했다.

```bash
kubectl scale deployment fastpass-worker -n fastpass --replicas=0
kubectl get pods -n fastpass
```

이 상태에서는 `fastpass-worker` Pod가 존재하지 않는다.

즉, Redis Queue를 소비할 Worker가 없는 상태이다.

---

## 10. API 포트포워딩

로컬에서 Kubernetes 내부 API Service에 접근하기 위해 port-forward를 실행했다.

```bash
kubectl port-forward -n fastpass service/fastpass-api 18080:8080
```

이후 로컬에서는 다음 주소로 API에 접근했다.

```text
http://localhost:18080
```

---

## 11. 이벤트 생성

Worker가 중지된 상태에서 이벤트를 생성했다.

```bash
curl -X POST http://localhost:18080/api/events \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"title\":\"FastPass Split Worker Event\",\"description\":\"api worker split test\",\"capacity\":3,\"eventStartAt\":\"2026-07-20T10:00:00\"}"
```

응답:

```json
{
  "id": 4,
  "title": "FastPass Split Worker Event",
  "description": "api worker split test",
  "capacity": 3,
  "appliedCount": 0,
  "eventStartAt": "2026-07-20T10:00:00",
  "createdAt": "2026-08-08T13:01:58.796576833"
}
```

이벤트 생성이 정상적으로 수행되었다.

---

## 12. 신청 요청

생성한 이벤트에 신청 요청을 보냈다.

```bash
curl -X POST http://localhost:18080/api/events/4/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"split-user1\"}"
```

응답:

```json
{
  "applicationId": 1092,
  "eventId": 4,
  "applicantName": "split-user1",
  "status": "PENDING",
  "createdAt": "2026-08-08T13:02:07.093780693"
}
```

신청 요청은 정상적으로 접수되었고, 상태는 `PENDING`으로 저장되었다.

이 시점에서 Worker는 중지되어 있으므로 신청이 바로 `SUCCESS`로 변경되지 않아야 한다.

---

## 13. Queue size 확인

Worker가 중지된 상태에서 Redis Queue size를 확인했다.

```bash
curl http://localhost:18080/api/queue/applications/size
```

응답:

```json
{
  "size": 1
}
```

이는 API가 신청 요청을 받은 뒤 `applicationId=1092`를 Redis Queue에 정상적으로 적재했음을 의미한다.

그리고 Worker가 중지되어 있기 때문에 Queue가 소비되지 않고 그대로 남아 있다.

---

## 14. 신청 상태 확인

신청 상태 조회 API는 이벤트 ID가 아니라 신청 ID를 사용한다.

```text
/api/applications/{applicationId}
```

이번 신청의 `applicationId`는 1092이다.

```bash
curl http://localhost:18080/api/applications/1092
```

Worker가 중지된 상태에서는 다음과 같이 `PENDING` 상태가 유지되어야 한다.

```json
{
  "applicationId": 1092,
  "eventId": 4,
  "applicantName": "split-user1",
  "status": "PENDING"
}
```

이 결과는 API와 Worker가 분리되었음을 보여준다.

만약 API Pod 내부에서 Worker가 함께 실행되고 있었다면, 신청은 곧바로 `SUCCESS`로 처리되었을 것이다.

하지만 Worker Deployment를 0개로 줄였을 때 신청이 `PENDING`으로 남았으므로, API Pod에서는 Worker가 실행되지 않는다는 것을 확인할 수 있다.

---

## 15. Worker 재시작

Worker Deployment를 다시 1개로 늘렸다.

```bash
kubectl scale deployment fastpass-worker -n fastpass --replicas=1
kubectl get pods -n fastpass
```

Worker Pod가 다시 실행되면 Redis Queue에 남아 있던 신청 ID를 소비한다.

---

## 16. Worker 재시작 후 상태 확인

Worker를 다시 실행한 뒤 신청 상태를 확인했다.

```bash
curl http://localhost:18080/api/applications/1092
```

기대 결과:

```json
{
  "applicationId": 1092,
  "eventId": 4,
  "applicantName": "split-user1",
  "status": "SUCCESS"
}
```

Queue size도 다시 확인한다.

```bash
curl http://localhost:18080/api/queue/applications/size
```

기대 결과:

```json
{
  "size": 0
}
```

이는 Worker Pod가 Redis Queue에 남아 있던 신청을 정상적으로 소비하고, 신청 상태를 `SUCCESS`로 변경했음을 의미한다.

---

## 17. 검증 결과 요약

이번 검증에서 확인한 내용은 다음과 같다.

```text
Worker replicas=0
  → fastpass-worker Pod 없음
  → API 이벤트 생성 성공
  → API 신청 요청 성공
  → 신청 상태 PENDING
  → Queue size 1

Worker replicas=1
  → fastpass-worker Pod 재실행
  → Redis Queue 소비
  → 신청 상태 SUCCESS
  → Queue size 0
```

이를 통해 API와 Worker가 Deployment 단위로 분리되었음을 확인했다.

---

## 18. 구조적 의미

이번 변경의 핵심은 API 요청 접수 계층과 Queue 처리 계층을 분리한 것이다.

기존에는 API와 Worker가 하나의 Pod 안에 함께 있었다.

```text
기존:
fastpass-api Pod
  ├── API
  └── Worker
```

개선 후에는 각각 독립적인 Deployment로 분리되었다.

```text
개선:
fastpass-api Deployment
  └── API

fastpass-worker Deployment
  └── Worker
```

이제 API와 Worker는 독립적으로 scale in/out 할 수 있다.

예를 들어 API 요청이 많으면 API Pod를 늘릴 수 있다.

```bash
kubectl scale deployment fastpass-api -n fastpass --replicas=3
```

Queue backlog가 많으면 Worker Pod를 늘릴 수 있다.

```bash
kubectl scale deployment fastpass-worker -n fastpass --replicas=3
```

즉, 요청 접수 처리량과 Queue 소비 처리량을 분리해서 조절할 수 있다.

---

## 19. 포트폴리오 관점의 의미

이번 단계는 단순한 기능 구현이 아니라, 운영 구조 개선에 해당한다.

포트폴리오에서는 다음과 같이 설명할 수 있다.

```text
초기에는 API 서버 내부 Scheduler로 Redis Queue를 처리했으나,
부하 테스트 결과 Worker 처리량이 주요 병목으로 확인되었다.

이후 Worker batch 처리를 통해 Queue 소비량을 개선했고,
추가로 API와 Worker를 별도 Kubernetes Deployment로 분리했다.

이를 통해 HTTP 요청 접수 계층과 Queue 처리 계층을 독립적으로 확장할 수 있는 구조를 구성했다.
```

이 흐름은 다음 역량을 보여준다.

```text
Kubernetes Deployment 설계
환경변수 기반 실행 역할 분리
Redis Queue 기반 비동기 처리 구조 이해
부하 테스트 기반 병목 확인
Worker 처리량 개선
API/Worker 독립 확장 구조 설계
```

---

## 20. 남아 있는 한계

현재 구조는 API와 Worker를 Deployment 단위로 분리했지만, 아직 완전히 독립된 애플리케이션은 아니다.

| 한계 | 설명 |
|---|---|
| 같은 Docker image 사용 | API와 Worker가 같은 Spring Boot 애플리케이션 이미지로 실행됨 |
| Worker Pod에도 HTTP 서버가 실행됨 | Worker Service는 없지만 내부적으로 Tomcat은 실행됨 |
| Controller Bean은 Worker Pod에도 로드됨 | 완전한 코드 레벨 분리는 아님 |
| 역할 구분이 환경변수에 의존 | `FASTPASS_WORKER_ENABLED` 설정이 중요함 |
| 독립적인 Worker image는 아직 없음 | `apps/api`, `apps/worker`, `apps/core` 구조는 아직 적용하지 않음 |

하지만 현재 단계에서는 같은 image를 사용하면서 Kubernetes Deployment와 환경변수로 역할을 분리하는 방식이 충분히 현실적이다.

이 방식은 구조가 단순하고, 하나의 코드베이스와 Docker image를 유지하면서도 운영 단위는 분리할 수 있다는 장점이 있다.

---

## 21. 다음 개선 방향

다음 단계에서는 Worker replica 수를 변경하면서 Queue 소비 성능이 어떻게 변하는지 확인할 수 있다.

예를 들어 다음과 같이 Worker를 2개로 늘린다.

```bash
kubectl scale deployment fastpass-worker -n fastpass --replicas=2
```

그 후 동일한 k6 부하 테스트를 다시 수행한다.

확인할 지표는 다음과 같다.

```text
Worker replica 1개:
  appliedCount
  Queue size
  p95 응답시간
  HTTP 실패율

Worker replica 2개:
  appliedCount
  Queue size
  p95 응답시간
  HTTP 실패율
```

이를 통해 Worker를 독립적으로 확장했을 때 Queue backlog가 감소하는지 검증할 수 있다.

---

## 22. 완료 기준

API/Worker Deployment 분리 단계의 완료 기준은 다음과 같다.

```text
ApplicationQueueWorker에 ConditionalOnProperty 적용
API Deployment에 FASTPASS_WORKER_ENABLED=false 설정
Worker Deployment에 FASTPASS_WORKER_ENABLED=true 설정
fastpass-api Pod와 fastpass-worker Pod가 각각 Running 상태
API Pod에서는 Worker enabled 로그 없음
Worker Pod에서는 Worker enabled 로그 확인
Worker replicas=0 상태에서 신청이 PENDING으로 유지됨
Worker replicas=0 상태에서 Queue size 증가 확인
Worker replicas=1 복구 후 신청이 SUCCESS로 처리됨
Worker replicas=1 복구 후 Queue size 감소 확인
```

위 기준을 만족했으므로 FastPass API와 Worker Deployment 분리를 완료했다.