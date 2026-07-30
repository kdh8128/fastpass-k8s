# 로컬 Kubernetes 기반 FastPass 배포 검증

## 1. 구현 목적

FastPass는 Docker Compose 환경에서 API, PostgreSQL, Redis를 컨테이너로 실행하는 단계까지 검증했다.

이번 단계에서는 동일한 구성을 로컬 Kubernetes 환경에 배포하여, Kubernetes 기본 리소스를 이용한 애플리케이션 실행 구조를 검증했다.

검증 목표는 다음과 같다.

```text
Docker Compose 기반 실행
  → Kubernetes 기반 실행으로 확장

api container
postgres container
redis container

  ↓

fastpass-api Deployment
postgres Deployment
redis Deployment
Service
Secret
ConfigMap
PVC
```

---

## 2. Kubernetes 환경

| 항목 | 내용 |
|---|---|
| Kubernetes 환경 | Docker Desktop Kubernetes |
| kubectl Client Version | v1.36.1 |
| Context | docker-desktop |
| Node | desktop-control-plane |
| Namespace | fastpass |

클러스터 확인 명령어:

```bash
kubectl config current-context
kubectl get nodes
```

확인 결과:

```text
docker-desktop

NAME                    STATUS   ROLES           VERSION
desktop-control-plane   Ready    control-plane   v1.36.1
```

---

## 3. Kubernetes 리소스 구성

이번 단계에서 작성한 manifest 파일은 다음과 같다.

```text
deploy/k8s/
├── namespace.yaml
├── postgres.yaml
├── redis.yaml
├── api-configmap.yaml
├── api-deployment.yaml
└── api-service.yaml
```

각 파일의 역할은 다음과 같다.

| 파일 | 역할 |
|---|---|
| namespace.yaml | FastPass 전용 namespace 생성 |
| postgres.yaml | PostgreSQL Secret, PVC, Deployment, Service 정의 |
| redis.yaml | Redis Deployment, Service 정의 |
| api-configmap.yaml | API 실행 profile 설정 |
| api-deployment.yaml | Spring Boot API Deployment 정의 |
| api-service.yaml | API Service 정의 |

---

## 4. 전체 배포 구조

```text
Client
  → kubectl port-forward
  → fastpass-api Service
  → fastpass-api Pod
      → postgres Service
          → postgres Pod
      → redis Service
          → redis Pod
```

Kubernetes 내부에서는 API가 PostgreSQL과 Redis에 직접 `localhost`로 접근하지 않는다.

Docker Compose와 마찬가지로 Kubernetes Service 이름을 통해 접근한다.

| 대상 | 접근 주소 |
|---|---|
| PostgreSQL | postgres:5432 |
| Redis | redis:6379 |

이를 위해 Spring Boot의 `docker` profile을 사용했다.

---

## 5. API 이미지 준비

Docker Compose 단계에서 생성한 API 이미지를 Kubernetes에서 사용했다.

확인 명령어:

```bash
docker images
```

확인된 이미지:

```text
fastpass-k8s-api:latest
```

로컬 Kubernetes 노드에서 해당 이미지를 사용하기 위해 Deployment에는 다음 설정을 적용했다.

```yaml
image: fastpass-k8s-api:latest
imagePullPolicy: Never
```

`imagePullPolicy: Never`는 외부 registry에서 이미지를 pull하지 않고, Kubernetes 노드 내부에 존재하는 이미지를 사용하라는 의미이다.

Docker Desktop Kubernetes 환경에서 이미지가 노드 내부에 없을 경우 `ErrImageNeverPull`이 발생할 수 있다.

이 경우 다음 명령어로 로컬 Docker 이미지를 Kubernetes 노드 내부 이미지 저장소로 import했다.

```bash
docker save fastpass-k8s-api:latest | docker exec -i desktop-control-plane ctr -n k8s.io images import -
```

---

## 6. Kubernetes 배포

배포 명령어:

```bash
cd /d/coding/project/fastpass-k8s

kubectl apply -f deploy/k8s/
```

배포 대상 namespace:

```text
fastpass
```

---

## 7. Pod 상태 검증

Pod 상태 확인:

```bash
kubectl get pods -n fastpass
```

확인 결과:

```text
NAME                            READY   STATUS    RESTARTS
fastpass-api-6d55775598-whg76   1/1     Running   0
postgres-85d56865bf-rkm7s       1/1     Running   0
redis-b67748d94-jtp8r           1/1     Running   0
```

