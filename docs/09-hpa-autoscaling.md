# HPA 기반 API/Worker 자동 확장 검증

## 1. 목적

이번 단계의 목적은 Kubernetes 환경에서 FastPass API 서버와 Queue Worker가 부하에 따라 자동으로 확장되는지 검증하는 것이다.

FastPass는 선착순 이벤트 신청 서비스이며, 신청 요청은 API 서버에서 접수된 뒤 Redis Queue에 저장된다. 이후 Worker가 Queue에서 신청 건을 꺼내 처리하고, 이벤트 정원에 따라 신청 상태를 `SUCCESS` 또는 `FAILED`로 변경한다.

따라서 운영 환경에서는 다음 두 구성 요소를 독립적으로 확장할 수 있어야 한다.

- API 서버: 사용자 요청을 수신하고 Redis Queue에 신청 건을 적재
- Worker: Redis Queue에 쌓인 신청 건을 비동기적으로 처리

이번 테스트에서는 Kubernetes Horizontal Pod Autoscaler(HPA)를 사용하여 API Deployment와 Worker Deployment가 CPU 사용률에 따라 자동으로 scale-out 되는지 확인하였다.

---

## 2. 테스트 환경

테스트는 Docker Desktop Kubernetes 로컬 클러스터에서 수행하였다.

구성 요소는 다음과 같다.

| 구성 요소 | 설명 |
|---|---|
| fastpass-api | 신청 API를 제공하는 Spring Boot 애플리케이션 |
| fastpass-worker | Redis Queue를 처리하는 Worker 애플리케이션 |
| postgres | 이벤트 및 신청 데이터 저장소 |
| redis | 신청 요청을 저장하는 Queue |
| metrics-server | HPA가 CPU metric을 수집하기 위해 사용 |
| k6 | 부하 테스트 도구 |

API와 Worker는 동일한 Docker image를 사용하지만, 환경변수 `FASTPASS_WORKER_ENABLED` 값을 다르게 설정하여 역할을 분리하였다.

| Deployment | FASTPASS_WORKER_ENABLED | 역할 |
|---|---:|---|
| fastpass-api | false | HTTP API 요청 처리 |
| fastpass-worker | true | Redis Queue 처리 |

---

## 3. 사전 문제와 해결

처음 `kubectl top` 명령어를 실행했을 때 다음과 같이 Metrics API를 사용할 수 없다는 문제가 발생하였다.

```text
error: Metrics API not available
```

이는 HPA가 CPU 사용률을 판단하기 위해 필요한 metrics-server가 아직 정상적으로 동작하지 않았기 때문이다.

metrics-server를 설치한 뒤, Docker Desktop Kubernetes 환경에서 kubelet 인증서 검증 문제를 피하기 위해 `--kubelet-insecure-tls` 옵션을 추가하였다.

이후 `kubectl top nodes`, `kubectl top pods` 명령어가 정상 동작하였고, HPA가 CPU metric을 수집할 수 있는 상태가 되었다.

또한 HPA를 처음 적용했을 때 다음 문제가 발생하였다.

```text
failed to get cpu utilization: missing request for cpu
```

HPA는 CPU utilization을 계산할 때 컨테이너의 CPU request 값을 기준으로 사용한다. 따라서 API와 Worker Deployment에 CPU request와 limit을 설정하였다.

이번 테스트에서는 로컬 환경에서 scale-out 동작을 명확히 관찰하기 위해 CPU request를 낮게 설정하고, HPA target CPU utilization을 20%로 설정하였다.

---

## 4. HPA 설정

API와 Worker에 각각 HPA를 적용하였다.

### API HPA

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: fastpass-api-hpa
  namespace: fastpass
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: fastpass-api
  minReplicas: 1
  maxReplicas: 3
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 20
```

### Worker HPA

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: fastpass-worker-hpa
  namespace: fastpass
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: fastpass-worker
  minReplicas: 1
  maxReplicas: 3
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 20
```

이번 테스트에서는 다음과 같은 설정을 사용하였다.

| 항목 | 값 |
|---|---:|
| minReplicas | 1 |
| maxReplicas | 3 |
| target CPU utilization | 20% |

---

## 5. 부하 테스트 전 상태

부하 테스트 전 HPA 상태는 다음과 같았다.

```text
NAME                  REFERENCE                    TARGETS        MINPODS   MAXPODS   REPLICAS
fastpass-api-hpa      Deployment/fastpass-api      cpu: 10%/20%   1         3         1
fastpass-worker-hpa   Deployment/fastpass-worker   cpu: 10%/20%   1         3         1
```

이를 통해 다음을 확인하였다.

- HPA 객체가 정상적으로 생성됨
- metrics-server를 통해 CPU metric이 정상 수집됨
- API와 Worker가 각각 1 replica 상태에서 테스트를 시작함
- 부하 전 CPU 사용률은 target 20%보다 낮음

즉, 이번 테스트는 API와 Worker가 각각 1 replica인 상태에서 시작하였으며, 부하에 따라 실제로 HPA가 replica를 늘리는지 확인할 수 있는 조건이었다.

