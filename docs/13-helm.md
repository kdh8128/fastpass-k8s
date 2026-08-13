# FastPass Helm Chart 검증

## 1. 목적

이번 단계의 목적은 기존 raw Kubernetes YAML로 관리하던
FastPass 배포 리소스를 Helm Chart로 패키징하는 것이다.

이전까지 FastPass는 다음 방식으로 Kubernetes에 배포하였다.

```bash
kubectl apply -f deploy/k8s/
```

이 방식은 초기 검증에는 단순하고 명확하지만, 리소스가 많아질수록
설정값을 일관되게 관리하기 어렵다.

FastPass에는 다음과 같은 Kubernetes 리소스가 포함되어 있다.

```text
Namespace
PostgreSQL Secret
PostgreSQL PVC
PostgreSQL Deployment
PostgreSQL Service
Redis Deployment
Redis Service
API ConfigMap
API Deployment
API Service
Worker Deployment
Worker Service
API HPA
Worker HPA
ServiceMonitor
PrometheusRule
```

따라서 이번 단계에서는 이 리소스들을 Helm Chart로 구성하여,
배포 구조를 템플릿화하고 설정값을 `values.yaml`에서 관리할 수 있도록
구성하였다.

---

## 2. Helm 도입 이유

초기 Kubernetes 배포에서는 raw YAML을 직접 작성하고 적용하였다.

```bash
kubectl apply -f deploy/k8s/
```

하지만 운영 환경을 가정하면 다음과 같은 문제가 생길 수 있다.

```text
1. image tag를 여러 YAML에서 반복 수정해야 한다.
2. namespace 변경 시 여러 파일을 수정해야 한다.
3. replica 수와 resource 설정을 일관되게 관리하기 어렵다.
4. monitoring, alerting 설정을 환경별로 켜고 끄기 어렵다.
5. GitOps 도구와 연동할 때 배포 단위가 명확하지 않다.
```

Helm을 사용하면 여러 Kubernetes 리소스를 하나의 Chart로 패키징하고,
환경별 설정값을 `values.yaml`에서 관리할 수 있다.

Helm 도입 후에는 다음과 같은 방식으로 배포할 수 있다.

```bash
helm install fastpass deploy/helm/fastpass
```

업데이트는 다음과 같이 수행할 수 있다.

```bash
helm upgrade fastpass deploy/helm/fastpass
```

삭제는 다음과 같이 수행할 수 있다.

```bash
helm uninstall fastpass
```

---

## 3. Helm Chart 구조

Helm Chart는 다음 경로에 생성하였다.

```text
deploy/helm/fastpass
```

최종 구조는 다음과 같다.

```text
deploy/helm/fastpass/
├── Chart.yaml
├── values.yaml
└── templates/
    ├── namespace.yaml
    ├── api-configmap.yaml
    ├── api-deployment.yaml
    ├── api-hpa.yaml
    ├── api-service.yaml
    ├── fastpass-prometheusrule.yaml
    ├── fastpass-servicemonitor.yaml
    ├── postgres.yaml
    ├── redis.yaml
    ├── worker-deployment.yaml
    ├── worker-hpa.yaml
    └── worker-service.yaml
```

기존 raw Kubernetes YAML은 다음 경로에 그대로 유지하였다.

```text
deploy/k8s/
```

이를 통해 다음과 같은 구조를 갖게 되었다.

```text
deploy/k8s/       초기 raw Kubernetes YAML
deploy/helm/      Helm Chart 기반 배포 템플릿
```

포트폴리오 관점에서는 다음 흐름을 보여줄 수 있다.

```text
1. raw Kubernetes YAML로 배포 구조를 직접 검증
2. 리소스가 증가한 뒤 Helm Chart로 배포 구조를 템플릿화
3. values.yaml을 통해 환경별 설정값 관리
```

---

## 4. Chart.yaml

`Chart.yaml`은 Helm Chart의 메타 정보를 정의한다.

파일 위치는 다음과 같다.

```text
deploy/helm/fastpass/Chart.yaml
```

작성한 내용은 다음과 같다.

```yaml
apiVersion: v2
name: fastpass
description: A Helm chart for FastPass Kubernetes deployment
type: application
version: 0.1.0
appVersion: "1.0.0"
```

