# Worker Replica 확장 테스트

## 1. 테스트 목적

이전 단계에서 FastPass는 API와 Worker를 Kubernetes Deployment 단위로 분리했다.

```text
fastpass-api Deployment
  → HTTP 요청 접수
  → PENDING 저장
  → Redis Queue 적재

fastpass-worker Deployment
  → Redis Queue 소비
  → SUCCESS / FAILED 처리
```

이번 테스트의 목적은 Worker Deployment를 독립적으로 확장했을 때 Queue 소비 성능이 실제로 개선되는지 확인하는 것이다.

즉, 다음 질문을 검증한다.

```text
Worker Pod 수를 늘리면 Redis Queue backlog가 줄어드는가?
Worker Pod 수를 늘리면 같은 부하 조건에서 처리 완료 수가 증가하는가?
API와 Worker를 분리한 구조가 독립 확장에 의미가 있는가?
```

---

## 2. 테스트 전제

테스트 전 API와 Worker는 이미 분리되어 있었다.

```text
fastpass-api Pod
  FASTPASS_WORKER_ENABLED=false

fastpass-worker Pod
  FASTPASS_WORKER_ENABLED=true
```

따라서 API Pod는 신청 요청을 접수하고 Redis Queue에 적재하는 역할만 담당한다.

Worker Pod는 Redis Queue를 소비하여 신청 상태를 `SUCCESS` 또는 `FAILED`로 변경한다.

---

## 3. 테스트 대상 Kubernetes 구성

테스트 대상 Namespace는 `fastpass`이다.

```bash
kubectl get pods -n fastpass
```

테스트 전 Pod 상태는 다음과 같았다.

```text
NAME                               READY   STATUS    RESTARTS
fastpass-api-595cfc459-lmllt       1/1     Running   1
fastpass-worker-5f47985cbd-h9phd   1/1     Running   1
postgres-85d56865bf-wk62x          1/1     Running   1
redis-b67748d94-z4n4p              1/1     Running   2
```

API, Worker, PostgreSQL, Redis가 모두 정상 실행 중인 상태에서 테스트를 진행했다.

---

## 4. 테스트 전 Queue 상태 확인

테스트 시작 전 Redis Queue가 비어 있는지 확인했다.

```bash
curl http://localhost:18080/api/queue/applications/size
```

응답:

```json
{
  "size": 0
}
```

Queue가 비어 있는 상태에서 테스트를 시작했기 때문에, 테스트 종료 시점의 Queue size는 해당 테스트에서 발생한 backlog로 해석할 수 있다.

---

## 5. 테스트 시나리오

동일한 k6 스크립트를 사용하여 Worker replica 수만 변경하면서 테스트했다.

사용한 k6 스크립트:

```text
load-test/k6/apply-queue-test.js
```

테스트 조건은 다음과 같다.

| 항목 | 값 |
|---|---:|
| 부하 테스트 도구 | k6 |
| 실행 방식 | Docker 기반 k6 실행 |
| API 접근 주소 | http://host.docker.internal:18080 |
| 가상 사용자 수 | 20 VUs |
| 테스트 시간 | 30초 |
| graceful stop | 30초 |
| 이벤트 capacity | 10000 |
| threshold: HTTP 실패율 | 5% 미만 |
| threshold: p95 응답시간 | 1000ms 미만 |

테스트 실행 명령어:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:18080 \
  -v "${PWD}/load-test/k6:/scripts" \
  grafana/k6 run /scripts/apply-queue-test.js
