# FastPass Simple Web Frontend 검증

## 1. 목적

이번 단계의 목적은 FastPass 프로젝트에 간단한 Web Frontend를 추가하여, 사용자가 브라우저에서 이벤트 생성, 이벤트 신청, 신청 상태 조회, Queue Size 확인을 직접 수행할 수 있도록 하는 것이다.

기존 FastPass는 Spring Boot API, Redis Queue Worker, Kubernetes, HPA, Prometheus/Grafana, Alertmanager, Helm, ArgoCD, GitHub Actions, GHCR까지 구성되어 있었다.

하지만 사용자가 직접 확인할 수 있는 화면은 없었고, 대부분의 기능 검증은 `curl` 명령어로 수행하였다.

이번 단계에서는 Spring Boot static resource 기반의 간단한 Web UI를 추가하여, FastPass를 백엔드 API 중심 프로젝트에서 사용자가 직접 조작 가능한 서비스 형태로 확장하였다.

---

## 2. 기존 방식의 한계

기존에는 이벤트 생성이나 신청을 확인하기 위해 다음과 같이 `curl` 명령어를 사용하였다.

```bash
curl -X POST http://localhost:18083/api/events \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-raw "{\"title\":\"FastPass Event\",\"description\":\"test\",\"capacity\":3,\"eventStartAt\":\"2026-07-20T10:00:00\"}"
```

이 방식은 개발 및 기능 검증에는 충분하지만, 포트폴리오를 보는 사람이 서비스 흐름을 직관적으로 이해하기 어렵다.

따라서 다음 기능을 브라우저에서 직접 확인할 수 있도록 간단한 Web UI를 추가하였다.

```text
이벤트 생성
이벤트 목록 조회
이벤트 신청
신청 상태 조회
API Health 확인
Redis Queue Size 확인
```

---

## 3. Frontend 구현 방식

Frontend는 별도 React/Vue 프로젝트로 구성하지 않고, Spring Boot static resource 방식으로 구현하였다.

정적 파일은 다음 위치에 추가하였다.

```text
apps/api/src/main/resources/static/
├── index.html
├── styles.css
└── app.js
```

Spring Boot는 `src/main/resources/static` 아래의 파일을 자동으로 정적 리소스로 제공한다.

따라서 애플리케이션이 실행되면 다음 주소에서 Web UI에 접속할 수 있다.

```text
http://localhost:8080/
```

Kubernetes 환경에서는 port-forward 후 다음 주소로 접속할 수 있다.

```text
http://localhost:18083/
```

---

## 4. Spring Boot Static Frontend를 선택한 이유

이번 프로젝트에서 별도 frontend framework를 사용하지 않은 이유는 다음과 같다.

```text
1. 별도 Node.js 설치가 필요 없다.
2. 별도 frontend build pipeline이 필요 없다.
3. 기존 Spring Boot API image 안에 frontend가 함께 포함된다.
4. API와 같은 origin에서 동작하므로 CORS 문제가 없다.
5. 기존 GitHub Actions, GHCR, ArgoCD 배포 흐름을 그대로 사용할 수 있다.
6. 포트폴리오 목적상 복잡한 UI보다 서비스 흐름을 보여주는 간단한 화면이 더 적합하다.
```

FastPass의 핵심은 frontend 자체가 아니라, Kubernetes 기반 운영 흐름과 CI/CD 검증이다.

따라서 frontend는 사용자가 API 기능을 브라우저에서 쉽게 확인할 수 있는 수준으로 구성하였다.

---

## 5. Frontend 주요 기능

추가된 Web UI는 다음 기능을 제공한다.

| 영역 | 기능 |
|---|---|
| System Status | API Health, Queue Size 확인 |
| 이벤트 생성 | title, description, capacity, eventStartAt 입력 후 이벤트 생성 |
| 이벤트 신청 | eventId와 applicantName 입력 후 신청 |
| 신청 상태 조회 | applicationId 기준 신청 상태 조회 |
| 이벤트 목록 | 현재 생성된 이벤트 목록 표시 |

화면 상단에서는 FastPass 프로젝트의 목적과 운영 상태를 확인할 수 있다.

또한 Queue Size와 API Health를 주기적으로 갱신하여, Redis Queue 기반 처리 구조가 동작하고 있음을 화면에서 확인할 수 있도록 하였다.

---

## 6. Frontend API 연동

Frontend는 JavaScript `fetch` API를 사용하여 Spring Boot API와 통신한다.

### 이벤트 생성

```text
POST /api/events
```

### 이벤트 목록 조회

```text
GET /api/events
```

### 이벤트 신청

```text
POST /api/events/{eventId}/apply
```

### 신청 상태 조회

```text
GET /api/applications/{applicationId}
```

### Queue Size 조회

```text
GET /api/queue/applications/size
```

### Health Check

```text
GET /actuator/health
```

Frontend는 API와 같은 Spring Boot 서버에서 제공되므로 별도 CORS 설정 없이 동작한다.

---