각 항목의 의미는 다음과 같다.

| 항목 | 의미 |
|---|---|
| `apiVersion` | Helm Chart API 버전 |
| `name` | Chart 이름 |
| `description` | Chart 설명 |
| `type` | application chart 여부 |
| `version` | Chart 자체의 버전 |
| `appVersion` | FastPass 애플리케이션 버전 |

---

## 5. values.yaml

`values.yaml`에는 환경에 따라 변경될 수 있는 설정값을 정의하였다.

파일 위치는 다음과 같다.

```text
deploy/helm/fastpass/values.yaml
```

주요 설정 항목은 다음과 같다.

```yaml
namespace: fastpass

image:
  repository: fastpass-k8s-api
  tag: latest
  pullPolicy: Never

api:
  replicas: 1
  port: 8080
  workerEnabled: false

worker:
  replicas: 1
  port: 8080
  workerEnabled: true

postgres:
  image: postgres:16
  database: fastpass
  username: fastpass
  password: fastpass
  port: 5432
  storage: 1Gi

redis:
  image: redis:7.2-alpine
  port: 6379

resources:
  api:
    requests:
      cpu: 10m
      memory: 256Mi
    limits:
      cpu: 500m
      memory: 768Mi
  worker:
    requests:
      cpu: 10m
      memory: 256Mi
    limits:
      cpu: 500m
      memory: 768Mi

hpa:
  enabled: true
  minReplicas: 1
  maxReplicas: 3
  cpuAverageUtilization: 50

monitoring:
  enabled: true
  namespace: monitoring
  releaseLabel: prometheus-stack

alerting:
  enabled: true
  queueBacklogThreshold: 50
```

이번 Helm Chart에서는 다음 설정을 `values.yaml`로 분리하였다.

```text
namespace
image repository
image tag
imagePullPolicy
API replica 수
Worker replica 수
PostgreSQL 설정
Redis 설정
resource requests / limits
HPA 설정
monitoring 활성화 여부
alerting 활성화 여부
Queue backlog alert threshold
```

---

## 6. Helm Template 적용 방식

기존 raw YAML에서 고정되어 있던 값은 Helm template 문법으로 변경하였다.

예를 들어 namespace는 기존에 다음과 같이 고정되어 있었다.

```yaml
namespace: fastpass
```

Helm Chart에서는 다음과 같이 변경하였다.

```yaml
namespace: {{ .Values.namespace }}
```

image도 기존에는 다음과 같이 고정되어 있었다.

```yaml
image: fastpass-k8s-api:latest
```

Helm Chart에서는 다음과 같이 변경하였다.

```yaml
image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
```

image pull policy도 `values.yaml`에서 관리하도록 변경하였다.

```yaml
imagePullPolicy: {{ .Values.image.pullPolicy }}
```

이를 통해 image tag, namespace, replica, resource, monitoring 설정을
하나의 `values.yaml`에서 일관되게 관리할 수 있게 되었다.

---

## 7. API와 Worker 분리 설정

FastPass는 동일한 Spring Boot image를 API와 Worker가 함께 사용한다.

대신 환경변수 `FASTPASS_WORKER_ENABLED` 값에 따라 API 역할과 Worker 역할을
분리하였다.

API Deployment에서는 Worker를 비활성화하였다.

```yaml
env:
  - name: FASTPASS_WORKER_ENABLED
    value: "false"
```

Worker Deployment에서는 Worker를 활성화하였다.

```yaml
env:
  - name: FASTPASS_WORKER_ENABLED
    value: "true"
```

Helm Chart에서는 이 값을 `values.yaml`에서 관리하도록 구성하였다.

```yaml
api:
  workerEnabled: false

worker:
  workerEnabled: true
```

이를 통해 동일한 Docker image를 사용하면서도 Kubernetes Deployment를
API와 Worker로 분리할 수 있다.

---

## 8. HPA 설정

API와 Worker에는 각각 HPA를 적용하였다.

FastPass의 HPA 설정은 다음 기준을 사용한다.

```yaml
hpa:
  enabled: true
  minReplicas: 1
  maxReplicas: 3
  cpuAverageUtilization: 50
```

HPA target CPU는 50%로 유지하였다.

