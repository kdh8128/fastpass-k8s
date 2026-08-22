# FastPass CI/CD 자동화 검증

## 1. 목적

이번 단계의 목적은 GitHub Actions와 ArgoCD를 연결하여, `git push` 이후 build, image push, Helm image tag update, GitOps 배포까지 이어지는 CI/CD 자동화 흐름을 구성하는 것이다.

기존에는 GitHub Actions가 Docker image를 GHCR에 push하더라도, Helm Chart의 image tag는 사람이 직접 수정해야 했다.

이번 단계에서는 GitHub Actions가 GHCR에 image를 push한 뒤, `deploy/helm/fastpass/values.yaml`의 image tag를 commit SHA로 자동 수정하고, 이를 다시 Git에 commit/push하도록 구성하였다.

ArgoCD는 이 Git 변경을 감지하여 Kubernetes에 자동 배포한다.

---

## 2. 최종 목표 흐름

이번 단계에서 목표로 한 CI/CD 흐름은 다음과 같다.

```text
git push
↓
GitHub Actions
↓
Spring Boot Gradle build
↓
Docker image build
↓
GHCR image push
↓
deploy/helm/fastpass/values.yaml image.tag 자동 수정
↓
GitHub Actions bot commit/push
↓
ArgoCD가 Git 변경 감지
↓
Kubernetes 자동 재배포
```

이 구조를 통해 개발자는 애플리케이션 코드를 push하기만 하면 된다.

이후 image build, registry push, manifest update, GitOps 배포는 자동으로 이어진다.

---

## 3. 기존 방식의 한계

기존 CI는 다음 작업까지 수행하였다.

```text
GitHub Actions
→ Gradle build
→ Docker image build
→ GHCR image push
```

하지만 Kubernetes가 새 image를 사용하려면 Helm Chart의 image tag가 변경되어야 한다.

기존에는 다음 작업을 수동으로 수행해야 했다.

```text
1. git rev-parse HEAD로 commit SHA 확인
2. deploy/helm/fastpass/values.yaml의 image.tag 수정
3. 수정 사항 commit/push
4. ArgoCD가 Git 변경 감지
5. Kubernetes 재배포
```

이 방식은 사람이 직접 tag를 바꿔야 하므로 실수 가능성이 있고, 자동화된 CD 흐름이라고 보기 어렵다.

따라서 image push 이후 Helm values의 image tag를 자동으로 변경하는 job을 추가하였다.

---

## 4. latest tag 기반 배포의 한계

이전 Helm Chart image 설정은 다음과 같았다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: latest
  pullPolicy: IfNotPresent
```

`latest` tag는 사용하기 쉽지만 다음 한계가 있다.

```text
1. 어떤 commit에서 만들어진 image인지 명확하지 않다.
2. 새 image가 GHCR에 push되어도 Git manifest 값은 그대로 latest이다.
3. ArgoCD는 Git 변경을 기준으로 sync하므로, image만 새로 push되어도 배포 변경을 감지하지 못할 수 있다.
4. imagePullPolicy가 IfNotPresent이면 node에 latest image가 이미 존재할 경우 새 image를 pull하지 않을 수 있다.
```

따라서 commit SHA 기반 image tag를 사용하도록 변경하였다.

---

## 5. commit SHA tag 전략

GitHub Actions는 현재 commit SHA를 Docker image tag로 사용한다.

예시는 다음과 같다.

```text
ghcr.io/kdh8128/fastpass-k8s-api:5c72a70...
```

commit SHA tag를 사용하면 다음 장점이 있다.

```text
1. 배포된 image가 어떤 commit에서 만들어졌는지 추적할 수 있다.
2. Kubernetes에 배포된 버전과 Git commit을 연결할 수 있다.
3. rollback 시 특정 image tag를 명확히 지정할 수 있다.
4. ArgoCD가 values.yaml 변경을 감지할 수 있다.
```

---

## 6. GitHub Actions Workflow 구조

최종 workflow job 구성은 다음과 같다.

```text
build
  ↓
docker-build-and-push
  ↓
update-helm-image-tag
```

각 job의 역할은 다음과 같다.

| Job | Role |
|---|---|
| `build` | Spring Boot Gradle build |
| `docker-build-and-push` | Docker image build 및 GHCR push |
| `update-helm-image-tag` | Helm values.yaml image.tag를 commit SHA로 자동 수정 후 commit/push |

---

## 7. Docker Image Build and Push

Docker image는 GHCR에 두 가지 tag로 push된다.

```yaml
tags: |
  ghcr.io/kdh8128/fastpass-k8s-api:latest
  ghcr.io/kdh8128/fastpass-k8s-api:${{ github.sha }}
```

`latest`는 최신 image 확인용이고, `${{ github.sha }}`는 commit 추적용이다.

이번 CD 자동화에서는 `${{ github.sha }}` 값을 Helm Chart image tag로 사용한다.

---

## 8. Helm image tag 자동 업데이트

GitHub Actions는 image push 이후 `deploy/helm/fastpass/values.yaml`의 image tag를 현재 commit SHA로 변경한다.

수정 대상 파일은 다음과 같다.

```text
deploy/helm/fastpass/values.yaml
```

수정 전 예시는 다음과 같다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: latest
  pullPolicy: IfNotPresent
```

