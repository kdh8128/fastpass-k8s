# FastPass GitHub Actions CI 검증

## 1. 목적

이번 단계의 목적은 FastPass 프로젝트에 GitHub Actions 기반 CI 파이프라인을 구성하는 것이다.

이전 단계까지 FastPass는 다음 흐름으로 발전하였다.

```text
1. Spring Boot API 구현
2. Redis Queue 기반 Worker 처리
3. Docker / Docker Compose 구성
4. Kubernetes 배포
5. HPA 기반 자동 확장
6. Prometheus / Grafana 모니터링
7. PrometheusRule / Alertmanager 기반 Alerting
8. Helm Chart 기반 배포
9. ArgoCD GitOps 기반 배포
```

이번 단계에서는 GitHub repository에 코드가 push되거나 pull request가 생성될 때, GitHub Actions가 자동으로 빌드를 수행하도록 구성하였다.

이를 통해 로컬 환경에 의존하지 않고, GitHub에 올라간 코드가 독립적인 CI 환경에서도 정상적으로 빌드되는지 검증할 수 있도록 하였다.

---

## 2. CI 도입 이유

기존에는 로컬 환경에서 직접 빌드를 수행하였다.

```bash
cd apps/api
./gradlew clean build
```

Docker image도 로컬에서 직접 빌드하였다.

```bash
docker build -t fastpass-k8s-api:latest ./apps/api
```

이 방식은 개발 중에는 충분히 유용하지만, 다음과 같은 한계가 있다.

```text
1. 개발자가 빌드 확인을 깜빡하고 push할 수 있다.
2. 로컬 PC에만 존재하는 설정이나 캐시에 의존할 수 있다.
3. GitHub에 올라간 코드가 실제로 빌드 가능한 상태인지 자동으로 보장하지 않는다.
4. Dockerfile이 깨져도 push 시점에 자동으로 감지되지 않는다.
5. 다른 사람이 repository를 clone했을 때 동일하게 빌드되는지 확인하기 어렵다.
```

GitHub Actions CI를 도입하면 push 또는 pull request 시점에 자동으로 빌드가 실행된다.

```text
git push
→ GitHub Actions 실행
→ Spring Boot Gradle build
→ Docker image build
→ 성공 또는 실패 결과 표시
```

이를 통해 코드 변경이 repository에 반영되는 시점마다 최소한의 빌드 안정성을 검증할 수 있다.

---

## 3. 로컬 빌드와 GitHub Actions CI의 차이

로컬 빌드는 개발자 개인 환경에서 직접 수행하는 검증이다.

```text
로컬 빌드:
내 PC의 JDK
내 PC의 Gradle cache
내 PC의 Docker daemon
내 PC의 환경변수
내가 직접 실행한 명령어
```

반면 GitHub Actions CI는 GitHub에서 제공하는 runner 환경에서 자동으로 수행된다.

```text
GitHub Actions CI:
GitHub repository checkout
새로운 Ubuntu runner 환경
JDK 설치
Gradle build 실행
Docker image build 실행
결과를 repository Actions 탭에 기록
```

에러 문구 자체는 로컬 빌드와 비슷할 수 있다.

예를 들어 컴파일 오류가 발생하면 로컬에서도, GitHub Actions에서도 비슷하게 `BUILD FAILED`가 출력된다.

하지만 중요한 차이는 다음과 같다.

```text
로컬 빌드:
내 컴퓨터에서 내가 수동으로 확인하는 것

GitHub Actions CI:
GitHub에 올라간 코드를 깨끗한 외부 환경에서 자동으로 검증하고,
그 결과를 repository에 기록하는 것
```

즉, GitHub Actions CI의 핵심 가치는 에러 메시지가 달라지는 것이 아니라, 자동화, 재현성, 공개 검증, 실수 방지에 있다.

---

## 4. CI 적용 범위

이번 단계에서 구성한 CI는 다음 두 가지를 검증한다.

```text
1. Spring Boot Gradle build
2. Docker image build
```

현재 CI는 Docker image를 registry에 push하지 않는다.

즉, 이번 단계의 목표는 배포까지 자동화하는 CD가 아니라, 코드와 Dockerfile이 정상적으로 빌드 가능한지 확인하는 CI이다.

현재 CI 범위는 다음과 같다.

```text
GitHub push 또는 pull request
→ Gradle clean build
→ Docker image build
→ GitHub Actions 결과 확인
```

아직 포함하지 않은 기능은 다음과 같다.

```text
Docker Hub / GHCR / ECR image push
Helm values image tag 자동 변경
ArgoCD 자동 배포 trigger
테스트 커버리지 리포트
보안 취약점 스캔
```

---

## 5. 프로젝트 구조

