# FastPass GHCR Container Registry 검증

## 1. 목적

이번 단계의 목적은 GitHub Actions CI에서 생성한 Docker image를
GitHub Container Registry, 즉 GHCR에 push하는 것이다.

이전 단계에서는 GitHub Actions를 이용하여 다음 작업을 자동화하였다.

```text
git push
→ GitHub Actions 실행
→ Spring Boot Gradle build
→ Docker image build
```

하지만 이전 단계의 Docker image는 GitHub Actions runner 내부에서만 생성되고,
workflow가 끝나면 사라지는 구조였다.

이번 단계에서는 Docker image build 이후 GHCR에 image를 push하도록 확장하였다.

이를 통해 FastPass API image가 외부 container registry에 저장되고,
이후 Kubernetes 또는 ArgoCD가 해당 image를 pull하여 배포할 수 있는 기반을 마련하였다.

---

## 2. Container Registry 도입 이유

기존 로컬 Kubernetes 배포에서는 다음과 같은 방식을 사용하였다.

```text
로컬 PC에서 Docker image build
→ Docker Desktop Kubernetes node에 image import
→ imagePullPolicy: Never
→ Kubernetes가 로컬 image 사용
```

즉, 기존 구조에서는 Kubernetes가 외부 registry에서 image를 가져오는 것이 아니라,
로컬 환경에 이미 존재하는 image를 사용하는 방식이었다.

이 방식은 로컬 실습에는 적합하지만 다음과 같은 한계가 있다.

```text
1. 다른 환경에서는 동일한 image를 바로 사용할 수 없다.
2. image를 매번 수동으로 build/import해야 한다.
3. 어떤 commit에서 만들어진 image인지 추적하기 어렵다.
4. ArgoCD가 GitOps 방식으로 배포하더라도 image 자체는 로컬 환경에 의존한다.
5. EKS 같은 실제 Kubernetes 환경에서는 로컬 imagePullPolicy: Never 방식이 적합하지 않다.
```

Container registry를 사용하면 다음과 같은 구조로 바꿀 수 있다.

```text
GitHub Actions
→ Docker image build
→ GHCR에 image push
→ Kubernetes가 GHCR에서 image pull
```

즉, image를 로컬 PC가 아니라 외부 registry에 저장함으로써
배포 환경에서 재사용 가능한 container image를 만들 수 있다.

---

## 3. 이번 단계의 목표

이번 단계의 목표는 다음과 같다.

```text
1. GitHub Actions workflow에 GHCR 로그인 단계를 추가한다.
2. Docker image build 후 GHCR에 push한다.
3. latest tag와 commit SHA tag를 함께 생성한다.
4. GitHub Packages에서 image가 생성되었는지 확인한다.
5. package visibility를 Public으로 설정한다.
6. docker pull 명령어로 image pull이 가능한 상태를 확인한다.
```

---

## 4. 기존 CI와의 차이

이전 GitHub Actions CI는 Docker image를 build만 했다.

```text
기존 CI:
Gradle build
→ Docker image build
→ workflow 종료
```

이 경우 image는 GitHub Actions runner 안에서만 존재하고,
workflow가 종료되면 사라진다.

이번 단계에서는 Docker image build 이후 GHCR에 push하도록 변경하였다.

```text
변경 후 CI:
Gradle build
→ Docker image build
→ GHCR login
→ GHCR image push
→ GitHub Packages에 image 저장
```

따라서 이번 단계 이후에는 FastPass API image를 다음 주소로 pull할 수 있다.

```text
ghcr.io/kdh8128/fastpass-k8s-api
```

---

## 5. GHCR 이미지 이름

FastPass API image는 다음 이름으로 GHCR에 push되도록 구성하였다.

```text
ghcr.io/kdh8128/fastpass-k8s-api
```

구성 요소는 다음과 같다.

```text
ghcr.io
→ GitHub Container Registry 주소

kdh8128
→ GitHub 사용자 또는 namespace

fastpass-k8s-api
→ FastPass API container image 이름
```

---

## 6. GitHub Actions Workflow 수정

기존 `.github/workflows/ci.yml`을 수정하여 Docker image를 GHCR에 push하도록 변경하였다.

수정된 workflow는 다음과 같다.

