# Roadmap

Time budget: 5–10 h/week. Week estimates are rough; finishing a phase matters more than speed.
The skeleton (Phases 0–7) is domain-agnostic. The product idea only needs to be decided by Phase 8.
Tags: 🖥️ = needs me at the PC. Untagged = fine from the phone.

---

## 🖥️ At-PC queue

Claude Code adds items here when they can't be done remotely. Clear this list whenever I'm at the PC.

- [ ] Windows power settings: when plugged in, never sleep. On a laptop, set closing the lid to "Do nothing".
      (A sleeping PC ends the remote session.)
- [ ] Docker Desktop → Settings → Kubernetes: make sure the built-in Kubernetes is **off** (we use minikube)
- [ ] Docker Desktop → Settings → General: start Docker Desktop on login (so remote sessions always have Docker)
- [x] Restart Claude Code from `C:\dev\myplatform` (done 2026-10-06)
- [ ] Delete the old OneDrive copy of the project
- [x] Install missing tools (a UAC prompt may appear):
      `winget install --id GitHub.cli -e` · `winget install --id Helm.Helm -e` · `winget install --id astral-sh.uv -e`
      Then open a new Git Bash and run `gh auth login`.
- [x] Approve the `.claude/settings.json` update (Claude Code may not edit its own permissions): allow `./scripts/mem.sh`
      instead of `free -h`, plus `winget list:*` / `winget search:*`; deny `wsl --shutdown:*` and `shutdown:*`.
- [ ] minikube warned "Failing to connect to https://registry.k8s.io/ from inside the minikube container".
      The cluster started anyway (images were cached). Before Phase 5, test pulls with
      `minikube ssh -- curl -sI https://registry.k8s.io`. Cloudflare WARP is a likely suspect.

## Phase 0: Setup (week 1)

Decision (2026-10-06): native Windows + Git Bash, not WSL2. Project lives in `C:\dev\myplatform`.

- [x] Confirm `docker`, `docker compose`, `kubectl` and `minikube` resolve in Git Bash; `docker run --rm hello-world` works
- [x] Create the minikube cluster with the start command in CLAUDE.md → `kubectl get nodes` shows `Ready`
      → `minikube stop` (verify only; no K8s work until Phase 5)
- [x] Inventory what's installed: git, JDK 21, Node LTS, Python 3.12+, uv, helm, gh
      2026-10-06: docker 27.2.0, compose 2.29.2, kubectl 1.31.0, minikube 1.34.0, git 2.45.2, Java 21.0.5,
      Node 24.19.0, Python 3.12.5 ✅ · missing: gh, helm, uv
- [x] 🖥️ Install anything missing with winget (may show a UAC prompt)
      2026-10-06: gh 2.102.0, helm v4.3.0, uv 0.12.23 ✅
- [x] Check `gh auth status`. Logged in as `heisamit3` (scopes: repo, workflow, read:org, gist).
- [x] Create the GitHub repo (public, so it counts for the portfolio): https://github.com/heisamit3/myplatform
- [x] Repo in `C:\dev\myplatform` with `CLAUDE.md`, `docs/ROADMAP.md`, `.claude/settings.json`, `.gitattributes`,
      `.gitignore`, `.editorconfig` and a basic `README.md`
- [x] Create the folder skeleton from the "Repo layout" section in CLAUDE.md
- [x] First commit (local, 2026-10-06). Pushed to GitHub 2026-10-06

## Phase 1: Local data + identity-service (weeks 2–3)

- [x] `infra/compose/compose.yaml` with postgres (pgvector image), redis and mailpit, all with memory limits and profiles
      2026-10-06: pgvector 0.8.7 / Postgres 18.6 (host 5433), redis 8.10.2, mailpit v1.31.4 (SMTP host 2525).
      `core` = postgres+redis, `events` = mailpit (Kafka/MongoDB join in Phase 3).
- [x] Postgres init script: separate database and user for identity-service
      `identity` role + DB (SCRAM password from `.env`); CONNECT revoked from PUBLIC on `identity` and `postgres`.
- [x] identity-service: Spring Boot project, Flyway, Actuator, `/health` `/ready` `/metrics`
      2026-10-06: Boot 4.1.1, Gradle 9.7.1. `/health` = liveness (no DB), `/ready` = readiness (incl. DB → 503 when
      Postgres is down), `/metrics` = Prometheus. 4 tests green (Testcontainers, same pgvector image as compose).
- [x] Tables: users, organizations, memberships, refresh_tokens
      Flyway `V1__identity_schema.sql` (ADR 0002), 6 schema tests on Testcontainers, applied to the compose DB.
