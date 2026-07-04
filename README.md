# FastPass

FastPass는 선착순 이벤트 신청 상황을 가정한 Kubernetes 기반 서비스 운영 자동화 프로젝트입니다.

## Project Goal

트래픽 급증이 발생하는 이벤트 신청 서비스를 Kubernetes 환경에 배포하고, GitOps, 모니터링, 오토스케일링, 장애 알림, 롤백 절차를 검증하는 것을 목표로 합니다.

## Core Scenario

- 사용자는 이벤트 목록을 조회한다.
- 사용자는 특정 이벤트에 선착순으로 신청한다.
- 신청 요청은 Redis Queue에 적재된다.
- Worker가 Queue를 소비하며 신청을 처리한다.
- 트래픽이 증가하면 Kubernetes HPA가 Pod를 자동 확장한다.
- 장애 발생 시 Prometheus/Grafana/Alertmanager를 통해 탐지하고 Slack으로 알림을 보낸다.
- 잘못된 배포가 발생하면 ArgoCD/Helm을 통해 롤백한다.

## Tech Stack

- Java / Spring Boot
- PostgreSQL
- Redis
- Docker
- Kubernetes
- Helm
- ArgoCD
- GitHub Actions
- Prometheus
- Grafana
- Loki
- Alertmanager
- k6
- AWS EKS
- Terraform