```yaml
name: FastPass CI

on:
  push:
    branches:
      - main
  pull_request:
    branches:
      - main

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: kdh8128/fastpass-k8s-api

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
    name: Build and Push Docker Image
    runs-on: ubuntu-latest
    needs: build

    permissions:
      contents: read
      packages: write

    steps:
      - name: Checkout repository
        uses: actions/checkout@v5

      - name: Log in to GitHub Container Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and push Docker image
        uses: docker/build-push-action@v6
        with:
          context: ./apps/api
          push: ${{ github.event_name == 'push' }}
          tags: |
            ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:latest
            ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:${{ github.sha }}
          labels: |
            org.opencontainers.image.source=https://github.com/kdh8128/fastpass-k8s
            org.opencontainers.image.description=FastPass Spring Boot API
            org.opencontainers.image.licenses=MIT
```

---

## 7. Workflow 권한 설정

GHCR에 image를 push하기 위해 `docker-build` job에 다음 권한을 추가하였다.

```yaml
permissions:
  contents: read
  packages: write
```

각 권한의 의미는 다음과 같다.

| 권한 | 의미 |
|---|---|
| `contents: read` | GitHub Actions runner가 repository 코드를 읽을 수 있도록 허용 |
| `packages: write` | GHCR에 container image를 push할 수 있도록 허용 |

이 권한이 없으면 GitHub Actions가 GHCR에 로그인하거나 image를 push하는 과정에서 권한 오류가 발생할 수 있다.

---

## 8. GHCR 로그인 설정

GHCR 로그인은 `docker/login-action`을 사용하였다.

```yaml
- name: Log in to GitHub Container Registry
  uses: docker/login-action@v3
  with:
    registry: ${{ env.REGISTRY }}
    username: ${{ github.actor }}
    password: ${{ secrets.GITHUB_TOKEN }}
```

이번 단계에서는 별도의 Personal Access Token을 만들지 않고,
GitHub Actions에서 기본 제공되는 `GITHUB_TOKEN`을 사용하였다.

```text
registry: ghcr.io
username: GitHub Actions 실행 사용자
password: GITHUB_TOKEN
```

이를 통해 workflow가 실행되는 repository 범위 안에서 GHCR에 image를 push할 수 있다.

---

## 9. Docker Build and Push 설정

Docker image build와 push는 `docker/build-push-action`을 사용하였다.

```yaml
- name: Build and push Docker image
  uses: docker/build-push-action@v6
  with:
    context: ./apps/api
    push: ${{ github.event_name == 'push' }}
    tags: |
      ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:latest
      ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:${{ github.sha }}
```

`context`는 Docker build context를 의미한다.

FastPass API의 Dockerfile은 `apps/api` 아래에 있으므로 다음과 같이 지정하였다.

```yaml
context: ./apps/api
```

`push` 설정은 다음과 같이 구성하였다.

```yaml
push: ${{ github.event_name == 'push' }}
```

이 설정의 의미는 다음과 같다.

```text
push event:
Docker image를 GHCR에 push

pull_request event:
Docker image build는 수행하지만 GHCR에는 push하지 않음
```

즉, pull request에서는 image push 없이 build 검증만 수행하고,
main branch push에서만 GHCR push가 수행되도록 구성하였다.

---

## 10. Image Tag 전략

이번 단계에서는 하나의 image에 두 가지 tag를 부여하였다.

```yaml
tags: |
  ghcr.io/kdh8128/fastpass-k8s-api:latest
  ghcr.io/kdh8128/fastpass-k8s-api:${{ github.sha }}
```

생성되는 image tag는 다음과 같다.

```text
ghcr.io/kdh8128/fastpass-k8s-api:latest
ghcr.io/kdh8128/fastpass-k8s-api:<commit-sha>
```

`latest` tag는 최신 image를 쉽게 확인하기 위한 용도이다.

```text
ghcr.io/kdh8128/fastpass-k8s-api:latest
```

commit SHA tag는 image가 어떤 commit에서 만들어졌는지 추적하기 위한 용도이다.

```text
ghcr.io/kdh8128/fastpass-k8s-api:834b63cde03fd9215fa18eb7d717c83d3400bf97
```

`latest`만 사용하면 어떤 commit에서 만들어진 image인지 추적하기 어렵다.

반면 commit SHA tag를 함께 사용하면 다음과 같은 장점이 있다.

```text
1. image와 source code commit을 연결할 수 있다.
2. 특정 배포 버전을 추적하기 쉽다.
3. 문제가 발생했을 때 어떤 commit의 image인지 확인할 수 있다.
4. rollback 시 특정 image tag를 지정할 수 있다.
```