로컬 테스트에서 CPU target을 너무 낮게 설정하면 scale-up은 쉽게 확인할 수
있지만, scale-down 검증이 불안정할 수 있다.

따라서 Helm Chart 단계에서는 다음 기준을 사용하였다.

```text
CPU average utilization target: 50%
minReplicas: 1
maxReplicas: 3
```

---

## 9. ServiceMonitor 템플릿화

FastPass API와 Worker metric을 Prometheus가 수집할 수 있도록
ServiceMonitor를 Helm Chart에 포함하였다.

ServiceMonitor는 `monitoring` namespace에 생성되며, 실제 scrape 대상
namespace는 `values.yaml`의 `namespace` 값을 사용한다.

핵심 설정은 다음과 같다.

```yaml
metadata:
  name: {{ .Release.Name }}-servicemonitor
  namespace: {{ .Values.monitoring.namespace }}
  labels:
    release: {{ .Values.monitoring.releaseLabel }}
```

namespaceSelector는 다음과 같이 템플릿화하였다.

```yaml
namespaceSelector:
  matchNames:
    - {{ .Values.namespace }}
```

이를 통해 Helm 설치 시 namespace를 변경해도 ServiceMonitor가 올바른
namespace의 API와 Worker를 바라보도록 구성하였다.

예를 들어 다음과 같이 설치하면:

```bash
helm install fastpass-helm deploy/helm/fastpass \
  --set namespace=fastpass-helm
```

ServiceMonitor는 다음 namespace를 scrape 대상으로 사용한다.

```yaml
namespaceSelector:
  matchNames:
    - fastpass-helm
```

---

## 10. PrometheusRule 템플릿화

FastPass alert rule도 Helm Chart에 포함하였다.

PrometheusRule은 `monitoring` namespace에 생성되며, PromQL 내부의
namespace 조건도 `values.yaml` 값을 사용하도록 변경하였다.

Queue backlog alert는 다음과 같이 템플릿화하였다.

```yaml
expr: >
  max(
    fastpass_queue_size{
      namespace="{{ .Values.namespace }}"
    }
  ) > {{ .Values.alerting.queueBacklogThreshold }}
```

Worker failure alert도 다음과 같이 namespace를 템플릿화하였다.

```yaml
expr: >
  sum(
    increase(
      fastpass_worker_processing_failed_total{
        namespace="{{ .Values.namespace }}",
        job="fastpass-worker"
      }[5m]
    )
  ) > 0
```

Target down alert도 다음과 같이 구성하였다.

```yaml
expr: min(up{namespace="{{ .Values.namespace }}"}) by (job) == 0
```

이를 통해 Helm Chart를 다른 namespace에 설치해도 alert rule이 해당
namespace의 metric을 바라보도록 구성하였다.

---

## 11. 리소스 이름 충돌 방지

Helm 테스트 설치는 기존 `fastpass` namespace와 충돌하지 않도록
`fastpass-helm` namespace에 수행하였다.

다만 ServiceMonitor와 PrometheusRule은 `monitoring` namespace에 생성되기
때문에 기존 리소스와 이름이 충돌할 수 있다.

이를 방지하기 위해 ServiceMonitor와 PrometheusRule 이름은 Helm release
name을 기반으로 생성되도록 구성하였다.

```yaml
name: {{ .Release.Name }}-servicemonitor
```

```yaml
name: {{ .Release.Name }}-prometheusrule
```

따라서 release name이 `fastpass-helm`이면 다음 리소스가 생성된다.

```text
fastpass-helm-servicemonitor
fastpass-helm-prometheusrule
```

기존 raw YAML로 적용한 리소스와 충돌하지 않는다.

```text
fastpass-servicemonitor
fastpass-prometheusrule
```

---

## 12. Helm Lint 검증

Chart 문법 검증을 위해 `helm lint`를 실행하였다.

```bash
helm lint deploy/helm/fastpass
```

결과는 다음과 같다.

```text
==> Linting deploy/helm/fastpass
[INFO] Chart.yaml: icon is recommended

1 chart(s) linted, 0 chart(s) failed
```

`icon is recommended`는 권장 사항일 뿐이며 오류는 아니다.

따라서 Helm Chart 문법 검증은 통과하였다.

---

## 13. Helm Template 검증

