# Worker Batch 처리 개선 및 부하 테스트 비교

## 1. 개선 목적

이전 k6 부하 테스트에서 FastPass API는 동시 신청 요청을 안정적으로 접수했지만, Worker 처리량이 낮아 Redis Queue backlog가 크게 발생했다.

이전 구조에서는 Worker가 일정 주기마다 Queue에서 신청 건을 1개씩만 꺼내 처리했다.

```text
기존 구조:
1초마다 Redis Queue 확인
  → applicationId 1개 dequeue
  → 신청 1건 처리
  → SUCCESS 또는 FAILED 상태 변경
```

이 구조는 단순하지만, 이벤트 오픈 시점처럼 짧은 시간 동안 많은 신청 요청이 몰리는 상황에서는 Queue 소비 속도가 요청 접수 속도를 따라가지 못한다.

따라서 Worker가 한 번 실행될 때 여러 신청 건을 가져와 처리하도록 batch 구조로 개선했다.

```text
개선 구조:
1초마다 Redis Queue 확인
  → applicationId를 최대 50개 dequeue
  → 신청 건들을 순차 처리
  → 각 신청 건을 SUCCESS 또는 FAILED 상태로 변경
```

---

## 2. 개선 전 확인된 문제

기존 Worker 구조에서 k6 부하 테스트를 수행한 결과는 다음과 같았다.

| 항목 | 결과 |
|---|---:|
| 가상 사용자 수 | 20 VUs |
| 테스트 시간 | 30초 |
| 신청 요청 수 | 550건 |
| HTTP 실패율 | 0.00% |
| Check 성공률 | 100.00% |
| p95 응답시간 | 251.38ms |
| 테스트 종료 시 처리 완료 수 | 29건 |
| 테스트 종료 시 Queue size | 521건 |

테스트 결과, API는 모든 신청 요청을 `PENDING` 상태로 정상 접수했다.

하지만 테스트 종료 시점에 실제 처리 완료 수는 29건에 불과했고, Redis Queue에는 521건이 남아 있었다.

```text
총 신청 요청: 550건
처리 완료: 29건
Queue 대기: 521건
```

이는 API 요청 접수는 정상적으로 수행되지만, Worker의 Queue 소비 속도가 낮다는 것을 의미한다.

---

## 3. 개선 방향

병목은 API 요청 접수가 아니라 Worker 처리량에 있었다.

따라서 다음과 같이 Worker 구조를 개선했다.

```text
기존:
@Scheduled(fixedDelay = 1000)
  → Queue에서 1건 dequeue
  → 1건 처리

개선:
@Scheduled(fixedDelay = 1000)
  → Queue에서 최대 50건 dequeue
  → 최대 50건 순차 처리
```

개선 목표는 다음과 같다.

```text
Redis Queue 소비량 증가
Queue backlog 감소
처리 완료 수 증가
부하 상황에서 PENDING 상태 유지 시간 감소
```

---

## 4. 주요 코드 변경

### 4.1 ApplicationQueueService batch dequeue 추가

`ApplicationQueueService`에 여러 신청 ID를 한 번에 가져오는 `dequeueBatch` 메서드를 추가했다.

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

이 메서드는 Redis Queue에서 최대 `batchSize`개만큼 신청 ID를 가져온다.

Queue가 비어 있으면 반복을 중단한다.

---

### 4.2 ApplicationProcessor 분리

기존에는 Worker 내부에서 신청 처리 로직을 직접 수행했다.

개선 후에는 신청 1건의 처리 책임을 `ApplicationProcessor`로 분리했다.

```java
@Transactional
public void process(Long applicationId) {
    EventApplication application = applicationRepository.findById(applicationId)
            .orElseThrow(() -> new NotFoundException("Application not found. id=" + applicationId));

    Event event = eventRepository.findByIdForUpdate(application.getEvent().getId())
            .orElseThrow(() -> new NotFoundException("Event not found. id=" + application.getEvent().getId()));

    if (event.isFull()) {
        application.markFailed();
        return;
    }

    event.increaseAppliedCount();
    application.markSuccess();
}
```

이렇게 분리한 이유는 다음과 같다.

```text
Worker는 Queue 소비 흐름을 담당
ApplicationProcessor는 신청 1건의 비즈니스 처리를 담당
각 신청 건 처리 로직을 독립적으로 관리 가능
향후 Worker 분리 또는 replica 확장 시 구조 확장 용이
```

---

### 4.3 ApplicationQueueWorker batch 처리 적용

Worker는 더 이상 Queue에서 1건만 꺼내지 않는다.

```java
private static final int BATCH_SIZE = 50;

@Scheduled(fixedDelay = 1000)
public void processApplicationQueue() {
    List<Long> applicationIds = applicationQueueService.dequeueBatch(BATCH_SIZE);

    if (applicationIds.isEmpty()) {
        return;
    }

    for (Long applicationId : applicationIds) {
        try {
            applicationProcessor.process(applicationId);
        } catch (Exception e) {
            System.err.println("Failed to process application. applicationId=" + applicationId + ", message=" + e.getMessage());
        }
    }
}
```