FastPass repository 구조에서 Spring Boot 애플리케이션은 `apps/api` 아래에 위치한다.

```text
fastpass-k8s/
├── apps/
│   └── api/
│       ├── build.gradle
│       ├── settings.gradle
│       ├── gradlew
│       ├── Dockerfile
│       └── src/
├── deploy/
├── docs/
└── .github/
```

따라서 GitHub Actions workflow에서 Gradle build를 실행할 때는 작업 디렉터리를 `apps/api`로 지정해야 한다.

Docker image build는 repository root에서 다음 경로를 기준으로 수행한다.

```bash
docker build -t fastpass-k8s-api:ci ./apps/api
```

---

## 6. GitHub Actions Workflow 작성

GitHub Actions workflow 파일은 다음 경로에 작성하였다.

```text
.github/workflows/ci.yml
```

최종 workflow는 다음과 같다.

```yaml
name: FastPass CI

on:
  push:
    branches:
      - main
  pull_request:
    branches:
      - main

jobs:
  build:
    name: Build Spring Boot API
    runs-on: ubuntu-latest

    defaults:
      run:
        working-directory: apps/api

    steps:
      - name: Checkout repository
        uses: actions/checkout@v5

      - name: Set up JDK 21
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v6

      - name: Grant execute permission for Gradle wrapper
        run: chmod +x ./gradlew

      - name: Build with Gradle
        run: ./gradlew clean build

  docker-build:
    name: Build Docker Image
    runs-on: ubuntu-latest
    needs: build

    steps:
      - name: Checkout repository
        uses: actions/checkout@v5

      - name: Build Docker image
        run: docker build -t fastpass-k8s-api:ci ./apps/api
```

---

## 7. Workflow Trigger

이번 workflow는 `main` branch에 push되거나, `main` branch를 대상으로 pull request가 생성될 때 실행된다.

```yaml
on:
  push:
    branches:
      - main
  pull_request:
    branches:
      - main
```

이를 통해 다음 상황에서 자동 검증이 수행된다.

```text
1. main branch에 직접 push할 때
2. main branch로 pull request를 생성할 때
```

현재 프로젝트는 개인 포트폴리오 프로젝트이므로 main branch push를 기준으로 CI를 검증하였다.

---

## 8. Build Spring Boot API Job

첫 번째 job은 Spring Boot 애플리케이션을 Gradle로 빌드하는 역할을 한다.

```yaml
build:
  name: Build Spring Boot API
  runs-on: ubuntu-latest
```

GitHub Actions runner는 Ubuntu 환경을 사용한다.

FastPass의 Spring Boot 프로젝트는 repository root가 아니라 `apps/api`에 있으므로, 기본 working directory를 다음과 같이 지정하였다.

```yaml
defaults:
  run:
    working-directory: apps/api
```

이 설정을 통해 이후 Gradle 명령어는 `apps/api` 디렉터리에서 실행된다.

---

## 9. Repository Checkout

GitHub Actions runner에서 repository 코드를 가져오기 위해 checkout action을 사용하였다.

```yaml
- name: Checkout repository
  uses: actions/checkout@v5
```

이 단계는 GitHub repository의 코드를 runner 환경에 내려받는 역할을 한다.

---

## 10. JDK 21 설정

FastPass API는 Java 21 기반 Spring Boot 애플리케이션이다.

따라서 GitHub Actions runner에 JDK 21을 설치하였다.

```yaml
- name: Set up JDK 21
  uses: actions/setup-java@v5
  with:
    distribution: temurin
    java-version: '21'
```

이를 통해 로컬 PC가 아닌 GitHub Actions runner에서도 Java 21 환경으로 빌드가 수행된다.

---

## 11. Gradle 설정

Gradle 실행 환경과 cache 최적화를 위해 Gradle 공식 action을 사용하였다.

```yaml
- name: Set up Gradle
  uses: gradle/actions/setup-gradle@v6
```

이 단계는 Gradle 실행에 필요한 환경을 설정하고, workflow 실행 간 Gradle cache를 활용할 수 있도록 한다.

실행 결과 GitHub Actions summary에서 Gradle build 정보가 표시되었다.

```text
Gradle Root Project: api
Requested Tasks: clean build
Gradle Version: 8.14.5
Build Outcome: success
```

---

## 12. Gradle Wrapper 실행 권한 부여

Linux runner 환경에서는 `gradlew` 파일에 실행 권한이 없으면 다음과 같은 오류가 발생할 수 있다.

```text
Permission denied: ./gradlew
```

이를 방지하기 위해 workflow에 실행 권한 부여 단계를 추가하였다.

```yaml
- name: Grant execute permission for Gradle wrapper
  run: chmod +x ./gradlew
```

---

## 13. Gradle Build 실행

Spring Boot API 빌드는 다음 명령어로 수행하였다.

