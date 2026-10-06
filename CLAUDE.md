# CLAUDE.md — Project memory

Claude Code: read this before every task. It records decisions that are already made.
Don't re-debate them unless I ask. If something here blocks a task, say so and propose a change.

## Premise

A portfolio project (for job hunting) that demonstrates **microservices + DevOps** end to end.
The product domain is **not decided yet**. I'm leaning toward B2B SaaS, AI SaaS, or a marketplace.

Strategy: build a **domain-agnostic, multi-tenant walking skeleton** first (identity, gateway,
events, small frontend, CI/CD, Kubernetes, GitOps, cloud). Domain services plug in later.
All three candidate domains need users, organizations, roles, events and an AI-ready database,
so the skeleton serves any of them.

## About me (adjust explanations accordingly)

- Comfortable with Docker; **new to Kubernetes**. When you introduce a K8s concept, explain it in 1–3 sentences.
- Knows Java/Spring and Node reasonably. Little FastAPI experience so far.
- 5–10 hours per week. Prefer small steps. Each session should end with something running and committed.
- **Native Windows 11 + Git Bash** (not inside WSL2) + Docker Desktop. **8 GB RAM**. See "Resource limits".
  Project path: `C:\dev\myplatform` (`/c/dev/myplatform` in Git Bash). Never OneDrive: sync breaks `.git` and builds.
- Local Kubernetes is **minikube** (v1.34, docker driver) with kubectl v1.31. Docker Desktop's built-in
  Kubernetes stays **disabled**; never run both.
- I often drive Claude Code **remotely from my phone** while the session runs on my PC.
  See "Remote / phone sessions".

## Architecture

```
React (Vite) ──HTTP──> api-gateway (Spring Cloud Gateway)
                          │  validates JWT, rate limits (Redis), routes
          ┌───────────────┼──────────────────────┐
          v               v                      v
  identity-service   notification-service    ai-service (later)
  Java/Spring Boot   Node/TypeScript         Python/FastAPI
  PostgreSQL         MongoDB                 PostgreSQL + pgvector
  Redis                    ^
          │                │
          └──── Kafka ─────┘   (async domain events)
```

| Service | Language | Data | Responsibility |
|---|---|---|---|
| api-gateway | Java 21, Spring Cloud Gateway (reactive) | Redis | Single entry point, JWT validation, routing, rate limiting, CORS |
| identity-service | Java 21, Spring Boot | PostgreSQL, Redis | Users, organizations (tenants), memberships, roles, login, token issuing |
| notification-service | Node LTS, TypeScript, NestJS | MongoDB | Consumes events, stores notification history, sends email |
| ai-service (later) | Python 3.12+, FastAPI | PostgreSQL + pgvector | AI/ML features: embeddings, semantic search, LLM calls |
| web | React + Vite + TypeScript | none | Login/register, org switcher, dashboard, profile |

Ports (local): gateway 8080, identity 8081, notification 8082, ai 8083, web 5173.
The frontend talks **only** to the gateway, never to services directly.

## Tech decisions (and why: I need to explain these in interviews)

- **Polyglot services:** Spring Boot for core business services (what enterprises hire for), Node for
  I/O-heavy event consumers, FastAPI because the Python ML ecosystem lives there.
- **Database per service:** PostgreSQL for relational/transactional data, MongoDB for flexible
  documents (notification logs/templates), Redis for cache, rate limits and token revocation.
  Locally, one Postgres server hosts separate databases with separate users per service to save RAM.
  Services must never read another service's database.
- **Vector search:** pgvector extension in PostgreSQL. No separate vector DB.
- **Sync communication:** REST + JSON, documented with OpenAPI. gRPC between two services is a later stretch goal.
- **Async communication:** Apache Kafka in KRaft mode (no ZooKeeper), single broker locally.
- **Auth:** our own JWT implementation in identity-service (details below).
- **Kubernetes packaging:** raw YAML for the first service (learning), then one shared Helm chart.
- **CI:** GitHub Actions. Images go to GitHub Container Registry (GHCR).
- **CD:** GitOps with Argo CD. CI never runs `kubectl apply` against a cluster.
- **Observability:** metrics first (Prometheus + Grafana). Loki logs and OpenTelemetry tracing come later.
- **Cloud:** AWS EKS as an **ephemeral** environment created and destroyed with Terraform.
- **Security scanning:** deferred (Trivy and Dependabot planned later).
- **Edge routing in K8s:** Kubernetes Gateway API (e.g. with Traefik). Don't use ingress-nginx,
  which the Kubernetes project has retired.
