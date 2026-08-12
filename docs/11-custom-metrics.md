# FastPass Custom Metrics 검증

## 1. 목적

이번 단계의 목적은 FastPass 서비스에 특화된 운영 지표를 Prometheus metric으로 직접 노출하고, Grafana에서 시각화하는 것이다.

이전 단계에서는 Prometheus/Grafana를 설치하고 Spring Boot Actuator의 기본 metric을 수집하였다. 이를 통해 API 요청량, 응답 시간, JVM memory, CPU, DB connection과 같은 기본 지표를 확인할 수 있었다.

하지만 FastPass는 Redis Queue 기반 비동기 처리 구조를 사용하기 때문에, 운영 관점에서는 다음과 같은 서비스 특화 지표가 더 중요하다.

```text
Redis Queue size
Worker 처리 건수
Worker 처리 실패 건수
Queue backlog
```

따라서 이번 단계에서는 FastPass에 특화된 custom metric을 추가하고, Prometheus/Grafana에서 해당 metric이 정상적으로 수집되는지 검증하였다.

---

## 2. 추가한 Custom Metrics

이번 단계에서 추가한 metric은 다음과 같다.

| Metric                                    | Type    | 설명                                |
| ----------------------------------------- | ------- | --------------------------------- |
| `fastpass_queue_size`                     | Gauge   | 현재 Redis application queue 크기     |
| `fastpass_worker_processed_total`         | Counter | Worker가 처리한 application 누적 건수     |
| `fastpass_worker_processing_failed_total` | Counter | Worker 처리 중 실패한 application 누적 건수 |

각 metric의 목적은 다음과 같다.

### fastpass_queue_size

현재 Redis Queue에 쌓여 있는 신청 건 수를 나타낸다.

이 값이 증가하면 API가 신청 요청을 Queue에 적재하는 속도보다 Worker가 처리하는 속도가 느리다는 의미이다.

### fastpass_worker_processed_total

Worker가 성공적으로 처리한 신청 건의 누적 수를 나타낸다.

이 값은 counter이므로 Pod가 살아 있는 동안 계속 증가한다.

### fastpass_worker_processing_failed_total

Worker가 Queue에서 applicationId를 꺼내 처리하는 과정에서 예외가 발생한 누적 수를 나타낸다.

예를 들어 Queue에는 applicationId가 남아 있지만 DB에서 해당 application을 찾을 수 없는 경우, 처리 실패로 기록된다.

---

## 3. Custom Metric 구현

Custom metric을 관리하기 위해 `FastPassMetrics` 컴포넌트를 추가하였다.

```java
package com.fastpass.api.metric;

import com.fastpass.api.queue.ApplicationQueueService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class FastPassMetrics {

    private final Counter workerProcessedCounter;
    private final Counter workerProcessingFailedCounter;

    public FastPassMetrics(
            MeterRegistry meterRegistry,
            ApplicationQueueService applicationQueueService
    ) {
        Gauge.builder(
                        "fastpass_queue_size",
                        applicationQueueService,
                        queueService -> queueService.getQueueSize().doubleValue()
                )
                .description("Current size of FastPass application queue")
                .register(meterRegistry);

        this.workerProcessedCounter = Counter.builder("fastpass_worker_processed_total")
                .description("Total number of applications processed by FastPass worker")
                .register(meterRegistry);

        this.workerProcessingFailedCounter = Counter.builder("fastpass_worker_processing_failed_total")
                .description("Total number of application processing failures in FastPass worker")
                .register(meterRegistry);
    }

    public void incrementWorkerProcessed() {
        workerProcessedCounter.increment();
    }

    public void incrementWorkerProcessingFailed() {
        workerProcessingFailedCounter.increment();
    }
}
```

`fastpass_queue_size`는 Redis Queue의 현재 크기를 조회하는 Gauge로 등록하였다.

`fastpass_worker_processed_total`과 `fastpass_worker_processing_failed_total`은 Worker 처리 결과에 따라 증가하는 Counter로 등록하였다.

---

## 4. Worker 처리 로직에 Metric 반영

Worker가 application을 처리할 때 성공/실패 여부에 따라 custom metric을 증가시키도록 수정하였다.

