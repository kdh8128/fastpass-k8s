# FastPass Portfolio Summary

## 1. 프로젝트 소개

FastPass는 선착순 이벤트 신청 상황을 가정한 Kubernetes 기반 DevOps/Cloud 포트폴리오 프로젝트입니다.

짧은 시간에 트래픽이 집중되는 상황에서 API 서버, Redis Queue, Worker, Kubernetes 배포, HPA 자동 확장, Prometheus/Grafana 모니터링, Alertmanager 알림, Helm, ArgoCD GitOps, GitHub Actions CI/CD, GHCR image 배포까지 연결하여 운영 흐름을 검증했습니다.

---

## 2. 핵심 시나리오

```text
사용자 신청 요청
→ API가 신청을 PENDING 상태로 저장
→ Redis Queue에 applicationId 적재
→ Worker가 Queue 소비
→ 정원 확인 후 SUCCESS 또는 FAILED 처리
```

이 구조를 통해 API 서버는 요청 수신에 집중하고, Worker는 실제 신청 처리에 집중하도록 역할을 분리했습니다.

---

## 3. 전체 구조

```text
Client / Web UI
  ↓
fastpass-api
  ├── PostgreSQL
  └── Redis Queue
          ↓
    fastpass-worker
          ↓
      PostgreSQL
```

배포 및 운영 흐름은 다음과 같습니다.

```text
Git Push
→ GitHub Actions
→ Docker Image Build
→ GHCR Push
→ Helm values image tag 자동 업데이트
→ ArgoCD Sync
→ Kubernetes Deployment
→ Prometheus/Grafana Monitoring
→ Alertmanager Alerting
```

---

## 4. 주요 기술 스택

| Area | Stack |
|---|---|
| Backend | Java 21, Spring Boot, JPA, Actuator |
| Database | PostgreSQL |
| Queue | Redis |
| Container | Docker, Docker Compose, GHCR |
| Kubernetes | Deployment, Service, ConfigMap, Secret, PVC, HPA |
| Load Test | k6 |
| Monitoring | Prometheus, Grafana, Micrometer |
| Alerting | PrometheusRule, Alertmanager |
| GitOps | Helm, ArgoCD |
| CI/CD | GitHub Actions |

---

## 5. 주요 구현 내용

### API / Worker 분리

API와 Worker를 별도 Kubernetes Deployment로 분리했습니다.

```text
fastpass-api:
HTTP 요청 처리

fastpass-worker:
Redis Queue 소비 및 신청 처리
```

이를 통해 API 요청 처리와 Queue 처리를 독립적으로 확장할 수 있도록 구성했습니다.

### Redis Queue 기반 비동기 처리

신청 요청을 즉시 처리하지 않고 `PENDING` 상태로 저장한 뒤 Redis Queue에 적재했습니다.

Worker가 Queue를 소비하며 정원에 따라 신청 상태를 `SUCCESS` 또는 `FAILED`로 변경합니다.

### HPA 자동 확장

API와 Worker에 각각 HPA를 적용했습니다.

k6 부하 테스트를 통해 부하 발생 시 API와 Worker Pod가 scale-out 되는 것을 확인했습니다.

### 모니터링

Prometheus와 Grafana를 구성하여 다음 지표를 관측했습니다.

```text
API request rate
API response time
Pod CPU usage
JVM memory usage
DB connection
Redis Queue size
Worker 처리량
Worker 실패 수
```

### Custom Metrics

FastPass 운영 관측을 위해 custom metric을 추가했습니다.

| Metric | Description |
|---|---|
| `fastpass_queue_size` | Redis Queue 크기 |
| `fastpass_worker_processed_total` | Worker 처리 수 |
| `fastpass_worker_processing_failed_total` | Worker 처리 실패 수 |

### Alerting

Queue backlog와 Worker 처리 실패 상황을 PrometheusRule과 Alertmanager로 감지하도록 구성했습니다.

### GitOps / CI/CD

Helm Chart로 Kubernetes 리소스를 관리하고, ArgoCD를 통해 Git 기준 배포 상태를 유지했습니다.

