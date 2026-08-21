# FastPass Troubleshooting Index

## 1. 목적

이 문서는 FastPass 프로젝트를 진행하면서 발생한 주요 문제와 해결 과정을 한 곳에 정리한 Troubleshooting Index이다.

FastPass는 단순히 기능 구현만 한 프로젝트가 아니라, Kubernetes 배포, 부하 테스트, HPA, Prometheus/Grafana, Alertmanager, Helm, ArgoCD, GitHub Actions, GHCR까지 단계적으로 확장한 DevOps/Cloud 운영 검증 프로젝트이다.

따라서 각 단계에서 실제로 발생한 문제와 해결 과정을 문서화하는 것은 프로젝트의 중요한 일부이다.

이 문서의 목적은 다음과 같다.

```text
1. 프로젝트 진행 중 발생한 문제를 한눈에 확인한다.
2. 각 문제의 원인과 해결 방법을 정리한다.
3. 운영 환경에서 발생할 수 있는 장애 대응 사고 흐름을 보여준다.
4. 면접 또는 포트폴리오 설명 시 트러블슈팅 경험을 구조적으로 설명할 수 있도록 한다.
```

---

## 2. Troubleshooting Summary

| No | Issue | Area | Cause | Resolution |
|---|---|---|---|---|
| 1 | PostgreSQL enum/check constraint 문제 | Spring Boot / DB | 기존 schema가 새로운 enum 값을 반영하지 못함 | volume 초기화 후 재생성 |
| 2 | Docker command not found | Docker | Docker Desktop 미설치 | Docker Desktop 설치 |
| 3 | Kubernetes `ErrImageNeverPull` | Kubernetes | node에 local image가 없음 | image import 또는 GHCR image 사용 |
| 4 | HPA metric `<unknown>` | Kubernetes HPA | metrics-server 미설치 또는 CPU request 없음 | metrics-server 설치, resource request 추가 |
| 5 | Worker 처리량 부족 | Queue / Worker | Worker가 applicationId를 1개씩 처리 | batch processing 도입 |
| 6 | Queue backlog 증가 | Load Test / Worker | 요청량 대비 Worker 처리량 부족 | Worker replica 증가 및 HPA 검증 |
| 7 | Spring Boot Pod 재시작 | Kubernetes Probe | livenessProbe가 너무 빨리 실행됨 | readiness/liveness endpoint 분리 및 delay 증가 |
| 8 | Prometheus target 미수집 | Monitoring | ServiceMonitor 대상 Service label/port 문제 | API/Worker Service 및 ServiceMonitor 구성 |
| 9 | Custom metric 중복 해석 | Monitoring | API/Worker 여러 Pod가 같은 Redis queue size 노출 | `sum` 대신 `max` 사용 |
| 10 | Worker failure metric 증가 | Worker / Alerting | Redis Queue에 DB에 없는 applicationId 존재 | failure metric 및 alert로 감지 |
| 11 | ArgoCD Degraded | ArgoCD / Kubernetes | probe 실패로 Pod Ready 지연 | probe 설정 수정 후 Sync |
| 12 | ArgoCD OutOfSync | ArgoCD / HPA | HPA가 Deployment replicas 변경 | `/spec/replicas` ignoreDifferences 적용 |
| 13 | rollout restart annotation drift | ArgoCD | `kubectl rollout restart`가 live annotation 추가 | annotation 제거 또는 GitOps 방식 사용 |
| 14 | GitHub Actions warning | CI | deprecated action version 사용 | action version 업데이트 |
| 15 | Docker image가 runner 안에서만 사라짐 | CI/CD | Docker build만 하고 registry push 없음 | GHCR push 추가 |
| 16 | Helm이 local image를 계속 사용 | Helm / ArgoCD | image repository가 local 기준 | GHCR image로 변경 |

---

## 3. Issue 1: PostgreSQL enum/check constraint 문제

### 문제 상황

Redis Queue 기반 처리 구조를 도입하면서 신청 상태에 `PENDING` 값을 추가하였다.

하지만 기존 PostgreSQL schema에는 이전 enum/check constraint가 남아 있었고, 새로운 `PENDING` 상태 저장 시 오류가 발생하였다.

API 요청 결과는 다음과 같이 나타났다.

```text
409 Conflict
Duplicated or invalid data.
```

### 원인