- **Repo:** monorepo (see layout).

## Auth & multi-tenancy conventions

- Tenant = **organization**. A user can belong to several orgs with a role per org (OWNER, ADMIN, MEMBER).
- Passwords are hashed with bcrypt. Never log passwords, tokens or full JWTs.
- Access token: JWT signed with **RS256**, about 15 min lifetime. Claims: `sub` (userId), `org` (active orgId), `roles`.
- Refresh token: opaque random string, stored hashed, rotated on every use, revocable (Redis), about 7 days.
- identity-service publishes its public key at `/.well-known/jwks.json`.
  The gateway validates every token; services validate again with the same JWKS (defense in depth).
- Every domain table that holds tenant data has an `org_id` column, and every query filters by it.

## Event conventions (Kafka)

- Topic name: `<service>.<entity>.<event>.v<version>`, e.g. `identity.user.registered.v1`.
- Envelope (JSON): `eventId` (UUID), `type`, `version`, `occurredAt` (ISO-8601 UTC), `orgId`, `payload`.
- Consumers must be **idempotent** (dedupe on `eventId`).
- Events are facts in past tense. Never use events as remote commands.
- Stretch goal: transactional outbox pattern in identity-service.

## Repo layout (monorepo)

```
CLAUDE.md
docs/ROADMAP.md            # phased plan with checkboxes; keep it updated
docs/adr/                  # short Architecture Decision Records (one file per decision)
contracts/openapi/         # OpenAPI spec per service
contracts/events/          # JSON Schemas for Kafka events
services/api-gateway/
services/identity-service/
services/notification-service/
services/ai-service/       # later
web/                       # React + Vite
infra/compose/             # docker compose files and profiles for local dev
infra/k8s/raw/             # hand-written YAML (learning phase)
infra/helm/                # shared chart + values-local.yaml / values-aws.yaml
infra/argocd/              # Argo CD Application manifests
infra/terraform/aws/       # VPC, EKS, IAM, etc.
.github/workflows/         # one workflow per service, filtered by path
```

## Resource limits (MUST follow: the machine has 8 GB RAM)

- Docker Desktop runs containers (and minikube) in its own WSL2 VM. There is **no** `.wslconfig`, so the VM
  uses WSL2's default limit (about 3.7 GB). Assume about 3.2 GB is usable for containers.
- Every container gets an explicit memory limit. Default budgets:
  postgres 256M · redis 64M · mongodb 384M (`--wiredTigerCacheSizeGB 0.25`) · kafka 768M (heap 512M) ·
  mailpit 64M · each Spring service 384M (`-XX:MaxRAMPercentage=40`, ADR 0005) · Node service 192M.
- Use **docker compose profiles** so I can start only what I'm working on
  (e.g. `core` = postgres+redis+identity, `events` = kafka+notification+mongodb).
- Don't run Compose and minikube at the same time. Local K8s runs a minimal set;
  Argo CD + Prometheus/Grafana run locally only during dedicated K8s sessions.
  The full stack runs together on AWS.
- minikube memory: 2560 MB by default. Raise it to 3072 MB only for Argo CD/monitoring sessions with
  nothing else running. Run `minikube stop` when a K8s session ends; a running cluster holds its RAM even when idle.
- Claude Code, the browser and IDEs run on Windows and share the same 8 GB. Run `./scripts/mem.sh` before heavy
  steps (Git Bash has no `free`). If Windows has under ~1.5 GB free, ask me to close apps before starting
  minikube or a big stack.
- Ask me before adding any new long-running container (database, broker, sidecar, operator).
- Code lives in `C:\dev\myplatform`, never under OneDrive or a path with spaces.

## Environments

