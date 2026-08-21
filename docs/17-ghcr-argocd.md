# FastPass GHCR Image 기반 ArgoCD 배포 검증

## 1. 목적

이번 단계의 목적은 FastPass Kubernetes 배포가 더 이상 로컬 Docker image에 의존하지 않고, GHCR에 push된 container image를 사용하도록 변경하는 것이다.

이전 단계에서는 GitHub Actions를 통해 FastPass API Docker image를 GHCR에 push하였다.

```text
GitHub Actions
→ Gradle build
→ Docker image build
→ GHCR image push
```

하지만 이 상태만으로는 Kubernetes 배포가 자동으로 GHCR image를 사용하는 것은 아니다.

기존 Helm Chart는 여전히 로컬 image를 사용하도록 설정되어 있었다.

```yaml
image:
  repository: fastpass-k8s-api
  tag: latest
  pullPolicy: Never
```

이번 단계에서는 Helm Chart의 image 설정을 GHCR 기준으로 변경하였다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: latest
  pullPolicy: IfNotPresent
```

이를 통해 ArgoCD가 GitHub repository의 Helm Chart를 Sync하면, Kubernetes가 GHCR에 저장된 FastPass API image를 pull하여 API와 Worker Pod를 실행하도록 구성하였다.

---

## 2. 이전 구조의 한계

기존 로컬 Kubernetes 배포에서는 다음 흐름을 사용하였다.

```text
로컬 PC에서 Docker image build
→ Docker Desktop Kubernetes node에 image import
→ Kubernetes Deployment에서 imagePullPolicy: Never 사용
→ 로컬 node에 존재하는 image로 Pod 실행
```

이 방식은 로컬 Kubernetes 검증에는 유용했다.

하지만 다음과 같은 한계가 있다.

```text
1. 로컬 PC에 image가 없으면 Pod를 실행할 수 없다.
2. Docker image를 매번 수동으로 build/import해야 한다.
3. 다른 Kubernetes 환경에서는 동일한 방식으로 재현하기 어렵다.
4. EKS 같은 실제 클러스터에서는 로컬 imagePullPolicy: Never 방식이 적합하지 않다.
5. ArgoCD가 GitOps 배포를 수행하더라도 image 자체는 여전히 로컬 환경에 의존한다.
```

즉, ArgoCD GitOps를 도입했더라도 image가 외부 registry에 있지 않으면 완전한 배포 자동화 구조라고 보기 어렵다.

---

## 3. 변경 후 구조

이번 변경 이후 FastPass의 image 배포 흐름은 다음과 같다.

```text
GitHub push
→ GitHub Actions 실행
→ Gradle build
→ Docker image build
→ GHCR image push
→ Helm Chart에서 GHCR image 참조
→ ArgoCD Sync
→ Kubernetes가 GHCR image pull
→ API / Worker Pod 실행
```

이제 Kubernetes는 로컬 node에 미리 import된 image가 아니라, GHCR에 저장된 image를 기준으로 Pod를 실행할 수 있다.

전체 구조는 다음과 같이 정리할 수 있다.

```text
Code
→ GitHub Actions CI
→ Docker Image
→ GHCR
→ Helm Chart
→ ArgoCD GitOps
→ Kubernetes
→ Prometheus/Grafana
→ Alertmanager
```

---

## 4. GHCR Image 정보

GitHub Actions를 통해 생성된 FastPass API image는 다음 주소로 push되었다.

```text
ghcr.io/kdh8128/fastpass-k8s-api
```

GHCR Package 화면에서 다음 정보를 확인하였다.

```text
Package name: fastpass-k8s-api
Visibility: Public
Tag: latest
Tag: commit SHA
```

예시 image pull 명령어는 다음과 같다.

```bash
docker pull ghcr.io/kdh8128/fastpass-k8s-api:latest
```

또는 특정 commit SHA tag를 사용할 수 있다.

```bash
docker pull ghcr.io/kdh8128/fastpass-k8s-api:<commit-sha>
```

이번 단계에서는 Helm Chart에서 `latest` tag를 사용하여 GHCR image 기반 배포를 검증하였다.

---

## 5. Helm values.yaml 수정

수정 대상 파일은 다음과 같다.

```text
deploy/helm/fastpass/values.yaml
```

기존 image 설정은 다음과 같았다.

```yaml
image:
  repository: fastpass-k8s-api
  tag: latest
  pullPolicy: Never
