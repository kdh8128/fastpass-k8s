# 20. 부하 테스트 기반 성능 병목 분석 및 Redis-first 구조 개선

## 1. 목적

FastPass는 선착순 이벤트 신청 서비스로, 순간적으로 많은 사용자가 동시에 `POST /api/events/{eventId}/apply` 요청을 보내는 상황을 핵심 부하 시나리오로 가정한다.

이번 개선의 목표는 다음과 같다.

- 300 VU 동시 신청 상황에서 발생한 높은 응답 지연의 원인을 계측으로 확인
- PostgreSQL connection contention 및 lock 경합 완화
- 선착순 정원(`capacity`) 초과 없이 정합성 유지
- 요청 실패율 0% 유지
- 구조 변경 전/후를 동일한 k6 조건으로 재측정
- 500 VU 스트레스 테스트를 통해 현재 구조의 한계 지점 확인

> 이 문서의 수치는 Docker Desktop 기반 로컬 Kubernetes 환경에서 측정한 결과다. 절대 성능 수치보다는 동일 환경/동일 부하 조건에서의 상대적 개선과 병목 분석 과정에 의미가 있다.

---

## 2. 테스트 환경

### 애플리케이션

- Spring Boot 3.5.x
- Java 21
- PostgreSQL 16
- Redis 7.2
- Kubernetes
- Argo CD + Helm GitOps
- HPA
- Prometheus / Grafana
- k6

### Kubernetes HPA

```yaml
hpa:
  enabled: true

  api:
    minReplicas: 1
    maxReplicas: 6
    cpuAverageUtilization: 60

  worker:
    minReplicas: 1
    maxReplicas: 6
    cpuAverageUtilization: 60
```

API/Worker의 CPU request는 초기 10m에서 50m으로 조정했다.

초기에는 CPU request가 지나치게 작아 유휴 상태에서도 HPA가 높은 CPU utilization으로 판단하는 문제가 있었고, 이후 다음 값으로 조정했다.

```yaml
resources:
  api:
    requests:
      cpu: 50m
      memory: 384Mi
    limits:
      cpu: 500m
      memory: 768Mi

  worker:
    requests:
      cpu: 50m
      memory: 384Mi
    limits:
      cpu: 500m
      memory: 768Mi
```

---

## 3. 부하 테스트 시나리오

### 기본 조건

- 동시 사용자: 300 VU
- 이벤트 정원: 100
- 각 VU는 한 번만 신청
- 모든 사용자의 `applicantName`은 서로 다르게 생성
- 신청 API는 `PENDING` 응답을 반환
- Worker가 Redis Queue를 소비해 최종적으로 `SUCCESS` 또는 `FAILED` 결정

### 검증 항목

k6에서 다음을 검증했다.

- 이벤트 생성 성공
- 모든 신청 요청이 200/201
- 신청 직후 상태가 `PENDING`
- `appliedCount <= capacity`
- `appliedCount == capacity`
- Queue가 최종적으로 모두 비워지는지 확인
- HTTP 요청 실패율 1% 미만

---

## 4. `/apply` 전용 latency 측정

초기 k6 스크립트는 `http_req_duration`을 사용했기 때문에 이벤트 생성, Queue 조회, Event 조회까지 포함된 전체 HTTP latency가 집계됐다.

최종 비교에서는 `/apply` 요청만 따로 측정하기 위해 custom Trend를 추가했다.

```javascript
import { Trend } from 'k6/metrics';

const applyDuration = new Trend(
  'apply_duration',
  true
);
```

신청 직후 실제 요청 시간을 기록한다.

```javascript
applyDuration.add(
  res.timings.duration
);
```

최종 threshold는 다음과 같다.

```javascript
thresholds: {
  apply_duration: ['p(95)<1000'],
  http_req_failed: ['rate<0.01'],
}
```

또한 Queue 응답은 다음 형식이다.

```json
{"size":0}
```

기존 코드의 아래 로직은 잘못된 검사였다.

```javascript
Number(queueRes.body) === 0
```

JSON 문자열 전체를 Number로 변환하면 `NaN`이 되기 때문이다.

따라서 아래와 같이 수정했다.

```javascript
const queueSize =
  Number(queueRes.json('size'));

if (queueSize === 0) {
  break;
}
```

최종적으로 `queue fully drained` check도 추가했다.

---

# 5. 최초 병목 분석

