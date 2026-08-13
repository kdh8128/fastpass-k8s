# FastPass ArgoCD GitOps 검증

## 1. 목적

이번 단계의 목적은 FastPass의 Kubernetes 배포 방식을
Helm 기반 수동 배포에서 ArgoCD 기반 GitOps 배포로 확장하는 것이다.

이전 단계까지 FastPass는 다음 흐름으로 발전하였다.

```text
1. raw Kubernetes YAML 기반 배포
2. Helm Chart 기반 배포
3. ArgoCD GitOps 기반 배포
```

기존에는 다음과 같이 직접 Kubernetes 리소스를 적용하였다.

```bash
kubectl apply -f deploy/k8s/
```

이후 Helm Chart를 도입하여 다음과 같이 배포할 수 있도록 개선하였다.

```bash
helm install fastpass deploy/helm/fastpass
```

이번 단계에서는 ArgoCD가 GitHub repository의 Helm Chart를 바라보도록
구성하여, Git에 정의된 상태를 Kubernetes 클러스터에 자동으로 동기화하는
GitOps 구조를 검증하였다.

---

## 2. GitOps 도입 이유

GitOps는 Kubernetes 배포 상태를 Git repository에 선언적으로 저장하고,
클러스터의 실제 상태가 Git에 정의된 desired state와 일치하도록 자동으로
동기화하는 방식이다.

FastPass 프로젝트에서 GitOps를 도입한 이유는 다음과 같다.

```text
1. Kubernetes 리소스 변경 이력을 Git으로 추적할 수 있다.
2. 배포 상태를 Git의 desired state 기준으로 관리할 수 있다.
3. 클러스터에서 수동 변경이 발생해도 Git 상태로 복구할 수 있다.
4. Helm Chart와 연동하여 환경별 배포를 구조화할 수 있다.
5. 운영 환경에서 배포 자동화와 롤백 기반을 마련할 수 있다.
```

ArgoCD를 사용하면 GitHub repository의 Helm Chart를 지속적으로 감시하고,
Git 상태와 Kubernetes live 상태를 비교하여 `Synced`, `OutOfSync`,
`Healthy`, `Degraded` 등의 상태를 확인할 수 있다.

---

## 3. 이번 단계의 목표

이번 단계의 주요 목표는 다음과 같다.

```text
1. 로컬 Kubernetes 클러스터에 ArgoCD를 설치한다.
2. ArgoCD UI에 접속한다.
3. FastPass ArgoCD Application을 작성한다.
4. GitHub repository의 Helm Chart를 ArgoCD에 연결한다.
5. ArgoCD를 통해 FastPass를 fastpass-gitops namespace에 배포한다.
6. Git 변경 사항이 ArgoCD Sync를 통해 Kubernetes에 반영되는지 확인한다.
7. ArgoCD self-heal 기능을 검증한다.
8. HPA와 ArgoCD diff 충돌 문제를 해결한다.
```

---

## 4. 배포 구조

이번 단계에서는 기존 검증용 namespace와 분리하여 ArgoCD 테스트용
namespace를 별도로 사용하였다.

```text
기존 Kubernetes 검증용 namespace: fastpass
ArgoCD GitOps 검증용 namespace: fastpass-gitops
ArgoCD 설치 namespace: argocd
모니터링 namespace: monitoring
```

ArgoCD Application은 GitHub repository의 Helm Chart를 바라본다.

```text
Repository: https://github.com/kdh8128/fastpass-k8s.git
Branch: main
Chart path: deploy/helm/fastpass
Helm release name: fastpass-gitops
Target namespace: fastpass-gitops
```

전체 흐름은 다음과 같다.

```text
GitHub repository
  └── deploy/helm/fastpass
        ↓
ArgoCD Application
        ↓
Helm render
        ↓
Kubernetes resources
        ↓
fastpass-gitops namespace
```

---

## 5. ArgoCD 설치

ArgoCD 설치를 위해 먼저 `argocd` namespace를 생성하였다.

```bash
kubectl create namespace argocd
```

그다음 ArgoCD 공식 install manifest를 적용하였다.