현재 설정에서는 Worker가 1초마다 최대 50건의 신청을 처리할 수 있다.

---

## 5. 개선 후 재배포 과정

Worker batch 처리 코드를 적용한 뒤 API 이미지를 다시 빌드했다.

```bash
cd /d/coding/project/fastpass-k8s

docker compose build api
```

로컬 Docker 이미지를 Kubernetes 노드 내부 이미지 저장소로 다시 import했다.

```bash
docker save fastpass-k8s-api:latest | docker exec -i desktop-control-plane ctr -n k8s.io images import -
```

API Deployment를 재시작했다.

```bash
kubectl rollout restart deployment/fastpass-api -n fastpass
```

Pod 상태를 확인했다.

```bash
kubectl get pods -n fastpass
```

확인 결과:

```text
fastpass-api   1/1   Running
postgres       1/1   Running
redis          1/1   Running
```

---

## 6. 개선 후 k6 부하 테스트

동일한 k6 스크립트를 사용하여 개선 후 테스트를 다시 수행했다.

테스트 조건은 다음과 같다.

| 항목 | 값 |
|---|---:|
| 가상 사용자 수 | 20 VUs |
| 테스트 시간 | 30초 |
| Threshold: 실패율 | 5% 미만 |
| Threshold: p95 응답시간 | 1000ms 미만 |

실행 명령어:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:18080 \
  -v "${PWD}/load-test/k6:/scripts" \
  grafana/k6 run /scripts/apply-queue-test.js
```

---

## 7. 개선 후 테스트 결과

k6 실행 결과 요약은 다음과 같다.

| 항목 | 결과 |
|---|---:|
| 신청 요청 수 | 540건 |
| HTTP 요청 수 | 543건 |
| HTTP 실패율 | 0.00% |
| Check 성공률 | 100.00% |
| 평균 응답시간 | 113.04ms |
| 중앙값 응답시간 | 79.87ms |
| p90 응답시간 | 120.18ms |
| p95 응답시간 | 152.13ms |
| 최대 응답시간 | 1.04s |
| 테스트 종료 시 처리 완료 수 | 434건 |
| 테스트 종료 시 Queue size | 100건 |

Threshold 결과:

```text
http_req_duration
  ✓ p(95)<1000
  p(95)=152.13ms

http_req_failed
  ✓ rate<0.05
  rate=0.00%
