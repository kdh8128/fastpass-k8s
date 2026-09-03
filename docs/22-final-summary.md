# FastPass Portfolio Summary

## 1. 프로젝트 소개

FastPass는 선착순 이벤트 신청 상황을 가정한 Kubernetes 기반 DevOps/Cloud 포트폴리오 프로젝트입니다.

짧은 시간에 트래픽이 집중되는 상황에서 API 서버, Redis Queue, Worker, PostgreSQL, Kubernetes 배포, HPA 자동 확장, Prometheus/Grafana 모니터링, Alertmanager 알림, Helm, ArgoCD GitOps, GitHub Actions CI/CD, GHCR image 배포까지 연결하여 운영 흐름을 검증했습니다.

또한 k6 부하 테스트와 Prometheus/HikariCP, PostgreSQL 지표를 이용해 성능 병목을 분석하고, 기존 동기 DB 저장 중심의 신청 구조를 Redis-first 비동기 접수 구조로 개선했습니다.

---

## 2. 핵심 시나리오

```text
사용자 신청 요청

→ Redis에서 신청 접수
   - 이벤트 존재 확인
   - 중복 신청 확인
   - applicationId 생성
   - PENDING 상태 저장
   - Queue 적재

→ API가 PENDING 상태로 즉시 응답

→ Worker가 Queue 소비

→ PostgreSQL에서 정원 확인 및 최종 반영

→ SUCCESS 또는 FAILED 처리
```

초기에는 API가 PostgreSQL에 `PENDING` 신청을 먼저 저장한 뒤 Redis Queue에 적재했으나, 부하 테스트에서 DB connection contention이 주요 병목으로 확인되었습니다.

이를 개선하여 신청 hot path에서는 Redis가 순간 트래픽을 먼저 수용하고, Worker가 PostgreSQL 최종 처리를 담당하도록 역할을 분리했습니다.

---

## 3. 전체 구조

```text
Client / Web UI
      ↓
fastpass-api
      ↓
Redis
 ├── PENDING 상태
 └── Application Queue
          ↓
    fastpass-worker
          ↓
      PostgreSQL
```

API와 Worker를 별도 Kubernetes Deployment로 구성하여 요청 접수와 실제 신청 처리를 독립적으로 확장할 수 있도록 구성했습니다.

배포 및 운영 흐름은 다음과 같습니다.

```text
Git Push

→ GitHub Actions

→ Gradle Build

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
Redis 기반 신청 접수
PENDING 응답

fastpass-worker:
Redis Queue 소비
PostgreSQL 최종 반영
SUCCESS / FAILED 처리
```

이를 통해 API 요청 처리와 Queue 처리를 독립적으로 확장할 수 있도록 구성했습니다.

### Redis-first 비동기 처리

기존에는 신청 요청이 PostgreSQL 저장과 commit을 기다린 뒤 Redis Queue에 적재되는 구조였습니다.

성능 분석 후 Redis Lua Script를 이용해 다음 작업을 원자적으로 처리하도록 변경했습니다.

```text
이벤트 존재 확인

→ 중복 신청 확인

→ applicationId 생성

→ PENDING 데이터 저장

→ Redis Queue 적재
```

API는 PostgreSQL transaction을 기다리지 않고 `PENDING`을 반환하며, Worker가 이후 PostgreSQL에 최종 결과를 반영합니다.

### 선착순 정합성 처리

초기 Worker에서는 `SELECT FOR UPDATE`를 사용했으나 PostgreSQL lock contention이 관찰되어 조건부 Atomic UPDATE 방식으로 변경했습니다.

```sql
UPDATE events
SET applied_count = applied_count + 1
WHERE id = ?
  AND applied_count < capacity;
```

이를 통해 동시에 많은 신청이 들어와도 정원을 초과하지 않도록 처리했습니다.

### HPA 자동 확장

API와 Worker에 각각 HPA를 적용했습니다.