---

## 6. 부하 테스트 방법

부하 테스트는 k6를 사용하여 수행하였다.

테스트 스크립트는 먼저 이벤트를 생성한 뒤, 생성된 이벤트 ID를 대상으로 신청 API를 반복 호출하도록 구성하였다.

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

export const options = {
  scenarios: {
    hpa_scale_test: {
      executor: 'constant-vus',
      vus: 30,
      duration: '90s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:18080';

export function setup() {
  const params = {
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
    },
  };

  for (let attempt = 1; attempt <= 5; attempt++) {
    const payload = JSON.stringify({
      title: `FastPass HPA Test ${Date.now()}`,
      description: 'hpa scale out test',
      capacity: 100000,
      eventStartAt: '2026-07-20T10:00:00',
    });

    const res = http.post(`${BASE_URL}/api/events`, payload, params);

    const ok = check(res, {
      'event created': (r) => r.status === 200 || r.status === 201,
    });

    if (ok) {
      return {
        eventId: res.json('id'),
      };
    }

    console.error(`Failed to create event. attempt=${attempt}, status=${res.status}, error=${res.error}, body=${res.body}`);
    sleep(2);
  }

  exec.test.abort('Failed to create event after 5 attempts.');
}

export default function (data) {
  const payload = JSON.stringify({
    applicantName: `hpa-user-${__VU}-${__ITER}`,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
    },
  };

  const res = http.post(`${BASE_URL}/api/events/${data.eventId}/apply`, payload, params);

  check(res, {
    'apply accepted': (r) => r.status === 200 || r.status === 201,
  });

  sleep(0.1);
}

export function teardown(data) {
  const queueRes = http.get(`${BASE_URL}/api/queue/applications/size`);
  console.log(`Final queue size response: ${queueRes.body}`);

  const eventRes = http.get(`${BASE_URL}/api/events/${data.eventId}`);
  console.log(`Event response: ${eventRes.body}`);
}
```

테스트 조건은 다음과 같다.

| 항목 | 값 |
|---|---:|
| VUs | 30 |
| Duration | 90초 |
| Executor | constant-vus |
| 요청 대상 | `/api/events/{eventId}/apply` |
| 실패율 기준 | `http_req_failed < 5%` |

테스트는 port-forward를 통해 로컬 PC에서 Kubernetes Service로 접근하는 방식으로 수행하였다.

```text
localhost:18080
  -> kubectl port-forward
  -> service/fastpass-api:8080
  -> fastpass-api Pod
```

k6는 Docker container로 실행하였기 때문에, container 내부에서 호스트 PC의 port-forward 포트에 접근하기 위해 `host.docker.internal:18080`을 사용하였다.

---

## 7. k6 테스트 결과

k6 테스트 결과는 다음과 같았다.

```text
scenarios:
  hpa_scale_test: 30 VUs for 1m30s

checks_total: 3656
checks_succeeded: 100.00%
checks_failed: 0.00%

http_req_failed: 0.00%
http_reqs: 3658
iterations: 3655

http_req_duration:
  avg: 638.86ms
  med: 560.43ms
  p90: 1s
  p95: 1.2s
  max: 3.62s
```

테스트 종료 시점의 Queue 및 Event 상태는 다음과 같았다.

```text
Final queue size response: {"size":2024}

Event response:
{
  "id":9,
  "title":"FastPass HPA Test 1786362271880",
  "description":"hpa scale out test",
  "capacity":100000,
  "appliedCount":1581,
  "eventStartAt":"2026-07-20T10:00:00",
  "createdAt":"2026-08-10T11:44:32.303813"
}
```

결과적으로 API는 부하 테스트 중 요청 실패 없이 신청 요청을 정상적으로 수신하였다.

요약하면 다음과 같다.

| 항목 | 결과 |
|---|---:|
| 총 신청 요청 수 | 3,655회 |
| HTTP 요청 수 | 3,658회 |
| HTTP 실패율 | 0.00% |
| Check 성공률 | 100.00% |
| p95 응답 시간 | 1.2초 |
| 테스트 종료 시 Queue size | 2,024 |
| 테스트 종료 시 appliedCount | 1,581 |

---

## 8. HPA 동작 결과

부하 테스트 중 HPA 상태를 관찰한 결과, API와 Worker 모두 scale-out 되었다.

초기 상태는 각각 1 replica였으며, 부하 발생 후 다음과 같이 증가하였다.

```text
fastpass-api-hpa      REPLICAS 1 -> 2 -> 3
fastpass-worker-hpa   REPLICAS 1 -> 2 -> 3
```

부하 중 CPU 사용률은 target인 20%를 초과하였다.

예시 관찰 결과는 다음과 같다.

```text
fastpass-api-hpa      cpu: 13%/20%    REPLICAS 2
fastpass-worker-hpa   cpu: 19%/20%    REPLICAS 2

fastpass-worker-hpa   cpu: 24%/20%    REPLICAS 2

fastpass-api-hpa      cpu: 308%/20%   REPLICAS 2
fastpass-worker-hpa   cpu: 146%/20%   REPLICAS 3

fastpass-api-hpa      cpu: 509%/20%   REPLICAS 3
fastpass-worker-hpa   cpu: 212%/20%   REPLICAS 3
```

이를 통해 HPA가 CPU 사용률 증가를 감지하고, API 및 Worker Deployment를 자동으로 확장했음을 확인하였다.

---

## 9. 테스트 결과 해석

이번 테스트에서 확인한 내용은 다음과 같다.

1. metrics-server가 정상적으로 동작하였다.
2. HPA가 CPU metric을 정상적으로 수집하였다.
3. API Deployment가 부하에 따라 자동으로 scale-out 되었다.
4. Worker Deployment도 부하에 따라 자동으로 scale-out 되었다.
5. k6 부하 테스트 중 HTTP 요청 실패율은 0.00%였다.

따라서 FastPass는 Kubernetes 환경에서 기본적인 CPU 기반 자동 확장 구조를 갖추었다고 볼 수 있다.

특히 API와 Worker를 별도 Deployment로 분리했기 때문에, 사용자 요청을 받는 API 계층과 Queue를 처리하는 Worker 계층을 독립적으로 확장할 수 있었다.

---

## 10. 한계점

이번 테스트에서 HPA scale-out은 성공했지만, 테스트 종료 시점에 Redis Queue에는 아직 처리되지 않은 신청 건이 남아 있었다.

```text
Final queue size: 2024
Event appliedCount: 1581
```

이는 API가 요청을 정상적으로 수신하여 Queue에 적재하는 속도보다 Worker가 Queue를 처리하는 속도가 느렸음을 의미한다.

즉, CPU 기반 HPA가 정상적으로 동작하더라도 Worker의 실제 병목은 CPU 사용률이 아니라 Queue backlog일 수 있다.

Worker의 경우 CPU 사용률 기반 HPA만으로는 Queue 적체 상황을 정확히 반영하기 어렵다. 운영 환경에서는 다음과 같은 방식이 더 적합할 수 있다.

- Redis Queue length 기반 Worker autoscaling
- Prometheus metric 기반 HPA
- KEDA를 이용한 event-driven autoscaling
- Queue backlog 기준 alerting
- Worker 처리량 및 지연 시간 모니터링

이번 결과는 단순히 “HPA가 동작했다”는 검증에 그치지 않고, Worker autoscaling 전략을 Queue 기반으로 고도화해야 한다는 개선 방향을 도출했다는 점에서 의미가 있다.

---

## 11. 포트포워딩 방식의 한계

이번 테스트는 기존 로컬 테스트 흐름을 유지하기 위해 `kubectl port-forward`를 사용하였다.

다만 HPA 테스트 중 port-forward 연결이 끊기는 문제가 발생할 수 있었다.

예시 에러는 다음과 같다.

```text
error: lost connection to pod
failed to connect to localhost:8080 inside namespace
connect: connection refused
```

이는 port-forward가 특정 Pod와의 연결에 의존하기 때문에, 부하 테스트 중 Pod 재시작, 일시적인 연결 불안정, HPA에 의한 Pod 변화가 발생하면 연결이 끊길 수 있기 때문이다.

따라서 port-forward는 간단한 기능 테스트에는 적합하지만, 반복적인 부하 테스트나 HPA 검증에는 안정성이 떨어질 수 있다.

운영 환경 또는 더 안정적인 로컬 검증에서는 다음 방식이 더 적합하다.

- Ingress
- LoadBalancer Service
- NodePort Service
- Kubernetes 내부 Job으로 k6 실행

이번 단계에서는 기존 개발 흐름을 유지하기 위해 port-forward를 사용했지만, 이후 단계에서는 Ingress 또는 Kubernetes 내부 부하 테스트 방식으로 개선할 수 있다.

---

## 12. 결론

이번 단계에서는 FastPass API와 Worker에 Kubernetes HPA를 적용하고, k6 부하 테스트를 통해 자동 확장 동작을 검증하였다.

부하 전 API와 Worker는 각각 1 replica 상태였고, HPA target CPU utilization은 20%로 설정하였다. k6를 이용해 30 VUs, 90초 동안 신청 API에 부하를 발생시킨 결과, HTTP 요청 실패율은 0.00%였으며 총 3,655회의 신청 요청이 성공하였다.

부하 중 API HPA는 1 replica에서 최대 3 replicas까지 증가했고, Worker HPA 역시 1 replica에서 최대 3 replicas까지 증가하였다.

다만 테스트 종료 시점에 Redis Queue size가 2,024로 남아 있었기 때문에, CPU 기반 HPA가 정상적으로 동작하더라도 Queue backlog를 직접 반영한 Worker scaling에는 한계가 있음을 확인하였다.

따라서 본 프로젝트의 다음 개선 방향은 Prometheus 또는 KEDA를 활용한 Queue Length 기반 Worker autoscaling이다.