수정 후 예시는 다음과 같다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: 5c72a70...
  pullPolicy: IfNotPresent
```

이를 통해 ArgoCD가 Git 변경을 감지할 수 있게 된다.

---

## 9. values.yaml 자동 수정 스크립트

GitHub Actions에서는 Python 스크립트를 사용하여 `values.yaml`의 최상위 `image:` block 내부 `tag:` 값을 변경하였다.

```yaml
- name: Update Helm image tag
  env:
    IMAGE_TAG: ${{ github.sha }}
  run: |
    python - <<'PY'
    import os
    from pathlib import Path

    path = Path("deploy/helm/fastpass/values.yaml")
    image_tag = os.environ["IMAGE_TAG"]

    lines = path.read_text().splitlines()
    output = []
    in_image_block = False

    for line in lines:
        if line.startswith("image:"):
            in_image_block = True
            output.append(line)
            continue

        if in_image_block and line and not line.startswith(" "):
            in_image_block = False

        if in_image_block and line.strip().startswith("tag:"):
            indent = line[:len(line) - len(line.lstrip())]
            output.append(f"{indent}tag: {image_tag}")
        else:
            output.append(line)

    path.write_text("\n".join(output) + "\n")
    PY
```

이 스크립트는 values.yaml의 최상위 `image:` block을 찾고, 해당 block 내부의 `tag:` 값을 현재 commit SHA로 변경한다.

---

## 10. 자동 Commit 및 Push

values.yaml이 수정되면 GitHub Actions bot이 변경 사항을 commit하고 main branch로 push한다.

```yaml
- name: Commit and push Helm image tag
  run: |
    git config user.name "github-actions[bot]"
    git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

    git add deploy/helm/fastpass/values.yaml

    if git diff --cached --quiet; then
      echo "No Helm image tag changes to commit."
      exit 0
    fi

    git commit -m "chore: update image tag to ${{ github.sha }} [skip ci]"
    git push origin HEAD:main
