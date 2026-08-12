# Prometheus/Grafana 기반 FastPass 모니터링 검증

## 1. 목적

이번 단계의 목적은 Kubernetes 환경에 배포된 FastPass API와 Worker의 상태를 Prometheus와 Grafana를 통해 관측할 수 있도록 구성하는 것이다.

이전 단계에서는 Kubernetes HPA를 통해 API와 Worker가 부하에 따라 자동 확장되는 것을 검증하였다. 이번 단계에서는 한 단계 더 나아가, 운영 환경에서 필요한 기본 관측성 구조를 구성하였다.

이를 통해 다음 항목을 확인하는 것을 목표로 하였다.

* FastPass API/Worker의 Prometheus metric 노출
* Prometheus의 FastPass metric 수집
* Grafana Dashboard를 통한 metric 시각화
* k6 부하 테스트 시 API 요청량, 응답 시간, CPU, JVM Memory, DB Connection 변화 확인

---

## 2. 전체 구조

이번 단계에서 구성한 모니터링 흐름은 다음과 같다.

```text
fastpass-api / fastpass-worker
  |
  | /actuator/prometheus
  v
Prometheus
  |
  | PromQL
  v
Grafana Dashboard
```

FastPass 애플리케이션은 Spring Boot Actuator와 Micrometer Prometheus Registry를 통해 `/actuator/prometheus` endpoint를 노출한다.

Prometheus는 ServiceMonitor를 통해 FastPass API와 Worker의 metric endpoint를 주기적으로 scrape한다.

Grafana는 Prometheus를 data source로 사용하여 수집된 metric을 dashboard 형태로 시각화한다.

---

## 3. 구성 요소

이번 단계에서 사용한 구성 요소는 다음과 같다.

| 구성 요소                          | 역할                                                          |
| ------------------------------ | ----------------------------------------------------------- |
| Spring Boot Actuator           | 애플리케이션 상태 및 metric endpoint 제공                              |
| Micrometer Prometheus Registry | Spring Boot metric을 Prometheus 형식으로 변환                      |
| Prometheus                     | metric 수집 및 저장                                              |
| Grafana                        | metric 시각화                                                  |
| ServiceMonitor                 | Prometheus Operator가 scrape 대상을 찾기 위한 리소스                   |
| kube-prometheus-stack          | Prometheus, Grafana, Alertmanager, Operator를 포함한 Helm chart |
| k6                             | 부하 테스트 도구                                                   |

---

## 4. kube-prometheus-stack 설치

Prometheus와 Grafana는 Helm을 이용하여 `kube-prometheus-stack`으로 설치하였다.

설치 후 `monitoring` namespace에 다음과 같은 주요 리소스가 생성되었다.

```text
prometheus-stack-grafana
prometheus-stack-kube-prom-operator
prometheus-stack-kube-state-metrics
prometheus-stack-prometheus-node-exporter
prometheus-prometheus-stack-kube-prom-prometheus
alertmanager-prometheus-stack-kube-prom-alertmanager
```

Helm release 상태는 다음과 같이 정상 배포 상태임을 확인하였다.

```text
NAME                 NAMESPACE    STATUS     CHART
prometheus-stack     monitoring   deployed   kube-prometheus-stack
```

Grafana는 port-forward를 통해 로컬 브라우저에서 접속하였고, 기본 admin 계정으로 로그인하였다.

---

## 5. FastPass Prometheus Endpoint 노출

FastPass 애플리케이션에서 Prometheus 형식의 metric을 노출하기 위해 Spring Boot 프로젝트에 Micrometer Prometheus Registry를 추가하였다.

`build.gradle`에는 다음 dependency를 추가하였다.

```gradle
runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
```

또한 `application-docker.yaml`에서 Actuator endpoint 노출 범위에 `prometheus`를 추가하였다.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

이후 Docker image를 다시 빌드하고 Kubernetes에 재배포하였다.

재배포 후 다음 endpoint에서 Prometheus 형식의 metric이 정상적으로 출력되는 것을 확인하였다.

```text
/actuator/prometheus
```