- **local-compose:** daily development.
- **local-k8s:** minikube (docker driver), for learning and testing manifests/Helm/Argo CD.
  - Load locally built images with `minikube image load <image>` instead of pushing to a registry.
  - Don't use minikube's `ingress` addon (it's ingress-nginx). Use Gateway API + Traefik as decided above.
  - Before any `kubectl` write command, confirm `kubectl config current-context` is `minikube`.
    This matters once an EKS context exists.
- **aws:** ephemeral EKS created with `terraform apply` and removed with `terraform destroy`. Budget is about $10/month.
  - No NAT gateways. No MSK, no RDS. Kafka and databases run in-cluster.
  - Use spot/small instances. Terraform state goes in S3.
  - **Before `terraform destroy`:** delete Argo CD apps and any `LoadBalancer` services first.
    Load balancers created by Kubernetes aren't in Terraform state and will keep billing if orphaned.
  - AWS Budgets alerts at $5 and $10 must exist before any other resource.

## Coding conventions

- Java: Java 21, Gradle (Kotlin DSL), Flyway migrations, Spring Boot Actuator + Micrometer Prometheus.
  Use the latest stable Spring Boot with the matching Spring Cloud release train, and pin versions.
- Node: TypeScript strict mode, NestJS. Use a maintained Kafka client (verify maintenance status before choosing).
- Python: FastAPI, Pydantic, uv for dependencies, Prometheus instrumentation.
- Every service exposes `/health` (liveness), `/ready` (readiness) and `/metrics`,
  and has a multi-stage Dockerfile that runs as a non-root user.
- Config comes from environment variables (12-factor). Secrets are never committed: use `.env` (gitignored)
  locally, GitHub Secrets in CI, Kubernetes Secrets in clusters.
- Tests: unit tests plus integration tests with Testcontainers for anything touching a database or Kafka.
- Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `ci:`, `infra:`).

## Windows / Git Bash rules

- The shell is **Git Bash**. Use POSIX syntax and `/c/...` paths. The tools are Windows binaries (docker, kubectl, minikube).
- Line endings: `.gitattributes` forces LF for everything except `.ps1/.bat/.cmd` (CRLF). Shell scripts,
  Dockerfiles and YAML must stay LF or they break inside Linux containers.
- **Docker commands with volume or container paths use `MSYS_NO_PATHCONV=1`.** Otherwise Git Bash rewrites
  `/data` into `C:/Program Files/Git/data`. Example:
  `MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)/infra:/infra" alpine ls /infra`
  (`pwd -W` prints a Windows-style path that Docker Desktop understands.) The same applies to
  `docker exec ... /path` and `kubectl exec ... -- /path`.
- Installs use **winget** (`winget install --id <Id> -e`). Open a new terminal afterwards so PATH refreshes.
- Commit `gradlew` and other scripts with the exec bit: `git update-index --chmod=+x <file>`.

## Remote / phone sessions

Assume I may be on my phone unless I say "I'm at the PC".

- **Keep replies short and scannable.** Start with the result (✅/❌ + one line), then details only if needed.
- **Verify with CLI output only:** test results, `curl -s http://localhost:...`, `kubectl get ...`, `docker compose ps`.
  Don't ask me to open a browser, GUI or Windows app while I'm remote.
- **Never block the session:** start servers detached (`docker compose up -d`, background processes),
  read logs with `--tail`, and don't run anything that waits for interactive input.
- **Never kill your own session:** no reboots or sign-outs. Also no `wsl --shutdown` or Docker Desktop
  restarts while remote: they take down every container and minikube, and Docker may not come back on its own.
- **UAC / admin prompts are At-PC only.** I can't click a UAC dialog from my phone. Prefer winget packages
  that install per-user (`--scope user`) when available. If an install or step needs elevation,
  add it to the "🖥️ At-PC queue" in `docs/ROADMAP.md` and move on.
- **Anything needing a GUI, browser login, account creation, or a hardware/power setting**
  goes to the At-PC queue instead of being attempted.
- **Announce approvals up front.** Before a step, list the commands it will need, so I can approve them in one go.
- **Commit locally after every finished checklist item,** so nothing is lost if the session drops.
  Push only when I approve.
- Roadmap tags: 🖥️ = needs me at the PC. Untagged items are fine to do remotely.

## How to work with me (Claude Code)

1. Before starting, check `docs/ROADMAP.md`, tell me which item we're on, and give a short plan.
2. Work on one service or one infra piece at a time. Keep changes small enough to review.
3. When a step is done: run it, show me how to verify it (command + expected output), then tick the box in the roadmap.
4. Record any new significant decision as a short ADR in `docs/adr/`.
5. Teach as we go: a brief "why" for non-obvious choices, especially for Kubernetes, Kafka and Terraform.
6. Never commit secrets, never disable tests to make CI pass, never add paid AWS services without asking.