```

이 설정은 로컬 Docker Desktop Kubernetes에서 직접 build/import한 image를 사용할 때 적합하다.

이번 단계에서는 다음과 같이 변경하였다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: latest
  pullPolicy: IfNotPresent
```

변경 사항의 의미는 다음과 같다.

| 항목 | 기존 | 변경 후 | 의미 |
|---|---|---|---|
| `repository` | `fastpass-k8s-api` | `ghcr.io/kdh8128/fastpass-k8s-api` | 로컬 image에서 GHCR image로 변경 |
| `tag` | `latest` | `latest` | 최신 image tag 사용 |
| `pullPolicy` | `Never` | `IfNotPresent` | 로컬 image만 사용하지 않고 필요 시 registry에서 pull |

---

## 6. imagePullPolicy 변경 의미

기존 설정은 다음과 같았다.

```yaml
pullPolicy: Never
```

`Never`는 Kubernetes가 registry에서 image를 pull하지 않고, node에 이미 존재하는 image만 사용하도록 한다.

따라서 로컬 node에 image가 없으면 Pod 실행에 실패할 수 있다.

GHCR image를 사용하려면 registry에서 image를 pull할 수 있어야 하므로 다음과 같이 변경하였다.

```yaml
pullPolicy: IfNotPresent
```

`IfNotPresent`는 node에 image가 없으면 registry에서 image를 pull한다.

이를 통해 FastPass Pod는 GHCR에 저장된 image를 사용할 수 있다.

---

## 7. Helm Template 검증

values.yaml 수정 후 Helm template 결과를 먼저 확인하였다.

```bash
helm template fastpass-gitops deploy/helm/fastpass \
  --set namespace=fastpass-gitops \
  | grep -n "image:"
```

기대 결과는 다음과 같다.

```text
image: ghcr.io/kdh8128/fastpass-k8s-api:latest
```

imagePullPolicy도 함께 확인하였다.

```bash
helm template fastpass-gitops deploy/helm/fastpass \
  --set namespace=fastpass-gitops \
  | grep -A 5 -B 2 "ghcr.io"
```

기대 결과는 다음과 같다.

```yaml
image: ghcr.io/kdh8128/fastpass-k8s-api:latest
imagePullPolicy: IfNotPresent
```

이를 통해 Helm Chart가 GHCR image 기준으로 정상 렌더링되는 것을 확인하였다.

---

## 8. 변경 사항 Commit 및 Push

Helm values 수정 후 Git에 반영하였다.

```bash
git add deploy/helm/fastpass/values.yaml

git commit -m "feat: use ghcr image in helm chart"

git push
```

ArgoCD는 GitHub repository의 `main` branch를 바라보고 있으므로, push된 변경 사항을 Sync 대상으로 인식한다.

---

## 9. ArgoCD Sync 확인

변경 사항 push 후 ArgoCD Application 상태를 확인하였다.

```bash
kubectl get application fastpass-gitops -n argocd
```

기대 상태는 다음과 같다.

```text
NAME              SYNC STATUS   HEALTH STATUS
fastpass-gitops   Synced        Healthy
```

자동 Sync가 바로 반영되지 않을 경우, 다음 명령어로 hard refresh를 수행할 수 있다.

```bash
kubectl annotate application fastpass-gitops -n argocd \
  argocd.argoproj.io/refresh=hard \
  --overwrite
```

또는 ArgoCD UI에서 직접 Sync를 수행할 수 있다.

이번 단계에서는 ArgoCD가 변경된 Helm Chart를 Sync하여, `fastpass-gitops` namespace의 Deployment가 GHCR image를 사용하도록 반영하였다.

---

## 10. Pod Rollout 확인

ArgoCD Sync 이후 API와 Worker Deployment의 rollout 상태를 확인하였다.