k6 부하 테스트를 통해 부하 발생 시 API와 Worker Pod가 scale-out 되고, 부하 종료 후 scale-in 되는 것을 확인했습니다.

초기에는 CPU request가 지나치게 작아 idle 상태에서도 HPA가 불필요하게 확장되는 문제가 있어 resource request와 HPA target을 조정했습니다.

### 모니터링

Prometheus와 Grafana를 구성하여 다음 지표를 관측했습니다.

```text
API request rate

API response time

Pod CPU usage

JVM memory usage

HikariCP DB connection

Redis Queue size

Worker 처리량

Worker 실패 수
```

성능 병목 분석 과정에서는 HikariCP의 active, pending, acquire time, usage time과 PostgreSQL `pg_stat_activity`를 함께 확인했습니다.

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

commit SHA 기반 image tag를 사용하여 실제 배포 버전을 추적할 수 있도록 구성했습니다.

---

## 6. 주요 검증 결과

| 검증 항목 | 결과 |
|---|---|
| API 기능 | 이벤트 생성, 신청, 상태 조회 정상 동작 |
| Redis Queue | Redis-first 접수 후 Worker가 Queue 처리 |
| 선착순 정합성 | 동시 요청에서도 capacity 초과 없음 |
| Worker Scaling | Worker replica 증가 시 Queue backlog 감소 |
| HPA | 부하 발생 시 API/Worker Pod 자동 확장 |
| Prometheus | API/Worker/HikariCP metric 수집 |
| Grafana | Queue, Worker, API, JVM, DB 지표 시각화 |
| Alertmanager | Queue backlog, Worker failure alert 확인 |
| ArgoCD | Synced / Healthy 상태 확인 |
| Self-Heal | ConfigMap 수동 변경 후 Git 상태로 복구 |
| GitHub Actions | Build, image push, tag update 자동화 성공 |
| GHCR | commit SHA 기반 image push 및 배포 성공 |
| Frontend | 브라우저에서 이벤트 생성/신청/조회 가능 |

### 성능 개선

동일한 k6 스크립트와 `300 VU / Capacity 100` 조건으로 Redis-first 적용 전후를 비교했습니다.

| Metric | Redis-first 이전 | Redis-first 이후 |
|---|---:|---:|
| Apply Avg | 7.11s | **1.31s** |
| Apply p95 | 11.21s | **2.20s** |
| Apply Max | 11.41s | **2.35s** |
| HTTP Failure | 0% | **0%** |
| Capacity 초과 | 없음 | **없음** |
| Queue drain | 성공 | **성공** |

```text
Apply p95

11.21s → 2.20s

약 80.4% 감소
```

성능 개선 이후에도 요청 실패 0%와 정확한 정원 배정을 유지했습니다.

### 500 VU Stress Test

추가로 `500 VU / Capacity 100` 조건에서 한계 테스트를 수행했습니다.

```text
Apply Avg  = 4.06s
Apply p95  = 6.41s
HTTP Error = 0%
Capacity   = 정확히 100
```

모든 신청 요청은 정상적으로 접수되고 정원 초과도 발생하지 않았지만, 제한 시간 내 Queue가 완전히 drain되지 않아 Worker 처리량과 Queue backlog가 다음 병목 구간임을 확인했습니다.

---