Spring Boot JPA의 `ddl-auto: update`는 entity 변경 사항을 일부 반영할 수 있지만, 기존 enum/check constraint를 항상 안전하게 수정하지는 않는다.

기존 schema에는 `SUCCESS`, `FAILED` 중심의 constraint가 남아 있었고, 새로 추가된 `PENDING` 값이 허용되지 않았다.

즉, 애플리케이션 코드는 변경되었지만 DB schema가 완전히 따라오지 못한 상태였다.

### 해결

로컬 개발 환경에서는 기존 volume을 삭제하고 DB를 새로 생성하였다.

```bash
docker compose down -v
docker compose up -d
```

이후 Spring Boot 애플리케이션을 다시 실행하여 schema를 새로 생성하였다.

```bash
cd apps/api
./gradlew.bat bootRun --args='--spring.profiles.active=local'
```

### 정리

개발 환경에서는 volume 초기화로 해결할 수 있지만, 운영 환경에서는 기존 데이터를 삭제할 수 없다.

운영 환경에서는 Flyway 또는 Liquibase 같은 migration 도구를 사용하여 schema 변경을 명시적으로 관리하는 것이 적절하다.

---

## 4. Issue 2: Docker command not found

### 문제 상황

Docker Compose 환경을 구성하려고 했지만 다음과 같은 오류가 발생하였다.

```text
docker: command not found
```

### 원인

Windows 환경에 Docker Desktop이 설치되어 있지 않았거나, Docker 명령어가 PATH에 등록되지 않은 상태였다.

### 해결

Docker Desktop을 설치하고, 터미널을 다시 실행한 뒤 Docker 명령어를 확인하였다.

```bash
docker version
docker compose version
```

Docker Desktop 설치 후 Docker Compose 기반으로 PostgreSQL, Redis, API를 실행할 수 있었다.

```bash
docker compose up -d
```

### 정리

Docker 기반 프로젝트에서는 Docker Desktop 설치 여부와 Docker daemon 실행 상태를 먼저 확인해야 한다.

---

## 5. Issue 3: Kubernetes ErrImageNeverPull

### 문제 상황

로컬 Kubernetes에 FastPass API Deployment를 배포했을 때 Pod가 정상 실행되지 않고 다음 상태가 발생하였다.

```text
ErrImageNeverPull
```

### 원인

Deployment image 설정은 다음과 같았다.

```yaml
image: fastpass-k8s-api:latest
imagePullPolicy: Never
```

`imagePullPolicy: Never`는 Kubernetes가 외부 registry에서 image를 pull하지 않고, node 내부에 존재하는 image만 사용하도록 한다.

하지만 Docker Desktop Kubernetes node에는 `fastpass-k8s-api:latest` image가 존재하지 않았다.

### 해결

초기에는 로컬 image를 Docker Desktop Kubernetes node로 import하여 해결하였다.

```bash
docker save fastpass-k8s-api:latest | docker exec -i desktop-control-plane ctr -n k8s.io images import -
```

이후 GHCR을 도입한 뒤에는 Helm Chart image 설정을 다음과 같이 변경하였다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: latest
  pullPolicy: IfNotPresent
```

### 정리

`imagePullPolicy: Never`는 로컬 Kubernetes 실습에서는 사용할 수 있지만, 일반적인 Kubernetes 배포 구조에는 적합하지 않다.

Container registry를 사용하면 image를 수동 import하지 않아도 Kubernetes가 registry에서 image를 pull할 수 있다.

---

## 6. Issue 4: HPA metric `<unknown>`

### 문제 상황

HPA를 생성한 뒤 다음과 같이 CPU metric이 `<unknown>`으로 표시되었다.

```text
TARGETS
cpu: <unknown>/50%
```

### 원인

주요 원인은 두 가지였다.

```text
1. metrics-server가 설치되어 있지 않음
2. Deployment에 CPU request가 설정되어 있지 않음
```

HPA의 CPU utilization은 container CPU request를 기준으로 계산된다.

따라서 CPU request가 없으면 CPU 사용률을 계산할 수 없다.

### 해결

먼저 metrics-server를 설치하였다.

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

Docker Desktop Kubernetes 환경에서는 TLS 문제를 피하기 위해 다음 옵션을 추가하였다.

```bash
kubectl patch deployment metrics-server -n kube-system \
  --type='json' \
  -p='[
    {
      "op": "add",
      "path": "/spec/template/spec/containers/0/args/-",
      "value": "--kubelet-insecure-tls"
    }
  ]'
