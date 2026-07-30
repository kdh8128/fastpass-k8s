# k6 기반 FastPass Queue 부하 테스트 검증

## 1. 테스트 목적

FastPass는 Redis Queue 기반 비동기 신청 처리 구조를 사용한다.

기존 기능 검증에서는 단일 요청 또는 소수의 요청이 정상적으로 `PENDING → SUCCESS/FAILED`로 처리되는지를 확인했다.

이번 단계에서는 k6를 이용해 이벤트 오픈 시점처럼 여러 사용자가 동시에 신청 요청을 보내는 상황을 재현하고, 다음 항목을 검증했다.

```text
동시 신청 요청 발생
  → API가 요청을 빠르게 PENDING으로 접수
  → Redis Queue에 신청 ID 적재
  → Worker가 Queue를 비동기적으로 소비
  → Queue size가 증가한 뒤 점진적으로 감소
```

---

## 2. 테스트 대상 구조

테스트는 로컬 Kubernetes에 배포된 FastPass API를 대상으로 수행했다.

```text
k6 Docker Container
  → host.docker.internal:18080
  → kubectl port-forward
  → fastpass-api Service
  → fastpass-api Pod
      → PostgreSQL Service/Pod
      → Redis Service/Pod
```

Kubernetes 내부 서비스는 로컬에서 직접 접근할 수 없기 때문에 `kubectl port-forward`를 사용했다.

```bash
kubectl port-forward -n fastpass service/fastpass-api 18080:8080
```

---

## 3. 테스트 환경

| 항목 | 내용 |
|---|---|
| 부하 테스트 도구 | k6 |
| 실행 방식 | Docker 기반 k6 실행 |
| API 실행 환경 | Docker Desktop Kubernetes |
| API 접근 주소 | http://localhost:18080 |
| k6 컨테이너 내부 접근 주소 | http://host.docker.internal:18080 |
| 가상 사용자 수 | 20 VUs |
| 테스트 시간 | 30초 |
| 신청 이벤트 capacity | 10000 |
| Worker 처리 방식 | Redis Queue를 주기적으로 소비 |

Kubernetes Pod 상태 확인 결과:

```text
NAME                            READY   STATUS    RESTARTS
fastpass-api-6d55775598-fs66t   1/1     Running   0
postgres-85d56865bf-mzv2b       1/1     Running   0
redis-b67748d94-k2658           1/1     Running   0
```

API, PostgreSQL, Redis가 모두 정상 실행 중인 상태에서 테스트를 수행했다.

---

## 4. k6 테스트 시나리오

테스트 스크립트 파일:

```text
load-test/k6/apply-queue-test.js
```

시나리오는 다음과 같다.

```text
1. setup 단계에서 테스트용 이벤트 생성
2. 20명의 가상 사용자가 30초 동안 반복적으로 신청 요청 전송
3. 각 신청 요청은 고유한 applicantName 사용
4. 신청 응답이 PENDING인지 확인
5. teardown 단계에서 Queue size와 Event 상태 조회
```

k6 옵션:

```javascript
export const options = {
  scenarios: {
    apply_queue_load: {
      executor: 'constant-vus',
      vus: 20,
      duration: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000'],
  },
};
```

검증 조건은 다음과 같다.

| Check | 의미 |
|---|---|
| event created | 테스트용 이벤트 생성 성공 |
| apply request accepted | 신청 요청이 HTTP 200 또는 201로 정상 접수 |
| application status is PENDING | 신청 응답 상태가 PENDING |

---

## 5. k6 실행 명령어

실행 명령어:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:18080 \
  -v "${PWD}/load-test/k6:/scripts" \
  grafana/k6 run /scripts/apply-queue-test.js