이를 통해 API, PostgreSQL, Redis가 모두 Kubernetes Pod로 정상 실행됨을 확인했다.

---

## 8. Service 및 리소스 확인

전체 리소스 확인 명령어:

```bash
kubectl get all -n fastpass
kubectl get pvc -n fastpass
```

이번 단계에서 확인해야 하는 핵심 리소스는 다음과 같다.

```text
deployment/fastpass-api
deployment/postgres
deployment/redis

service/fastpass-api
service/postgres
service/redis

persistentvolumeclaim/postgres-pvc
secret/postgres-secret
configmap/fastpass-api-config
```

---

## 9. API 로그 확인

API 로그 확인 명령어:

```bash
kubectl logs -n fastpass deployment/fastpass-api
```

정상 실행 시 확인할 수 있는 주요 로그는 다음과 같다.

```text
The following 1 profile is active: "docker"
Tomcat started on port 8080
Started ApiApplication
```

이를 통해 Kubernetes 환경에서도 Spring Boot API가 `docker` profile로 실행됨을 확인했다.

---

## 10. 로컬 접근 방식: Port Forward

Kubernetes 내부 Service는 기본적으로 로컬 PC의 `localhost`에서 바로 접근할 수 없다.

따라서 다음 명령어로 로컬 포트와 Kubernetes Service 포트를 연결했다.

```bash
kubectl port-forward -n fastpass service/fastpass-api 18080:8080
```

포트포워딩 구조는 다음과 같다.

```text
localhost:18080
  → fastpass-api Service:8080
  → fastpass-api Pod:8080
```

이 터미널은 포트포워딩이 유지되는 동안 계속 실행 상태로 두어야 한다.

---

## 11. Health Check 검증

요청:

```bash
curl http://localhost:18080/actuator/health
```

응답:

```json
{
  "status": "UP",
  "groups": [
    "liveness",
    "readiness"
  ]
}
```

이를 통해 Kubernetes 환경에서 실행 중인 API가 정상적으로 요청을 받을 수 있음을 확인했다.

---

## 12. 이벤트 생성 검증

요청:

```bash
curl -X POST http://localhost:18080/api/events \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"title\":\"FastPass K8s Event\",\"description\":\"kubernetes local test\",\"capacity\":3,\"eventStartAt\":\"2026-07-20T10:00:00\"}"
```

응답:

```json
{
  "id": 1,
  "title": "FastPass K8s Event",
  "description": "kubernetes local test",
  "capacity": 3,
  "appliedCount": 0,
  "eventStartAt": "2026-07-20T10:00:00",
  "createdAt": "2026-07-23T14:14:02.500096627"
}
```

이를 통해 API Pod가 PostgreSQL Service 및 Pod에 정상적으로 연결되어 이벤트 데이터를 저장할 수 있음을 확인했다.

---

## 13. 신청 요청 검증

요청:

```bash
curl -X POST http://localhost:18080/api/events/1/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"k8s-user1\"}"
```

응답:

```json
{
  "applicationId": 1,
  "eventId": 1,
  "applicantName": "k8s-user1",
  "status": "PENDING",
  "createdAt": "2026-07-23T14:14:15.288709653"
}
```

신청 직후 `PENDING` 상태가 반환되는 것을 확인했다.

이는 API가 신청 요청을 즉시 최종 처리하지 않고, 신청 데이터를 저장한 뒤 Redis Queue에 적재하는 구조가 Kubernetes 환경에서도 동작한다는 의미이다.

---

## 14. Worker 처리 결과 검증

신청 결과 조회:

```bash
curl http://localhost:18080/api/applications/1
```

응답:

```json
{
  "applicationId": 1,
  "eventId": 1,
  "applicantName": "k8s-user1",
  "status": "SUCCESS",
  "createdAt": "2026-07-23T14:14:15.28871"
}
```

신청 직후에는 `PENDING`이었지만, 잠시 후 조회 시 `SUCCESS`로 변경되었다.

이를 통해 API Pod 내부에서 실행 중인 Worker가 Redis Queue에서 신청 ID를 소비하고, PostgreSQL의 신청 상태를 정상적으로 갱신했음을 확인했다.

---

## 15. Queue 크기 검증

요청:

```bash
curl http://localhost:18080/api/queue/applications/size
```

응답:

```json
{
  "size": 0
}
```