```

metrics-server를 재시작하였다.

```bash
kubectl rollout restart deployment metrics-server -n kube-system
kubectl rollout status deployment metrics-server -n kube-system
```

그다음 API와 Worker Deployment에 CPU request를 추가하였다.

```yaml
resources:
  requests:
    cpu: 10m
    memory: 256Mi
  limits:
    cpu: 500m
    memory: 768Mi
```

### 정리

HPA를 사용하려면 metrics-server와 resource request 설정이 필요하다.

CPU 기반 HPA는 단순히 Pod CPU 사용량만 보는 것이 아니라, request 대비 사용률을 계산한다.

---

## 7. Issue 5: Worker 처리량 부족

### 문제 상황

k6 부하 테스트를 수행했을 때 API 요청은 정상적으로 처리되었지만, Redis Queue backlog가 크게 증가하였다.

초기 테스트에서 Queue size가 많이 남았고, Event의 appliedCount도 요청 수에 비해 천천히 증가하였다.

### 원인

초기 Worker는 Queue에서 applicationId를 한 번에 하나씩 꺼내 처리하였다.

```text
Worker 1회 실행
→ applicationId 1개 dequeue
→ application 1개 처리
```

요청량이 증가하면 API는 빠르게 Queue에 신청을 적재하지만, Worker 처리 속도가 이를 따라가지 못해 backlog가 증가하였다.

### 해결

Worker가 한 번에 여러 applicationId를 가져와 처리하도록 batch processing을 도입하였다.

```java
private static final int BATCH_SIZE = 50;
```

Queue service에는 batch dequeue 메서드를 추가하였다.

```java
public List<Long> dequeueBatch(int batchSize) {
    List<Long> applicationIds = new ArrayList<>();

    for (int i = 0; i < batchSize; i++) {
        Long applicationId = dequeue();

        if (applicationId == null) {
            break;
        }

        applicationIds.add(applicationId);
    }

    return applicationIds;
}
```

### 정리

Queue 기반 비동기 처리에서는 API 처리량과 Worker 처리량의 균형이 중요하다.

Worker batch processing은 Queue backlog를 줄이는 가장 기본적인 개선 방식이다.

---

## 8. Issue 6: Queue backlog 증가

### 문제 상황

부하 테스트 중 Queue backlog가 계속 증가하였다.

```text
Final queue size: large number
```

### 원인

API 서버는 요청을 빠르게 받아 Redis Queue에 적재했지만, Worker 처리량이 요청 유입량보다 낮았다.

즉, 시스템 병목은 API가 아니라 Worker 처리량이었다.

### 해결

API와 Worker를 별도 Deployment로 분리한 뒤, Worker replica를 증가시켜 처리량을 비교하였다.

```bash
kubectl scale deployment fastpass-worker -n fastpass --replicas=2
```

Worker replica 증가 후 Queue backlog가 더 빠르게 감소하는 것을 확인하였다.

이후 HPA를 API와 Worker 각각에 적용하였다.

```yaml
kind: HorizontalPodAutoscaler
metadata:
  name: fastpass-worker-hpa
spec:
  minReplicas: 1
  maxReplicas: 3
```

### 정리

Queue 기반 시스템에서는 API Pod 확장만으로는 전체 처리량이 개선되지 않을 수 있다.

Worker가 병목인 경우 Worker replica를 독립적으로 확장해야 한다.

---

## 9. Issue 7: Spring Boot Pod 재시작

### 문제 상황

ArgoCD로 FastPass를 배포한 뒤 Application이 `Degraded` 상태가 되었다.

Pod는 Running 상태였지만 Ready가 되지 못하거나 재시작이 발생하였다.

이벤트에서는 다음과 같은 메시지가 확인되었다.

```text
Liveness probe failed
Readiness probe failed
connection refused
context deadline exceeded
```

API 로그에서는 Spring Boot 애플리케이션이 약 50초 후에 시작된 것을 확인하였다.

```text
Started ApiApplication in 50.277 seconds
```

### 원인

Spring Boot 애플리케이션 기동 시간이 livenessProbe 초기 지연 시간보다 길었다.

기존 probe는 너무 이른 시점부터 `/actuator/health`를 호출했다.

```yaml
readinessProbe:
  initialDelaySeconds: 20