실제 Kubernetes에 적용하기 전에 `helm template` 명령어로 렌더링 결과를
확인하였다.

```bash
helm template fastpass deploy/helm/fastpass
```

렌더링 결과 다음 리소스들이 정상적으로 생성되는 것을 확인하였다.

```text
Namespace
Secret
ConfigMap
PersistentVolumeClaim
Service
Deployment
HorizontalPodAutoscaler
PrometheusRule
ServiceMonitor
```

---

## 14. Namespace Override 검증

Helm Chart가 다른 namespace에도 배포 가능한지 확인하기 위해
`namespace` 값을 override하여 렌더링하였다.

```bash
helm template fastpass deploy/helm/fastpass \
  --set namespace=fastpass-helm \
  > fastpass-rendered-test.yaml
```

이후 다음 명령어로 `fastpass-helm` namespace가 반영되었는지 확인하였다.

```bash
grep -n "fastpass-helm" fastpass-rendered-test.yaml
```

결과적으로 다음 항목들이 모두 `fastpass-helm`으로 렌더링되는 것을
확인하였다.

```text
Kubernetes resource namespace
ServiceMonitor namespaceSelector
PrometheusRule PromQL namespace condition
```

PrometheusRule의 PromQL도 다음과 같이 변경되었다.

```text
namespace="fastpass-helm"
```

ServiceMonitor의 namespaceSelector도 다음과 같이 변경되었다.

```yaml
namespaceSelector:
  matchNames:
    - fastpass-helm
```

이를 통해 `values.yaml` 기반 namespace override가 정상적으로 동작함을
확인하였다.

---

## 15. Release Name 기반 리소스명 검증

ServiceMonitor와 PrometheusRule이 release name을 기반으로 생성되는지도
확인하였다.

다음 명령어로 release name을 `fastpass-helm`으로 지정해 렌더링하였다.

```bash
helm template fastpass-helm deploy/helm/fastpass \
  --set namespace=fastpass-helm \
  > fastpass-rendered-test.yaml
```

이후 다음 명령어로 리소스명을 확인하였다.

```bash
grep -n "servicemonitor" fastpass-rendered-test.yaml
grep -n "prometheusrule" fastpass-rendered-test.yaml
```

release name을 기반으로 다음 이름이 생성되는 것을 확인하였다.

```text
fastpass-helm-servicemonitor
fastpass-helm-prometheusrule
```

이를 통해 기존 `fastpass-servicemonitor`,
`fastpass-prometheusrule`과 충돌하지 않도록 구성되었음을 확인하였다.

---

## 16. Helm Install 검증

기존 `fastpass` namespace와 충돌하지 않도록 Helm 테스트 설치는
`fastpass-helm` namespace에 수행하였다.

```bash
helm install fastpass-helm deploy/helm/fastpass \
  --set namespace=fastpass-helm
```

설치 후 Helm release 상태를 확인하였다.

```bash
helm list
```

결과는 다음과 같다.

```text
NAME            NAMESPACE   REVISION   STATUS     CHART            APP VERSION
fastpass-helm   default     1          deployed   fastpass-0.1.0   1.0.0
```

Helm release 기록은 `default` namespace에 생성되었고,
실제 FastPass 리소스는 `values.yaml`의 namespace override에 따라
`fastpass-helm` namespace에 생성되었다.

---

## 17. Kubernetes 리소스 확인

Helm 설치 후 `fastpass-helm` namespace의 Pod 상태를 확인하였다.

```bash
kubectl get pods -n fastpass-helm
```

결과는 다음과 같다.

```text
NAME                               READY   STATUS    RESTARTS   AGE
fastpass-api-6cdd676775-ppx6s      1/1     Running   0          70s
fastpass-worker-5574bcd5b6-fwtff   1/1     Running   0          70s
postgres-85d56865bf-8mp79          1/1     Running   0          70s
redis-b67748d94-lshvx              1/1     Running   0          70s
```

API, Worker, PostgreSQL, Redis가 모두 정상적으로 Running 상태가 된 것을
확인하였다.

Service도 정상적으로 생성되었다.

```bash
kubectl get svc -n fastpass-helm
```

결과는 다음과 같다.