---

## 11. OCI Label 설정

image metadata를 남기기 위해 OCI label도 추가하였다.

```yaml
labels: |
  org.opencontainers.image.source=https://github.com/kdh8128/fastpass-k8s
  org.opencontainers.image.description=FastPass Spring Boot API
  org.opencontainers.image.licenses=MIT
```

각 label의 의미는 다음과 같다.

| Label | 의미 |
|---|---|
| `org.opencontainers.image.source` | image의 source repository |
| `org.opencontainers.image.description` | image 설명 |
| `org.opencontainers.image.licenses` | image license 정보 |

이를 통해 GHCR Package 화면에서 image의 source repository와 설명을 확인할 수 있다.

---

## 12. Workflow 실행

workflow 수정 후 다음 명령어로 변경 사항을 commit하고 push하였다.

```bash
git add .github/workflows/ci.yml

git commit -m "ci: push docker image to ghcr"

git push
```

push 이후 GitHub Actions가 자동으로 실행되었다.

GitHub repository의 Actions 탭에서 다음 job이 성공한 것을 확인하였다.

```text
Build Spring Boot API
Build and Push Docker Image
```

`Build and Push Docker Image` job 안에서는 다음 단계가 성공하였다.

```text
Checkout repository
Log in to GitHub Container Registry
Build and push Docker image
```

---

## 13. GHCR Package 생성 확인

GitHub Actions workflow가 성공한 뒤 GitHub Packages에서 다음 package가 생성된 것을 확인하였다.

```text
fastpass-k8s-api
```

Package 화면에서 다음 정보가 확인되었다.

```text
Package name: fastpass-k8s-api
Description: FastPass Spring Boot API
Visibility: Public
Latest tag: latest
Commit SHA tag: 834b63cde03fd9215fa18eb7d717c83d3400bf97
Last published: 16 minutes ago
Total downloads: 1
```

GitHub Packages 화면에서는 다음 pull 명령어도 확인되었다.

```bash
docker pull ghcr.io/kdh8128/fastpass-k8s-api:834b63cde03fd9215fa18eb7d717c83d3400bf97
```

이를 통해 FastPass API image가 GHCR에 정상적으로 push되었음을 확인하였다.

---

## 14. Package Visibility 설정

생성된 GHCR package는 Public 상태로 확인되었다.

```text
Visibility: Public
```

Package가 Public이면 별도 인증 없이 다음과 같이 image를 pull할 수 있다.

```bash
docker pull ghcr.io/kdh8128/fastpass-k8s-api:latest
```

또는 commit SHA tag를 사용하여 특정 image를 pull할 수 있다.

```bash
docker pull ghcr.io/kdh8128/fastpass-k8s-api:834b63cde03fd9215fa18eb7d717c83d3400bf97
```

포트폴리오 프로젝트에서는 package를 Public으로 설정해두면,
repository를 보는 사람이 실제 container image까지 확인할 수 있다는 장점이 있다.

---

## 15. 검증 결과

이번 단계에서 확인한 내용은 다음과 같다.

```text
1. GitHub Actions workflow에 GHCR push 단계를 추가하였다.
2. docker/login-action을 사용하여 GHCR에 로그인하였다.
3. docker/build-push-action을 사용하여 Docker image를 build하였다.
4. main branch push 시 Docker image가 GHCR에 push되도록 구성하였다.
5. pull_request 이벤트에서는 image push 없이 build 검증만 수행하도록 구성하였다.
6. latest tag와 commit SHA tag를 함께 생성하였다.
7. GHCR에 fastpass-k8s-api package가 생성된 것을 확인하였다.
8. package가 Public 상태인 것을 확인하였다.
9. GitHub Packages 화면에서 docker pull 명령어가 제공되는 것을 확인하였다.
```

---

## 16. 현재 구조

이번 단계 이후 FastPass의 CI 흐름은 다음과 같다.

```text
Developer
  ↓
git push
  ↓
GitHub Actions
  ↓
Gradle build
  ↓
Docker image build
  ↓
GHCR image push
  ↓
GitHub Packages
```

image는 다음 이름으로 저장된다.

```text
ghcr.io/kdh8128/fastpass-k8s-api:latest
ghcr.io/kdh8128/fastpass-k8s-api:<commit-sha>
```

현재까지의 전체 DevOps 흐름은 다음과 같이 정리할 수 있다.

