# FastPass Alerting 검증

## 1. 목적

이번 단계의 목적은 FastPass 서비스의 주요 장애 상황을
Prometheus Alert Rule로 감지하고, Alertmanager까지 전달되는지
검증하는 것이다.

이전 단계에서는 Prometheus/Grafana를 통해 FastPass의 기본 metric과
custom metric을 시각화하였다.

이번 단계에서는 관측 가능한 지표를 기반으로 실제 운영에서 문제가
될 수 있는 상황을 alert로 정의하였다.

주요 alert 대상은 다음과 같다.

```text
1. Redis Queue backlog 증가
2. Worker 처리 실패 발생
3. FastPass target down
```

이를 통해 단순히 metric을 보는 수준을 넘어서, 장애 징후를 자동으로
감지할 수 있는 구조를 구성하였다.

---

## 2. Alerting 대상

FastPass는 API와 Worker가 분리된 구조로 동작한다.

API는 사용자의 신청 요청을 받아 Redis Queue에 적재하고,
Worker는 Redis Queue에서 applicationId를 꺼내 실제 신청 처리를 수행한다.

따라서 운영 관점에서 중요한 장애 지표는 다음과 같다.

| Alert | 의미 |
|---|---|
| `FastPassQueueBacklogHigh` | Redis Queue에 처리되지 않은 신청이 많이 쌓인 상태 |
| `FastPassWorkerProcessingFailureDetected` | Worker가 application 처리 중 실패한 상태 |
| `FastPassTargetDown` | Prometheus가 FastPass API 또는 Worker target을 scrape하지 못하는 상태 |

---

## 3. PrometheusRule 작성

FastPass alert rule은 `PrometheusRule` 리소스로 작성하였다.

파일 위치는 다음과 같다.

```text
deploy/k8s/fastpass-prometheusrule.yaml
```

작성한 내용은 다음과 같다.

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: fastpass-prometheusrule
  namespace: monitoring
  labels:
    release: prometheus-stack
spec:
  groups:
    - name: fastpass.rules
      rules:
        - alert: FastPassQueueBacklogHigh
          expr: max(fastpass_queue_size{namespace="fastpass"}) > 50
          for: 1m
          labels:
            severity: warning
            service: fastpass
          annotations:
            summary: "FastPass queue backlog is high"
            description: >
              FastPass Redis queue size has been greater than 50
              for more than 1 minute.

        - alert: FastPassWorkerProcessingFailureDetected
          expr: >
            sum(
              increase(
                fastpass_worker_processing_failed_total{
                  namespace="fastpass",
                  job="fastpass-worker"
                }[5m]
              )
            ) > 0
          for: 30s
          labels:
            severity: warning
            service: fastpass
          annotations:
            summary: "FastPass worker processing failure detected"
            description: >
              FastPass worker processing failures have increased
              within the last 5 minutes.

        - alert: FastPassTargetDown
          expr: min(up{namespace="fastpass"}) by (job) == 0
          for: 1m
          labels:
            severity: critical
            service: fastpass
          annotations:
            summary: "FastPass target is down"
            description: >
              One or more FastPass Prometheus targets are down
              for more than 1 minute.
```

`metadata.labels.release: prometheus-stack`을 지정하여
kube-prometheus-stack이 해당 rule을 인식하도록 설정하였다.

---

## 4. PrometheusRule 적용

작성한 PrometheusRule을 Kubernetes에 적용하였다.

```bash
kubectl apply -f deploy/k8s/fastpass-prometheusrule.yaml
```

적용 여부는 다음 명령어로 확인하였다.

```bash
kubectl get prometheusrule -n monitoring
```

정상적으로 적용되면 다음과 같이 `fastpass-prometheusrule`이 조회된다.

```text
fastpass-prometheusrule
```

---

## 5. Prometheus Rule Health 확인

Prometheus UI에서 다음 경로로 이동하여 rule 등록 상태를 확인하였다.

```text
Status → Rule health
```

`fastpass.rules` 그룹 아래에 다음 alert rule들이 표시되는 것을 확인하였다.

```text
FastPassQueueBacklogHigh
FastPassWorkerProcessingFailureDetected
FastPassTargetDown
```

각 rule의 상태가 `OK`로 표시되어 Prometheus가 FastPass alert rule을
정상적으로 로드했음을 확인하였다.

---

## 6. Queue Backlog Alert

### 6.1 Alert 조건

`FastPassQueueBacklogHigh` alert는 Redis Queue size가 50을 초과한
상태로 1분 이상 유지될 때 발생한다.

```promql
max(fastpass_queue_size{namespace="fastpass"}) > 50
```

해당 조건은 다음 상황을 감지하기 위한 것이다.

```text
API는 신청 요청을 계속 Queue에 적재하고 있지만,
Worker의 처리 속도가 부족하거나 Worker가 중지되어
Queue backlog가 증가하는 상황
```

---

### 6.2 테스트 방법

Queue backlog를 의도적으로 만들기 위해 Worker HPA를 잠시 삭제하고
Worker replica를 0으로 줄였다.

```bash
kubectl delete hpa fastpass-worker-hpa \
  -n fastpass \
  --ignore-not-found=true