## 5.1 증상

300 VU burst에서 모든 요청과 정원 정합성은 정상적으로 유지됐지만 응답 지연이 매우 컸다.

대표적인 초기 300 VU 결과:

| 지표 | 값 |
|---|---:|
| Avg | 약 6.08s |
| p95 | 약 10.25s |
| 실패율 | 0% |
| Capacity | 정확히 100 |
| Oversell | 없음 |

단순 CPU 사용률만으로는 원인을 설명하기 어려워 HikariCP와 PostgreSQL 내부 상태를 추가로 확인했다.

---

## 5.2 HikariCP 확인

Prometheus에서 다음 metric을 확인했다.

```promql
hikaricp_connections_active{job="fastpass-api"}
```

```promql
hikaricp_connections_pending{job="fastpass-api"}
```

```promql
hikaricp_connections_acquire_seconds_max{job="fastpass-api"}
```

```promql
hikaricp_connections_usage_seconds_max{job="fastpass-api"}
```

초기 진단에서 확인한 대표 값:

- Hikari maximum pool size: 10
- active: 10 / 10 도달
- pending: 약 184
- acquire max: 약 12.9초
- timeout: 0

즉 DB connection을 얻지 못해 애플리케이션 요청이 대기하는 현상이 명확하게 확인됐다.

---

## 5.3 PostgreSQL 확인

PostgreSQL의 `max_connections`는 100이었다.

```sql
SHOW max_connections;
```

부하 중 `pg_stat_activity`를 관찰했을 때 다음 현상이 확인됐다.

- `idle in transaction`
- `ClientRead`
- `LWLock / WALWrite`
- `Lock / transactionid`
- `COMMIT` 대기
- 특정 transaction을 blocker로 갖는 세션

따라서 단순 Hikari pool 부족만이 아니라 트랜잭션이 connection을 오래 점유하고, 동일 Event row에 대한 동시 갱신 과정에서 lock contention이 발생하고 있음을 확인했다.

단, 실제 deadlock이 발생한 것은 아니므로 이를 deadlock으로 표현하지 않는다.

---

# 6. 단계별 개선

## 6.1 1차 개선 — Redis enqueue를 DB Transaction 밖으로 분리

### 기존

```text
@Transactional
Event 조회
→ 중복 확인
→ PENDING INSERT
→ Redis enqueue
→ COMMIT
```

Redis 작업까지 DB transaction 내부에서 수행돼 connection이 불필요하게 오래 유지됐다.

### 개선

DB 저장을 별도 서비스로 분리했다.

```text
DB Transaction
Event 조회
→ 중복 확인
→ PENDING INSERT
→ COMMIT
→ connection 반환

Redis enqueue
```

### 결과

API Hikari `usage_seconds_max`는 약 5.1초에서 약 2.9초로 감소했으나, 사용자 체감 p95는 유의미하게 개선되지 않았다.

즉 **트랜잭션 범위는 개선됐지만 이것만으로 전체 병목은 해결되지 않았다.**

---

## 6.2 2차 개선 — Pessimistic Lock을 Atomic UPDATE로 변경

### 기존 Worker

```text
Application SELECT
→ Event SELECT FOR UPDATE
→ isFull()
→ appliedCount 증가
→ SUCCESS / FAILED
```

`PESSIMISTIC_WRITE`를 사용해 동일 Event row에 대한 명시적 lock 경쟁이 발생했다.

### 개선

다음 조건부 UPDATE로 변경했다.

```sql
UPDATE events
SET applied_count = applied_count + 1
WHERE id = ?
  AND applied_count < capacity;
```

JPA에서는 affected row 수를 이용한다.

```java
int updatedRows =
    eventRepository.tryIncreaseAppliedCount(eventId);

if (updatedRows == 0) {
    application.markFailed();
    return;
}

application.markSuccess();
```

PostgreSQL 내부 row lock 자체가 사라지는 것은 아니지만, 명시적 `SELECT FOR UPDATE` 후 Java에서 검사하는 방식보다 lock scope를 줄였다.

### 결과

Worker의 DB connection usage max는 감소했지만 Apply API p95는 거의 변하지 않았다.

따라서 **Worker lock contention은 존재했으나 Apply API 응답 지연의 주원인은 아니었다.**

---

## 6.3 3차 개선 — 중복 신청 사전 SELECT 제거

### 기존