## 7. 주요 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| `ErrImageNeverPull` | Kubernetes node에 local image 없음 | GHCR image 사용으로 전환 |
| HPA metric `<unknown>` | metrics-server 미설치, CPU request 없음 | metrics-server 설치 및 resource request 추가 |
| Idle 상태 HPA 확장 | CPU request가 지나치게 작음 | CPU request 및 HPA target 조정 |
| Pod 재시작 | livenessProbe가 너무 빨리 실행 | readiness/liveness 분리 및 delay 증가 |
| 높은 Apply p95 | 동기 DB 저장 및 Hikari connection contention | Redis-first 구조로 재설계 |
| DB transaction 지연 | Redis enqueue까지 transaction 내부에서 실행 | DB commit 이후 Redis 처리로 transaction 범위 축소 |
| Event row lock 경합 | `SELECT FOR UPDATE` 사용 | 조건부 Atomic UPDATE로 변경 |
| 불필요한 DB Query | 중복 신청 여부를 별도 SELECT | DB UNIQUE constraint 활용 |
| Queue backlog 증가 | Worker 처리량 부족 | Worker batch 처리 및 scaling 검증 |
| Prometheus metric 중복 | 여러 Pod가 같은 Queue size 노출 | `sum` 대신 `max` 사용 |
| ArgoCD OutOfSync | HPA가 replicas 변경 | `/spec/replicas` ignoreDifferences 적용 |
| `latest` 배포 문제 | Git manifest 변경 없음 | commit SHA tag 기반 배포로 전환 |
| CI/CD loop 가능성 | Actions bot commit이 다시 CI 실행 가능 | `[skip ci]` 적용 |
| PostgreSQL connection 고갈 | HPA 확장 상태에서 Rolling Update가 겹쳐 connection 증가 | replica 안정화 후 배포하도록 운영 절차 정리 |

성능 분석 과정에서 HikariCP connection pool saturation과 PostgreSQL lock contention을 확인하고, 트랜잭션 범위 축소 → Atomic UPDATE → 중복 SELECT 제거 → Redis-first 구조 변경 순으로 개선했습니다.

또한 부하 테스트 후 API/Worker가 각각 6 replicas까지 확장된 상태에서 Rolling Update를 수행하면서 다음 오류가 발생했습니다.

```text
FATAL: sorry, too many clients already
```

이를 통해 HPA 최대 replica, Hikari connection pool, Rolling Update의 동시 Pod 수, PostgreSQL `max_connections`를 함께 고려해야 한다는 점을 확인했습니다.

Hikari pool을 10에서 5로 제한하는 실험도 수행했으나 300 VU에서 p95가 2.20초 대비 3.79~4.34초로 증가하여 해당 변경은 Git revert로 원복했습니다.

---

## 8. 간단 요약

FastPass는 선착순 이벤트 신청 서비스를 예제로, Redis Queue 기반 비동기 처리와 Kubernetes 운영 환경에서의 배포, 확장, 모니터링, 알림, GitOps, CI/CD 흐름을 구현하고 검증한 DevOps/Cloud 포트폴리오 프로젝트입니다.

k6 부하 테스트에서 PostgreSQL connection contention과 transaction lock 경합을 발견하고, 트랜잭션 범위 축소, Atomic UPDATE, 불필요한 Query 제거를 단계적으로 적용했습니다.

이후 신청 hot path에서 PostgreSQL을 제거하는 Redis-first 비동기 접수 구조로 재설계하여 동일한 300 VU 조건에서 Apply API p95를 **11.21초에서 2.20초로 약 80.4% 단축**했으며, 요청 실패 0%와 정원 초과 없는 선착순 정합성을 유지했습니다.

500 VU 스트레스 테스트에서는 모든 요청을 정상 접수하고 정원 정합성을 유지했지만 Queue drain 지연을 확인하여 Worker 처리량을 다음 개선 과제로 도출했습니다.

---

## 9. 향후 개선 방향

```text
Queue length 기반 Worker autoscaling

KEDA 기반 Redis Queue scaling

API / Worker DB connection pool 독립 설정

PgBouncer 기반 DB connection 관리

이벤트 시작 시간 기반 Pre-scaling

Redis Queue retry / DLQ / idempotency 강화

Loki 기반 로그 수집

Trivy image vulnerability scan

ArgoCD Image Updater 적용

장애 대응 Runbook 정리

실제 Cloud Kubernetes(EKS 등) 환경에서 부하 재검증

LLM 기반 로그 분석 및 장애 원인 요약 실험
```