출력된 주요 metric 예시는 다음과 같다.

```text
application_ready_time_seconds
application_started_time_seconds
http_server_requests_seconds_count
http_server_requests_seconds_sum
hikaricp_connections
hikaricp_connections_active
jdbc_connections_active
jvm_info
jvm_memory_used_bytes
jvm_gc_pause_seconds
process_cpu_usage
```

이를 통해 FastPass API와 Worker가 Prometheus가 수집할 수 있는 형식으로 애플리케이션 metric을 노출하고 있음을 확인하였다.

---

## 6. FastPass ServiceMonitor 구성

Prometheus가 FastPass API와 Worker의 `/actuator/prometheus` endpoint를 자동으로 수집하도록 ServiceMonitor를 구성하였다.

API Service에는 ServiceMonitor가 선택할 수 있도록 label을 추가하였다.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: fastpass-api
  namespace: fastpass
  labels:
    app: fastpass-api
spec:
  type: ClusterIP
  selector:
    app: fastpass-api
  ports:
    - name: http
      port: 8080
      targetPort: 8080
```

Worker는 외부 API 요청을 받는 서비스는 아니지만, Prometheus가 metric을 scrape하려면 Kubernetes Service가 필요하다. 따라서 Worker용 ClusterIP Service를 추가하였다.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: fastpass-worker
  namespace: fastpass
  labels:
    app: fastpass-worker
spec:
  type: ClusterIP
  selector:
    app: fastpass-worker
  ports:
    - name: http
      port: 8080
      targetPort: 8080
```

ServiceMonitor는 다음과 같이 구성하였다.

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: fastpass-servicemonitor
  namespace: monitoring
  labels:
    release: prometheus-stack
spec:
  namespaceSelector:
    matchNames:
      - fastpass
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

여기서 `release: prometheus-stack` label은 Helm으로 설치한 Prometheus release와 ServiceMonitor를 연결하기 위해 사용하였다.

---

## 7. Prometheus Target 확인

ServiceMonitor 적용 후 Prometheus UI에서 Target 상태를 확인하였다.

Prometheus의 `Status > Targets` 화면에서 다음 scrape pool이 생성되었다.

```text
serviceMonitor/monitoring/fastpass-servicemonitor/0
```

Target 상태는 다음과 같이 확인되었다.

```text
6 / 6 up
```

이는 Prometheus가 FastPass namespace에 있는 API Pod 3개와 Worker Pod 3개를 모두 정상적으로 수집하고 있음을 의미한다.

확인된 대상은 다음과 같다.

```text
fastpass-api
fastpass-worker
```

각 target은 다음 endpoint를 통해 scrape되었다.

```text
http://<pod-ip>:8080/actuator/prometheus
```

Prometheus에서 다음 PromQL을 실행하여 FastPass target 상태를 확인하였다.

```promql
up{namespace="fastpass"}
```

모든 FastPass target이 `1`로 표시되어 정상 수집 상태임을 확인하였다.

또한 다음 쿼리를 통해 Spring Boot HTTP request metric이 수집되고 있음을 확인하였다.

```promql
http_server_requests_seconds_count{namespace="fastpass"}
```

---

## 8. Grafana Dashboard 구성

Grafana에서는 Prometheus를 data source로 사용하여 `FastPass Overview` dashboard를 생성하였다.

Dashboard에는 다음 패널을 구성하였다.

| 패널                        | 목적                           |
| ------------------------- | ---------------------------- |
| FastPass Target Status    | API/Worker target 정상 여부 확인   |
| API Request Rate          | API endpoint별 요청량 확인         |
| API Average Response Time | API endpoint별 평균 응답 시간 확인    |
| Pod CPU Usage             | Pod별 CPU 사용량 확인              |
| JVM Memory Usage          | API/Worker JVM memory 사용량 확인 |
| DB Active Connections     | DB connection 사용량 확인         |

---

## 9. Grafana Panel 구성

### 9.1 FastPass Target Status

FastPass API와 Worker가 Prometheus target으로 정상 수집되고 있는지 확인하기 위한 패널이다.