```

---

## 6. Worker replica 1개 테스트

Worker Deployment를 1개로 설정했다.

```bash
kubectl scale deployment fastpass-worker -n fastpass --replicas=1
kubectl get pods -n fastpass
```

이후 k6 테스트를 실행했다.

### 6.1 Worker 1개 테스트 결과

k6 결과 요약:

```text
iterations: 540
http_reqs: 543
http_req_failed: 0.00%
checks_succeeded: 100.00%
http_req_duration avg: 116.22ms
http_req_duration med: 87.54ms
http_req_duration p(90): 175.86ms
http_req_duration p(95): 223.95ms
http_req_duration max: 737.98ms
```

테스트 종료 시점 로그:

```text
Final queue size response: {"size":80}
Event response: {"id":5,"title":"FastPass k6 Event 1786266951341","description":"k6 queue load test","capacity":10000,"appliedCount":431}
```

Worker 1개 테스트 결과는 다음과 같다.

| 항목 | 결과 |
|---|---:|
| 신청 요청 수 | 540건 |
| 처리 완료 수 | 431건 |
| Queue size | 80건 |
| HTTP 실패율 | 0.00% |
| Check 성공률 | 100.00% |
| 평균 응답시간 | 116.22ms |
| p95 응답시간 | 223.95ms |

Worker 1개 상태에서는 30초 동안 540건의 신청 요청이 발생했고, 테스트 종료 시점까지 431건이 처리되었다.

Queue에는 80건이 남아 있었다.

---

## 7. Worker replica 2개 테스트

Worker Deployment를 2개로 확장했다.

```bash
kubectl scale deployment fastpass-worker -n fastpass --replicas=2
kubectl get pods -n fastpass
```

이후 동일한 k6 스크립트를 다시 실행했다.

### 7.1 Worker 2개 테스트 결과

k6 결과 요약:

```text
iterations: 565
http_reqs: 568
http_req_failed: 0.00%
checks_succeeded: 100.00%
http_req_duration avg: 70.97ms
http_req_duration med: 58.8ms
http_req_duration p(90): 109.9ms
http_req_duration p(95): 155.25ms
http_req_duration max: 230.7ms
```

테스트 종료 시점 로그:

```text
Final queue size response: {"size":0}
Event response: {"id":6,"title":"FastPass k6 Event 1786269664599","description":"k6 queue load test","capacity":10000,"appliedCount":565}
```

Worker 2개 테스트 결과는 다음과 같다.

| 항목 | 결과 |
|---|---:|
| 신청 요청 수 | 565건 |
| 처리 완료 수 | 565건 |
| Queue size | 0건 |
| HTTP 실패율 | 0.00% |
| Check 성공률 | 100.00% |
| 평균 응답시간 | 70.97ms |
| p95 응답시간 | 155.25ms |

Worker 2개 상태에서는 30초 동안 565건의 신청 요청이 발생했고, 테스트 종료 시점까지 565건이 모두 처리되었다.

Queue에는 남은 신청이 없었다.

---

## 8. Worker 1개 vs Worker 2개 비교

| 항목 | Worker 1개 | Worker 2개 |
|---|---:|---:|
| 테스트 시간 | 30초 | 30초 |
| VUs | 20 | 20 |
| 신청 요청 수 | 540건 | 565건 |
| 처리 완료 수 | 431건 | 565건 |
| Queue size | 80건 | 0건 |
| HTTP 실패율 | 0.00% | 0.00% |
| Check 성공률 | 100.00% | 100.00% |
| 평균 응답시간 | 116.22ms | 70.97ms |
| p95 응답시간 | 223.95ms | 155.25ms |

---

## 9. 처리 완료율 비교

Worker 1개 상태의 처리 완료율은 다음과 같다.

```text
처리 완료율 = 처리 완료 수 / 신청 요청 수
           = 431 / 540
           ≈ 79.8%
```

Worker 2개 상태의 처리 완료율은 다음과 같다.

```text
처리 완료율 = 처리 완료 수 / 신청 요청 수
           = 565 / 565
           = 100.0%
```

비교 결과:

| 항목 | Worker 1개 | Worker 2개 |
|---|---:|---:|
| 처리 완료율 | 약 79.8% | 100.0% |

Worker replica를 1개에서 2개로 늘렸을 때, 테스트 종료 시점 기준 처리 완료율이 약 79.8%에서 100.0%로 증가했다.

---

## 10. Queue backlog 비교

Worker 1개 상태에서는 테스트 종료 시점에 Queue가 남아 있었다.

```text
Worker 1개:
Queue size = 80
```

Worker 2개 상태에서는 Queue가 모두 소비되었다.

```text
Worker 2개:
Queue size = 0
```

즉, Worker replica 증가 후 Queue backlog가 제거되었다.

```text
Queue backlog 감소:
80건 → 0건
```

이는 Worker Pod 수 증가가 Redis Queue 소비 속도 향상으로 이어졌음을 보여준다.

---

## 11. 응답시간 비교

응답시간 지표도 함께 비교했다.

```text
평균 응답시간:
116.22ms → 70.97ms