livenessProbe:
  initialDelaySeconds: 40
```

Spring Boot가 완전히 뜨기 전에 livenessProbe가 실패하면서 Kubernetes가 컨테이너를 재시작시켰다.

### 해결

readiness와 liveness endpoint를 분리하고 초기 지연 시간을 늘렸다.

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 6

livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 90
  periodSeconds: 10
  timeoutSeconds: 3
  failureThreshold: 6
```

### 정리

livenessProbe는 애플리케이션이 복구 불가능한 상태인지 판단하는 용도이다.

기동 시간이 긴 애플리케이션에 livenessProbe를 너무 빨리 적용하면 정상적으로 시작 중인 컨테이너를 불필요하게 재시작시킬 수 있다.

---

## 10. Issue 8: Prometheus target 미수집

### 문제 상황

Prometheus에서 FastPass API/Worker metric이 바로 조회되지 않았다.

```promql
up{namespace="fastpass"}
```

또는 custom metric이 조회되지 않았다.

```promql
fastpass_queue_size
```

### 원인

Prometheus Operator 기반 환경에서는 ServiceMonitor가 Service를 기준으로 target을 찾는다.

따라서 다음 조건이 맞아야 한다.

```text
1. Service가 존재해야 한다.
2. Service label이 ServiceMonitor selector와 일치해야 한다.
3. Service port name이 ServiceMonitor endpoint port와 일치해야 한다.
4. Actuator prometheus endpoint가 노출되어야 한다.
```

초기에는 Worker가 HTTP 트래픽을 직접 받지 않기 때문에 Service가 필요 없다고 생각할 수 있지만, Prometheus가 Worker metric을 scrape하려면 Worker Service도 필요하다.

### 해결

API Service와 Worker Service에 label과 port name을 명확히 설정하였다.

```yaml
metadata:
  labels:
    app: fastpass-worker
spec:
  ports:
    - name: http
      port: 8080
      targetPort: 8080
```

ServiceMonitor는 API와 Worker를 모두 선택하도록 구성하였다.

```yaml
selector:
  matchExpressions:
    - key: app
      operator: In
      values:
        - fastpass-api
        - fastpass-worker

endpoints:
  - port: http
    path: /actuator/prometheus
    interval: 15s
```

Spring Boot 설정에도 prometheus endpoint를 노출하였다.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

### 정리

Prometheus Operator 환경에서는 Pod를 직접 scrape하는 것이 아니라 ServiceMonitor가 Service를 기준으로 target을 찾는다.

Worker처럼 외부 트래픽을 받지 않는 컴포넌트도 metric scrape를 위해 Service가 필요할 수 있다.

---

## 11. Issue 9: Custom metric 중복 해석

### 문제 상황

Prometheus에서 `fastpass_queue_size` metric이 여러 개 조회되었다.

```promql
fastpass_queue_size{namespace="fastpass"}
```

API와 Worker Pod가 여러 개일 경우 같은 Redis Queue size가 여러 series로 표시되었다.

### 원인

`fastpass_queue_size`는 Redis Queue의 현재 크기를 나타내는 Gauge이다.

API와 Worker Pod가 각각 같은 Redis Queue를 바라보고 metric을 노출하기 때문에, Pod 수만큼 동일한 queue size 값이 노출될 수 있다.

이 값을 `sum`하면 실제 Queue size보다 크게 계산된다.

### 해결

Queue size는 `sum`이 아니라 `max`로 조회하였다.

```promql
max(fastpass_queue_size{namespace="fastpass"})
```

ArgoCD GitOps namespace에서는 다음과 같이 조회하였다.

```promql
max(fastpass_queue_size{namespace="fastpass-gitops"})
```

### 정리

모든 metric을 무조건 `sum`하면 안 된다.

Counter 계열 처리량은 `sum(rate(...))`가 적절할 수 있지만, 전체 시스템 상태를 여러 Pod가 동일하게 노출하는 Gauge는 `max` 또는 `avg`가 더 적절할 수 있다.

---

## 12. Issue 10: Worker failure metric 증가

### 문제 상황

Grafana에서 Worker failure metric이 증가하였다.

Worker logs에는 다음과 같은 메시지가 출력되었다.

```text
Failed to process application. applicationId=7654, message=Application not found. id=7654
```

### 원인