```text
Event SELECT
→ existsByEvent_IdAndApplicantName SELECT
→ INSERT
→ COMMIT
```

DB에는 이미 다음 UNIQUE constraint가 존재했다.

```java
@UniqueConstraint(
    name = "uk_event_applicant",
    columnNames = {"event_id", "applicant_name"}
)
```

따라서 애플리케이션에서 중복 여부를 사전 SELECT하는 대신 DB UNIQUE constraint를 최종 방어선으로 사용하도록 변경했다.

### 개선 후

```text
Event SELECT
→ INSERT
→ UNIQUE violation이면 DuplicateApplicationException
→ COMMIT
```

### 결과

대표적으로 다음 변화가 관찰됐다.

- API pending: 약 184 → 약 62
- acquire max: 약 12.9초 → 약 7.3초
- API connection usage max: 약 5.1초 → 약 0.9초

그러나 300 VU의 p95는 여전히 약 10초 수준이었다.

이 시점에서 단순 query 최적화보다 **Apply 요청이 PostgreSQL persistence를 동기적으로 기다리는 구조 자체**가 병목이라고 판단했다.

---

# 7. Redis-first 구조로 재설계

## 7.1 기존 구조

```text
Client
  ↓
API
  ↓
PostgreSQL Event SELECT
  ↓
PostgreSQL PENDING INSERT
  ↓
COMMIT
  ↓
Redis Queue
  ↓
PENDING Response
```

사용자가 PENDING 응답을 받기 위해 PostgreSQL transaction 완료까지 기다려야 했다.

---

## 7.2 개선 구조

```text
Client
  ↓
API
  ↓
Redis Lua Script
  ├─ Event 존재 확인
  ├─ 중복 신청 확인
  ├─ applicationId 발급
  ├─ PENDING 데이터 저장
  └─ Queue 적재
  ↓
PENDING Response

---------------- 비동기 ----------------

Worker
  ↓
Redis Queue dequeue
  ↓
PendingApplication 조회
  ↓
PostgreSQL Transaction
  ├─ capacity Atomic UPDATE
  └─ EventApplication INSERT
  ↓
SUCCESS / FAILED 저장
  ↓
Redis 임시 PENDING 제거
```

핵심은 **`POST /apply` hot path에서 PostgreSQL을 제거**한 것이다.

---

# 8. Redis Lua Script 기반 원자적 접수

다음 작업을 하나의 Lua Script에서 처리하도록 구성했다.

1. Event 존재 확인
2. `(eventId, applicantName)` 중복 여부 확인
3. `INCR`로 applicationId 생성
4. 신청 정보 Hash 저장
5. Queue `RPUSH`

개념적 흐름:

```text
EXISTS eventKey

SET duplicateKey 1 NX

INCR applicationSequence

HSET application:data:{id}
  eventId
  applicantName
  createdAt
  status=PENDING

RPUSH applicationQueue id
```

이를 하나의 Redis script에서 실행해 아래와 같은 중간 실패 상태를 방지했다.

```text
중복키 생성 성공
→ Queue 적재 실패
```

또는

```text
ID 생성 성공
→ PENDING 데이터 저장 실패
```

즉 Redis 접수 단계 내 작업을 원자적으로 묶었다.

---

# 9. Application ID 구조 변경

Redis-first에서는 DB INSERT 전에 사용자에게 `applicationId`를 반환해야 한다.

기존에는 PostgreSQL의 `IDENTITY` PK가 applicationId 역할을 했지만 Redis-first 구조에서는 DB가 아직 INSERT되지 않은 상태이므로 Redis에서 외부 request ID를 생성한다.

DB 구조는 다음과 같이 분리했다.

```text
event_applications

id             PostgreSQL 내부 PK
request_id     Redis에서 발급한 외부 applicationId
event_id
applicant_name
status
created_at
```

API에서는 `request_id`를 사용자에게 `applicationId`로 반환한다.

Redis sequence가 재시작되면서 기존 DB PK와 충돌하는 문제도 테스트 중 발견했다.

이를 막기 위해 application startup 시:

```text
max(DB request_id)
max(DB id)
```

중 큰 값을 Redis sequence의 최소값으로 사용하도록 보완했다.

---

# 10. Redis 상태 초기화

이벤트 생성 시 PostgreSQL commit 완료 후 Redis에 Event 존재 key를 생성한다.

