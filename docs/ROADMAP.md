# Roadmap

Time budget: 5–10 h/week. Week estimates are rough; finishing a phase matters more than speed.
The skeleton (Phases 0–7) is domain-agnostic. The product idea only needs to be decided by Phase 8.
Tags: 🖥️ = needs me at the PC. Untagged = fine from the phone.

---

## 📍 Status (last session: 2026-10-07)

- **Done:** Phases 0–4, Phase 5 except one item, Phase 6. CI is green on `main` and
  now writes each new image SHA to `infra/helm/services/<svc>/values-image.yaml`; Argo CD deploys it.
- **Open in Phase 5:** rewrite `docs/k8s-objects.md` in my own words (draft exists), then tick it.
- **Next:** Phase 7 (AWS). Starts with 🖥️ items: AWS account, MFA, Budgets alerts.
- **State left behind:** minikube stopped. Argo CD is installed but scaled to 0 (saves ~0.5 GB);
  `./scripts/k8s-local-platform.sh --argocd` brings it back. Services run the CI images (`b46eb73`); the web footer shows the build.

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
- [x] minikube warned "Failing to connect to https://registry.k8s.io/ from inside the minikube container".
      2026-10-06: false alarm. From inside minikube, registry.k8s.io / Docker Hub / ghcr.io all answer, and a real
      `docker pull` of a registry.k8s.io image works. The warning still shows on every start; ignore it.
- [x] GHCR packages are private by default. Make each public (GitHub → profile → Packages → `myplatform/<service>`
      → Package settings → Change visibility → Public) for identity-service, api-gateway, notification-service, web,
      so minikube/EKS can pull without a pull secret.
      2026-10-07: already public (anonymous pull of all 4 works).
- [ ] `gh auth refresh -s read:packages` (browser login) so Claude Code can list GHCR images/tags from the CLI.
- [ ] Look at the Grafana dashboard in a browser (K8s monitoring session): `kubectl -n monitoring port-forward
      svc/grafana 3000:80` → http://localhost:3000 → Dashboards → myplatform → "myplatform services". Screenshot for the README.
- [ ] Phase 5 UI click-through in Kubernetes (K8s session): `kubectl -n traefik port-forward svc/traefik 8088:80`, open
      http://localhost:8088 → register → create org → switch → reload (stays logged in) → log out.
- [ ] Phase 2 UI click-through: `docker compose -f infra/compose/compose.yaml --profile core up -d`, then
      `cd web && npm run dev`, open http://localhost:5173 → register → (lands on dashboard) → create an org →
      create a second org → switch with the header dropdown → Profile → reload the page (should stay logged in) → Log out.

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
- [x] Endpoints: register, login, refresh, logout, `GET /me`, create org, switch active org
      2026-10-06: ✅ `POST /auth/register` (201, bcrypt cost 10, 409 on duplicate) and `POST /auth/login`
      (access + refresh token, active org = first joined, same 401 + bcrypt time for unknown emails).
      Errors are RFC 9457 problem+json.
      ✅ `POST /auth/refresh` (rotation, row lock, reuse → family revoked) and `POST /auth/logout`
      (204, idempotent). ADR 0003: revocation stays in Postgres.
      ✅ Spring Security resource server (Bearer JWT, own public key + issuer check); `GET /me`,
      `POST /orgs` (creator = OWNER, 409 on slug), `POST /auth/switch-org` (rotates the session, 403 if not a member).
- [x] RS256 key pair + `/.well-known/jwks.json`
      PKCS#8 key from `JWT_PRIVATE_KEY_PATH` (no key → startup fails), kid = RFC 7638 thumbprint,
      `AccessTokenIssuer` (sub/org/roles, 15 min, iss `http://identity-service`). 20 tests green.
- [x] OpenAPI spec exported to `contracts/openapi/identity.yaml`
      2026-10-06: code-first with springdoc 3.1.1 (ADR 0004). `OpenApiContractTests` fails on drift;
      all errors are problem+json (incl. the 401 from Spring Security). `redocly lint`: valid, 3 accepted warnings.
- [x] Unit tests + Testcontainers integration test for register/login
      2026-10-06: integration: `AuthFlowTests` 6, `RefreshFlowTests` 7, `TenancyFlowTests` 5 (Testcontainers).
      Unit (no Spring, no Docker, ~2 s): `RefreshTokenServiceTests` 9, `RequestValidationTests` 6,
      `CreateOrgRequestTests` 15, `EmailsTests` 2. 71 tests total.