```bash
kubectl rollout status deployment/fastpass-api -n fastpass-gitops
kubectl rollout status deployment/fastpass-worker -n fastpass-gitops
```

정상적으로 rollout이 완료되면 다음과 같은 메시지가 출력된다.

```text
deployment "fastpass-api" successfully rolled out
deployment "fastpass-worker" successfully rolled out
```

Pod 상태도 확인하였다.

```bash
kubectl get pods -n fastpass-gitops
```

기대 상태는 다음과 같다.

```text
fastpass-api       1/1 Running
fastpass-worker    1/1 Running
postgres           1/1 Running
redis              1/1 Running
```

HPA가 적용되어 있는 경우 API와 Worker Pod는 CPU 사용률에 따라 1개 이상으로 유지될 수 있다.

예를 들어 HPA가 scale-out한 경우 다음과 같이 API와 Worker가 각각 3개까지 증가할 수 있다.

```text
fastpass-api       3 pods Running
fastpass-worker    3 pods Running
```

이는 HPA에 의해 관리되는 정상 동작이다.

---

## 11. Deployment Image 확인

실제 Kubernetes Deployment가 GHCR image를 사용하고 있는지 확인하였다.

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

이를 통해 API와 Worker Deployment가 더 이상 로컬 image인 `fastpass-k8s-api:latest`를 사용하지 않고, GHCR image인 `ghcr.io/kdh8128/fastpass-k8s-api:latest`를 사용하고 있음을 확인하였다.

---

## 12. API Health Check 검증

GHCR image 기반으로 재배포된 API가 정상 동작하는지 확인하기 위해 port-forward를 수행하였다.

```bash
kubectl port-forward -n fastpass-gitops service/fastpass-api 18083:8080
```

다른 터미널에서 health endpoint를 호출하였다.

```bash
curl http://localhost:18083/actuator/health
```

정상 응답은 다음과 같다.

```json
{"status":"UP","groups":["liveness","readiness"]}
```

readiness endpoint도 확인할 수 있다.

```bash
curl http://localhost:18083/actuator/health/readiness
```

정상 응답은 다음과 같다.

```json
{"status":"UP"}
```

liveness endpoint도 확인할 수 있다.

```bash
curl http://localhost:18083/actuator/health/liveness
```

정상 응답은 다음과 같다.

```json
{"status":"UP"}
```

이를 통해 GHCR image로 실행된 FastPass API가 정상적으로 기동되었음을 확인하였다.

---

## 13. Queue API 확인

API와 Worker가 정상적으로 연동되는지 간단히 확인하기 위해 Queue size endpoint를 호출하였다.

```bash
curl http://localhost:18083/api/queue/applications/size
```

정상 응답 예시는 다음과 같다.

```json
{"size":0}
```

이는 API가 정상 응답하고 있으며, Redis Queue 조회도 정상적으로 수행됨을 의미한다.

필요한 경우 이벤트 생성과 신청 요청까지 수행하여 Worker 처리까지 검증할 수 있다.

```bash
EVENT_ID=$(curl -s -X POST http://localhost:18083/api/events \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"title\":\"GHCR ArgoCD Test\",\
\"description\":\"ghcr image deployment validation\",\
\"capacity\":3,\
\"eventStartAt\":\"2026-07-20T10:00:00\"}" \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')

echo $EVENT_ID
```

신청 요청은 다음과 같이 수행할 수 있다.

```bash
curl -X POST http://localhost:18083/api/events/${EVENT_ID}/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"ghcr-user-1\"}"
```

Worker 처리 후 Queue size는 다시 0이 된다.

```bash
curl http://localhost:18083/api/queue/applications/size
```

---

## 14. Prometheus 수집 확인

GHCR image로 재배포된 이후에도 Prometheus 수집이 정상적으로 유지되는지 확인할 수 있다.

Prometheus UI에서 다음 PromQL을 실행한다.

```promql
up{namespace="fastpass-gitops"}
```

API와 Worker target이 `1`이면 정상이다.

FastPass custom metric도 확인할 수 있다.