또한 애플리케이션 시작 시 `RedisStateInitializer`를 통해 기존 DB 상태에서 다음 정보를 복원하도록 구성했다.

- Event 존재 key
- 기존 신청자의 duplicate key
- application sequence floor

이를 통해 Redis가 재시작되더라도 DB에 존재하는 기본 상태를 다시 구성할 수 있게 했다.

단, 현재 구현은 startup 시 DB 데이터를 조회하므로 데이터 규모가 매우 커질 경우 startup 부하가 증가할 수 있다. 이는 향후 개선 대상이다.

---

# 11. Redis-first 기능 검증

별도 로컬 Redis를 사용해 기존 Kubernetes Worker와 Queue를 격리한 뒤 기능 테스트를 수행했다.

테스트 이벤트:

```text
capacity = 2
```

신청:

```text
user-a
user-b
user-c
```

접수 직후:

```text
user-a → PENDING
user-b → PENDING
user-c → PENDING
```

Worker 처리 후:

```text
user-a → SUCCESS
user-b → SUCCESS
user-c → FAILED
```

Queue:

```text
size = 0
```

추가 확인:

- 동일 사용자 재신청 → `409 Conflict`
- 존재하지 않는 Event 신청 → `404 Not Found`

즉 Redis-first 전환 후에도 중복 방지, Event 검증, capacity 정합성, 최종 상태 저장이 정상 동작함을 확인했다.

---

# 12. 최종 300 VU Before / After 비교

성능 비교의 공정성을 위해 Redis-first 이전 이미지와 Redis-first 이후 이미지를 동일한 Kubernetes 환경에서 실행하고, **동일한 최종 k6 스크립트의 `apply_duration` metric**으로 재측정했다.

## Redis-first 이전

```text
USERS = 300
CAPACITY = 100
```

결과:

| 지표 | 값 |
|---|---:|
| Apply Avg | 7.11s |
| Apply Median | 7.66s |
| Apply p90 | 10.80s |
| Apply p95 | 11.21s |
| Apply Max | 11.41s |
| HTTP 실패율 | 0% |
| Capacity 초과 | 없음 |
| Capacity 완전 배정 | 성공 |
| Queue drain | 성공 |

---

## Redis-first 이후

동일 조건:

```text
USERS = 300
CAPACITY = 100
```

결과:

| 지표 | 값 |
|---|---:|
| Apply Avg | 1.31s |
| Apply Median | 1.36s |
| Apply p90 | 2.13s |
| Apply p95 | 2.20s |
| Apply Max | 2.35s |
| HTTP 실패율 | 0% |
| Capacity 초과 | 없음 |
| Capacity 완전 배정 | 성공 |
| Queue drain | 성공 |

---

## 개선율

### Apply Avg

```text
7.11s → 1.31s
```

약 **81.6% 감소**

### Apply p95

```text
11.21s → 2.20s
```

약 **80.4% 감소**

### Apply Max

```text
11.41s → 2.35s
```

약 **79.4% 감소**

가장 중요한 점은 성능 개선과 동시에 다음을 모두 유지했다는 것이다.

```text
HTTP failure = 0%
Oversell = 0
appliedCount = capacity = 100
Queue drain 성공
```

---

# 13. HikariCP 변화

Redis-first 전환 이전에는 Apply API가 PostgreSQL connection을 직접 필요로 했기 때문에 높은 connection contention이 확인됐다.

대표적인 진단값:

```text
active = 10 / 10
pending ≈ 184
acquire max ≈ 12.9s
```

단계별 query/transaction 개선 후에도:

```text
pending ≈ 62
acquire max ≈ 7.3s
```

수준의 대기가 남아 있었다.

Redis-first 전환 후 `/apply` hot path에서 DB 접근을 제거하면서 API의 Hikari pending이 0으로 관찰됐다.

따라서 Apply API 지연의 주요 원인이 동기 PostgreSQL persistence 및 connection contention이었다는 판단을 뒷받침한다.

---

# 14. 500 VU Stress Test

최종 Redis-first 구조에서 한계 탐색을 위해 500 VU burst를 수행했다.

조건:

```text
USERS = 500
CAPACITY = 100
```

결과:

| 지표 | 값 |
|---|---:|
| Apply Avg | 4.06s |
| Apply Median | 4.08s |
| Apply p90 | 6.29s |
| Apply p95 | 6.41s |
| Apply Max | 6.61s |
| HTTP 실패율 | 0% |
| 신청 PENDING 응답 | 모두 성공 |
| Capacity 초과 | 없음 |
| Capacity 100명 배정 | 성공 |
| Queue drain | 제한 시간 내 실패 |