```java
package com.fastpass.api.queue;

import com.fastpass.api.metric.FastPassMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(
        name = "fastpass.worker.enabled",
        havingValue = "true"
)
public class ApplicationQueueWorker {

    private static final int BATCH_SIZE = 50;

    private final ApplicationQueueService applicationQueueService;
    private final ApplicationProcessor applicationProcessor;
    private final FastPassMetrics fastPassMetrics;

    public ApplicationQueueWorker(
            ApplicationQueueService applicationQueueService,
            ApplicationProcessor applicationProcessor,
            FastPassMetrics fastPassMetrics
    ) {
        this.applicationQueueService = applicationQueueService;
        this.applicationProcessor = applicationProcessor;
        this.fastPassMetrics = fastPassMetrics;

        System.out.println("FastPass queue worker is enabled. batchSize=" + BATCH_SIZE);
    }

    @Scheduled(fixedDelay = 1000)
    public void processApplicationQueue() {
        List<Long> applicationIds = applicationQueueService.dequeueBatch(BATCH_SIZE);

        if (applicationIds.isEmpty()) {
            return;
        }

        for (Long applicationId : applicationIds) {
            try {
                applicationProcessor.process(applicationId);
                fastPassMetrics.incrementWorkerProcessed();
            } catch (Exception e) {
                fastPassMetrics.incrementWorkerProcessingFailed();

                System.err.println(
                        "Failed to process application. applicationId="
                                + applicationId
                                + ", message="
                                + e.getMessage()
                );
            }
        }
    }
}
```

정상 처리된 application은 `fastpass_worker_processed_total`을 증가시키고, 예외가 발생한 application은 `fastpass_worker_processing_failed_total`을 증가시킨다.

---

## 5. Prometheus Metric 노출 확인

애플리케이션 재배포 후 `/actuator/prometheus` endpoint에서 custom metric이 정상적으로 노출되는 것을 확인하였다.

```text
# HELP fastpass_queue_size Current size of FastPass application queue
# TYPE fastpass_queue_size gauge
fastpass_queue_size 0.0

# HELP fastpass_worker_processed_total Total number of applications processed by FastPass worker
# TYPE fastpass_worker_processed_total counter
fastpass_worker_processed_total 0.0

# HELP fastpass_worker_processing_failed_total Total number of application processing failures in FastPass worker
# TYPE fastpass_worker_processing_failed_total counter
fastpass_worker_processing_failed_total 0.0
```

이를 통해 Spring Boot 애플리케이션이 FastPass custom metric을 Prometheus 형식으로 정상 노출하고 있음을 확인하였다.

---

## 6. Prometheus 수집 확인

Prometheus UI에서 다음 PromQL을 실행하여 custom metric이 정상 수집되는 것을 확인하였다.

### Queue Size

```promql
fastpass_queue_size{namespace="fastpass"}
```

Prometheus에서는 API Pod와 Worker Pod 각각이 `/actuator/prometheus`를 노출하므로 여러 series가 조회된다.

API 3개, Worker 3개가 떠 있는 상태에서는 총 6개의 series가 조회되었다.

```text
fastpass-api      3 series
fastpass-worker   3 series
```

이는 Prometheus가 API와 Worker Pod의 metric endpoint를 모두 정상적으로 scrape하고 있음을 의미한다.

다만 `fastpass_queue_size`는 하나의 Redis Queue를 바라보는 값이므로, 여러 Pod에서 같은 Queue size를 중복해서 노출할 수 있다.

따라서 Grafana에서는 다음과 같이 `max`를 사용하여 하나의 값으로 표현하였다.

```promql
max(fastpass_queue_size{namespace="fastpass"})
```

---

## 7. Grafana Dashboard 구성

Grafana에 FastPass custom metric 패널을 추가하였다.

구성한 패널은 다음과 같다.

| 패널           | PromQL                                                                                        | 설명                     |
| ------------ | --------------------------------------------------------------------------------------------- | ---------------------- |
| Queue Size   | `max(fastpass_queue_size{namespace="fastpass"})`                                              | Redis Queue backlog 확인 |
| Worker 처리 누적 | `sum(fastpass_worker_processed_total{namespace="fastpass", job="fastpass-worker"})`           | Worker 처리 누적 건수        |
| Worker 처리 속도 | `sum(rate(fastpass_worker_processed_total{namespace="fastpass", job="fastpass-worker"}[1m]))` | Worker 초당 처리량          |
| Worker 실패 누적 | `sum(fastpass_worker_processing_failed_total{namespace="fastpass", job="fastpass-worker"})`   | Worker 처리 실패 누적 건수     |