```promql
max(fastpass_queue_size{namespace="fastpass-gitops"})
```

이 값이 조회되면 GHCR image 기반으로 실행된 API와 Worker에서도 Actuator Prometheus endpoint가 정상적으로 노출되고 있음을 의미한다.

---

## 15. 이번 단계에서 확인한 내용

이번 단계에서 확인한 내용은 다음과 같다.

```text
1. Helm Chart의 image.repository를 GHCR image로 변경하였다.
2. imagePullPolicy를 Never에서 IfNotPresent로 변경하였다.
3. Helm template 결과에서 GHCR image가 렌더링되는 것을 확인하였다.
4. 변경 사항을 GitHub repository에 push하였다.
5. ArgoCD가 변경된 Helm Chart를 Sync하였다.
6. fastpass-gitops namespace의 API/Worker Deployment가 GHCR image를 사용하도록 변경되었다.
7. API와 Worker Pod가 정상 Running 상태가 되었다.
8. health/readiness/liveness endpoint가 정상 응답하였다.
9. Queue API가 정상 응답하였다.
10. Prometheus metric 수집도 유지되는 것을 확인하였다.
```

---

## 16. 기존 구조와 변경 후 구조 비교

### 기존 구조

```text
로컬 Docker build
→ Docker Desktop Kubernetes node에 image import
→ imagePullPolicy: Never
→ Kubernetes가 로컬 image 사용
```

기존 image 설정은 다음과 같았다.

```yaml
image:
  repository: fastpass-k8s-api
  tag: latest
  pullPolicy: Never
```

### 변경 후 구조

```text
GitHub Actions
→ Docker image build
→ GHCR image push
→ Helm Chart에서 GHCR image 참조
→ ArgoCD Sync
→ Kubernetes가 GHCR image pull
```

변경 후 image 설정은 다음과 같다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: latest
  pullPolicy: IfNotPresent
```

이 변경으로 인해 FastPass는 로컬 image import에 의존하지 않고, container registry 기반으로 배포할 수 있는 구조가 되었다.

---

## 17. 포트폴리오 관점의 의미

이번 단계는 DevOps/Cloud 포트폴리오 관점에서 중요하다.

이전에는 Kubernetes와 ArgoCD를 사용하더라도 image가 로컬 환경에 묶여 있었다.

```text
ArgoCD는 Helm Chart를 Sync하지만,
Pod image는 로컬 Docker Desktop Kubernetes node에 존재해야 실행 가능
```

이번 단계 이후에는 image가 GHCR에 저장되고, Helm Chart가 해당 image를 참조한다.

```text
ArgoCD는 Git의 Helm Chart를 Sync하고,
Kubernetes는 GHCR image를 pull하여 Pod 실행
```

따라서 FastPass는 다음 흐름을 갖게 되었다.

```text
Code
→ CI
→ Image Registry
→ GitOps
→ Kubernetes Runtime
→ Monitoring
→ Alerting
```

이는 실제 운영 환경에서 사용하는 CI/CD 구조에 더 가까운 형태이다.

특히 EKS와 같은 managed Kubernetes 환경으로 확장할 때도, container registry 기반 배포는 필수적인 구조이다.

---

## 18. 주의점: latest tag와 IfNotPresent

이번 단계에서는 검증 편의를 위해 `latest` tag를 사용하였다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: latest
  pullPolicy: IfNotPresent
```

다만 운영 관점에서는 `latest` tag만 사용하는 방식에는 한계가 있다.

```text
1. latest가 정확히 어떤 commit의 image인지 명확하지 않다.
2. 같은 latest tag가 새 image로 갱신되어도 Git manifest는 바뀌지 않는다.
3. ArgoCD는 Git의 desired state 변경을 기준으로 Sync하므로, image tag가 그대로라면 새 image push만으로는 배포 변경을 감지하기 어렵다.
4. imagePullPolicy가 IfNotPresent이면 node에 latest image가 이미 존재할 경우 새 image를 pull하지 않을 수 있다.
```

따라서 이후 단계에서는 commit SHA tag 기반 배포를 고려할 수 있다.