500 VU에서도 모든 HTTP 요청은 정상적으로 접수됐고 정원 초과도 발생하지 않았다.

그러나 300 VU 대비 latency가 크게 증가했다.

```text
300 VU p95 = 2.20s
500 VU p95 = 6.41s
```

또한 k6 teardown의 Queue drain 대기 시간 내에 Queue가 완전히 비워지지 않았다.

따라서 현재 로컬 환경에서는 500 VU 부근에서 다음 한계가 드러났다고 판단한다.

- API burst latency 증가
- Worker 최종 처리량 한계
- Queue backlog 발생

500 VU 테스트는 안정 운용 성능값보다는 **현재 구조의 stress boundary를 확인하기 위한 테스트**로 해석한다.

---

# 15. API Pre-scaling 실험

Redis-first 전환 이전에 API를 1개에서 2개로 미리 늘리는 실험도 수행했다.

대표 결과:

```text
API 1 Pod
p95 ≈ 10.26s

API 2 Pods pre-scale
p95 ≈ 11.73s
```

당시에는 API Pod가 늘어날수록 PostgreSQL에 동시에 접근하는 connection 수도 증가해 유의미한 개선이 없었다.

따라서 단순 replica 증가를 최종 해결책으로 채택하지 않았다.

Redis-first 전환 후에는 구조가 달라졌기 때문에 event pre-scaling은 향후 별도의 재평가 대상이다.

---

# 16. Hikari Pool 축소 실험 및 Revert

HPA 최대 replica와 PostgreSQL connection budget을 고려해 Hikari pool을 다음과 같이 낮추는 실험을 했다.

```yaml
hikari:
  maximum-pool-size: 5
  minimum-idle: 1
```

목적은 성능 향상이 아니라 DB connection ceiling을 피하는 것이었다.

기본 pool 10일 때 theoretical maximum:

```text
API
6 Pods × 10 = 60

Worker
6 Pods × 10 = 60

Total = 120
```

PostgreSQL:

```text
max_connections = 100
```

따라서 설정상 최대 DB connection 요구량이 PostgreSQL 한도를 초과할 수 있다.

Pool 5 적용 시:

```text
6 × 5 + 6 × 5 = 60
```

으로 connection budget은 안전해진다.

그러나 Redis-first 300 VU 테스트에서 다음 결과가 관찰됐다.

### Pool 10

```text
Apply Avg = 1.31s
Apply p95 = 2.20s
```

### Pool 5 - Run 1

```text
Apply Avg = 2.70s
Apply p95 = 4.34s
```

### Pool 5 - Run 2

```text
Apply Avg = 2.23s
Apply p95 = 3.79s
```

API Hikari pending은 계속 0이었기 때문에 pool 축소가 Apply API의 직접적인 DB connection 대기를 만든 것은 아니었다.

다만 동일 환경에서 성능 저하가 반복 관찰됐기 때문에 해당 변경은 채택하지 않고 Git revert로 원복했다.

```text
perf: limit database connection pool
→ Revert "perf: limit database connection pool"
```

이 실험을 통해 성능과 connection budget을 함께 고려해야 한다는 점을 확인했다.

---

# 17. 부하 직후 Rolling Update 장애

500 VU 및 여러 부하테스트 과정에서 HPA가 다음 상태까지 확장됐다.

```text
API = 6 replicas
Worker = 6 replicas
```

이 상태에서 replica가 1로 내려가기 전에 새 이미지 rollout을 수행했다.

Rolling Update 특성상 기존 ReplicaSet과 신규 ReplicaSet의 Pod가 일시적으로 동시에 존재했고, 각 Pod가 Hikari connection pool을 초기화하면서 PostgreSQL connection ceiling을 초과했다.

실제 로그:

```text
FATAL: sorry, too many clients already
```

신규 API/Worker Pod는 DB metadata connection도 확보하지 못해 JPA 초기화에 실패했고 `CrashLoopBackOff` 상태가 발생했다.

대표 흐름:

```text
Load Test
  ↓
HPA API 6 / Worker 6
  ↓
부하 종료 직후 Rollout
  ↓
Old Pods + New Pods 동시 존재
  ↓
Hikari connection 증가
  ↓
PostgreSQL max_connections=100 초과
  ↓
new Pod startup 실패
  ↓
CrashLoopBackOff
```