---

## 8. Queue Size Panel

Queue Size 패널은 Redis Queue에 쌓인 신청 건 수를 보여준다.

```promql
max(fastpass_queue_size{namespace="fastpass"})
```

부하 테스트 중 Queue size가 증가하고, Worker가 Queue를 처리하면서 다시 감소하는 것을 확인하였다.

이를 통해 FastPass의 Queue backlog를 Grafana에서 직접 관측할 수 있게 되었다.

---

## 9. Worker Processed Total Panel

Worker 처리 누적 패널은 Worker가 처리한 신청 건의 누적 수를 보여준다.

```promql
sum(fastpass_worker_processed_total{namespace="fastpass", job="fastpass-worker"})
```

k6 부하 테스트 후 Worker 처리 누적 수가 증가하는 것을 확인하였다.

이는 Worker가 Redis Queue에서 applicationId를 꺼내 정상적으로 처리하고 있음을 의미한다.

---

## 10. Worker Processing Rate Panel

Worker 처리 속도 패널은 Worker가 초당 얼마나 많은 application을 처리하는지 보여준다.

```promql
sum(rate(fastpass_worker_processed_total{namespace="fastpass", job="fastpass-worker"}[1m]))
```

k6 부하 발생 시점에 Worker 처리 속도가 증가하는 것을 확인하였다.

이 지표는 Worker 처리량을 관측하는 데 유용하며, 향후 Worker scaling 정책을 설계할 때 활용할 수 있다.

---

## 11. Worker Failure Panel

Worker 실패 누적 패널은 Worker 처리 중 발생한 실패 누적 수를 보여준다.

```promql
sum(fastpass_worker_processing_failed_total{namespace="fastpass", job="fastpass-worker"})
```

테스트 중 Worker 실패 누적 값이 2 증가하였다.

Prometheus에서 Pod별 값을 확인한 결과, 특정 Worker Pod에서 실패 counter가 2로 증가한 것을 확인하였다.

```text
fastpass-worker-7f5696cdbd-tgmb9   failed_total = 2
fastpass-worker-7f5696cdbd-k8lxh   failed_total = 0
fastpass-worker-7f5696cdbd-2cmf5   failed_total = 0
```

Worker 로그를 확인한 결과는 다음과 같았다.

```text
Failed to process application. applicationId=7654, message=Application not found. id=7654
Failed to process application. applicationId=7690, message=Application not found. id=7690
```

이는 Redis Queue에 남아 있던 오래된 applicationId를 Worker가 처리하려고 했지만, PostgreSQL의 application 테이블에서 해당 ID를 찾지 못해 발생한 실패이다.

즉, Queue와 DB 상태가 불일치할 때 Worker 처리 실패가 발생할 수 있음을 확인하였다.

이번 custom metric은 이러한 실패를 `fastpass_worker_processing_failed_total`로 정상적으로 포착하였다.

---

## 12. 누적 Counter 해석 주의사항

`fastpass_worker_processed_total`과 `fastpass_worker_processing_failed_total`은 Counter metric이다.

Counter는 Pod가 살아 있는 동안 계속 증가한다.

따라서 Grafana에서 누적값을 그대로 보면 이전 테스트의 값이 남아 있을 수 있다.

운영 관점에서는 최근 일정 시간 동안의 증가량을 보는 것이 더 유용하다.

예를 들어 최근 10분 동안 발생한 Worker 실패 수는 다음과 같이 확인할 수 있다.

```promql
sum(increase(fastpass_worker_processing_failed_total{namespace="fastpass", job="fastpass-worker"}[10m]))
```

최근 10분 동안의 Worker 처리량은 다음과 같이 확인할 수 있다.

```promql
sum(increase(fastpass_worker_processed_total{namespace="fastpass", job="fastpass-worker"}[10m]))
```

따라서 운영 대시보드에서는 누적값과 함께 최근 증가량을 함께 보는 것이 좋다.

---

## 13. HPA와 Custom Metric 검증 시 주의사항

이번 테스트는 HPA가 적용된 상태에서 수행하였다.

HPA가 살아 있는 상태에서는 부하에 따라 API와 Worker Pod가 자동으로 증가할 수 있다.

따라서 Prometheus에서 동일한 metric이 여러 Pod series로 표시될 수 있다.