- [ ] Endpoints: register, login, refresh, logout, `GET /me`, create org, switch active org
- [ ] RS256 key pair + `/.well-known/jwks.json`
- [ ] OpenAPI spec exported to `contracts/openapi/identity.yaml`
- [ ] Unit tests + Testcontainers integration test for register/login
- [ ] Dockerfile (multi-stage, non-root), added to the compose `core` profile

## Phase 2: Gateway + frontend (weeks 4–5)

- [ ] api-gateway: routes to identity-service, JWT validation through JWKS, CORS
- [ ] Redis-backed rate limiting on login/register
- [ ] web: React + Vite + TS. Pages: register, login, dashboard, profile, org switcher
- [ ] Token handling: short-lived access token in memory, refresh flow
- [ ] Everything reachable through `localhost:8080` only (verify with curl remotely)
- [ ] 🖥️ Click through the UI in a browser: register → login → dashboard → switch org

## Phase 3: Events (weeks 6–7)

- [ ] Kafka (KRaft, single broker, heap 512M) + MongoDB in an `events` compose profile
- [ ] Event envelope JSON Schema in `contracts/events/`
- [ ] identity-service publishes `identity.user.registered.v1`
- [ ] notification-service (NestJS): consumes it idempotently, stores history in MongoDB, sends a welcome email to Mailpit
- [ ] Verify: register through the API, then the email appears in Mailpit (check via Mailpit's API with curl)
- [ ] Stretch: transactional outbox in identity-service

## Phase 4: CI with GitHub Actions (week 8)

- [ ] One workflow per service, triggered only by changes in that service's path
- [ ] Steps: build → test → build Docker image → push to GHCR (tagged with commit SHA)
- [ ] Frontend workflow: lint, typecheck, build
- [ ] CI status badges in README

## Phase 5: Kubernetes locally (weeks 9–11)

- [ ] Turn off Compose, `minikube start` (command in CLAUDE.md)
- [ ] Load images with `minikube image load` (no registry needed locally)
- [ ] Hand-write raw YAML for identity-service: Deployment, Service, ConfigMap, Secret, probes, resource limits
- [ ] Postgres + Redis in the cluster (StatefulSet + PersistentVolumeClaim)
- [ ] Write a short note in `docs/` explaining each object in my own words (interview prep)
- [ ] Convert to one shared Helm chart used by all services + `values-local.yaml`
- [ ] Expose the gateway and web with Gateway API (Traefik)
- [ ] Prometheus + Grafana (lightweight setup) with one dashboard: requests, errors, latency, JVM memory

## Phase 6: GitOps with Argo CD (week 12)

- [ ] Install Argo CD locally (K8s session only, because of RAM; minikube at 3072 MB)
- [ ] Argo CD Applications in `infra/argocd/` pointing to the Helm chart
- [ ] CI updates the image tag in `values-*.yaml` after pushing (bot commit with `[skip ci]`)
- [ ] Verify: merge a change, then Argo CD syncs the new version without a manual deploy

## Phase 7: AWS ephemeral environment (weeks 13–15)

- [ ] 🖥️ AWS account, MFA on root, IAM user/role for Terraform, `aws configure`
- [ ] 🖥️ **AWS Budgets alerts at $5 and $10, before anything else**
- [ ] 🖥️ First `terraform apply` / `destroy` done at the PC (I want to watch costs live the first time)
- [ ] Terraform: S3 state backend, VPC (public subnets, no NAT), EKS with small spot nodes
- [ ] Argo CD on EKS syncs `values-aws.yaml`
- [ ] Full stack running together, including Kafka and monitoring
- [ ] 🖥️ Portfolio capture: screenshots, Grafana dashboard, short demo video, architecture diagram in README
- [ ] Teardown runbook: delete Argo CD apps and LoadBalancer services → `terraform destroy` → check the billing console

---

## 🎯 Decision point: choose the product domain

Questions to settle at this point:
- Who is the customer? (B2B teams, consumers, buyers and sellers)
- What is the core workflow? (one sentence)
- What does AI add to it, if anything?
- Which 1–2 domain services are needed first?

## Phase 8: Domain services (open-ended)

- [ ] Write 2–3 ADRs describing the domain model and service boundaries
- [ ] First domain service (Spring Boot, tenant-aware with `org_id` everywhere)
- [ ] ai-service (FastAPI + pgvector): embeddings + semantic search over domain data
- [ ] Frontend pages for the core workflow

## Later upgrades (pick any; each makes a good interview story)

- [ ] Loki for centralized logs
- [ ] OpenTelemetry + Jaeger for distributed tracing across gateway → services → Kafka
- [ ] Trivy image scanning + Dependabot
- [ ] One internal call converted to gRPC
- [ ] Horizontal Pod Autoscaler + a load test (k6)
- [ ] Kubernetes NetworkPolicies (services accept traffic only from the gateway)
- [ ] Billing/subscriptions service (if B2B/AI SaaS)