```bash
kubectl apply -n argocd --server-side --force-conflicts \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

설치 후 Pod 상태를 확인하였다.

```bash
kubectl get pods -n argocd
```

또는 다음 명령어로 Running 상태가 될 때까지 확인하였다.

```bash
kubectl get pods -n argocd -w
```

모든 ArgoCD 관련 Pod가 Running 상태가 되면 설치가 완료된 것이다.

---

## 6. ArgoCD UI 접속

ArgoCD UI에 접속하기 위해 `argocd-server` Service를 port-forward하였다.

```bash
kubectl port-forward -n argocd svc/argocd-server 18082:443
```

브라우저에서 다음 주소로 접속하였다.

```text
https://localhost:18082
```

로컬 환경에서는 인증서 경고가 표시될 수 있다.

초기 로그인 계정은 다음과 같다.

```text
ID: admin
```

초기 비밀번호는 다음 명령어로 확인하였다.

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 -d; echo
```

---

## 7. ArgoCD Application 작성

FastPass를 ArgoCD로 배포하기 위해 다음 파일을 작성하였다.

```text
deploy/argocd/fastpass-application.yaml
```

Application 리소스는 GitHub repository의 Helm Chart를 바라보도록
구성하였다.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: fastpass-gitops
  namespace: argocd
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: default

  source:
    repoURL: https://github.com/kdh8128/fastpass-k8s.git
    targetRevision: main
    path: deploy/helm/fastpass
    helm:
      releaseName: fastpass-gitops
      parameters:
        - name: namespace
          value: fastpass-gitops

  destination:
    server: https://kubernetes.default.svc
    namespace: fastpass-gitops

  ignoreDifferences:
    - group: apps
      kind: Deployment
      jsonPointers:
        - /spec/replicas

  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - RespectIgnoreDifferences=true
```

주요 설정의 의미는 다음과 같다.

| 항목 | 의미 |
|---|---|
| `repoURL` | FastPass GitHub repository |
| `targetRevision` | 배포 기준 branch |
| `path` | Helm Chart 경로 |
| `releaseName` | Helm release 이름 |
| `parameters.namespace` | Helm values의 namespace override |
| `destination.namespace` | 배포 대상 namespace |
| `prune` | Git에서 삭제된 리소스를 클러스터에서도 삭제 |
| `selfHeal` | 클러스터 수동 변경 시 Git 상태로 자동 복구 |
| `ignoreDifferences` | HPA가 관리하는 replica 차이 무시 |

---

## 8. Application 적용

작성한 Application을 Kubernetes에 적용하였다.

```bash
kubectl apply -f deploy/argocd/fastpass-application.yaml
```

적용 시 다음과 같은 warning이 표시될 수 있다.

```text
metadata.finalizers:
"resources-finalizer.argocd.argoproj.io":
prefer a domain-qualified finalizer name including a path (/)
```

이는 warning이며, Application 생성은 정상적으로 완료되었다.

```text
application.argoproj.io/fastpass-gitops created
```

Application 상태는 다음 명령어로 확인하였다.

```bash
kubectl get application fastpass-gitops -n argocd
```

기대 상태는 다음과 같다.

```text
NAME              SYNC STATUS   HEALTH STATUS
fastpass-gitops   Synced        Healthy
```

---

## 9. ArgoCD Sync 상태 확인

ArgoCD Application이 생성된 후 다음 명령어로 상태를 확인하였다.

```bash
kubectl get application fastpass-gitops -n argocd
```

최종적으로 다음 상태를 확인하였다.

```text
NAME              SYNC STATUS   HEALTH STATUS
fastpass-gitops   Synced        Healthy
```

이는 다음을 의미한다.

```text
Synced:
GitHub repository의 Helm Chart desired state와
Kubernetes live state가 일치함