```text
API Pod 3개
Worker Pod 3개
총 6개 target
```

이것은 오류가 아니라 Prometheus가 각 Pod의 metric endpoint를 개별적으로 수집하고 있기 때문에 발생하는 정상 동작이다.

또한 HPA는 scale-up뿐 아니라 scale-down도 수행한다.

다만 부하가 끝났다고 해서 즉시 replica를 줄이지 않고, 일정 시간 동안 안정화한 뒤 replica 수를 줄일 수 있다.

따라서 부하 테스트 직후 API/Worker가 3 replicas 상태로 유지되는 것은 비정상 동작이 아니다.

---

## 14. 테스트 결과 해석

이번 테스트에서 확인한 내용은 다음과 같다.

1. FastPass custom metric이 `/actuator/prometheus`에 정상 노출되었다.
2. Prometheus가 custom metric을 정상 수집하였다.
3. Grafana에서 Queue Size, Worker 처리 누적, Worker 처리 속도, Worker 실패 수를 시각화하였다.
4. k6 부하 테스트 중 Queue size가 증가했다가 Worker 처리에 따라 감소하는 것을 확인하였다.
5. Worker 처리 누적 수가 증가하는 것을 확인하였다.
6. Worker 처리 속도가 부하 시점에 증가하는 것을 확인하였다.
7. Worker 처리 실패 2건이 metric에 반영되었고, 로그를 통해 원인을 확인하였다.

이번 결과는 FastPass의 핵심 운영 지표인 Queue backlog와 Worker 처리 상태를 직접 관측할 수 있게 되었다는 점에서 의미가 있다.

---

## 15. 한계점

이번 단계에서는 FastPass custom metric을 추가하여 Queue와 Worker 상태를 관측할 수 있게 만들었다.

다만 아직 다음 기능은 구현하지 않았다.

```text
Queue backlog 기반 alerting
Worker failure alerting
Queue length 기반 autoscaling
KEDA 기반 Worker autoscaling
Grafana dashboard provisioning
```

현재는 Grafana에서 metric을 수동으로 확인하는 단계이다.

향후에는 Queue size가 일정 기준 이상 유지되거나 Worker failure가 증가할 경우 Alertmanager와 Slack으로 알림을 보내도록 확장할 수 있다.

또한 Worker scaling 역시 CPU 기반이 아니라 Redis Queue length 기반으로 개선할 수 있다.

---

## 16. 다음 단계

다음 단계에서는 custom metric을 기반으로 alerting 또는 autoscaling을 확장할 수 있다.

가능한 확장 방향은 다음과 같다.

```text
1. Queue backlog alert
2. Worker failure alert
3. Alertmanager + Slack 연동
4. Grafana dashboard JSON export 및 Git 관리
5. PrometheusRule 작성
6. KEDA 기반 Redis Queue length autoscaling
```

특히 FastPass의 구조에서는 Worker 처리 병목이 CPU 사용률보다 Queue backlog로 더 잘 드러날 수 있다.

따라서 향후 Worker autoscaling은 다음과 같은 지표를 기준으로 설계하는 것이 더 적합하다.

```text
fastpass_queue_size
fastpass_worker_processed_total
fastpass_worker_processing_failed_total
```

---

## 17. 결론

이번 단계에서는 FastPass 서비스에 특화된 custom metric을 추가하고 Prometheus/Grafana에서 이를 수집 및 시각화하였다.

`fastpass_queue_size`를 통해 Redis Queue backlog를 확인할 수 있게 되었고, `fastpass_worker_processed_total`을 통해 Worker 처리 누적 건수를 확인할 수 있게 되었다.

또한 `fastpass_worker_processing_failed_total`을 통해 Worker 처리 실패를 metric으로 포착할 수 있게 되었다.

k6 부하 테스트를 통해 Queue size 증가, Worker 처리량 증가, Worker 처리 속도 증가를 Grafana에서 확인하였다.

테스트 중 발생한 Worker 실패 2건은 Redis Queue에 남아 있던 오래된 applicationId가 DB에 존재하지 않아 발생한 것으로 확인되었다.

이를 통해 FastPass는 단순한 기본 JVM/HTTP metric을 넘어서, 서비스의 핵심 병목인 Queue와 Worker 처리 상태를 직접 관측할 수 있는 구조를 갖추게 되었다.