- [x] Dockerfile (multi-stage, non-root), added to the compose `core` profile
      2026-10-06: JDK 21 noble build stage (Gradle, BuildKit cache) → Temurin 21.0.12.1 JRE alpine, UID 10001,
      Spring Boot layers (app layer 61 kB, image 275 MB). Compose: JWT key as a compose secret, waits for postgres,
      `/ready` healthcheck. Under load (100 logins + 300 `/me`): 300 MiB of 384 MiB, 0 restarts.
      Heap cap lowered from 75% to 40% of the limit (ADR 0005).

## Phase 2: Gateway + frontend (weeks 4–5)

- [x] api-gateway: routes to identity-service, JWT validation through JWKS, CORS
      2026-10-06: Boot 4.0.8 + Spring Cloud 2025.1.3 (2025.1 doesn't support Boot 4.1 yet, ADR 0006).
      Routes `/auth/**` (public), `/me`, `/orgs/**` (JWT: JWKS signature, exp, iss). 401 = problem+json.
      CORS for `localhost:5173` with credentials. 14 tests (fake identity on a JDK HttpServer). Compose `core`,
      176 MiB of 384 MiB after start.
- [x] Redis-backed rate limiting on login/register
      2026-10-06: `RequestRateLimiter` token buckets per client IP (ADR 0007): login 10/min, register 5/min,
      429 + `X-RateLimit-*`. Fails open without Redis. `RATE_LIMIT_TRUSTED_PROXIES` for X-Forwarded-For
      behind Traefik (Phase 5). 21 gateway tests (Testcontainers Redis). curl: 11th login → 429.
- [x] web: React + Vite + TS. Pages: register, login, dashboard, profile, org switcher
      2026-10-06: React 19, Vite 8, TS 6 strict, react-router 8, oxlint, Vitest. Dashboard creates an org
      (slug from name) and switches into it; header org switcher; profile lists memberships + roles.
- [x] Token handling: short-lived access token in memory, refresh flow
      2026-10-06: refresh token in an HttpOnly/Secure/SameSite=Strict cookie on `/auth`, set by the gateway
      (ADR 0008). `ApiClient`: token in memory, refresh on 401 + one retry, single-flight refresh, session resumed
      on reload. 14 web tests. curl replay with `Origin: localhost:5173`: register → login → reload → org → switch → logout.
- [x] Everything reachable through `localhost:8080` only (verify with curl remotely)
      2026-10-06: identity-service has no host port anymore (`:8081` → connection refused). register → login →
      `/me` → refresh all work on `:8080`. Postgres/Redis keep their 127.0.0.1 ports for dev tools.
- [ ] 🖥️ Click through the UI in a browser: register → login → dashboard → switch org

## Phase 3: Events (weeks 6–7)

- [x] Kafka (KRaft, single broker, heap 512M) + MongoDB in an `events` compose profile
      2026-10-06: `apache/kafka:4.3.1` (broker+controller, `kafka:9092` inside, `localhost:9094` from the host,
      auto-create off) + `mongo:8.0.32` (`notification` user, readWrite on its own DB only). ADR 0009.
      Produce/consume round trip OK. Idle: Kafka 391 of 768 MiB, MongoDB 201 of 384 MiB.
- [x] Event envelope JSON Schema in `contracts/events/`
      2026-10-06: `envelope.v1` + `identity.user.registered.v1` (draft 2020-12, ADR 0010). Strict for producers,
      `orgId` nullable for user-level events. `./scripts/check-event-schemas.sh`: schemas compile, example valid,
      2 invalid cases rejected (extra `passwordHash`, non-UTC time).
- [x] identity-service publishes `identity.user.registered.v1`
      2026-10-06: after commit (`@TransactionalEventListener` + `@Async`), JSON as String (no Java type headers),
      key = userId, topic declared by the service (3 partitions). ADR 0011. `UserRegisteredEventTests` validates the
      real event (Testcontainers Kafka) against `contracts/events/`. 75 tests. Compose: register → event on the topic;
      with Kafka stopped, register still 201 in ~0.4 s (event logged as lost; outbox fixes that).
- [x] notification-service (NestJS): consumes it idempotently, stores history in MongoDB, sends a welcome email to Mailpit
      2026-10-06: Nest 12 (ESM), TS 6 strict, Vitest, oxlint. Kafka client `@confluentinc/kafka-javascript` 1.10
      (librdkafka 2.15; kafkajs is unmaintained, ADR 0012). Unique index `{eventId, template}`, pending → sent,
      Message-ID from eventId; poison messages skipped, SMTP/Mongo errors back off 1–30 s and redeliver (ADR 0013).
      31 tests (25 unit, 6 Testcontainers with Kafka 4.3.1 / MongoDB 8.0 / Mailpit). Image node 24.21 alpine,
      UID 10001, 227 MB. Compose `events`: 52 of 192 MiB.
- [x] Verify: register through the API, then the email appears in Mailpit (check via Mailpit's API with curl)
      2026-10-06: register → 201 → Mailpit has 1 email with Message-ID `<eventId>.welcome@...`. Replaying the whole
      topic (offsets reset to earliest) → `duplicate, ignored` for every event, still the same number of emails. Mailpit stopped
      during a registration → `EDNS` failure, retry, sent once Mailpit was back (`attempts: 2`, 1 email).
- [x] Stretch: transactional outbox in identity-service
      2026-10-06: Flyway V2 `outbox_events`. A synchronous listener writes the envelope in the registration
      transaction (`MANDATORY`). `OutboxRelay` polls every 500 ms under a Postgres advisory lock (one relay
      across replicas), sends in id order, stops at the first failure, backs off 0.5 → 30 s, and deletes
      published rows after 7 days. Metrics `identity_outbox_pending` / `_published_total` / `_failures_total`. ADR 0014.
      86 tests (8 relay unit, 5 outbox integration). Compose: Kafka stopped → register 201 in 0.3 s → row pending
      (7 attempts, backing off) → Kafka started → published → welcome email in Mailpit. Commit → Kafka 170–580 ms.

## Phase 4: CI with GitHub Actions (week 8)

- [x] One workflow per service, triggered only by changes in that service's path
      2026-10-06: `identity-service`, `api-gateway`, `notification-service`, `web`, `contracts` (ADR 0015).
      Filters include the contracts a service's tests read. Verified: a gateway-only commit ran only `api-gateway`.
      Actions pinned by commit SHA, `permissions: {}` by default, actionlint clean.
- [x] Steps: build → test → build Docker image → push to GHCR (tagged with commit SHA)
      2026-10-06: test job (Gradle / npm incl. Testcontainers) → reusable `_build-image.yml` (Buildx, GHA layer
      cache). Push only from `main`, PRs build only. `ghcr.io/heisamit3/myplatform/<service>:<full SHA>`, no `:latest`.
      First runs: identity 4m54s, notification 3m13s, gateway ~2 min. CI caught a timing-dependent assertion in
      `RateLimitTests` (refill during the 11 requests on a slower runner), fixed.
- [x] Frontend workflow: lint, typecheck, build
      2026-10-06: `web.yml` also runs the Vitest tests. 37 s. Plus `contracts.yml` (event schemas + redocly lint), 27 s.
- [x] CI status badges in README

## Phase 5: Kubernetes locally (weeks 9–11)

- [x] Turn off Compose, `minikube start` (command in CLAUDE.md)
      2026-10-06: Windows 1.8 GB free before start; minikube container 776 MiB idle.
- [x] Load images with `minikube image load` (no registry needed locally)
      2026-10-06: `minikube image load` fails in 1.34 on this Windows build (`exec: "wmic": executable file not found`).
      Workaround: `./scripts/minikube-load.sh <image>` = `docker save | docker load` into minikube's daemon (docker-env).
- [x] Hand-write raw YAML for identity-service: Deployment, Service, ConfigMap, Secret, probes, resource limits
      2026-10-06: `infra/k8s/raw/identity-service.yaml`, namespace `myplatform`. startup/liveness (`/health`)/readiness
      (`/ready`) probes, 384Mi request = limit, no CPU limit, UID 10001, read-only root FS, JWT key as a mounted Secret.
      Secrets come from `.env` + the JWT key via `./scripts/k8s-local-secrets.sh` (never committed). Ready in ~20 s.
      Port-forward: register 201 → login → `/me` OK; event pending in the outbox (no Kafka in the cluster yet).
      Postgres scaled to 0 → pod `0/1`, removed from the Service endpoints, **0 restarts** → back to `1/1` after.
- [x] Postgres + Redis in the cluster (StatefulSet + PersistentVolumeClaim)
      2026-10-06: Postgres = StatefulSet + 1Gi PVC + headless Service, compose init script via ConfigMap; data survives
      the pod being deleted and recreated. Redis = Deployment (no persistence, so nothing for a StatefulSet to keep).
      minikube container: 1.44 of 2.5 GiB with postgres + redis + identity-service.
- [ ] Write a short note in `docs/` explaining each object in my own words (interview prep)
      2026-10-06: draft by Claude Code in `docs/k8s-objects.md` (every object we use + the gotchas we hit).
      Left open on purpose: rewrite it in my own words, then tick.
- [x] Convert to one shared Helm chart used by all services + `values-local.yaml`
      2026-10-06: `infra/helm/service` (Deployment, Service, ConfigMap, optional HTTPRoute), one release per service,
      values in `infra/helm/services/<svc>/values.yaml` + `values-local.yaml` (ADR 0016). identity-service moved from
      raw YAML to Helm (raw kept in `infra/k8s/raw/reference/`); api-gateway and web deployed too; notification-service
      validated with `helm template` only (needs Kafka/MongoDB: AWS). Gotcha found: K8s service-link env vars
      (`REDIS_PORT=tcp://...`) crashed the gateway → `enableServiceLinks: false` in the chart.
- [x] Expose the gateway and web with Gateway API (Traefik)
      2026-10-06: Gateway API CRDs v1.6.1 + Traefik 3.7.13 (chart 41.6.1, Gateway API provider only), our Gateway
      `myplatform`, HTTPRoutes from the chart. One origin (ADR 0017): `/auth`, `/me`, `/orgs` → api-gateway, the rest →
      web. web image: nginx-unprivileged 1.30.5, 60 MB, ~10 MiB RAM, built with `VITE_API_URL=""`; CI builds/pushes it.
      Port-forward `localhost:8088`: SPA deep links 200, register → login (cookie) → `/me` → `POST /orgs` → refresh OK,
      spoofed `X-Forwarded-For` doesn't escape the login limit.
- [x] Prometheus + Grafana (lightweight setup) with one dashboard: requests, errors, latency, JVM memory
      2026-10-06: Prometheus 3.15 server only (no operator/Alertmanager/exporters) + Grafana 13.2, both provisioned
      from files (ADR 0018); `./scripts/k8s-local-platform.sh --monitoring`. Scrapes annotated pods + filtered cAdvisor.
      Spring services got SLO latency buckets (25 ms .. 2 s) for p95. Dashboard "myplatform services": requests/s,
      errors/s, p95/p50, JVM heap, pod memory, Traefik status codes. Verified through Grafana's datasource proxy under
      steady traffic: gateway 3.5 req/s, identity 1.76 req/s, 401s as gateway CLIENT_ERROR, p95 ~25 ms.
      Grafana: OOMKilled at 192 Mi + liveness killed it mid-migration → 320 Mi, startup probe, `Recreate`.
      Memory: Prometheus 68 MiB, Grafana ~280 MiB, Traefik 33 MiB; whole cluster 2.3 GiB (minikube raised to 3 GB in
      place for the session, then monitoring uninstalled and the limit set back to 2560 MB).

## Phase 6: GitOps with Argo CD (week 12)

- [x] Install Argo CD locally (K8s session only, because of RAM; minikube at 3072 MB)
      2026-10-07: Argo CD v3.5.3 (chart 10.9.6), only controller/repo-server/server/redis (ADR 0019),
      `./scripts/k8s-local-platform.sh --argocd`. Whole cluster 2.34 of 3 GiB with Argo CD + app + Traefik.
- [x] Argo CD Applications in `infra/argocd/` pointing to the Helm chart
      2026-10-07: app of apps: `infra/argocd/local/root.yaml` → `apps/` (identity-service, api-gateway, web),
      automated sync with prune + selfHeal. Took over the Helm-installed objects (old release records deleted).
      selfHeal check: `kubectl scale deploy/web --replicas=3` → back to 1 after 8 s. Smoke via Traefik:
      register 201, `/me` 200, `/` 200.
- [x] CI updates the image tag in `values-*.yaml` after pushing (bot commit with `[skip ci]`)
      2026-10-07: `bump-tag` job in `_build-image.yml` → `values-image.yaml`. First run: 4 services pushed bot commits
      at once, retries worked, Argo CD deployed `ghcr.io/.../<svc>:a45e5a8`. Gotcha: my own commit message
      quoted the skip marker, so GitHub skipped that push's CI.
- [x] Verify: merge a change, then Argo CD syncs the new version without a manual deploy
      2026-10-07: pushed `b46eb73` (web footer shows the build commit) → CI green ×4 → 4 bot commits → Argo CD rolled
      api-gateway, web and identity-service to `b46eb73` within ~1 min of each bot commit, no manual step. Rolling
      update: the old pod served until the new one was Ready. Bundle served via Traefik contains `b46eb73`;
      register 201.

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