```text
NAME              TYPE        PORT(S)
fastpass-api      ClusterIP   8080/TCP
fastpass-worker   ClusterIP   8080/TCP
postgres          ClusterIP   5432/TCP
redis             ClusterIP   6379/TCP
```

HPA도 정상적으로 생성되었다.

```bash
kubectl get hpa -n fastpass-helm
```

결과는 다음과 같다.

```text
NAME                  REFERENCE                    TARGETS
fastpass-api-hpa      Deployment/fastpass-api      cpu: <unknown>/50%
fastpass-worker-hpa   Deployment/fastpass-worker   cpu: <unknown>/50%
```

설치 직후에는 metrics-server가 CPU 값을 아직 수집하지 못해
`<unknown>`으로 보일 수 있다.

이는 초기 상태에서 발생할 수 있으며, 일정 시간이 지나 metric이 수집되면
HPA target 값이 표시된다.

---

## 18. API Health 검증

Helm으로 배포한 FastPass API에 접근하기 위해 port-forward를 실행하였다.

```bash
kubectl port-forward -n fastpass-helm service/fastpass-api 18081:8080
```

다른 터미널에서 health endpoint를 호출하였다.

```bash
curl http://localhost:18081/actuator/health
```

응답은 다음과 같았다.

```json
{"status":"UP","groups":["liveness","readiness"]}
```

이를 통해 Helm으로 배포한 API Pod가 정상적으로 요청을 처리할 수 있음을
확인하였다.

---

## 19. API 기능 검증

Helm으로 배포한 FastPass에 테스트 이벤트를 생성하였다.

```bash
EVENT_ID=$(curl -s -X POST http://localhost:18081/api/events \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"title\":\"Helm Test Event\",\
\"description\":\"helm deployment test\",\
\"capacity\":3,\
\"eventStartAt\":\"2026-07-20T10:00:00\"}" \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')

echo $EVENT_ID
```

결과는 다음과 같았다.

```text
1
```

생성된 이벤트에 신청 요청을 보냈다.

```bash
curl -X POST http://localhost:18081/api/events/${EVENT_ID}/apply \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"applicantName\":\"helm-user-1\"}"
```

응답은 다음과 같았다.

```json
{
  "applicationId": 1,
  "eventId": 1,
  "applicantName": "helm-user-1",
  "status": "PENDING",
  "createdAt": "2026-08-13T11:10:29.167336781"
}
```

신청 요청은 Redis Queue에 적재된 후 Worker에 의해 처리되었다.

Queue size를 확인하였다.

```bash
curl http://localhost:18081/api/queue/applications/size
```

응답은 다음과 같았다.

```json
{"size":0}
```

이를 통해 Helm으로 배포한 API와 Worker가 정상적으로 연동되는 것을
확인하였다.

---

## 20. Prometheus 수집 검증

Helm으로 배포한 `fastpass-helm` namespace의 metric이 Prometheus에서
수집되는지 확인하였다.

Prometheus에서 다음 PromQL을 실행하였다.

```promql
up{namespace="fastpass-helm"}
```

FastPass API와 Worker target이 조회되었고, 값이 모두 `1`로 표시되었다.

```text
up{namespace="fastpass-helm"} = 1
```

이를 통해 Helm Chart에 포함된 ServiceMonitor가 정상적으로 동작하고,
Prometheus가 Helm으로 배포한 API와 Worker metric을 수집하고 있음을
확인하였다.

---

## 21. Helm 테스트 리소스 정리

Helm 설치 검증이 끝난 뒤 테스트 release를 삭제하였다.

```bash
helm uninstall fastpass-helm
kubectl delete namespace fastpass-helm
```

결과는 다음과 같았다.

```text
release "fastpass-helm" uninstalled
namespace "fastpass-helm" deleted
```

이후 monitoring namespace에 남아 있는 FastPass 관련 리소스를 확인하였다.

```bash
kubectl get servicemonitor -n monitoring | grep fastpass
kubectl get prometheusrule -n monitoring | grep fastpass
```

결과는 다음과 같았다.

```text
fastpass-servicemonitor
fastpass-prometheusrule
```

이는 기존 raw Kubernetes YAML로 적용한 원래 모니터링 리소스이다.

Helm 테스트 release에서 생성된 `fastpass-helm-servicemonitor`와
`fastpass-helm-prometheusrule`은 uninstall 과정에서 정상적으로 삭제되었다.