예시는 다음과 같다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: 834b63cde03fd9215fa18eb7d717c83d3400bf97
  pullPolicy: IfNotPresent
```

이 방식은 image와 source code commit을 명확히 연결할 수 있다는 장점이 있다.

---

## 19. 현재 한계점

이번 단계에서는 Helm Chart와 ArgoCD가 GHCR image를 사용하도록 연결하였다.

다만 아직 다음 기능은 구현하지 않았다.

```text
commit SHA tag 기반 자동 배포
Helm values.yaml image.tag 자동 업데이트
ArgoCD Image Updater
GitHub Actions에서 배포 manifest 자동 변경
ArgoCD sync webhook
배포 실패 시 자동 rollback
image vulnerability scan
multi-architecture image build
branch별 dev/staging/prod image tag 전략
```

현재 구조는 다음 수준까지 완성되었다.

```text
GitHub Actions가 GHCR에 image push
Helm Chart가 GHCR image 참조
ArgoCD가 Helm Chart Sync
Kubernetes가 GHCR image로 Pod 실행
```

하지만 새 image가 push될 때마다 자동으로 Helm values의 tag가 바뀌는 구조는 아직 아니다.

---

## 20. 다음 단계

다음 단계는 프로젝트 마무리 방향에 따라 두 가지로 나눌 수 있다.

### 20.1 CI/CD 고도화

더 깊게 확장하려면 다음을 구현할 수 있다.

```text
1. GitHub Actions에서 commit SHA tag로 image push
2. Helm values.yaml의 image.tag를 commit SHA로 자동 업데이트
3. 변경된 values.yaml을 GitHub Actions가 commit/push
4. ArgoCD가 Git 변경을 감지하고 자동 Sync
5. Kubernetes가 특정 commit SHA image로 재배포
```

이렇게 하면 다음 흐름이 완성된다.

```text
git push
→ GitHub Actions build/test
→ Docker image push
→ Helm values image tag update
→ Git push
→ ArgoCD Sync
→ Kubernetes deployment
```

### 20.2 포트폴리오 정리

현재까지 구현한 범위만으로도 다음 흐름은 충분히 설명할 수 있다.

```text
Spring Boot API
Redis Queue Worker
Docker Compose
Kubernetes
HPA
Prometheus/Grafana
Alertmanager
Helm
ArgoCD
GitHub Actions CI
GHCR
```

따라서 다음 작업으로는 다음을 정리할 수 있다.

```text
1. README 최종 정리
2. 전체 아키텍처 다이어그램 작성
3. docs index 정리
4. 트러블슈팅 모음 정리
5. 면접용 프로젝트 설명 문장 정리
6. 향후 확장 방향 정리
```

---

## 21. 결론

이번 단계에서는 FastPass의 Kubernetes 배포가 GHCR image를 사용하도록 변경하였다.

기존에는 로컬 Docker image와 `imagePullPolicy: Never`에 의존하여 Kubernetes Pod를 실행하였다.

이번 변경을 통해 Helm Chart의 image 설정을 `ghcr.io/kdh8128/fastpass-k8s-api:latest`로 변경하고, `imagePullPolicy`를 `IfNotPresent`로 설정하였다.

이후 ArgoCD Sync를 통해 `fastpass-gitops` namespace의 API와 Worker Deployment가 GHCR image를 사용하도록 반영하였다.

검증 결과 API와 Worker Pod가 정상 Running 상태가 되었고, health endpoint와 Queue API도 정상 응답하였다.

이를 통해 FastPass는 로컬 image 기반 배포에서 container registry 기반 배포 구조로 전환되었다.

결과적으로 FastPass의 DevOps 흐름은 다음과 같이 확장되었다.

```text
Code
→ GitHub Actions CI
→ Docker image build
→ GHCR push
→ Helm Chart
→ ArgoCD GitOps
→ Kubernetes deployment
→ Monitoring
→ Alerting
```

이 단계까지 완료하면서 FastPass는 로컬 개발 환경에만 의존하지 않고, GitHub Actions와 GHCR을 활용한 container image 배포 기반을 갖추게 되었다.