kubectl scale deployment fastpass-worker \
  -n fastpass \
  --replicas=0
```

그 후 Redis Queue를 초기화하였다.

```bash
kubectl exec -n fastpass deployment/redis -- \
  redis-cli DEL fastpass:application:queue
```

API를 통해 테스트 이벤트를 생성하였다.

```bash
EVENT_ID=$(curl -s -X POST http://localhost:18080/api/events \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{
    \"title\":\"Alert Test Event\",
    \"description\":\"queue backlog alert test\",
    \"capacity\":10000,
    \"eventStartAt\":\"2026-07-20T10:00:00\"
  }" \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')

echo $EVENT_ID
```

이후 신청 요청을 여러 건 전송하여 Queue에 applicationId가 쌓이도록
하였다.

```bash
for i in $(seq 1 150); do
  curl -s -X POST \
    http://localhost:18080/api/events/${EVENT_ID}/apply \
    -H "Content-Type: application/json; charset=UTF-8" \
    --data-raw "{\"applicantName\":\"alert-user-${i}\"}" \
    > /dev/null
done
```

Queue size는 다음 명령어로 확인하였다.

```bash
curl http://localhost:18080/api/queue/applications/size
```

Worker가 중지된 상태였기 때문에 Queue size가 증가하였다.

---

### 6.3 검증 결과

Prometheus Alerts 화면에서 `FastPassQueueBacklogHigh` alert가
`FIRING` 상태로 전환되는 것을 확인하였다.

```text
FastPassQueueBacklogHigh → FIRING
```

또한 Alertmanager UI에서도 동일한 alert가 전달된 것을 확인하였다.

```text
alertname="FastPassQueueBacklogHigh"
service="fastpass"
severity="warning"
```

이를 통해 Redis Queue backlog 증가 상황이 Prometheus alert로 감지되고
Alertmanager까지 전달되는 것을 검증하였다.

---

## 7. Queue Backlog 복구

테스트 후 Worker를 다시 실행하였다.

```bash
kubectl scale deployment fastpass-worker \
  -n fastpass \
  --replicas=1

kubectl rollout status deployment fastpass-worker \
  -n fastpass
```

Worker HPA도 다시 적용하였다.

```bash
kubectl apply -f deploy/k8s/worker-hpa.yaml
```

Queue size가 0으로 감소하는 것을 확인하였다.

```bash
curl http://localhost:18080/api/queue/applications/size
```

정상 복구 상태는 다음과 같다.

```json
{"size":0}
```

Queue가 처리되면서 `FastPassQueueBacklogHigh` alert도 이후 inactive
상태로 해제된다.

---

## 8. Worker Failure Alert

### 8.1 Alert 조건

`FastPassWorkerProcessingFailureDetected` alert는 최근 5분 동안
Worker 처리 실패 counter가 증가한 경우 발생한다.

```promql
sum(
  increase(
    fastpass_worker_processing_failed_total{
      namespace="fastpass",
      job="fastpass-worker"
    }[5m]
  )
) > 0
```

이 alert는 다음 상황을 감지하기 위한 것이다.

```text
Worker가 Redis Queue에서 applicationId를 꺼냈지만,
DB 조회 또는 처리 과정에서 예외가 발생한 상황
```

예를 들어 Queue에는 applicationId가 존재하지만, DB에는 해당
application이 없는 경우 Worker 처리 실패가 발생한다.

---

### 8.2 테스트 방법

Worker failure alert를 검증하기 위해 Redis Queue에 존재하지 않는
applicationId를 직접 추가하였다.

```bash
kubectl exec -n fastpass deployment/redis -- \
  redis-cli RPUSH fastpass:application:queue 99999999