```yaml
- name: Build with Gradle
  run: ./gradlew clean build
```

이 단계에서 수행되는 검증은 다음과 같다.

```text
1. Java 코드 컴파일
2. Gradle dependency resolve
3. 테스트 실행
4. Spring Boot jar build
```

GitHub Actions에서 이 단계가 성공하면, GitHub repository에 올라간 API 코드가 CI runner 환경에서도 정상적으로 빌드된다는 의미이다.

---

## 14. Docker Image Build Job

두 번째 job은 Docker image build를 검증한다.

```yaml
docker-build:
  name: Build Docker Image
  runs-on: ubuntu-latest
  needs: build
```

`needs: build`를 지정했기 때문에, Gradle build job이 성공한 후에만 Docker image build가 실행된다.

즉, 코드 빌드가 실패하면 Docker image build는 실행되지 않는다.

---

## 15. Docker Image Build 실행

Docker image build는 repository root 기준으로 `apps/api` 디렉터리를 build context로 사용한다.

```yaml
- name: Build Docker image
  run: docker build -t fastpass-k8s-api:ci ./apps/api
```

이 단계에서 검증되는 것은 다음과 같다.

```text
1. apps/api/Dockerfile 문법 정상 여부
2. Docker build context 경로 정상 여부
3. Gradle bootJar 기반 image build 가능 여부
4. container image 생성 가능 여부
```

이 단계가 성공하면, FastPass API Dockerfile이 GitHub Actions runner 환경에서도 정상적으로 image를 생성할 수 있음을 의미한다.

---

## 16. CI 실행 결과

workflow를 push한 뒤 GitHub repository의 Actions 탭에서 CI 실행 결과를 확인하였다.

GitHub Actions summary에서 `Build Spring Boot API` job이 성공하였다.

```text
Build Outcome: success
```

Gradle summary에는 다음 내용이 표시되었다.

```text
Gradle Root Project: api
Requested Tasks: clean build
Gradle Version: 8.14.5
Build Outcome: success
```

Docker image build job도 성공하였다.

최종적으로 GitHub Actions workflow는 green check 상태가 되었다.

```text
Build Spring Boot API: success
Build Docker Image: success
```

이를 통해 main branch push 시 FastPass API build와 Docker image build가 자동으로 검증되는 것을 확인하였다.

---

## 17. Warning 확인 및 해결

초기 workflow 실행은 성공했지만, GitHub Actions summary에 warning이 표시되었다.

대표 warning은 다음과 같았다.

```text
Node.js 20 is deprecated.
The following actions target Node.js 20 but are being forced to run on Node.js 24.
```

또한 다음 warning도 표시되었다.

```text
setup-java v4 is deprecated and will no longer receive updates.
Please migrate to actions/setup-java@v5.
```

초기 workflow에서는 다음 action 버전을 사용하였다.

```yaml
uses: actions/checkout@v4
uses: actions/setup-java@v4
uses: gradle/actions/setup-gradle@v4
```

warning을 해결하기 위해 action 버전을 최신화하였다.

```yaml
uses: actions/checkout@v5
uses: actions/setup-java@v5
uses: gradle/actions/setup-gradle@v6
```

수정 후 다시 workflow를 실행하였고, 기존 warning이 사라진 것을 확인하였다.

이 변경은 CI 기능 자체를 크게 바꾸는 것은 아니지만, GitHub Actions 런타임 변경에 대한 호환성을 개선하고 향후 deprecated action으로 인한 문제 가능성을 줄인다.

---

## 18. 현재 CI의 의미

이번 CI 구성으로 인해 FastPass는 다음 상태를 갖게 되었다.

```text
코드 push
→ GitHub Actions 실행
→ Gradle build 검증
→ Docker image build 검증
→ GitHub Actions 결과 기록
```

이전에는 개발자가 로컬에서 직접 빌드를 확인해야 했다.

```text
로컬 수동 검증:
개발자 PC에서만 build 확인
검증 여부가 기록으로 남지 않음
push 시 자동 검증 없음
```

이제는 GitHub Actions를 통해 자동 검증이 수행된다.

```text
CI 자동 검증:
GitHub에 올라간 코드 기준으로 검증
독립적인 runner 환경에서 build 확인
성공/실패 결과가 repository에 기록됨
```

따라서 FastPass repository는 단순히 코드만 올라가 있는 상태가 아니라, push 시점마다 빌드 가능 여부를 자동으로 확인하는 구조를 갖추게 되었다.

---

## 19. 포트폴리오 관점의 의미

이번 단계는 DevOps 포트폴리오 관점에서 중요하다.

이전까지 FastPass는 Kubernetes 배포와 운영 관측 측면을 중심으로 발전하였다.