---

## 22. 검증 결과 요약

이번 Helm Chart 단계에서 확인한 내용은 다음과 같다.

```text
1. deploy/helm/fastpass Helm Chart 구조를 생성하였다.
2. 기존 Kubernetes 리소스를 Helm templates로 변환하였다.
3. values.yaml을 통해 주요 설정값을 관리하도록 구성하였다.
4. helm lint 검증을 통과하였다.
5. helm template 렌더링을 성공적으로 확인하였다.
6. namespace override가 정상적으로 동작하는 것을 확인하였다.
7. ServiceMonitor namespaceSelector가 override namespace를 바라보는 것을 확인하였다.
8. PrometheusRule PromQL namespace 조건이 override namespace로 변경되는 것을 확인하였다.
9. 별도 namespace인 fastpass-helm에 Helm Chart를 설치하였다.
10. API, Worker, PostgreSQL, Redis Pod가 정상 Running 상태가 되었다.
11. API health endpoint가 UP 상태를 반환하였다.
12. 이벤트 생성과 신청 요청이 정상 동작하였다.
13. Worker가 Queue를 처리하여 Queue size가 0이 되는 것을 확인하였다.
14. Prometheus가 fastpass-helm namespace의 API/Worker metric을 수집하였다.
15. Helm 테스트 release와 namespace를 정상적으로 삭제하였다.
```

---

## 23. 한계점

이번 단계에서는 로컬 Kubernetes 환경에서 Helm Chart를 작성하고
설치 검증을 수행하였다.

다만 아직 다음 기능은 포함하지 않았다.

```text
환경별 values 파일 분리
values-local.yaml
values-dev.yaml
values-prod.yaml
Ingress 설정
외부 PostgreSQL 사용 옵션
Secret 외부 주입 방식
Helm chart dependency 관리
ArgoCD Application 연동
OCI registry 기반 chart 배포
```

현재 Helm Chart는 로컬 Kubernetes 검증을 위한 형태이다.

향후 EKS 또는 운영 환경으로 확장할 경우, 환경별 values 파일을 분리하고
외부 DB, Ingress, TLS, image registry 설정을 추가할 수 있다.

---

## 24. 다음 단계

다음 단계에서는 Helm Chart를 기반으로 ArgoCD GitOps 배포를 구성한다.

현재는 Helm Chart를 다음 명령어로 직접 설치하였다.

```bash
helm install fastpass-helm deploy/helm/fastpass \
  --set namespace=fastpass-helm
```

GitOps 단계에서는 ArgoCD가 GitHub repository의 Helm Chart를 바라보도록
구성한다.

목표는 다음과 같다.

```text
1. ArgoCD 설치
2. FastPass ArgoCD Application 작성
3. GitHub repository의 deploy/helm/fastpass Chart 연결
4. ArgoCD를 통한 FastPass 배포
5. Git 변경 사항이 ArgoCD에 의해 동기화되는지 확인
```

이를 통해 배포 방식은 다음 구조로 발전한다.

```text
kubectl apply
→ Helm install
→ ArgoCD GitOps sync
```

---

## 25. 결론

이번 단계에서는 FastPass Kubernetes 리소스를 Helm Chart로 패키징하였다.

기존 raw YAML로 관리하던 API, Worker, PostgreSQL, Redis, HPA,
ServiceMonitor, PrometheusRule을 Helm templates로 변환하였다.

반복되거나 환경에 따라 변경될 수 있는 값은 `values.yaml`로 분리하였다.

또한 `namespace` 값을 override하여 별도 namespace인 `fastpass-helm`에
설치하는 방식으로 Helm Chart를 검증하였다.

검증 결과 API, Worker, PostgreSQL, Redis가 정상적으로 Running 상태가
되었고, API health check, 이벤트 생성, 신청 요청, Queue 처리까지 모두
정상 동작하였다.

Prometheus에서도 `fastpass-helm` namespace의 API와 Worker metric이
정상적으로 수집되는 것을 확인하였다.

이를 통해 FastPass는 raw Kubernetes YAML 기반 배포에서 Helm Chart 기반
배포로 발전하였으며, 이후 ArgoCD GitOps 배포로 확장할 수 있는 기반을
갖추게 되었다.