GitHub Actions는 다음 작업을 자동화합니다.

```text
Gradle build
Docker image build
GHCR push
Helm image tag 자동 수정
Git commit
ArgoCD 자동 배포
```

---

## 6. 주요 검증 결과

| 검증 항목 | 결과 |
|---|---|
| API 기능 | 이벤트 생성, 신청, 상태 조회 정상 동작 |
| Redis Queue | 신청 요청이 Queue에 적재되고 Worker가 처리 |
| Worker Scaling | Worker replica 증가 시 Queue backlog 감소 |
| HPA | 부하 발생 시 API/Worker Pod 자동 확장 |
| Prometheus | API/Worker metric 수집 |
| Grafana | Queue, Worker, API, JVM, DB 지표 시각화 |
| Alertmanager | Queue backlog, Worker failure alert 확인 |
| ArgoCD | Synced / Healthy 상태 확인 |
| Self-Heal | ConfigMap 수동 변경 후 Git 상태로 복구 |
| GitHub Actions | Build, image push, tag update 자동화 성공 |
| GHCR | commit SHA 기반 image push 및 배포 성공 |
| Frontend | 브라우저에서 이벤트 생성/신청/조회 가능 |

---

## 7. 주요 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| `ErrImageNeverPull` | Kubernetes node에 local image 없음 | GHCR image 사용으로 전환 |
| HPA metric `<unknown>` | metrics-server 미설치, CPU request 없음 | metrics-server 설치 및 resource request 추가 |
| Pod 재시작 | livenessProbe가 너무 빨리 실행 | readiness/liveness 분리 및 delay 증가 |
| Queue backlog 증가 | Worker 처리량 부족 | Worker batch 처리 및 scaling 검증 |
| Prometheus metric 중복 | 여러 Pod가 같은 Queue size 노출 | `sum` 대신 `max` 사용 |
| ArgoCD OutOfSync | HPA가 replicas 변경 | `/spec/replicas` ignoreDifferences 적용 |
| `latest` 배포 문제 | Git manifest 변경 없음 | commit SHA tag 기반 배포로 전환 |
| CI/CD loop 가능성 | Actions bot commit이 다시 CI 실행 가능 | `[skip ci]` 적용 |

---

## 8. 프로젝트를 통해 보여줄 수 있는 역량

이 프로젝트를 통해 다음 역량을 보여줄 수 있습니다.

```text
Spring Boot API 설계
Redis Queue 기반 비동기 처리
Kubernetes 배포 구조 설계
API/Worker 분리와 독립 확장
HPA 기반 autoscaling 검증
Prometheus/Grafana 기반 observability 구성
Alertmanager 기반 장애 알림 구성
Helm Chart 작성
ArgoCD GitOps 배포
GitHub Actions CI/CD 구성
GHCR 기반 container image 배포
운영 문제 트러블슈팅
```

---

## 9. 한 줄 요약

FastPass는 선착순 이벤트 신청 서비스를 예제로, Redis Queue 기반 비동기 처리와 Kubernetes 운영 환경에서의 배포, 확장, 모니터링, 알림, GitOps, CI/CD 흐름을 검증한 DevOps/Cloud 포트폴리오 프로젝트입니다.

---

## 10. 이력서용 문장

```text
선착순 이벤트 신청 서비스를 예제로 Spring Boot API와 Redis Queue Worker를 구현하고, Kubernetes에서 API/Worker를 분리 배포하여 HPA 자동 확장, Prometheus/Grafana 모니터링, Alertmanager 알림, Helm, ArgoCD GitOps, GitHub Actions CI/CD, GHCR image 배포까지 구성한 DevOps/Cloud 프로젝트를 수행했습니다.
```

---

## 11. 향후 개선 방향

```text
Queue length 기반 Worker autoscaling
KEDA 기반 Redis Queue scaling
Loki 기반 로그 수집
Trivy image vulnerability scan
ArgoCD Image Updater 적용
장애 대응 Runbook 정리
LLM 기반 로그 분석 및 장애 원인 요약 실험
```