Healthy:
배포된 Kubernetes 리소스들이 정상 상태임
```

ArgoCD UI에서도 `fastpass-gitops` Application이 `Synced` 및 `Healthy`
상태로 표시되는 것을 확인하였다.

---

## 10. Kubernetes 리소스 확인

ArgoCD가 FastPass를 `fastpass-gitops` namespace에 배포했는지 확인하였다.

```bash
kubectl get pods -n fastpass-gitops
```

최종 확인된 Pod 상태는 다음과 같다.

```text
NAME                               READY   STATUS    RESTARTS
fastpass-api-65747d67f-5q26m       1/1     Running   0
fastpass-api-65747d67f-66wqp       1/1     Running   0
fastpass-api-65747d67f-gncml       1/1     Running   0
fastpass-worker-5855cd4676-d7zr8   1/1     Running   0
fastpass-worker-5855cd4676-gqgf4   1/1     Running   0
fastpass-worker-5855cd4676-hm5s2   1/1     Running   0
postgres-7bdfc57f49-kgz8d          1/1     Running
redis-6f546d8c7d-ktkpl             1/1     Running
```

API, Worker, PostgreSQL, Redis가 모두 정상적으로 Running 상태인 것을
확인하였다.

Service도 다음 명령어로 확인하였다.

```bash
kubectl get svc -n fastpass-gitops
```

HPA도 다음 명령어로 확인하였다.

```bash
kubectl get hpa -n fastpass-gitops
```

확인된 HPA 상태는 다음과 같다.

```text
NAME                  REFERENCE                    TARGETS
fastpass-api-hpa      Deployment/fastpass-api      cpu: 46%/50%
fastpass-worker-hpa   Deployment/fastpass-worker   cpu: 56%/50%
```

HPA는 API와 Worker의 CPU 사용률을 기준으로 replica 수를 조정하고 있었다.

---

## 11. Health Check 검증

ArgoCD로 배포한 FastPass API에 접근하기 위해 port-forward를 실행하였다.

```bash
kubectl port-forward -n fastpass-gitops service/fastpass-api 18083:8080
```

다른 터미널에서 health endpoint를 호출하였다.

```bash
curl http://localhost:18083/actuator/health
```

응답은 다음과 같았다.

```json
{"status":"UP","groups":["liveness","readiness"]}
```

readiness endpoint도 확인하였다.

```bash
curl http://localhost:18083/actuator/health/readiness
```

응답은 다음과 같았다.

```json
{"status":"UP"}
```

liveness endpoint도 확인하였다.

```bash
curl http://localhost:18083/actuator/health/liveness
```

응답은 다음과 같았다.

```json
{"status":"UP"}
```

이를 통해 ArgoCD로 배포된 FastPass API가 정상적으로 기동되었고,
readiness/liveness endpoint도 정상 동작함을 확인하였다.

---

## 12. API 기능 검증

ArgoCD로 배포된 FastPass가 실제 API 요청을 처리할 수 있는지 확인하였다.

먼저 테스트 이벤트를 생성하였다.

```bash
EVENT_ID=$(curl -s -X POST http://localhost:18083/api/events \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"title\":\"ArgoCD Final Test\",\
\"description\":\"gitops validation\",\
\"capacity\":3,\
\"eventStartAt\":\"2026-07-20T10:00:00\"}" \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')

echo $EVENT_ID
```

그다음 이벤트에 신청 요청을 보냈다.

```bash
curl -X POST http://localhost:18083/api/events/${EVENT_ID}/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"argocd-final-user-1\"}"
```

신청 요청은 API에서 `PENDING` 상태로 생성되고 Redis Queue에 적재된다.

Worker가 Queue에서 applicationId를 꺼내 처리한 뒤 Queue size는 0이 된다.

```bash
curl http://localhost:18083/api/queue/applications/size
```

정상 상태는 다음과 같다.

```json
{"size":0}
```

이를 통해 ArgoCD로 배포된 API, Redis, Worker, PostgreSQL이 정상적으로
연동되는 것을 확인하였다.

---

## 13. Prometheus 수집 검증

ArgoCD로 배포한 FastPass도 Prometheus에서 정상적으로 수집되는지
확인하였다.

Prometheus UI에서 다음 PromQL을 실행하였다.

```promql
up{namespace="fastpass-gitops"}
```

API와 Worker target이 조회되고 값이 `1`이면 정상이다.

또한 FastPass custom metric도 확인하였다.

```promql
max(fastpass_queue_size{namespace="fastpass-gitops"})
```

이를 통해 Helm Chart에 포함된 ServiceMonitor가 정상적으로 동작하고,
Prometheus가 `fastpass-gitops` namespace의 API와 Worker metric을
수집하고 있음을 확인하였다.

---

## 14. Self-Heal 검증

ArgoCD Application에는 다음 설정을 적용하였다.

```yaml
syncPolicy:
  automated:
    prune: true
    selfHeal: true
```

`selfHeal: true` 설정은 클러스터의 live state가 Git desired state와
달라졌을 때, ArgoCD가 다시 Git 상태로 복구하도록 한다.

이를 검증하기 위해 FastPass ConfigMap을 수동으로 변경하였다.

```bash
kubectl patch configmap fastpass-api-config \
  -n fastpass-gitops \
  --type merge \
  -p '{"data":{"SPRING_PROFILES_ACTIVE":"local"}}'
```

수동 변경 직후 ConfigMap을 확인하였다.

```bash
kubectl get configmap fastpass-api-config \
  -n fastpass-gitops \
  -o yaml
```

ArgoCD self-heal에 의해 ConfigMap은 다시 Git에 정의된 값으로 복구되었다.

```yaml
data:
  SPRING_PROFILES_ACTIVE: docker
```

다음 명령어로 최종 값을 확인하였다.

```bash
kubectl get configmap fastpass-api-config \
  -n fastpass-gitops \
  -o jsonpath="{.data.SPRING_PROFILES_ACTIVE}"
```

결과는 다음과 같았다.

```text
docker
```

또한 Application 상태는 계속 `Synced` 및 `Healthy`였다.

```bash
kubectl get application fastpass-gitops -n argocd
```

결과는 다음과 같았다.

```text
fastpass-gitops   Synced   Healthy
```

이를 통해 ArgoCD가 클러스터에서 발생한 수동 변경을 감지하고,
Git desired state로 자동 복구하는 것을 검증하였다.

---

## 15. Git 변경 사항 반영 검증

ArgoCD는 GitHub repository의 `main` branch에 있는 Helm Chart를 기준으로
배포 상태를 관리한다.

이번 단계에서 readiness/liveness probe 설정을 수정한 뒤 GitHub에 push하였다.

수정 후 ArgoCD UI에서 Sync를 수행하였고, 변경 사항이 Kubernetes
Deployment에 반영되는 것을 확인하였다.

Deployment의 readinessProbe는 다음과 같이 적용되었다.

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 60
```

Deployment의 livenessProbe는 다음과 같이 적용되었다.

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 90
```

이를 통해 GitHub에 push된 Helm Chart 변경 사항이 ArgoCD Sync를 통해
Kubernetes live state에 반영되는 것을 확인하였다.

---

## 16. Troubleshooting 1: Spring Boot 기동 시간과 Probe 설정 문제

### 16.1 문제 상황

ArgoCD로 FastPass를 처음 배포했을 때 Application 상태가 다음과 같이
표시되었다.

```text
SYNC STATUS: Synced
HEALTH STATUS: Degraded
```

Pod 상태를 확인한 결과 API와 Worker가 Running 상태였지만 Ready 상태가
되지 못하고 재시작이 발생하였다.

```text
fastpass-api      0/1 Running
fastpass-worker   0/1 Running
```

이벤트를 확인한 결과 다음과 같은 메시지가 확인되었다.

```text
Liveness probe failed:
Get "http://<pod-ip>:8080/actuator/health":
context deadline exceeded

Readiness probe failed:
Get "http://<pod-ip>:8080/actuator/health":
connect: connection refused
```

API 로그에서는 Spring Boot 애플리케이션이 약 50초 후에 정상 기동된 것을
확인하였다.

```text
Started ApiApplication in 50.277 seconds
```

### 16.2 원인

Spring Boot 애플리케이션의 초기 기동 시간이 길었는데,
기존 livenessProbe가 너무 이른 시점부터 동작하였다.

기존 probe 설정은 다음과 같았다.

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 20
  periodSeconds: 5

livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 40
  periodSeconds: 10
```

Spring Boot가 완전히 뜨기 전에 livenessProbe가 실패하면서 Kubernetes가
컨테이너를 재시작시켰고, 이로 인해 ArgoCD에서는 일시적으로 `Degraded`
상태가 표시되었다.

즉, 애플리케이션 자체가 실패한 것이 아니라, 초기 기동 시간을 고려하지
못한 probe 설정이 문제였다.

### 16.3 해결

readiness와 liveness endpoint를 분리하고, 초기 지연 시간을 늘렸다.

수정한 설정은 다음과 같다.

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

수정 대상 파일은 다음과 같다.

```text
deploy/helm/fastpass/templates/api-deployment.yaml
deploy/helm/fastpass/templates/worker-deployment.yaml
deploy/k8s/api-deployment.yaml
deploy/k8s/worker-deployment.yaml
```

수정 후 GitHub에 push하고 ArgoCD Sync를 수행하였다.

그 결과 API와 Worker가 불필요한 probe 실패 없이 정상적으로 Ready 상태가
되었고, Application도 `Synced` 및 `Healthy` 상태로 전환되었다.

### 16.4 정리

이번 문제는 ArgoCD 자체의 문제가 아니라 Kubernetes health probe 설정의
문제였다.

Spring Boot 초기 기동 시간이 긴 경우 livenessProbe를 너무 빨리 실행하면
정상적으로 기동 중인 애플리케이션을 Kubernetes가 실패로 판단하고
재시작할 수 있다.

따라서 readinessProbe는 트래픽 수신 가능 여부를 확인하고,
livenessProbe는 애플리케이션이 복구 불가능한 상태인지 확인하도록
endpoint와 delay를 분리하는 것이 적절하다.

---

## 17. Troubleshooting 2: HPA로 인한 ArgoCD OutOfSync

### 17.1 문제 상황

ArgoCD Application이 다음과 같은 상태로 표시되었다.

```text
SYNC STATUS: OutOfSync
HEALTH STATUS: Healthy
```

리소스별 Sync 상태를 확인하였다.

```bash
kubectl get application fastpass-gitops -n argocd \
  -o jsonpath='{range .status.resources[*]}{.kind}{"\t"}{.namespace}{"\t"}{.name}{"\t"}{.status}{"\n"}{end}'
```

확인 결과 API와 Worker Deployment만 `OutOfSync` 상태였다.

```text
Deployment    fastpass-gitops    fastpass-api       OutOfSync
Deployment    fastpass-gitops    fastpass-worker    OutOfSync
```

ArgoCD diff 화면에서는 다음 차이가 확인되었다.

```yaml
# Live state
replicas: 3

# Desired state
replicas: 1
```

### 17.2 원인

Helm Chart의 `values.yaml`에는 API와 Worker의 기본 replica 수가 1로
정의되어 있다.

```yaml
api:
  replicas: 1

worker:
  replicas: 1
```

따라서 Git/Helm 기준 desired manifest는 다음과 같이 렌더링된다.

```yaml
spec:
  replicas: 1
```

하지만 FastPass에는 HPA가 적용되어 있다.

```yaml
hpa:
  enabled: true
  minReplicas: 1
  maxReplicas: 3
  cpuAverageUtilization: 50
```

HPA는 CPU 사용률에 따라 Deployment의 `spec.replicas` 값을 직접 변경한다.

따라서 부하 또는 초기 기동 시점에 HPA가 API/Worker Deployment의 replica
수를 3으로 조정할 수 있다.

이 경우 ArgoCD는 다음 차이를 감지한다.

```text
Git/Helm desired: replicas = 1
Kubernetes live: replicas = 3
```

이로 인해 Application이 Healthy 상태임에도 OutOfSync로 표시되었다.

중요한 점은 `values.yaml` 파일이 실시간으로 변하는 것이 아니라는 것이다.

`values.yaml`은 Git에 커밋된 고정값이다.  
다만 ArgoCD는 Git의 desired manifest와 현재 Kubernetes live object를
비교하기 때문에, HPA가 변경한 live replica 값이 diff로 표시된다.

즉, 이 문제는 Helm values를 잘못 읽은 문제가 아니라,
HPA와 ArgoCD diff 기준이 충돌한 문제이다.

### 17.3 해결

HPA가 관리하는 `/spec/replicas` 필드는 ArgoCD diff 대상에서 제외하도록
설정하였다.

`deploy/argocd/fastpass-application.yaml`에 다음 설정을 추가하였다.

```yaml
ignoreDifferences:
  - group: apps
    kind: Deployment
    jsonPointers:
      - /spec/replicas
```

또한 sync 시에도 `ignoreDifferences` 설정을 존중하도록 다음 옵션을
추가하였다.

```yaml
syncPolicy:
  automated:
    prune: true
    selfHeal: true
  syncOptions:
    - RespectIgnoreDifferences=true
```

최종적으로 Application 설정은 다음 구조가 되었다.

```yaml
spec:
  destination:
    server: https://kubernetes.default.svc
    namespace: fastpass-gitops

  ignoreDifferences:
    - group: apps
      kind: Deployment
      jsonPointers:
        - /spec/replicas

  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - RespectIgnoreDifferences=true
```

수정 후 Application을 다시 적용하였다.

```bash
kubectl apply -f deploy/argocd/fastpass-application.yaml
```

그 후 ArgoCD UI에서 Sync를 수행하였고, Application은 다음 상태로
복구되었다.

```text
SYNC STATUS: Synced
HEALTH STATUS: Healthy
```

### 17.4 정리

이번 문제는 Helm Chart의 `values.yaml`이 잘못 적용된 것이 아니었다.

정확한 원인은 다음과 같다.

```text
1. values.yaml에는 replicas: 1이 정의되어 있다.
2. Helm desired manifest도 replicas: 1로 렌더링된다.
3. HPA가 live Deployment의 spec.replicas를 3으로 변경한다.
4. ArgoCD가 desired replicas 1과 live replicas 3을 비교한다.
5. 그 결과 Deployment가 OutOfSync로 표시된다.
```

HPA를 사용하는 Deployment에서는 `/spec/replicas`가 런타임에 변할 수 있다.

따라서 GitOps 환경에서는 해당 필드를 ArgoCD diff에서 제외하는 것이
적절하다.

---

## 18. Troubleshooting 3: Rollout Restart로 인한 OutOfSync 가능성

### 18.1 문제 상황

문제 확인 과정에서 다음 명령어를 실행하였다.

```bash
kubectl rollout restart deployment -n fastpass-gitops
```

이 명령어는 Deployment를 재시작하기 위해 Pod template에 restart
annotation을 추가한다.

```text
kubectl.kubernetes.io/restartedAt
```

이 annotation은 Git의 Helm Chart에는 존재하지 않는 값이다.

따라서 ArgoCD가 live Deployment와 desired manifest를 비교할 때
OutOfSync로 판단할 수 있다.

### 18.2 해결 방법

필요한 경우 다음 명령어로 restart annotation을 제거할 수 있다.

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

PostgreSQL과 Redis도 rollout restart를 수행했다면 다음과 같이 제거할 수
있다.

```bash
kubectl annotate deployment postgres \
  -n fastpass-gitops \
  kubectl.kubernetes.io/restartedAt- \
  --overwrite

kubectl annotate deployment redis \
  -n fastpass-gitops \
  kubectl.kubernetes.io/restartedAt- \
  --overwrite
```

그 후 ArgoCD Application을 hard refresh하였다.

```bash
kubectl annotate application fastpass-gitops -n argocd \
  argocd.argoproj.io/refresh=hard \
  --overwrite
```

### 18.3 정리

GitOps 환경에서는 클러스터에서 직접 수행한 수동 변경이 Git desired state와
차이를 만들 수 있다.

따라서 운영 환경에서는 가능한 한 `kubectl`로 직접 리소스를 변경하기보다,
Git 변경 후 ArgoCD Sync를 통해 반영하는 것이 적절하다.

---

## 19. 검증 결과 요약

이번 ArgoCD GitOps 단계에서 확인한 내용은 다음과 같다.

```text
1. 로컬 Kubernetes 클러스터에 ArgoCD를 설치하였다.
2. ArgoCD UI에 접속하였다.
3. FastPass ArgoCD Application을 작성하였다.
4. GitHub repository의 deploy/helm/fastpass Helm Chart를 연결하였다.
5. fastpass-gitops namespace에 FastPass를 배포하였다.
6. API, Worker, PostgreSQL, Redis가 정상 Running 상태가 되었다.
7. ArgoCD Application이 Synced / Healthy 상태가 되었다.
8. API health, readiness, liveness endpoint가 모두 UP 상태를 반환하였다.
9. 이벤트 생성과 신청 요청이 정상적으로 동작하였다.
10. Worker가 Queue를 처리하여 Queue size가 0이 되는 것을 확인하였다.
11. Prometheus가 fastpass-gitops namespace의 metric을 수집하였다.
12. ConfigMap 수동 변경 후 ArgoCD self-heal로 복구되는 것을 확인하였다.
13. Spring Boot 기동 시간과 probe 설정 문제를 해결하였다.
14. HPA replica drift로 인한 ArgoCD OutOfSync 문제를 해결하였다.
```

---

## 20. 한계점

이번 단계에서는 로컬 Kubernetes 환경에서 ArgoCD를 설치하고,
FastPass Helm Chart를 GitOps 방식으로 배포하는 것을 검증하였다.

다만 아직 다음 기능은 구현하지 않았다.

```text
GitHub Actions와 ArgoCD 연동
Docker image registry push
image tag 자동 업데이트
ArgoCD Image Updater
환경별 Application 분리
dev/staging/prod AppProject 구성
Ingress 기반 ArgoCD 외부 접속
SSO 연동
RBAC 세분화
Slack 알림 연동
```

현재는 로컬 Kubernetes 클러스터에서 GitHub repository의 Helm Chart를
ArgoCD가 sync하는 구조까지 검증하였다.

향후에는 CI/CD 파이프라인과 연결하여 다음 흐름으로 확장할 수 있다.

```text
GitHub push
→ GitHub Actions build/test
→ Docker image build
→ image registry push
→ Helm values image tag update
→ ArgoCD sync
→ Kubernetes 배포
```

---

## 21. 다음 단계

다음 단계에서는 GitHub Actions CI를 구성한다.

목표는 다음과 같다.

```text
1. main branch push 또는 pull request 발생 시 CI 실행
2. Gradle build 실행
3. 테스트 실행
4. Docker image build 검증
5. CI 성공/실패를 GitHub Actions에서 확인
```

초기 단계에서는 Docker image registry push까지 바로 구현하지 않고,
먼저 build와 Docker image 생성 검증에 집중한다.

이후 확장 단계에서 Docker Hub, GHCR, ECR 같은 image registry에 push하고,
ArgoCD가 해당 image tag를 배포하도록 연결할 수 있다.

---

## 22. 결론

이번 단계에서는 FastPass의 배포 방식을 ArgoCD 기반 GitOps 구조로
확장하였다.

GitHub repository의 Helm Chart를 ArgoCD Application에 연결하고,
`fastpass-gitops` namespace에 FastPass를 배포하였다.

배포 결과 API, Worker, PostgreSQL, Redis가 정상적으로 Running 상태가
되었고, API health, readiness, liveness endpoint도 모두 정상적으로
응답하였다.

또한 ConfigMap을 수동으로 변경했을 때 ArgoCD self-heal 기능이 Git에
정의된 상태로 자동 복구하는 것을 확인하였다.

문제 해결 과정에서는 Spring Boot 초기 기동 시간으로 인한 probe 실패와,
HPA가 Deployment replica 수를 변경하면서 발생한 ArgoCD OutOfSync 문제를
확인하고 해결하였다.

이를 통해 FastPass는 raw Kubernetes YAML, Helm Chart를 거쳐
ArgoCD GitOps 기반 배포 구조까지 확장되었으며, 이후 GitHub Actions CI와
연계하여 CI/CD 파이프라인으로 발전시킬 수 있는 기반을 갖추게 되었다.