```promql
min(up{namespace="fastpass"}) by (job)
```

Visualization은 `Stat`을 사용하였다.

이 패널은 다음과 같이 표시된다.

```text
fastpass-api      1
fastpass-worker   1
```

값이 `1`이면 해당 job의 target이 정상적으로 수집되고 있음을 의미한다.

---

### 9.2 API Request Rate

Actuator endpoint를 제외하고 실제 FastPass API 요청량을 확인하기 위한 패널이다.

```promql
sum(rate(http_server_requests_seconds_count{namespace="fastpass", job="fastpass-api", uri!~"/actuator.*"}[1m])) by (uri)
```

이 패널을 통해 다음 endpoint들의 요청량을 확인할 수 있다.

```text
/api/events
/api/events/{eventId}
/api/events/{eventId}/apply
/api/queue/applications/size
```

k6 부하 테스트를 실행하면 `/api/events/{eventId}/apply` 요청량이 증가하는 것을 확인할 수 있다.

---

### 9.3 API Average Response Time

Actuator endpoint를 제외하고 실제 FastPass API의 평균 응답 시간을 확인하기 위한 패널이다.

```promql
sum(rate(http_server_requests_seconds_sum{namespace="fastpass", job="fastpass-api", uri!~"/actuator.*"}[1m])) by (uri)
/
sum(rate(http_server_requests_seconds_count{namespace="fastpass", job="fastpass-api", uri!~"/actuator.*"}[1m])) by (uri)
```

Unit은 `seconds`로 설정하였다.

이 패널을 통해 부하 테스트 중 endpoint별 평균 응답 시간이 어떻게 변하는지 확인할 수 있다.

---

### 9.4 Pod CPU Usage

FastPass namespace의 Pod별 CPU 사용량을 확인하기 위한 패널이다.

```promql
sum(rate(container_cpu_usage_seconds_total{namespace="fastpass", container!="POD", container!=""}[1m])) by (pod)
```

이 패널에서는 API Pod, Worker Pod뿐만 아니라 PostgreSQL, Redis Pod의 CPU 사용량도 함께 확인할 수 있다.

HPA 테스트나 k6 부하 테스트를 실행하면 API/Worker Pod의 CPU 사용량이 증가하는 것을 확인할 수 있다.

---

### 9.5 JVM Memory Usage

FastPass API와 Worker의 JVM memory 사용량을 확인하기 위한 패널이다.

```promql
sum(jvm_memory_used_bytes{namespace="fastpass"}) by (job, area)
```

Unit은 `bytes`로 설정하였다.

이 패널에서는 API와 Worker의 heap, nonheap memory 사용량을 확인할 수 있다.

---

### 9.6 DB Active Connections

FastPass API와 Worker의 DB connection 사용량을 확인하기 위한 패널이다.

```promql
sum(hikaricp_connections_active{namespace="fastpass"}) by (job)
```

이 패널을 통해 부하 테스트 중 PostgreSQL connection pool의 active connection 변화를 확인할 수 있다.

---

## 10. k6 부하 테스트를 통한 Dashboard 검증

Grafana dashboard를 생성한 뒤, k6 부하 테스트를 실행하여 metric 변화가 실제로 dashboard에 반영되는지 확인하였다.

부하 테스트 중 Grafana에서 다음 변화를 확인하였다.

* API Request Rate 증가
* `/api/events/{eventId}/apply` 요청량 증가
* API Average Response Time 변화
* Pod CPU Usage spike 발생
* JVM Memory Usage 변화
* DB Active Connections 일시적 증가

특히 API Request Rate 패널에서는 실제 FastPass API endpoint들이 표시되었다.

```text
/api/events
/api/events/{eventId}
/api/events/{eventId}/apply
/api/queue/applications/size
```

Pod CPU Usage 패널에서는 부하 발생 시점에 API/Worker Pod의 CPU 사용량이 증가하는 것을 확인하였다.

DB Active Connections 패널에서는 부하 발생 시점에 active connection이 일시적으로 증가하는 것을 확인하였다.