```

Worker는 Queue에서 `99999999`를 꺼내 처리하려고 시도한다.

하지만 PostgreSQL의 `event_applications` 테이블에는 해당 applicationId가
존재하지 않으므로 `Application not found` 예외가 발생한다.

Worker 로그는 다음 명령어로 확인하였다.

```bash
kubectl logs -n fastpass -l app=fastpass-worker \
  --since=5m \
  | grep "Failed to process application"
```

기대되는 로그 형태는 다음과 같다.

```text
Failed to process application. applicationId=99999999,
message=Application not found. id=99999999
```

---

### 8.3 검증 결과

Prometheus Alerts 화면에서 `FastPassWorkerProcessingFailureDetected`
alert가 `FIRING` 상태로 전환되는 것을 확인하였다.

```text
FastPassWorkerProcessingFailureDetected → FIRING
```

Alert rule의 조건은 다음과 같이 표시되었다.

```promql
sum(
  increase(
    fastpass_worker_processing_failed_total{
      job="fastpass-worker",
      namespace="fastpass"
    }[5m]
  )
) > 0
```

Prometheus 화면에서 확인한 값은 약 1 이상으로 표시되었고,
alert 상태는 `FIRING`이었다.

이를 통해 Worker 처리 실패가 발생했을 때 custom metric이 증가하고,
해당 metric을 기반으로 alert가 발생하는 것을 확인하였다.

---

## 9. Worker Failure Alert 해석

`FastPassWorkerProcessingFailureDetected`는 최근 5분 동안 Worker
failure counter가 증가했는지를 기준으로 판단한다.

따라서 테스트 후 Queue를 정리하더라도 최근 5분 안에 실패가 발생했다면
alert는 잠시 유지될 수 있다.

```promql
sum(
  increase(
    fastpass_worker_processing_failed_total{
      namespace="fastpass",
      job="fastpass-worker"
    }[5m]
  )
) > 0
```

즉, cleanup 직후에도 alert가 바로 사라지지 않는 것은 정상이다.

5분 window가 지나고 더 이상 새로운 실패가 발생하지 않으면 alert는
inactive 상태로 전환된다.

---

## 10. Target Down Alert

### 10.1 Alert 조건

`FastPassTargetDown` alert는 FastPass namespace의 Prometheus target 중
하나 이상이 down 상태가 되었을 때 발생하도록 정의하였다.

```promql
min(up{namespace="fastpass"}) by (job) == 0
```

이 alert는 다음 상황을 감지하기 위한 것이다.

```text
Prometheus가 FastPass API 또는 Worker의 /actuator/prometheus
endpoint를 scrape하지 못하는 상황
```

예를 들어 API Pod 또는 Worker Pod가 down되거나, ServiceMonitor 설정이
잘못되거나, application이 정상적으로 metric endpoint를 노출하지 못하면
해당 alert가 발생할 수 있다.

---

### 10.2 검증 상태

이번 단계에서는 Worker replica를 0으로 줄이는 과정에서
`FastPassTargetDown` alert가 `PENDING` 상태로 표시되는 것을 확인하였다.

```text
FastPassTargetDown → PENDING
```

다만 본 단계의 주요 검증 대상은 Queue backlog alert와 Worker failure
alert였으므로, TargetDown alert의 full firing 검증은 별도 장애 테스트
단계에서 추가로 검증할 수 있다.

---

## 11. Alertmanager 확인

Alertmanager UI는 다음 포트포워딩으로 접근하였다.

```bash
kubectl port-forward -n monitoring \
  svc/prometheus-stack-kube-prom-alertmanager \
  9093:9093