## Commands

(Fill in as they get created.)

- First time: `cp infra/compose/.env.example infra/compose/.env` and set `POSTGRES_PASSWORD`
- Start core stack (postgres, redis, identity-service on 8081, api-gateway on 8080): `docker compose -f infra/compose/compose.yaml --profile core up -d`
  Needs the JWT key (see below). Rebuild after code changes: `docker compose -f infra/compose/compose.yaml --profile core up -d --build identity-service`
  To run the jar from the host instead, free port 8081 first: `docker compose -f infra/compose/compose.yaml stop identity-service`
- Add the events profile: `docker compose -f infra/compose/compose.yaml --profile core --profile events up -d`
- Host ports: postgres **5433** (a native Windows PostgreSQL 16 service holds 5432), redis 6379,
  mailpit SMTP **2525** (Windows won't bind 1025) and UI/API 8025. Inside compose use `postgres:5432`, `mailpit:1025`.
- psql: `MSYS_NO_PATHCONV=1 docker exec -it myplatform-postgres-1 psql -U postgres`
- Service DBs/roles: `infra/compose/postgres/init/01-service-databases.sh` runs only on an empty volume.
  On an existing volume, re-run it: `MSYS_NO_PATHCONV=1 docker exec myplatform-postgres-1 bash /docker-entrypoint-initdb.d/01-service-databases.sh`
  Inside the postgres container 127.0.0.1 is trusted (no password check). Test logins from another container on `myplatform_default`.
- Stop everything: `docker compose -f infra/compose/compose.yaml down`
- identity-service build + tests: `cd services/identity-service && ./gradlew build && ./gradlew --stop`
- api-gateway build + tests (no Docker needed): `cd services/api-gateway && ./gradlew build && ./gradlew --stop`
  Rebuild the container: `docker compose -f infra/compose/compose.yaml --profile core up -d --build api-gateway`
  (`--stop` frees the Gradle daemon's RAM. Tests need Docker for Testcontainers.)
- JWT signing key (once): `./scripts/gen-jwt-key.sh` → `infra/compose/secrets/jwt-private.pem` (gitignored)
- identity-service run on the host (needs postgres up, the key, and port 8081 free), lighter than bootRun:
  `DB_PASSWORD=$(grep '^IDENTITY_DB_PASSWORD=' infra/compose/.env | cut -d= -f2-) JWT_PRIVATE_KEY_PATH="$(pwd -W)/infra/compose/secrets/jwt-private.pem" java -Xmx256m -jar services/identity-service/build/libs/identity-service-0.0.1-SNAPSHOT.jar`
  (Without a key the app refuses to start; tests use an ephemeral key via `src/test/resources/config/application.yaml`.)
- Register / login (identity-service on 8081):
  `curl -s -H 'Content-Type: application/json' -d '{"email":"a@example.com","password":"correct horse battery","displayName":"A"}' localhost:8081/auth/register`
  then the same with `{"email":...,"password":...}` to `/auth/login` → `accessToken`, `refreshToken`, `expiresIn`
  `{"refreshToken":"..."}` to `/auth/refresh` (new pair) or `/auth/logout` (204)
  Bearer endpoints: `curl -s -H "Authorization: Bearer $ACCESS" localhost:8081/me` · `POST /orgs {"name","slug"}`
  · switch org: `POST /auth/switch-org {"refreshToken","orgId"}` → new pair with the `org` claim
- OpenAPI: served at `localhost:8081/v3/api-docs.yaml`; committed copy `contracts/openapi/identity.yaml` is checked by a test.
  After an API change: `cd services/identity-service && UPDATE_CONTRACTS=true ./gradlew test --tests '*OpenApiContractTests'`
  Lint: `npx -y @redocly/cli@latest lint contracts/openapi/identity.yaml`
- Probes: `curl -s localhost:8081/health` · `/ready` · `/metrics` (full health: `/actuator/health`) · JWKS: `/.well-known/jwks.json`
- Start local K8s: `minikube start --driver=docker --kubernetes-version=v1.31.0 --memory=2560 --cpus=2`
- Check K8s: `kubectl config current-context && kubectl get nodes`
- Stop local K8s (frees RAM, keeps the cluster): `minikube stop`
- Memory check: `./scripts/mem.sh` (Windows free RAM, Docker VM size, per-container usage)