## 7. 추가된 파일

추가한 파일은 다음과 같다.

```text
apps/api/src/main/resources/static/index.html
apps/api/src/main/resources/static/styles.css
apps/api/src/main/resources/static/app.js
```

각 파일의 역할은 다음과 같다.

| File | Role |
|---|---|
| `index.html` | FastPass Web UI 구조 |
| `styles.css` | 화면 스타일링 |
| `app.js` | API 호출 및 화면 상태 갱신 |

---

## 8. 로컬 검증

PostgreSQL과 Redis를 먼저 실행하였다.

```bash
docker compose up -d postgres redis
```

Spring Boot 애플리케이션을 local profile로 실행하였다.

```bash
cd apps/api
./gradlew.bat bootRun --args='--spring.profiles.active=local'
```

브라우저에서 다음 주소에 접속하였다.

```text
http://localhost:8080/
```

검증한 항목은 다음과 같다.

```text
1. FastPass Web UI가 정상 표시되는지
2. API Health가 UP으로 표시되는지
3. Queue Size가 조회되는지
4. 이벤트 생성이 가능한지
5. 이벤트 목록에 생성된 이벤트가 표시되는지
6. 이벤트 신청이 가능한지
7. 신청 상태 조회가 가능한지
```

검증 결과, Web UI에서 FastPass의 주요 API 흐름을 직접 확인할 수 있었다.

---

## 9. Kubernetes 환경 검증

Frontend가 포함된 image가 배포된 후, Kubernetes API Service를 port-forward하였다.

```bash
kubectl port-forward -n fastpass-gitops service/fastpass-api 18083:8080
```

브라우저에서 다음 주소에 접속하였다.

```text
http://localhost:18083/
```

확인한 항목은 다음과 같다.

```text
1. FastPass Web UI가 표시되는지
2. API Health가 UP으로 표시되는지
3. Queue Size가 조회되는지
4. 이벤트 생성이 가능한지
5. 이벤트 신청이 가능한지
6. 신청 상태 조회가 가능한지
7. 이벤트 목록이 표시되는지
```

이를 통해 frontend가 포함된 Spring Boot image가 Kubernetes에서도 정상 동작함을 확인하였다.

---

## 10. Frontend 추가 후 CI 검증

Frontend 파일 추가 후 Git에 commit하였다.

```bash
git add apps/api/src/main/resources/static

git commit -m "feat: add simple web frontend"

git push
```

push 이후 GitHub Actions가 자동으로 실행되었다.

실행된 workflow는 다음 job을 포함한다.

```text
Build Spring Boot API
Build and Push Docker Image
Update Helm Image Tag
```

모든 job이 성공하였다.

```text
Build Spring Boot API        success
Build and Push Docker Image  success
Update Helm Image Tag        success
```

이를 통해 frontend 정적 파일이 포함된 Spring Boot application build와 Docker image build가 정상적으로 수행되는 것을 확인하였다.

---

## 11. 포트폴리오 관점의 의미

이번 단계는 FastPass를 단순 백엔드 API 프로젝트에서 브라우저로 직접 확인 가능한 웹 서비스 형태로 확장했다는 의미가 있다.

기존에는 API 요청을 모두 `curl`로 수행해야 했지만, 이제는 사용자가 직접 다음 흐름을 확인할 수 있다.

```text
브라우저 접속
→ 이벤트 생성
→ 이벤트 신청
→ 신청 상태 확인
→ Queue Size 확인
→ API Health 확인
```

이를 통해 포트폴리오를 보는 사람이 FastPass의 기능과 Queue 기반 처리 구조를 더 직관적으로 이해할 수 있다.

---

## 12. 현재 한계점

이번 frontend는 서비스 흐름을 보여주기 위한 간단한 static frontend이다.

아직 다음 기능은 구현하지 않았다.

```text
React/Vue 기반 SPA
로그인/회원가입
상세한 관리자 화면
Grafana dashboard embed
실시간 WebSocket 업데이트
Frontend 전용 CI pipeline
Frontend unit test
```

현재 목적은 복잡한 frontend 개발이 아니라, Kubernetes와 CI/CD로 배포되는 FastPass API를 브라우저에서 직접 조작할 수 있게 하는 것이다.

---

## 13. 결론

이번 단계에서는 FastPass에 간단한 Web Frontend를 추가하였다.

Frontend는 Spring Boot static resource 방식으로 구현하여 별도 frontend build pipeline 없이 기존 API image에 포함되도록 구성하였다.

이를 통해 사용자는 브라우저에서 이벤트 생성, 이벤트 신청, 신청 상태 조회, Queue Size, API Health 상태를 직접 확인할 수 있게 되었다.

검증 결과 로컬 환경과 Kubernetes 환경 모두에서 Web UI가 정상 동작하였다.

FastPass는 이를 통해 백엔드 API와 DevOps 운영 검증 중심 프로젝트에서, 사용자가 직접 확인할 수 있는 간단한 웹 서비스 형태로 확장되었다.