이를 통해 Grafana dashboard가 실제 부하 상황에서 FastPass 애플리케이션의 상태 변화를 시각화할 수 있음을 확인하였다.

---

## 11. 이번 단계에서 확인한 내용

이번 단계에서 확인한 내용은 다음과 같다.

1. kube-prometheus-stack을 통해 Prometheus/Grafana/Alertmanager를 Kubernetes 클러스터에 설치하였다.
2. FastPass API와 Worker가 `/actuator/prometheus` endpoint를 통해 Prometheus 형식 metric을 노출하였다.
3. ServiceMonitor를 통해 Prometheus가 FastPass API/Worker metric을 자동으로 수집하도록 구성하였다.
4. Prometheus Targets 화면에서 FastPass target이 `6 / 6 up` 상태임을 확인하였다.
5. Grafana에서 `FastPass Overview` dashboard를 생성하였다.
6. k6 부하 테스트를 통해 API 요청량, 응답 시간, CPU, JVM Memory, DB Connection 변화가 Grafana에 반영되는 것을 확인하였다.

---

## 12. 한계점

이번 단계에서는 Prometheus와 Grafana를 이용해 기본적인 애플리케이션 metric과 Kubernetes metric을 수집하고 시각화하였다.

다만 현재 dashboard에서 확인할 수 있는 metric은 대부분 다음과 같은 기본 metric이다.

```text
HTTP request metric
JVM memory metric
DB connection metric
Pod CPU metric
Target up/down metric
```

FastPass의 핵심 운영 지표는 Redis Queue 기반 처리 구조와 관련되어 있다.

예를 들면 다음과 같은 지표가 더 중요하다.

```text
Redis Queue size
Worker 처리 건수
Worker 처리 실패 건수
Queue backlog
```

현재 Redis Queue size는 `/api/queue/applications/size` API를 통해 확인할 수 있지만, Prometheus metric으로 직접 노출되지는 않는다.

따라서 다음 단계에서는 FastPass 서비스에 특화된 custom metric을 추가해야 한다.

---

## 13. 다음 단계

다음 단계에서는 FastPass custom metric을 추가한다.

추가할 metric 후보는 다음과 같다.

```text
fastpass_queue_size
fastpass_worker_processed_total
fastpass_worker_failed_total
```

이를 통해 Grafana dashboard에 다음 패널을 추가할 수 있다.

* Redis Queue Size
* Worker Processed Count
* Worker Failed Count
* Queue Backlog Trend
* Worker 처리량 대비 Queue 적체량

또한 이후에는 Queue backlog를 기준으로 alerting이나 autoscaling을 확장할 수 있다.

예상 확장 방향은 다음과 같다.

* Queue backlog 기반 alerting
* Alertmanager/Slack 장애 알림
* Prometheus metric 기반 HPA
* KEDA 기반 Worker autoscaling
* 장애 대응 Runbook 작성

---

## 14. 결론

이번 단계에서는 FastPass 애플리케이션을 Prometheus와 Grafana에 연결하여 기본적인 모니터링 구조를 구축하였다.

Spring Boot Actuator와 Micrometer Prometheus Registry를 통해 API와 Worker가 `/actuator/prometheus` endpoint를 노출하도록 구성하였다.

ServiceMonitor를 통해 Prometheus가 FastPass API/Worker metric을 자동으로 수집하도록 설정하였고, Prometheus Targets에서 `6 / 6 up` 상태를 확인하였다.

Grafana에서는 `FastPass Overview` dashboard를 생성하여 API/Worker target 상태, API 요청량, 평균 응답 시간, Pod CPU 사용량, JVM memory 사용량, DB active connection을 시각화하였다.

k6 부하 테스트를 통해 실제 트래픽 발생 시 Grafana dashboard의 metric이 변화하는 것도 확인하였다.

따라서 FastPass는 Kubernetes 환경에서 기본적인 Prometheus/Grafana 기반 observability 구성을 갖추었다고 볼 수 있다.

다음 단계에서는 FastPass의 핵심 병목인 Redis Queue와 Worker 처리 상태를 직접 관측하기 위해 custom metric을 추가한다.