부하가 종료되고 HPA replica가 감소하면서 connection이 반환된 후 서비스는 정상 상태로 복구됐다.

### 운영상 얻은 교훈

부하 직후 배포할 경우 단순히 현재 CPU가 낮은지만 확인할 것이 아니라 다음을 함께 확인해야 한다.

```bash
kubectl get hpa -n fastpass-gitops
kubectl get pods -n fastpass-gitops
```

배포 전 API/Worker replica가 안정적인 수준으로 scale-down 되었는지 확인한다.

또한 production 수준에서는 다음 개선이 필요하다.

- API / Worker별 독립 Hikari pool sizing
- HPA max replica와 DB connection budget 연계
- PgBouncer와 같은 connection pooler 검토
- Rolling Update의 `maxSurge` 고려
- PostgreSQL connection alert 추가

---

# 18. 최종 구조 평가

이번 개선에서 단순히 replica 수나 DB pool 크기를 늘리는 대신 다음 순서로 문제를 분석했다.

```text
부하 재현
↓
Prometheus/Hikari 계측
↓
PostgreSQL pg_stat_activity 분석
↓
Transaction 범위 축소
↓
Pessimistic Lock 제거
↓
중복 SELECT 제거
↓
구조적 병목 확인
↓
Redis-first 비동기 접수로 재설계
↓
동일 조건 Before/After 재측정
↓
500 VU Stress Test
↓
운영 중 DB connection ceiling 문제 확인
```

최종적으로 300 VU에서:

```text
Apply p95
11.21s → 2.20s

약 80.4% 감소
```

하면서:

```text
HTTP failure = 0%
Oversell = 0
Capacity = 정확히 100
Queue drain = 성공
```

을 유지했다.

---

# 19. 최종 결론

이번 성능 개선의 핵심은 단순한 parameter tuning이 아니라 **요청 처리 경로에서 동기 DB persistence를 제거하는 구조 변경**이었다.

초기에는 Hikari pool saturation과 PostgreSQL transaction lock contention이 확인됐고, query/transaction 수준의 최적화로 connection 사용 시간은 줄었지만 p95는 충분히 개선되지 않았다.

이를 통해 병목이 특정 query 하나가 아니라 Apply 요청이 PostgreSQL commit을 기다리는 구조 자체에 있음을 판단했다.

최종적으로 Redis가 burst를 먼저 흡수하고 PostgreSQL 최종 반영을 Worker로 비동기화하는 Redis-first 구조를 적용했다.

```text
Before
Client
→ API
→ PostgreSQL
→ Redis
→ Response

After
Client
→ API
→ Redis
→ Response

          ↓

        Worker
          ↓
      PostgreSQL
```

동일한 300 VU 테스트에서 Apply p95가 **11.21초에서 2.20초로 약 80.4% 감소**했다.

500 VU에서는 요청 실패와 oversell 없이 접수 자체는 유지됐지만 latency 증가와 Queue drain 지연이 확인됐다. 따라서 다음 개선 대상은 Worker 처리량, API burst 처리량, connection budget 관리로 정리했다.

---

# 20. 향후 개선 과제

현재 단계에서는 추가적인 로컬 튜닝보다 다음 항목을 향후 개선 과제로 남긴다.

## 20.1 Worker 처리량 개선

500 VU에서 Queue drain 지연이 확인됐으므로 다음을 검토할 수 있다.

- Worker batch 처리 방식 개선
- Worker concurrency 조정
- Redis batch pop 최적화
- DB batch INSERT
- 성공/실패 처리 경로 분리

## 20.2 API / Worker DB Pool 분리

현재 API와 Worker가 동일한 datasource 설정을 사용한다.

실제 역할은 다르므로 향후 다음과 같이 분리할 수 있다.

```text
API
DB 사용량 낮음
→ 작은 pool

Worker
DB 처리 중심
→ 상대적으로 큰 pool
```

이를 통해 전체 connection budget을 지키면서 Worker throughput을 유지할 수 있다.

## 20.3 PgBouncer

HPA와 Rolling Update에서 Pod 수가 일시적으로 증가해도 PostgreSQL backend connection이 직접 폭증하지 않도록 PgBouncer 도입을 검토할 수 있다.