Redis Queue에 존재하는 applicationId가 PostgreSQL DB에는 존재하지 않았다.

이는 이전 테스트 과정에서 Redis Queue에 남아 있던 stale item이거나, 수동 테스트 중 잘못된 applicationId가 Queue에 들어간 경우 발생할 수 있다.

### 해결

Worker에서 처리 실패 시 실패 counter를 증가시키도록 구성하였다.

```java
catch (Exception e) {
    fastPassMetrics.incrementWorkerProcessingFailed();

    System.err.println(
            "Failed to process application. applicationId="
                    + applicationId
                    + ", message="
                    + e.getMessage()
    );
}
```

Prometheus metric은 다음과 같다.

```promql
fastpass_worker_processing_failed_total
```

Alert rule도 추가하였다.

```promql
sum(increase(fastpass_worker_processing_failed_total{namespace="fastpass", job="fastpass-worker"}[5m])) > 0
```

### 정리

실패를 단순히 로그로만 남기면 운영자가 빠르게 감지하기 어렵다.

실패 counter metric과 alert을 함께 구성하면 Worker 처리 실패를 Prometheus/Alertmanager를 통해 확인할 수 있다.

---

## 13. Issue 11: ArgoCD Degraded

### 문제 상황

ArgoCD Application은 `Synced`였지만 `Healthy`가 아니라 `Degraded`로 표시되었다.

```text
SYNC STATUS: Synced
HEALTH STATUS: Degraded
```

### 원인

Git desired state와 Kubernetes live state는 일치했지만, Pod health 상태가 정상으로 판단되지 않았다.

구체적인 원인은 Spring Boot 초기 기동 시간 대비 probe 설정이 너무 짧았기 때문이다.

### 해결

Kubernetes Deployment의 readinessProbe와 livenessProbe를 조정하였다.

```yaml
readinessProbe:
  path: /actuator/health/readiness
  initialDelaySeconds: 60

livenessProbe:
  path: /actuator/health/liveness
  initialDelaySeconds: 90
```

변경 사항을 GitHub에 push한 뒤 ArgoCD Sync를 수행하였다.

### 정리

ArgoCD의 `Synced`와 `Healthy`는 다른 의미이다.

```text
Synced:
Git desired state와 live state가 일치함

Healthy:
배포된 리소스가 Kubernetes 관점에서 정상 상태임
```

따라서 `Synced`이면서 `Degraded`일 수 있다.

---

## 14. Issue 12: ArgoCD OutOfSync due to HPA

### 문제 상황

ArgoCD Application이 다음 상태가 되었다.

```text
SYNC STATUS: OutOfSync
HEALTH STATUS: Healthy
```

Deployment diff에서는 다음 차이가 확인되었다.

```yaml
# Desired
replicas: 1

# Live
replicas: 3
```

### 원인

Helm Chart의 `values.yaml`에는 API와 Worker replica 수가 1로 정의되어 있었다.

```yaml
api:
  replicas: 1

worker:
  replicas: 1
```

하지만 HPA가 CPU 사용률에 따라 live Deployment의 `spec.replicas` 값을 3으로 변경하였다.

ArgoCD는 Git desired state와 Kubernetes live state를 비교하기 때문에 다음 차이를 OutOfSync로 판단하였다.

```text
Git desired replicas: 1
Live replicas changed by HPA: 3
```

### 해결

HPA가 관리하는 `/spec/replicas` 필드는 ArgoCD diff 대상에서 제외하였다.

```yaml
ignoreDifferences:
  - group: apps
    kind: Deployment
    jsonPointers:
      - /spec/replicas
```

또한 sync 시 ignoreDifferences를 존중하도록 설정하였다.

```yaml
syncPolicy:
  automated:
    prune: true
    selfHeal: true
  syncOptions:
    - RespectIgnoreDifferences=true
```

### 정리

HPA와 ArgoCD를 함께 사용할 경우 replicas 필드는 live state에서 변할 수 있다.

따라서 HPA가 관리하는 Deployment의 `/spec/replicas`는 ArgoCD diff에서 제외하는 것이 적절하다.

---

## 15. Issue 13: rollout restart annotation drift

### 문제 상황

문제 확인 과정에서 다음 명령어를 실행하였다.

```bash
kubectl rollout restart deployment -n fastpass-gitops
```

이후 ArgoCD diff에 annotation 차이가 나타날 수 있었다.

### 원인