```text
Kubernetes 배포
HPA 자동 확장
Prometheus/Grafana 모니터링
PrometheusRule Alerting
Helm Chart
ArgoCD GitOps
```

GitHub Actions CI를 추가함으로써 코드 변경 단계의 자동 검증까지 포함하게 되었다.

즉, FastPass는 다음 흐름을 갖게 되었다.

```text
Code
→ Build
→ Container
→ Kubernetes
→ GitOps
→ Monitoring
→ Alerting
```

이를 통해 프로젝트는 단순 Kubernetes 배포 예제가 아니라, 애플리케이션 변경부터 배포 및 운영 관측까지 이어지는 DevOps 흐름을 보여줄 수 있게 되었다.

---

## 20. 검증 결과 요약

이번 GitHub Actions CI 단계에서 확인한 내용은 다음과 같다.

```text
1. .github/workflows/ci.yml 파일을 생성하였다.
2. main branch push 시 GitHub Actions workflow가 실행되도록 구성하였다.
3. pull request 시에도 CI가 실행되도록 구성하였다.
4. JDK 21 기반 Gradle build 환경을 구성하였다.
5. apps/api 디렉터리에서 ./gradlew clean build를 실행하도록 구성하였다.
6. Gradle build가 성공하는 것을 확인하였다.
7. Docker image build job을 추가하였다.
8. Gradle build 성공 후 Docker image build가 실행되도록 needs 관계를 설정하였다.
9. Dockerfile 기반 image build가 성공하는 것을 확인하였다.
10. GitHub Actions summary에서 build success를 확인하였다.
11. deprecated action warning을 확인하였다.
12. checkout, setup-java, setup-gradle action 버전을 최신화하였다.
13. 수정 후 warning이 사라지고 CI가 정상 성공하는 것을 확인하였다.
```

---

## 21. 한계점

이번 단계에서는 GitHub Actions를 이용하여 build 검증까지만 수행하였다.

아직 다음 기능은 구현하지 않았다.

```text
Docker image registry push
GHCR 또는 Docker Hub 연동
ECR 연동
image tag 자동 생성
Helm values image tag 자동 업데이트
ArgoCD 자동 sync trigger
테스트 커버리지 리포트
정적 분석
보안 취약점 스캔
branch protection rule
pull request merge 조건 설정
```

현재 CI는 다음 질문에 답하는 단계이다.

```text
이 코드가 빌드 가능한가?
이 Dockerfile로 image를 만들 수 있는가?
```

아직 다음 질문까지는 처리하지 않는다.

```text
빌드된 image를 registry에 올렸는가?
ArgoCD가 새 image를 자동으로 배포하는가?
운영 환경까지 자동 배포되는가?
```

따라서 이번 단계는 CI의 기초 단계이며, 이후 CD 단계로 확장할 수 있다.

---

## 22. 다음 단계

다음 단계에서는 CI를 확장하여 container registry push를 구성할 수 있다.

가능한 방향은 다음과 같다.

```text
1. GitHub Container Registry 또는 Docker Hub 선택
2. GitHub Actions에서 Docker image build
3. commit SHA 기반 image tag 생성
4. image registry에 push
5. Helm values.yaml의 image tag 업데이트 방식 설계
6. ArgoCD가 새 image를 배포하도록 CD 구조 확장
```

가장 자연스러운 다음 단계는 GitHub Container Registry, 즉 GHCR을 사용하는 것이다.

예상 흐름은 다음과 같다.

```text
git push
→ GitHub Actions
→ Gradle build
→ Docker image build
→ ghcr.io/kdh8128/fastpass-k8s-api:<commit-sha> push
→ Helm Chart image tag 변경
→ ArgoCD sync
→ Kubernetes 배포
```

처음에는 image push까지만 구현하고, 이후 Helm values update와 ArgoCD 자동 배포까지 확장하는 방식이 적절하다.

---

## 23. 결론

이번 단계에서는 FastPass 프로젝트에 GitHub Actions 기반 CI 파이프라인을 구성하였다.

main branch push 및 pull request 시 GitHub Actions가 자동으로 실행되도록 설정하였고, Spring Boot API의 Gradle build와 Docker image build를 검증하였다.

초기 실행에서 deprecated action warning이 발생하였으나, `actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle` 버전을 최신화하여 warning을 해결하였다.

이를 통해 FastPass는 로컬 개발 환경에만 의존하지 않고, GitHub Actions runner의 독립적인 환경에서 build가 재현 가능함을 검증할 수 있게 되었다.

결과적으로 FastPass 프로젝트는 코드 변경을 자동으로 검증하는 CI 단계를 갖추게 되었으며, 이후 container registry push와 ArgoCD 기반 CD 파이프라인으로 확장할 수 있는 기반을 마련하였다.