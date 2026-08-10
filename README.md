# FastPass

FastPass는 선착순 이벤트 신청 상황을 가정한 Kubernetes 기반 서비스 운영 자동화 프로젝트입니다.

트래픽이 짧은 시간에 집중되는 상황에서 API 서버, Redis Queue, Worker, Kubernetes 배포, 부하 테스트, 오토스케일링, 모니터링 구조를 단계적으로 검증하는 것을 목표로 합니다.

---

## Project Goal

- Redis Queue 기반 비동기 신청 처리
- API 서버와 Worker 역할 분리
- Kubernetes 기반 배포
- k6 기반 부하 테스트
- HPA 기반 자동 확장 검증
- 모니터링, 알림, GitOps, EKS 환경으로 확장

---

## Core Scenario

1. 사용자가 이벤트에 신청한다.
2. API 서버는 신청 요청을 `PENDING` 상태로 저장한다.
3. 신청 ID는 Redis Queue에 적재된다.
4. Worker가 Queue를 소비하며 신청을 처리한다.
5. 정원이 남아 있으면 `SUCCESS`, 초과되면 `FAILED`로 상태를 변경한다.
6. 트래픽이 증가하면 Kubernetes HPA가 API/Worker Pod를 자동 확장한다.

---

## Architecture

```text
Client
  -> fastpass-api
  -> PostgreSQL

fastpass-api
  -> Redis Queue
  -> fastpass-worker
  -> PostgreSQL
```

API와 Worker는 동일한 Spring Boot 이미지를 사용하지만, Kubernetes Deployment를 분리하여 독립적으로 확장할 수 있도록 구성했습니다.

| Deployment | Role |
|---|---|
| fastpass-api | HTTP API 요청 처리 |
| fastpass-worker | Redis Queue 처리 |

---

## Tech Stack

### Implemented

- Java 21
- Spring Boot
- PostgreSQL
- Redis
- Docker
- Docker Compose
- Kubernetes
- metrics-server
- HPA
- k6

### Planned

- Prometheus
- Grafana
- Loki
- Alertmanager
- Helm
- ArgoCD
- GitHub Actions
- AWS EKS
- Terraform

---

## Features

- 이벤트 생성/조회 API
- 이벤트 신청 API
- 신청 상태 조회 API
- 중복 신청 방지
- 정원 초과 처리
- Redis Queue 기반 비동기 처리
- API/Worker Deployment 분리
- Worker batch processing
- k6 부하 테스트
- HPA 기반 자동 확장 검증

---

## API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/events` | 이벤트 생성 |
| GET | `/api/events` | 이벤트 목록 조회 |
| GET | `/api/events/{eventId}` | 이벤트 단건 조회 |
| POST | `/api/events/{eventId}/apply` | 이벤트 신청 |
| GET | `/api/applications/{applicationId}` | 신청 상태 조회 |
| GET | `/api/queue/applications/size` | Redis Queue 크기 조회 |

---

## Validation Summary

### Queue Processing

신청 요청을 Redis Queue에 적재하고, Worker가 비동기적으로 처리하는 구조를 구현했습니다.

이를 통해 API 서버는 요청 수신에 집중하고, Worker는 Queue 처리에 집중하도록 역할을 분리했습니다.

### Worker Scaling

API와 Worker를 별도 Deployment로 분리한 뒤, Worker replica 증가에 따른 Queue 처리 성능 개선을 검증했습니다.

Worker replica를 늘렸을 때 Queue backlog가 감소하는 것을 확인했습니다.

### HPA Autoscaling

API와 Worker에 Kubernetes HPA를 적용하고, k6 부하 테스트를 통해 자동 확장 동작을 검증했습니다.

부하 발생 시 API와 Worker가 각각 1 replica에서 최대 3 replicas까지 scale-out 되는 것을 확인했습니다.

다만 테스트 종료 시점에 Queue backlog가 남아 있어, Worker autoscaling은 향후 CPU 기반이 아니라 Queue length 기반으로 개선할 필요가 있음을 확인했습니다.

---

## Documentation

세부 구현 및 검증 과정은 `docs/` 디렉터리에 정리했습니다.

| Document | Description |
|---|---|
| `docs/00-project-overview.md` | 프로젝트 개요 |
| `docs/01-api-mvp.md` | API MVP 구현 |
| `docs/02-redis-queue.md` | Redis Queue 처리 구조 |
| `docs/03-docker-compose.md` | Docker Compose 검증 |
| `docs/04-kubernetes-local.md` | Kubernetes 로컬 배포 |
| `docs/05-load-test.md` | k6 부하 테스트 |
| `docs/06-worker-batch-improvement.md` | Worker batch 처리 개선 |
| `docs/07-api-worker-split.md` | API/Worker 분리 |
| `docs/08-worker-scaling-test.md` | Worker scaling 검증 |
| `docs/09-hpa-autoscaling.md` | HPA 자동 확장 검증 |

---

## Next Steps

- Prometheus/Grafana 기반 모니터링
- Loki 기반 로그 수집
- Alertmanager 기반 장애 알림
- Queue backlog 기반 alerting
- KEDA 또는 Prometheus metric 기반 Worker autoscaling
- Helm/ArgoCD 기반 GitOps 배포
- GitHub Actions 기반 CI/CD
- AWS EKS 및 Terraform 확장

---

## Project Direction

FastPass는 단순 API 구현 프로젝트가 아니라, Kubernetes 환경에서 트래픽 급증, Queue 적체, Pod 확장, 장애 탐지, 배포 롤백 같은 운영 시나리오를 검증하는 DevOps/Cloud 포트폴리오 프로젝트입니다.

향후 Observability, GitOps, EKS, Terraform, 장애 대응 Runbook, LLM 기반 로그 분석 및 운영 자동화 방향으로 확장할 수 있습니다.

---

## License

This project is licensed under the MIT License.