`kubectl rollout restart`는 Deployment pod template에 다음 annotation을 추가한다.

```text
kubectl.kubernetes.io/restartedAt
```

이 annotation은 Git의 Helm Chart에는 존재하지 않는 live 변경 사항이다.

따라서 ArgoCD가 OutOfSync로 판단할 수 있다.

### 해결

필요한 경우 annotation을 제거한다.

```bash
kubectl annotate deployment fastpass-api \
  -n fastpass-gitops \
  kubectl.kubernetes.io/restartedAt- \
  --overwrite

kubectl annotate deployment fastpass-worker \
  -n fastpass-gitops \
  kubectl.kubernetes.io/restartedAt- \
  --overwrite
```

이후 ArgoCD Application을 refresh한다.

```bash
kubectl annotate application fastpass-gitops -n argocd \
  argocd.argoproj.io/refresh=hard \
  --overwrite
```

### 정리

GitOps 환경에서는 클러스터에서 직접 수동 변경을 수행하면 Git desired state와 live state가 달라질 수 있다.

가능하면 Git 변경 후 ArgoCD Sync를 통해 반영하는 방식이 적절하다.

---

## 16. Issue 14: GitHub Actions deprecated warning

### 문제 상황

GitHub Actions workflow는 성공했지만 summary에 warning이 표시되었다.

```text
Node.js 20 is deprecated
setup-java v4 is deprecated
```

### 원인

workflow에서 사용한 action 버전이 오래된 Node runtime을 기준으로 동작하고 있었다.

초기 workflow는 다음 버전을 사용하였다.

```yaml
uses: actions/checkout@v4
uses: actions/setup-java@v4
uses: gradle/actions/setup-gradle@v4
```

### 해결

action version을 업데이트하였다.

```yaml
uses: actions/checkout@v5
uses: actions/setup-java@v5
uses: gradle/actions/setup-gradle@v6
```

업데이트 후 warning이 사라지고 workflow가 정상 성공하였다.

### 정리

CI가 성공하더라도 warning은 장기 유지보수 관점에서 확인해야 한다.

Deprecated action을 계속 사용하면 이후 GitHub Actions runtime 변경 시 workflow가 실패할 수 있다.

---

## 17. Issue 15: Docker image가 runner 안에서만 사라짐

### 문제 상황

초기 GitHub Actions CI는 Docker image build까지 성공했지만, 생성된 image는 GitHub Actions runner 안에서만 존재했다.

workflow가 종료되면 image는 사라졌다.

### 원인

Docker image를 build만 하고 container registry에 push하지 않았기 때문이다.

```text
GitHub Actions
→ docker build
→ workflow 종료
→ image 사라짐
```

### 해결

GitHub Container Registry, 즉 GHCR에 image를 push하도록 workflow를 확장하였다.

```yaml
permissions:
  contents: read
  packages: write
```

GHCR 로그인 단계도 추가하였다.

```yaml
- name: Log in to GitHub Container Registry
  uses: docker/login-action@v3
  with:
    registry: ghcr.io
    username: ${{ github.actor }}
    password: ${{ secrets.GITHUB_TOKEN }}
```

Docker image build and push 단계도 추가하였다.

```yaml
- name: Build and push Docker image
  uses: docker/build-push-action@v6
  with:
    context: ./apps/api
    push: ${{ github.event_name == 'push' }}
    tags: |
      ghcr.io/kdh8128/fastpass-k8s-api:latest
      ghcr.io/kdh8128/fastpass-k8s-api:${{ github.sha }}
```

### 정리

CI에서 Docker image build만 수행하면 image 검증까지만 가능하다.

Container registry에 push해야 이후 Kubernetes, Helm, ArgoCD 배포에서 해당 image를 사용할 수 있다.

---

## 18. Issue 16: Helm이 local image를 계속 사용

### 문제 상황

GHCR에 image push는 성공했지만, Helm Chart는 여전히 local image를 사용하고 있었다.

기존 설정은 다음과 같았다.

```yaml
image:
  repository: fastpass-k8s-api
  tag: latest
  pullPolicy: Never
```

### 원인

GHCR push와 Kubernetes 배포는 별개의 단계이다.

Image가 GHCR에 올라가도 Helm Chart의 image repository가 바뀌지 않으면 Kubernetes는 계속 기존 image 설정을 사용한다.

### 해결