## 20.4 Event-aware Pre-scaling

선착순 이벤트는 시작 시간이 사전에 정해져 있으므로 이벤트 오픈 직전 API/Worker를 미리 scale-out하고 종료 후 scale-in하는 방식도 검토할 수 있다.

CPU 기반 HPA는 수 초 단위 burst보다 늦게 반응할 수 있으므로 event-aware scaling이 적합할 수 있다.

## 20.5 Redis 내구성

현재 Redis-first 구조에서는 Worker 처리 전까지 Redis가 신청 접수 상태의 중요한 저장소 역할을 한다.

production 수준에서는 다음을 추가 검토해야 한다.

- AOF persistence
- Redis replication
- Redis Sentinel / Cluster
- Queue 처리 보장
- DLQ
- retry 횟수 제한
- idempotent Worker
- Outbox 또는 durable messaging system

## 20.6 RedisStateInitializer 개선

현재 startup 시 DB를 조회해 Redis Event/duplicate 상태를 복원한다.

데이터가 커질 경우 전체 조회는 startup 비용이 될 수 있으므로 다음 구조를 고려할 수 있다.

- 필요한 Event만 lazy cache
- TTL 기반 key 관리
- 별도 cache warm-up job
- Redis persistence를 통한 재구축 최소화

---

# 21. 대표 포트폴리오 문구

> 300 VU 동시 신청 부하에서 Apply API p95가 11.21초까지 증가하는 문제를 재현하고, Prometheus/HikariCP와 PostgreSQL `pg_stat_activity`를 통해 DB connection contention 및 transaction lock 경합을 분석했습니다. 트랜잭션 범위 축소, Pessimistic Lock의 Atomic UPDATE 전환, 중복 SELECT 제거를 단계적으로 적용한 뒤 동기 DB persistence 자체가 hot path의 구조적 병목임을 확인했습니다. 이후 Redis Lua Script 기반의 Redis-first 비동기 접수 구조로 재설계하여 동일 조건에서 Apply p95를 2.20초로 약 80.4% 단축했고, HTTP 실패 0%와 정원 초과 없는 선착순 정합성을 유지했습니다. 추가 500 VU 스트레스 테스트에서는 요청 유실이나 oversell 없이 접수를 유지했으나 Queue drain 지연을 확인해 Worker throughput을 다음 개선 과제로 도출했습니다.

---

# 22. 주요 재현 명령

## 300 VU

```bash
docker run --rm -i \
  -e USERS=300 \
  -e CAPACITY=100 \
  -e BASE_URL=http://host.docker.internal:18081 \
  grafana/k6 run - < load-test/k6/apply-queue-burst.js
```

## 500 VU

```bash
docker run --rm -i \
  -e USERS=500 \
  -e CAPACITY=100 \
  -e BASE_URL=http://host.docker.internal:18081 \
  grafana/k6 run - < load-test/k6/apply-queue-burst.js
```

## 상태 확인

```bash
kubectl get application fastpass-gitops -n argocd
kubectl get hpa -n fastpass-gitops
kubectl get pods -n fastpass-gitops
curl http://localhost:18081/api/queue/applications/size
```

## HikariCP

```promql
hikaricp_connections_active{job="fastpass-api"}
```

```promql
hikaricp_connections_pending{job="fastpass-api"}
```

```promql
hikaricp_connections_acquire_seconds_max{job="fastpass-api"}
```

```promql
hikaricp_connections_usage_seconds_max{job="fastpass-api"}
```

```promql
hikaricp_connections_usage_seconds_max{job="fastpass-worker"}
```

---

## 요약

```text
300 VU / Capacity 100

Redis-first 이전
Apply Avg   7.11s
Apply p95  11.21s
Failure     0%

Redis-first 이후
Apply Avg   1.31s
Apply p95   2.20s
Failure     0%

p95 약 80.4% 감소

500 VU
Apply Avg   4.06s
Apply p95   6.41s
Failure     0%
Capacity    100 정확
Queue       제한 시간 내 drain 실패
```

**최종 판단:**  
300 VU에서는 Redis-first 전환으로 DB connection contention을 크게 줄이면서 성능과 정합성을 동시에 개선했다. 500 VU에서는 시스템이 요청 자체는 유실 없이 수용했지만 latency와 Worker backlog가 증가해 현재 로컬 환경의 다음 병목 구간을 확인했다.