p95 응답시간:
223.95ms → 155.25ms
```

p95 응답시간 감소율은 다음과 같다.

```text
감소량 = 223.95ms - 155.25ms = 68.70ms
감소율 = 68.70 / 223.95 × 100 ≈ 30.7%
```

다만 API 응답시간은 Worker replica 증가만으로 결정되는 지표는 아니다.

FastPass API는 신청 요청을 최종 처리하지 않고 `PENDING`으로 저장한 뒤 Redis Queue에 적재하고 응답한다.

따라서 Worker 수 증가는 주로 Queue 소비 속도와 처리 완료 수에 직접적인 영향을 준다.

응답시간 개선은 보조 지표로 해석하는 것이 적절하다.

---

## 12. HTTP 안정성 확인

두 테스트 모두 HTTP 실패율은 0.00%였다.

```text
Worker 1개:
http_req_failed = 0.00%

Worker 2개:
http_req_failed = 0.00%
```

또한 k6 check도 모두 성공했다.

```text
Worker 1개:
checks_succeeded = 100.00%

Worker 2개:
checks_succeeded = 100.00%
```

검증한 check는 다음과 같다.

```text
event created
apply request accepted
application status is PENDING
```

즉, Worker replica 수를 변경해도 API 요청 접수는 안정적으로 유지되었다.

---

## 13. 결과 해석

이번 테스트의 핵심 결과는 다음과 같다.

```text
Worker 1개:
540건 신청 요청
431건 처리 완료
Queue 80건 잔여

Worker 2개:
565건 신청 요청
565건 처리 완료
Queue 0건 잔여
```

Worker replica를 1개에서 2개로 확장하자, 동일한 20 VU / 30초 부하 조건에서 Queue backlog가 사라지고 처리 완료율이 100%가 되었다.

이를 통해 API와 Worker를 분리한 구조가 실제로 독립 확장 효과를 가진다는 것을 확인했다.

```text
API 요청 접수 계층은 그대로 유지
Worker 처리 계층만 확장
Redis Queue 소비 속도 증가
Queue backlog 감소
```

---

## 14. Kubernetes 관점의 의미

이번 테스트는 단순히 Worker Pod를 하나 더 실행한 것이 아니라, Kubernetes Deployment 분리의 효과를 검증한 것이다.

API와 Worker가 하나의 Pod 안에 있었다면 Worker만 따로 늘릴 수 없다.

기존 구조에서는 API Pod를 늘리는 것과 Worker를 늘리는 것이 함께 묶인다.

```text
기존 구조:
fastpass-api Pod 안에 API + Worker 동시 실행
→ API만 확장하거나 Worker만 확장하기 어려움
```

현재 구조에서는 Worker만 독립적으로 확장할 수 있다.

```bash
kubectl scale deployment fastpass-worker -n fastpass --replicas=2
```

즉, 요청 접수 계층과 Queue 소비 계층을 각각 다른 기준으로 확장할 수 있다.

```text
API 요청량 증가:
  → fastpass-api replicas 증가

Queue backlog 증가:
  → fastpass-worker replicas 증가
```

---

## 15. 동시 처리 안정성

Worker가 2개 이상 실행되면 여러 Worker가 동시에 같은 이벤트의 신청을 처리할 수 있다.

이때 정원 초과나 appliedCount 불일치가 발생하지 않도록 Event 조회 시 pessimistic lock을 사용한다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select e from Event e where e.id = :eventId")
Optional<Event> findByIdForUpdate(@Param("eventId") Long eventId);
```

이 락은 여러 Worker가 동일 Event를 동시에 갱신할 때 하나의 트랜잭션만 먼저 처리되도록 한다.