```

---

## 6. 실행 명령어 설명

### 6.1 MSYS_NO_PATHCONV=1

```bash
MSYS_NO_PATHCONV=1
```

Windows Git Bash는 `/scripts` 같은 경로를 Windows 경로로 자동 변환하려는 경우가 있다.

Docker 컨테이너 내부 경로가 잘못 변환되는 것을 방지하기 위해 `MSYS_NO_PATHCONV=1`을 사용했다.

---

### 6.2 docker run

```bash
docker run
```

Docker 컨테이너를 실행하는 명령어이다.

이번 테스트에서는 k6를 로컬에 직접 설치하지 않고, `grafana/k6` Docker 이미지를 사용했다.

---

### 6.3 --rm

```bash
--rm
```

컨테이너 실행이 끝난 뒤 테스트용 컨테이너를 자동으로 삭제한다.

k6 컨테이너는 테스트 실행 후 유지할 필요가 없기 때문에 적절한 옵션이다.

---

### 6.4 -i

```bash
-i
```

컨테이너의 표준 입력을 열어둔다.

k6 실행 시 Docker 컨테이너를 일회성으로 실행하기 위해 함께 사용했다.

---

### 6.5 BASE_URL 환경변수

```bash
-e BASE_URL=http://host.docker.internal:18080
```

k6 스크립트에서 사용할 API 주소를 환경변수로 전달한다.

중요한 점은 k6가 Docker 컨테이너 안에서 실행된다는 것이다.

컨테이너 내부에서 `localhost:18080`은 사용자 PC가 아니라 k6 컨테이너 자신을 의미한다.

따라서 Docker 컨테이너에서 호스트 PC의 포트포워딩 주소로 접근하기 위해 다음 주소를 사용했다.

```text
host.docker.internal:18080
```

전체 접근 흐름은 다음과 같다.

```text
k6 container
  → host.docker.internal:18080
  → host PC localhost:18080
  → kubectl port-forward
  → fastpass-api Service
  → fastpass-api Pod
```

---

### 6.6 volume mount

```bash
-v "${PWD}/load-test/k6:/scripts"
```

로컬 프로젝트의 k6 스크립트 폴더를 컨테이너 내부 `/scripts` 경로에 연결한다.

로컬 파일:

```text
load-test/k6/apply-queue-test.js
```

컨테이너 내부 경로:

```text
/scripts/apply-queue-test.js
```

---

### 6.7 grafana/k6

```bash
grafana/k6
```

k6 실행 환경이 포함된 Docker 이미지이다.

처음 실행 시 로컬에 이미지가 없으면 Docker가 자동으로 이미지를 다운로드한다.

---

### 6.8 run /scripts/apply-queue-test.js

```bash
run /scripts/apply-queue-test.js
```

컨테이너 내부의 k6 스크립트를 실행한다.

---

## 7. 테스트 실행 결과

k6 실행 결과 요약:

```text
20 max VUs
30s duration
550 complete iterations
553 HTTP requests
0 interrupted iterations
```

주요 결과:

| 지표 | 결과 |
|---|---|
| checks_succeeded | 100.00% |
| checks_failed | 0.00% |
| http_req_failed | 0.00% |
| http_req_duration avg | 115.61ms |
| http_req_duration med | 67.88ms |
| http_req_duration p(90) | 143.59ms |
| http_req_duration p(95) | 251.38ms |
| http_req_duration max | 3.13s |
| iterations | 550 |
| http_reqs | 553 |

Threshold 결과:

```text
http_req_duration
  ✓ p(95)<1000
  p(95)=251.38ms

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

모든 신청 요청이 실패 없이 접수되었고, 신청 직후 상태가 `PENDING`으로 반환됨을 확인했다.

---

## 8. Queue 상태 확인

테스트 종료 시점의 k6 teardown 로그:

```text
Final queue size response: {"size":521}
Event response: {"id":2,"title":"FastPass k6 Event 1785395356780","description":"k6 queue load test","capacity":10000,"appliedCount":29}
```

테스트 종료 후 Queue size 직접 확인:

```bash
curl http://localhost:18080/api/queue/applications/size
```

응답:

```json
{"size":458}
```

다시 확인:

```json
{"size":450}
```

이를 통해 테스트 종료 이후에도 Worker가 Redis Queue를 계속 소비하고 있으며, Queue size가 점진적으로 감소하고 있음을 확인했다.

---

## 9. 결과 해석

이번 테스트에서는 20명의 가상 사용자가 30초 동안 총 550건의 신청 요청을 발생시켰다.

API는 모든 신청 요청을 실패 없이 접수했고, 모든 신청 응답은 `PENDING` 상태였다.

이는 FastPass API가 신청 요청을 즉시 최종 처리하지 않고, 요청 접수와 실제 신청 처리를 분리하고 있음을 보여준다.

```text
신청 요청
  → API가 PENDING 저장
  → Redis Queue에 applicationId 적재
  → 응답 반환
```

테스트 종료 시점에 Queue size는 521이었고, 이후 458, 450으로 감소했다.

이는 Worker가 Redis Queue를 비동기적으로 소비하고 있음을 보여준다.

```text
Redis Queue
  → Worker 소비
  → PostgreSQL 상태 갱신
  → Queue size 감소
```