```

브라우저에서 다음 주소로 접속하였다.

```text
http://localhost:9093
```

Alertmanager UI에서 `FastPassQueueBacklogHigh` alert가 표시되는 것을
확인하였다.

```text
alertname="FastPassQueueBacklogHigh"
service="fastpass"
severity="warning"
```

이를 통해 Prometheus에서 firing된 alert가 Alertmanager까지 정상적으로
전달되는 것을 확인하였다.

---

## 12. 최종 복구 확인

테스트 후 FastPass 리소스가 정상 상태로 복구되었는지 확인하였다.

```bash
kubectl get pods -n fastpass
kubectl get hpa -n fastpass
curl http://localhost:18080/api/queue/applications/size
```

정상 기준은 다음과 같다.

```text
API Pod Running
Worker Pod Running
HPA 존재
Queue size 0
```

Queue size는 다음과 같이 0으로 복구되었다.

```json
{"size":0}
```

---

## 13. 검증 결과 요약

이번 단계에서 확인한 내용은 다음과 같다.

```text
1. PrometheusRule 리소스를 생성하였다.
2. Prometheus Rule health 화면에서 fastpass.rules가 OK 상태로 등록되었다.
3. FastPassQueueBacklogHigh alert를 실제 firing 상태로 만들었다.
4. Queue backlog alert가 Alertmanager까지 전달되는 것을 확인하였다.
5. FastPassWorkerProcessingFailureDetected alert를 실제 firing 상태로 만들었다.
6. Worker failure alert가 custom metric 기반으로 동작하는 것을 확인하였다.
7. 테스트 후 Worker를 복구하고 Queue size가 0으로 감소하는 것을 확인하였다.
```

이번 검증을 통해 FastPass는 단순 metric 시각화뿐 아니라, 주요 장애
상황을 자동으로 감지할 수 있는 alerting 구조를 갖추게 되었다.

---

## 14. 한계점

이번 단계에서는 PrometheusRule 기반 alerting을 구성하고
Alertmanager UI에서 alert 전달을 확인하였다.

다만 아직 다음 기능은 구현하지 않았다.

```text
Slack 또는 Discord 알림 연동
Email 알림 연동
Alertmanager routing 정책 세분화
severity별 receiver 분리
Runbook URL 연결
TargetDown alert의 full firing 검증
```

현재는 Alertmanager UI에서 alert를 확인하는 수준이다.

향후에는 Alertmanager receiver를 Slack과 연결하여 Queue backlog,
Worker failure, Target down 상황이 발생했을 때 운영자가 즉시 알림을
받을 수 있도록 확장할 수 있다.

---

## 15. 다음 단계

다음 단계에서는 Kubernetes YAML 배포 구조를 Helm Chart로 패키징한다.

현재는 다음 방식으로 Kubernetes 리소스를 적용하고 있다.

```bash
kubectl apply -f deploy/k8s/
```

Helm Chart를 도입하면 다음과 같은 방식으로 배포할 수 있다.

```bash
helm install fastpass deploy/helm/fastpass
helm upgrade fastpass deploy/helm/fastpass
```

이를 통해 환경별 설정값 관리, 배포 템플릿화, GitOps 연동이 쉬워진다.

따라서 다음 단계의 목표는 다음과 같다.

```text
FastPass Kubernetes 리소스를 Helm Chart로 패키징하고,
values.yaml을 통해 환경별 설정을 관리할 수 있도록 구성한다.
```

---

## 16. 결론

이번 단계에서는 FastPass의 주요 장애 상황을 감지하기 위한
PrometheusRule을 작성하고, Prometheus 및 Alertmanager에서 alert 동작을
검증하였다.

Redis Queue backlog가 증가했을 때 `FastPassQueueBacklogHigh` alert가
firing되는 것을 확인하였고, 해당 alert가 Alertmanager까지 전달되는 것을
확인하였다.

또한 Worker 처리 실패를 의도적으로 발생시켜
`FastPassWorkerProcessingFailureDetected` alert가 firing되는 것을
확인하였다.

이를 통해 FastPass는 Queue backlog와 Worker failure 같은 서비스 핵심
장애 상황을 metric 기반으로 자동 감지할 수 있는 구조를 갖추게 되었다.