Helm values를 GHCR 기준으로 변경하였다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: latest
  pullPolicy: IfNotPresent
```

ArgoCD Sync 후 Deployment image를 확인하였다.

```bash
kubectl get deployment fastpass-api fastpass-worker \
  -n fastpass-gitops \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.template.spec.containers[0].image}{"\t"}{.spec.template.spec.containers[0].imagePullPolicy}{"\n"}{end}'
```

기대 결과는 다음과 같다.

```text
fastpass-api      ghcr.io/kdh8128/fastpass-k8s-api:latest      IfNotPresent
fastpass-worker   ghcr.io/kdh8128/fastpass-k8s-api:latest      IfNotPresent
```

### 정리

Container registry에 image를 push하는 것만으로는 배포 image가 자동으로 바뀌지 않는다.

Helm Chart 또는 Kubernetes manifest가 해당 registry image를 참조해야 한다.

---

## 19. 운영 관점에서 얻은 교훈

이번 프로젝트의 Troubleshooting을 통해 확인한 운영 관점의 교훈은 다음과 같다.

```text
1. Queue 기반 시스템은 API 처리량과 Worker 처리량을 분리해서 봐야 한다.
2. Queue backlog는 Worker 병목을 보여주는 중요한 지표이다.
3. HPA를 사용하려면 metrics-server와 resource request가 필요하다.
4. livenessProbe는 너무 이른 시점에 실행하면 오히려 장애를 만들 수 있다.
5. Prometheus metric은 type과 의미에 따라 적절한 PromQL을 사용해야 한다.
6. ArgoCD의 Synced와 Healthy는 다른 개념이다.
7. HPA와 ArgoCD를 함께 사용할 때 replicas drift를 고려해야 한다.
8. CI에서 Docker build만 하는 것과 registry push까지 하는 것은 다르다.
9. GitOps 환경에서는 수동 kubectl 변경이 drift를 만들 수 있다.
10. 문제 해결 과정 자체가 DevOps 포트폴리오의 중요한 증거가 된다.
```

---

## 20. 문서별 상세 참고

각 문제의 자세한 구현 및 검증 과정은 다음 문서에서 확인할 수 있다.

| Issue | Related Document |
|---|---|
| API MVP, DB schema | `docs/01-api-mvp.md` |
| Redis Queue | `docs/02-redis-queue.md` |
| Docker Compose | `docs/03-docker-compose.md` |
| Kubernetes local 배포 | `docs/04-kubernetes-local.md` |
| k6 load test | `docs/05-load-test.md` |
| Worker batch improvement | `docs/06-worker-batch-improvement.md` |
| API/Worker split | `docs/07-api-worker-split.md` |
| Worker scaling | `docs/08-worker-scaling-test.md` |
| HPA autoscaling | `docs/09-hpa-autoscaling.md` |
| Prometheus/Grafana | `docs/10-prometheus-grafana-monitoring.md` |
| Custom metrics | `docs/11-custom-metrics.md` |
| Alerting | `docs/12-alerting.md` |
| Helm | `docs/13-helm.md` |
| ArgoCD GitOps | `docs/14-argocd-gitops.md` |
| GitHub Actions CI | `docs/15-github-actions-ci.md` |
| GHCR image push | `docs/16-container-registry-ghcr.md` |
| GHCR image deployment | `docs/17-ghcr-argocd-deployment.md` |

---

## 21. 결론

FastPass 프로젝트에서는 구현 단계뿐만 아니라 운영 검증 과정에서 다양한 문제가 발생하였다.

각 문제는 단순 오류 수정이 아니라, Kubernetes 기반 서비스 운영에서 실제로 고려해야 하는 요소와 연결되어 있었다.

특히 다음 문제들은 DevOps 관점에서 중요한 경험이었다.

```text
Kubernetes image pull 문제
HPA metric 수집 문제
Queue backlog와 Worker 병목
Spring Boot probe 설정 문제
Prometheus custom metric 해석 문제
ArgoCD와 HPA의 replicas drift
CI build와 registry push의 차이
GHCR image 기반 배포 전환
```

이 문서는 이러한 문제들을 한 곳에 정리하여, FastPass가 단순 기능 구현 프로젝트가 아니라 운영 환경의 문제를 단계적으로 해결한 DevOps/Cloud 포트폴리오 프로젝트임을 보여주는 근거로 사용한다.