---

## 10. 확인된 병목

현재 구조에서는 Worker가 Queue를 처리하는 속도가 요청 접수 속도보다 낮다.

테스트 결과:

```text
총 신청 요청: 550건
테스트 종료 시 appliedCount: 29
테스트 종료 시 Queue size: 521
```

이는 다음과 같이 해석할 수 있다.

```text
API는 요청을 빠르게 접수할 수 있음
하지만 Worker 처리량이 낮아 Queue backlog가 발생함
```

현재 구현은 Worker가 일정 주기마다 Queue에서 신청 건을 하나씩 꺼내 처리하는 구조이다.

따라서 동시 요청이 증가할수록 Queue에 대기 중인 신청 건이 쌓인다.

이 병목은 Redis Queue 구조의 실패가 아니라, 현재 Worker 처리량이 낮기 때문에 나타난 현상이다.

---

## 11. 몇 만 건 테스트에 대한 해석

현재 구조에서도 수천 건 또는 수만 건의 신청 요청을 발생시키는 것은 가능하다.

다만 두 가지를 구분해서 해석해야 한다.

```text
요청 접수 테스트
  → API가 신청 요청을 PENDING으로 받아낼 수 있는지 확인

처리 완료 테스트
  → Worker가 Queue를 소비하여 SUCCESS/FAILED까지 빠르게 처리하는지 확인
```

현재 구조에서는 수만 건 요청을 넣으면 대부분 Redis Queue에 적재될 수 있지만, Worker 처리 완료까지는 오래 걸릴 수 있다.

예를 들어 Worker가 1초에 1건 수준으로 처리한다면 10,000건 처리에는 약 10,000초가 필요하다.

```text
10,000초 = 약 2시간 46분
```

따라서 현재 단계에서 수만 건 테스트는 “처리 완료 테스트”보다는 “접수 및 Queue 적재 테스트”로 해석하는 것이 적절하다.

---

## 12. 현재 테스트의 의미

이번 테스트를 통해 다음 내용을 검증했다.

```text
동시 신청 요청이 발생해도 API는 요청을 실패 없이 접수한다.
신청 요청은 즉시 SUCCESS/FAILED로 처리되지 않고 PENDING으로 반환된다.
신청 ID는 Redis Queue에 적재된다.
Worker는 Redis Queue를 소비하며 신청 상태를 비동기적으로 갱신한다.
요청량이 Worker 처리량보다 많으면 Queue backlog가 발생한다.
Queue backlog는 시간이 지나면서 감소한다.
```

이는 FastPass가 트래픽 집중 상황에서 요청 접수와 실제 처리를 분리하는 Queue 기반 구조를 갖추었음을 보여준다.

---

## 13. 다음 개선 방향

이번 테스트에서 확인된 병목은 Worker 처리량이다.

다음 단계에서는 Worker 처리량을 개선하고, 개선 전후의 부하 테스트 결과를 비교할 수 있다.

| 개선 항목 | 설명 |
|---|---|
| Worker batch 처리 | 한 번의 스케줄 실행에서 여러 신청 건을 처리 |
| Worker 분리 | API와 Worker를 별도 애플리케이션 또는 별도 Pod로 분리 |
| Worker replica 증가 | Worker Pod 수를 늘려 Queue 소비 속도 향상 |
| HPA 적용 | Queue 길이 또는 CPU 사용량 기반 자동 확장 |
| 처리 지연 시간 측정 | PENDING 상태가 SUCCESS/FAILED로 바뀌기까지 걸린 시간 측정 |
| 부하 테스트 시나리오 확장 | 1천, 1만, 5만 건 요청 접수 테스트 추가 |
| 모니터링 추가 | Prometheus/Grafana로 요청량, Queue 길이, 처리량 시각화 |

---

## 14. 완료 기준

이번 부하 테스트 단계의 완료 기준은 다음과 같다.

```text
k6 테스트 스크립트 작성 완료
Docker 기반 k6 실행 성공
Kubernetes에 배포된 FastPass API 대상 테스트 성공
이벤트 생성 성공
신청 요청 실패율 0.00%
신청 응답 PENDING 검증 성공
p95 응답시간 threshold 통과
Redis Queue backlog 발생 확인
Queue size 감소 확인
Kubernetes Pod 정상 상태 확인
```

위 기준을 모두 만족했으므로 k6 기반 FastPass Queue 부하 테스트 검증을 완료했다.