```text
Code
→ GitHub Actions CI
→ Docker Image
→ GHCR
→ Helm Chart
→ ArgoCD GitOps
→ Kubernetes
→ Prometheus/Grafana
→ Alertmanager
```

다만 아직 Helm Chart와 ArgoCD가 GHCR image를 직접 사용하도록 연결한 단계는 아니다.

현재 Helm Chart의 image 설정은 기존 로컬 Kubernetes 검증에 맞춰져 있다.

```yaml
image:
  repository: fastpass-k8s-api
  tag: latest
  pullPolicy: Never
```

따라서 다음 단계에서는 Helm Chart의 image 설정을 GHCR 기준으로 변경해야 한다.

---

## 17. 기존 Helm Image 설정의 한계

현재 Helm Chart의 image 설정은 다음과 같다.

```yaml
image:
  repository: fastpass-k8s-api
  tag: latest
  pullPolicy: Never
```

이 설정은 로컬 Docker Desktop Kubernetes 환경에서 image를 직접 import하여 사용할 때 적합하다.

```text
로컬 Docker image 사용
→ imagePullPolicy: Never
→ Kubernetes가 외부 registry에서 image를 pull하지 않음
```

하지만 GHCR image를 사용하려면 다음과 같은 구조가 필요하다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: latest
  pullPolicy: IfNotPresent
```

이렇게 변경하면 Kubernetes는 로컬 image만 바라보는 것이 아니라,
GHCR에서 image를 pull할 수 있는 구조가 된다.

---

## 18. 다음 단계

다음 단계에서는 Helm Chart가 GHCR image를 사용하도록 변경한다.

목표는 다음과 같다.

```text
1. deploy/helm/fastpass/values.yaml image 설정 변경
2. image.repository를 ghcr.io/kdh8128/fastpass-k8s-api로 변경
3. image.pullPolicy를 IfNotPresent로 변경
4. ArgoCD Sync 수행
5. fastpass-gitops namespace의 Pod가 GHCR image를 사용하는지 확인
6. 더 이상 로컬 image import에 의존하지 않는 배포 구조 검증
```

예상 변경 사항은 다음과 같다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: latest
  pullPolicy: IfNotPresent
```

검증 명령어는 다음과 같이 사용할 수 있다.

```bash
kubectl get pod -n fastpass-gitops \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[0].image}{"\n"}{end}'
```

기대 결과는 다음과 같다.

```text
fastpass-api-xxxxx      ghcr.io/kdh8128/fastpass-k8s-api:latest
fastpass-worker-xxxxx   ghcr.io/kdh8128/fastpass-k8s-api:latest
```

---

## 19. 한계점

이번 단계에서는 GHCR image push까지만 구현하였다.

아직 다음 기능은 구현하지 않았다.

```text
Helm Chart의 GHCR image 사용
ArgoCD를 통한 GHCR image 배포
image tag 자동 업데이트
ArgoCD Image Updater
commit SHA tag 기반 배포 고정
rollback 자동화
image vulnerability scan
multi-architecture build
```

현재 구조에서는 GHCR에 image는 정상적으로 push되지만,
Kubernetes 배포는 아직 기존 Helm image 설정을 사용한다.

따라서 다음 단계에서 Helm Chart와 ArgoCD가 GHCR image를 사용하도록 연결해야 한다.

---

## 20. 결론

이번 단계에서는 GitHub Actions CI를 확장하여 Docker image를 GHCR에 push하도록 구성하였다.

기존 CI는 Gradle build와 Docker image build까지만 수행했기 때문에,
생성된 image가 workflow 종료 후 사라지는 구조였다.

이번 변경을 통해 FastPass API image가 `ghcr.io/kdh8128/fastpass-k8s-api`에 저장되도록 하였고,
`latest` tag와 commit SHA tag를 함께 생성하였다.

GitHub Packages 화면에서 `fastpass-k8s-api` package가 생성된 것을 확인하였으며,
package visibility도 Public 상태로 설정되어 외부에서 pull 가능한 상태가 되었다.

이를 통해 FastPass는 로컬 image import 방식에서 벗어나,
container registry 기반 배포로 확장할 수 있는 기반을 갖추게 되었다.

다음 단계에서는 Helm Chart의 image 설정을 GHCR 기준으로 변경하고,
ArgoCD가 GHCR image를 사용하는 배포 구조를 검증한다.