따라서 Worker replica를 늘려도 Event의 `appliedCount` 증가와 정원 초과 판단이 안정적으로 수행될 수 있다.

---

## 16. 포트폴리오 관점의 정리

이번 단계는 FastPass 프로젝트에서 운영형 구조를 보여주는 중요한 검증이다.

포트폴리오에서는 다음과 같이 설명할 수 있다.

```text
API와 Worker를 별도 Kubernetes Deployment로 분리한 뒤,
Worker replica 수를 1개에서 2개로 확장하여 Queue 소비 성능을 비교했다.

동일한 k6 부하 조건에서 Worker 1개일 때는 540건 중 431건이 처리되고 Queue에 80건이 남았지만,
Worker 2개일 때는 565건이 모두 처리되고 Queue size가 0으로 감소했다.

이를 통해 Redis Queue 기반 비동기 구조에서 Worker 계층을 독립적으로 확장할 수 있음을 확인했다.
```

이 결과는 다음 역량을 보여준다.

```text
Kubernetes Deployment 분리
환경변수 기반 역할 제어
Redis Queue 기반 비동기 처리
k6 부하 테스트
Worker replica 확장
Queue backlog 관찰
병목 확인 및 개선
```

---

## 17. 남아 있는 한계

이번 테스트는 로컬 Docker Desktop Kubernetes 환경에서 수행되었다.

따라서 실제 운영 환경과는 차이가 있다.

| 한계 | 설명 |
|---|---|
| 로컬 Kubernetes 환경 | 실제 EKS나 운영 클러스터가 아님 |
| 단일 노드 환경 | Pod가 하나의 로컬 노드에서 실행됨 |
| 동일한 PostgreSQL 사용 | DB 성능 병목이 별도로 분리되지 않음 |
| 테스트 규모 제한 | 20 VU / 30초 단위의 소규모 부하 |
| Worker 2개까지만 비교 | 더 많은 replica에서의 확장성은 추가 검증 필요 |
| Queue 처리 지연 시간 미측정 | PENDING에서 SUCCESS까지 걸린 시간을 별도 지표로 수집하지 않음 |
| 모니터링 미구축 | Prometheus/Grafana 기반 시각화는 아직 적용 전 |

따라서 이번 결과는 "로컬 Kubernetes 환경에서 Worker replica 증가가 Queue backlog 감소에 효과적임을 확인한 테스트"로 해석하는 것이 적절하다.

---

## 18. 다음 개선 방향

다음 단계에서는 다음 개선을 진행할 수 있다.

| 개선 방향 | 설명 |
|---|---|
| Worker 3개 이상 테스트 | replica 수 증가에 따른 Queue 소비 성능 추가 비교 |
| 1,000건 이상 대용량 테스트 | 요청량을 늘려 Queue backlog 발생 조건 확인 |
| 처리 지연 시간 측정 | 신청 생성 시각과 처리 완료 시각 차이 계산 |
| Prometheus/Grafana 적용 | 요청 수, 응답시간, Queue size, 처리량 시각화 |
| HPA 적용 | CPU 또는 Queue size 기반 자동 확장 구성 |
| 리소스 제한 설정 | requests/limits를 설정하여 Pod 자원 관리 |
| 장애 테스트 | Worker 중지, Redis 중지, API Pod 재시작 상황 검증 |

---

## 19. 완료 기준

Worker replica 확장 테스트의 완료 기준은 다음과 같다.

```text
테스트 전 Queue size 0 확인
Worker replica 1개 설정
Worker 1개 상태에서 k6 테스트 수행
Worker 1개 상태에서 Queue backlog 확인
Worker replica 2개 설정
Worker 2개 상태에서 k6 테스트 수행
Worker 2개 상태에서 Queue size 0 확인
두 테스트 모두 HTTP 실패율 0.00% 확인
두 테스트 모두 Check 성공률 100.00% 확인
Worker replica 증가 후 처리 완료 수 증가 확인
Worker replica 증가 후 Queue backlog 감소 확인
```

위 기준을 만족했으므로 Worker replica 확장 테스트를 완료했다.