이를 통해 Worker가 Redis Queue의 신청 건을 정상적으로 처리했으며, 처리 후 Queue가 비어 있음을 확인했다.

---

## 16. 검증된 처리 흐름

이번 Kubernetes 배포에서 검증된 전체 처리 흐름은 다음과 같다.

```text
Client
  → localhost:18080
  → kubectl port-forward
  → fastpass-api Service
  → fastpass-api Pod
      → POST /api/events
          → postgres Service
          → postgres Pod
          → 이벤트 저장

      → POST /api/events/1/apply
          → 신청 데이터 PENDING 저장
          → redis Service
          → redis Pod
          → applicationId Queue 적재

      → Worker
          → Redis Queue 소비
          → PostgreSQL에서 Event 조회
          → 정원 확인
          → Application 상태 SUCCESS/FAILED 갱신
```

---

## 17. Kubernetes 리소스 사용 의미

이번 단계에서 사용한 Kubernetes 리소스의 의미는 다음과 같다.

| 리소스 | 적용 대상 | 의미 |
|---|---|---|
| Namespace | fastpass | FastPass 관련 리소스 격리 |
| Deployment | api, postgres, redis | Pod 생성 및 재시작 관리 |
| Service | api, postgres, redis | Pod 접근을 위한 고정 네트워크 엔드포인트 제공 |
| Secret | postgres | DB 계정 및 비밀번호 관리 |
| ConfigMap | api | API 실행 profile 관리 |
| PVC | postgres | PostgreSQL 데이터 저장 공간 유지 |
| Readiness Probe | api, postgres, redis | 요청을 받을 준비가 되었는지 확인 |
| Liveness Probe | api | 애플리케이션 생존 상태 확인 |

---

## 18. 현재 단계의 한계

현재 Kubernetes 구성은 로컬 개발 및 검증 목적의 최소 배포 구조이다.

한계는 다음과 같다.

| 한계 | 설명 |
|---|---|
| API와 Worker 미분리 | 현재 Worker는 API 애플리케이션 내부 스케줄러로 동작 |
| 단일 API replica | 현재 fastpass-api replica는 1개 |
| 단일 Redis Pod | Redis 고가용성 구성은 아직 없음 |
| 단일 PostgreSQL Pod | 운영용 DB 고가용성 구성은 아직 없음 |
| 외부 노출 방식 제한 | 현재는 port-forward로만 로컬 접근 |
| 이미지 배포 자동화 없음 | 로컬 이미지를 수동으로 Kubernetes 노드에 import |
| 리소스 제한 미설정 | CPU, Memory requests/limits가 아직 없음 |
| 오토스케일링 미적용 | HPA가 아직 적용되지 않음 |

---

## 19. 다음 개선 방향

다음 단계에서는 다음 항목을 개선할 수 있다.

| 개선 항목 | 설명 |
|---|---|
| k6 부하 테스트 | 동시 신청 요청을 발생시켜 Queue 기반 처리 구조 검증 |
| API/Worker 분리 | API Pod와 Worker Pod를 별도 Deployment로 분리 |
| Resource requests/limits | Pod별 CPU, Memory 요청량 및 제한 설정 |
| HPA 적용 | API 또는 Worker에 Horizontal Pod Autoscaler 적용 |
| Ingress 적용 | port-forward 대신 Ingress를 통한 접근 구성 |
| Helm Chart 작성 | Kubernetes manifest를 Helm 기반으로 템플릿화 |
| 모니터링 추가 | Prometheus, Grafana 기반 지표 수집 |
| 로그 수집 추가 | Loki 또는 EFK 기반 로그 수집 |
| GitOps 적용 | Argo CD 기반 배포 자동화 |

---

## 20. 완료 기준

이번 단계의 완료 기준은 다음과 같다.

```text
Docker Desktop Kubernetes context 설정 완료
fastpass namespace 생성 완료
PostgreSQL Pod Running
Redis Pod Running
fastpass-api Pod Running
fastpass-api Service 생성 완료
PostgreSQL Service 생성 완료
Redis Service 생성 완료
port-forward를 통한 API 접근 성공
/actuator/health 응답 성공
이벤트 생성 API 성공
신청 API PENDING 응답 확인
Worker 처리 후 SUCCESS 변경 확인
Queue size 0 확인
```

위 기준을 모두 만족했으므로 로컬 Kubernetes 기반 FastPass 배포 검증을 완료했다.