```

Check 결과:

```text
✓ event created
✓ apply request accepted
✓ application status is PENDING
```

테스트 종료 시점의 로그:

```text
Final queue size response: {"size":100}
Event response: {"id":3,"title":"FastPass k6 Event 1786180635756","description":"k6 queue load test","capacity":10000,"appliedCount":434}
```

---

## 8. 개선 전후 비교

| 항목 | 개선 전 | 개선 후 |
|---|---:|---:|
| 테스트 시간 | 30초 | 30초 |
| 가상 사용자 수 | 20 VUs | 20 VUs |
| 신청 요청 수 | 550건 | 540건 |
| 처리 완료 수 | 29건 | 434건 |
| 남은 Queue size | 521건 | 100건 |
| HTTP 실패율 | 0.00% | 0.00% |
| Check 성공률 | 100.00% | 100.00% |
| p95 응답시간 | 251.38ms | 152.13ms |

---

## 9. 개선 효과 분석

### 9.1 처리 완료 수 증가

```text
개선 전: 29건
개선 후: 434건
```

Worker batch 처리 적용 후 테스트 종료 시점의 처리 완료 수가 크게 증가했다.

```text
434 / 29 ≈ 14.97
```

즉, 테스트 종료 시점 기준 처리 완료 수가 약 15배 증가했다.

---

### 9.2 Queue 잔여량 감소

```text
개선 전 Queue size: 521건
개선 후 Queue size: 100건
```

Queue 잔여량은 다음과 같이 감소했다.

```text
감소량 = 521 - 100 = 421건
감소율 = 421 / 521 × 100 ≈ 80.8%
```

즉, Worker batch 처리 적용 후 테스트 종료 시점의 Queue backlog가 약 80.8% 감소했다.

---

### 9.3 p95 응답시간 개선

```text
개선 전 p95: 251.38ms
개선 후 p95: 152.13ms
```

p95 응답시간은 다음과 같이 개선되었다.

```text
감소량 = 251.38 - 152.13 = 99.25ms
감소율 = 99.25 / 251.38 × 100 ≈ 39.5%
```

응답시간 개선은 Worker batch 처리만의 효과라고 단정하기는 어렵다.

다만 동일한 테스트 조건에서 API 요청 실패 없이 더 많은 신청을 처리했고, p95 응답시간도 threshold를 안정적으로 만족했다.

---

## 10. 결과 해석

이번 개선으로 FastPass는 동시 신청 요청을 계속 `PENDING`으로 안정적으로 접수하면서도, Worker가 Redis Queue를 훨씬 빠르게 소비할 수 있게 되었다.

개선 전에는 요청 접수와 실제 처리 사이의 차이가 크게 벌어졌다.

```text
개선 전:
550건 요청
29건 처리
521건 Queue 대기
```

개선 후에는 같은 30초 부하 상황에서 훨씬 많은 신청이 처리되었다.

```text
개선 후:
540건 요청
434건 처리
100건 Queue 대기
```

이는 Worker batch 처리가 Queue backlog를 줄이는 데 효과적이었음을 보여준다.

---

## 11. appliedCount와 Queue size가 정확히 합산되지 않는 이유

개선 후 결과는 다음과 같다.

```text
iterations = 540
appliedCount = 434
queue size = 100
```

두 값을 합치면 534건이다.

```text
434 + 100 = 534
```

신청 요청 수 540건과 6건 차이가 발생한다.

이는 측정 시점의 차이 때문에 발생할 수 있다.

Worker가 Redis Queue에서 applicationId를 꺼낸 뒤 DB 처리 중인 순간에는 다음과 같은 상태가 된다.

```text
Redis Queue에서는 제거됨
아직 Event appliedCount에는 반영되지 않음
```

또한 k6의 teardown 단계에서 Queue 조회와 Event 조회는 완전히 같은 시각에 동시에 실행되지 않고 순차적으로 실행된다.

따라서 몇 건 정도의 차이는 비동기 처리 구조에서 자연스럽게 발생할 수 있다.

---

## 12. 현재 구조의 의미

이번 개선은 단순히 성능 수치를 높인 것이 아니라, 부하 테스트를 통해 병목을 확인하고 구조를 개선한 과정이다.

포트폴리오 관점에서 중요한 흐름은 다음과 같다.

```text
1. Redis Queue 기반 비동기 신청 처리 구현
2. k6 부하 테스트 수행
3. Queue backlog와 Worker 처리량 병목 확인
4. Worker batch 처리 적용
5. 동일 조건에서 부하 테스트 재수행
6. 처리 완료 수 증가 및 Queue backlog 감소 확인
```

즉, FastPass는 단순히 Kubernetes에 배포된 애플리케이션이 아니라, 부하 상황에서 병목을 관찰하고 개선한 운영형 프로젝트로 발전했다.

---

## 13. 남아 있는 한계

Worker batch 처리로 처리량은 개선되었지만, 현재 구조에는 아직 한계가 있다.

| 한계 | 설명 |
|---|---|
| API와 Worker가 같은 애플리케이션 내부에 존재 | API Pod가 늘어나면 Worker도 함께 늘어나는 구조 |
| Worker replica를 독립적으로 조절하기 어려움 | API 트래픽과 Queue 처리량을 분리해서 확장하기 어려움 |
| Redis Queue 처리 실패 재시도 구조 부족 | Worker 처리 중 예외 발생 시 재처리 정책이 제한적 |
| Dead Letter Queue 없음 | 반복 실패한 신청 건을 별도 Queue로 분리하지 않음 |
| Queue 처리 지연 시간 측정 없음 | PENDING에서 SUCCESS/FAILED까지 걸린 시간을 아직 수집하지 않음 |
| HPA 미적용 | Queue backlog나 CPU 사용량 기반 자동 확장 없음 |

---

## 14. 다음 개선 방향

다음 단계에서는 API와 Worker를 분리하여 더 운영 환경에 가까운 구조로 개선할 수 있다.

| 개선 방향 | 설명 |
|---|---|
| API/Worker 분리 | API는 요청 접수만 담당하고 Worker는 Queue 처리만 담당 |
| Worker Deployment 별도 생성 | Kubernetes에서 Worker Pod 수를 독립적으로 조절 |
| Worker replica 증가 테스트 | Worker Pod 수 증가에 따른 Queue 소비 속도 비교 |
| Queue 처리 지연 시간 측정 | 신청 생성 시각과 처리 완료 시각 차이를 측정 |
| k6 대용량 테스트 | 1,000건, 10,000건 단위 접수 테스트 수행 |
| HPA 적용 | CPU 또는 Queue size 기반 자동 확장 적용 |
| 모니터링 구축 | Prometheus/Grafana로 요청량, Queue size, 처리량 시각화 |

---

## 15. 완료 기준

이번 Worker batch 처리 개선 단계의 완료 기준은 다음과 같다.

```text
ApplicationQueueService에 batch dequeue 기능 추가
ApplicationProcessor로 신청 처리 로직 분리
ApplicationQueueWorker에 batch 처리 적용
Docker API 이미지 재빌드
Kubernetes API Pod 재배포
k6 동일 조건 부하 테스트 재수행
HTTP 실패율 0.00% 확인
신청 응답 PENDING 100% 확인
처리 완료 수 증가 확인
Queue 잔여량 감소 확인
개선 전후 비교 완료
```

위 기준을 만족했으므로 Worker batch 처리 개선을 완료했다.