```

자동 commit 메시지는 다음 형식이다.

```text
chore: update image tag to <commit-sha> [skip ci]
```

---

## 11. 무한 CI Loop 방지

자동 commit 메시지에는 `[skip ci]`를 포함하였다.

```text
chore: update image tag to <commit-sha> [skip ci]
```

이유는 GitHub Actions가 values.yaml을 수정하고 다시 push하면, 그 push로 인해 workflow가 다시 실행될 수 있기 때문이다.

`[skip ci]`를 사용하지 않으면 다음과 같은 반복이 발생할 수 있다.

```text
GitHub Actions가 values.yaml 수정
↓
자동 commit/push
↓
다시 GitHub Actions 실행
↓
다시 values.yaml 수정
↓
반복
```

따라서 자동 commit에는 `[skip ci]`를 포함하여 workflow 재실행을 방지하였다.

---

## 12. Workflow 파일 확장 시 발생한 문제

처음에는 workflow 수정이 GitHub Actions에 반영되지 않았다.

원인은 실제 사용 중인 workflow 파일이 `.github/workflows/ci.yaml`이었는데, 수정 기준을 `.github/workflows/ci.yml`로 생각했기 때문이다.

GitHub Actions는 다음 확장자를 모두 지원한다.

```text
.github/workflows/*.yml
.github/workflows/*.yaml
```

하지만 실제 repository에서 사용 중인 파일과 다른 파일을 수정하면, 기대한 workflow 변경이 반영되지 않는다.

최종적으로 실제 사용 중인 `ci.yaml` 파일을 수정하여 workflow가 정상 실행되었다.

---

## 13. GitHub Actions 실행 결과

workflow 수정 후 push하자 GitHub Actions가 실행되었다.

실행 화면에서 다음 세 job이 모두 성공하였다.

```text
Build Spring Boot API
Build and Push Docker Image
Update Helm Image Tag
```

workflow 실행 결과는 다음과 같았다.

```text
Status: Success
Total duration: 약 2분
```

이를 통해 다음 흐름이 정상 동작함을 확인하였다.

```text
Spring Boot build 성공
Docker image build 성공
GHCR push 성공
Helm image tag update 성공
```

---

## 14. 자동 Commit 확인

GitHub Actions가 성공한 후, GitHub repository에 자동 commit이 생성되었다.

자동 commit 메시지는 다음 형식이다.

```text
chore: update image tag to <commit-sha> [skip ci]
```

로컬 repository에서는 다음 명령어로 원격 변경 사항을 가져왔다.

```bash
git pull
```

이후 values.yaml을 확인하였다.

```bash
grep -A 3 "^image:" deploy/helm/fastpass/values.yaml
```

확인 결과 image tag가 `latest`가 아니라 commit SHA로 변경되어 있었다.

```yaml
image:
  repository: ghcr.io/kdh8128/fastpass-k8s-api
  tag: <commit-sha>
  pullPolicy: IfNotPresent
```

---

## 15. ArgoCD 자동 감지 및 배포 확인

ArgoCD는 GitHub repository의 Helm Chart를 바라보고 있다.

따라서 GitHub Actions bot이 values.yaml을 commit/push하면, ArgoCD는 Git 변경을 감지한다.

Application 상태를 확인하였다.

```bash
kubectl get application fastpass-gitops -n argocd
```

기대 상태는 다음과 같다.

```text
fastpass-gitops   Synced   Healthy
```

실제 Kubernetes Deployment image도 확인하였다.

```bash
kubectl get deployment fastpass-api fastpass-worker \
  -n fastpass-gitops \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.template.spec.containers[0].image}{"\n"}{end}'
```

확인 결과 API와 Worker가 commit SHA 기반 image를 사용하고 있었다.

```text
fastpass-api      ghcr.io/kdh8128/fastpass-k8s-api:<commit-sha>
fastpass-worker   ghcr.io/kdh8128/fastpass-k8s-api:<commit-sha>
```

이를 통해 ArgoCD가 GitHub Actions의 values.yaml 자동 업데이트 commit을 감지하고 Kubernetes에 재배포하는 것을 확인하였다.

---

## 16. 최종 CI/CD 구조

이번 단계 이후 FastPass의 CI/CD 구조는 다음과 같다.

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
values.yaml image.tag 자동 수정
  ↓
GitHub Actions bot commit/push
  ↓
ArgoCD GitOps sync
  ↓
Kubernetes deployment
```

운영 관측 흐름은 기존과 동일하게 유지된다.

```text
FastPass API / Worker
  ↓
Actuator Prometheus endpoint
  ↓
ServiceMonitor
  ↓
Prometheus
  ↓
Grafana / Alertmanager
```

---

## 17. 기존 방식과 변경 후 방식 비교

### 기존 방식

```text
코드 수정
↓
git push
↓
GitHub Actions build
↓
GHCR image push
↓
사람이 values.yaml tag 직접 수정
↓
Git commit/push
↓
ArgoCD sync
```

### 변경 후 방식

```text
코드 수정
↓
git push
↓
GitHub Actions build
↓
GHCR image push
↓
GitHub Actions가 values.yaml tag 자동 수정
↓
GitHub Actions bot commit/push
↓
ArgoCD sync
↓
Kubernetes 자동 배포
```

변경 후에는 사람이 image tag를 직접 바꾸지 않아도 된다.

---

## 18. 포트폴리오 관점의 의미

이번 단계는 FastPass의 CI/CD 흐름을 한 단계 더 실제 운영 구조에 가깝게 만든 것이다.

이전에는 CI가 image를 build/push하더라도 배포 manifest 수정은 수동이었다.

이번 단계 이후에는 GitHub Actions가 image push 후 Helm values.yaml image tag까지 자동으로 수정한다.

이를 통해 FastPass는 다음 DevOps 흐름을 보여줄 수 있게 되었다.

```text
Code change
→ Build
→ Image push
→ Manifest update
→ GitOps sync
→ Kubernetes deployment
```

이는 실제 운영 환경에서 자주 사용하는 GitOps 기반 CD 방식에 가까운 구조이다.

---

## 19. 현재 한계점

이번 단계에서 CI/CD 자동화 흐름을 구성했지만, 아직 다음 기능은 구현하지 않았다.

```text
1. ArgoCD Image Updater
2. PR 기반 release promotion
3. dev/staging/prod 환경 분리
4. rollback 자동화
5. image vulnerability scan
6. branch protection rule
7. 배포 승인 단계
8. ArgoCD sync webhook
```

현재 방식은 GitHub Actions가 Helm values.yaml을 직접 수정하고 commit/push하는 구조이다.

향후에는 ArgoCD Image Updater를 사용하여 image tag 업데이트를 ArgoCD 생태계 안에서 처리하는 방식도 비교할 수 있다.

---

## 20. 결론

이번 단계에서는 GitHub Actions 기반 CI/CD 자동화 흐름을 구성하였다.

GitHub Actions는 애플리케이션 코드를 build하고, Docker image를 GHCR에 push한 뒤, Helm values.yaml의 image.tag를 commit SHA로 자동 수정한다.

수정된 values.yaml은 GitHub Actions bot이 다시 commit/push하며, ArgoCD는 이 Git 변경을 감지하여 Kubernetes에 자동 배포한다.

결과적으로 FastPass는 다음 흐름을 갖추게 되었다.

```text
git push
→ GitHub Actions build
→ Docker image push
→ Helm image tag update
→ Git commit
→ ArgoCD sync
→ Kubernetes deployment
```

이를 통해 FastPass는 단순 CI를 넘어, GitOps 기반 CD 자동화 흐름까지 검증한 DevOps/Cloud 포트폴리오 프로젝트로 확장되었다.