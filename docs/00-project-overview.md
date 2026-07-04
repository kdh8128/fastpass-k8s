# FastPass Project Overview

## 1. 프로젝트 개요

FastPass는 이벤트 오픈 시점에 사용자가 몰리는 선착순 신청 서비스를 가정한 프로젝트이다.

이 프로젝트의 목적은 단순 웹서비스 개발이 아니라, Kubernetes 기반 환경에서 배포 자동화, 오토스케일링, 모니터링, 장애 알림, 롤백 절차를 직접 구성하고 검증하는 것이다.

## 2. 핵심 시나리오

- 사용자는 이벤트 목록을 조회한다.
- 사용자는 특정 이벤트에 선착순으로 신청한다.
- 신청 요청은 Redis Queue에 적재된다.
- Worker가 Queue를 소비하며 신청을 처리한다.
- 트래픽이 증가하면 Kubernetes HPA가 Pod를 자동 확장한다.
- 장애 발생 시 Prometheus/Grafana/Alertmanager를 통해 탐지하고 Slack으로 알림을 보낸다.
- 잘못된 배포가 발생하면 ArgoCD/Helm을 통해 롤백한다.

## 3. 최종 목표

- Spring Boot 기반 API 서버 구현
- Redis 기반 대기열 처리
- Docker 이미지화
- Kubernetes 배포
- Helm Chart 구성
- GitHub Actions + ArgoCD 기반 GitOps 배포
- Prometheus/Grafana/Loki 기반 관측
- k6 부하 테스트
- HPA 자동 확장 검증
- 장애 대응 Runbook 작성