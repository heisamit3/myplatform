# 0016: One shared Helm chart, one release per service, values per service and environment

Date: 2026-10-06 · Status: accepted

## Context

identity-service was first deployed with hand-written YAML (kept in `infra/k8s/raw/reference/`). The other
services need the same objects with different names, ports, env vars and limits. Copying ~150 lines per service
per environment would drift. Phase 6 (Argo CD) needs one place per service and environment where CI writes the
image tag.

## Decision

- **One chart, `infra/helm/service/`,** renders Deployment, Service, ConfigMap (from `env`) and an optional
  Gateway API HTTPRoute. It works for every service because the services share conventions (CLAUDE.md):
  `/health` + `/ready` + `/metrics`, a numeric non-root UID, config from env vars.
- **One Helm release per service** (`helm upgrade --install identity-service infra/helm/service ...`), so each
  service is deployed, upgraded and rolled back on its own. An umbrella chart would deploy them together.
- **Values in layers:** chart defaults (`service/values.yaml`) → `services/<name>/values.yaml` (same in every
  environment) → `services/<name>/values-<env>.yaml` (image repository/tag/pull policy, URLs). Helm merges maps
  key by key, so the env file only holds differences; `null` removes a default key (web's `fsGroup`).
- **Image tag only from values,** never `latest`: `local` + `pullPolicy: Never` in minikube; the commit SHA in
  `values-aws.yaml`, written by CI in Phase 6 (ADR 0015).
- **Safe defaults in the chart:** memory request = limit, no CPU limit, startup/liveness/readiness probes,
  `runAsNonRoot`, read-only root filesystem + `/tmp` emptyDir, all capabilities dropped,
  `enableServiceLinks: false`, and a `checksum/config` annotation that restarts pods when their ConfigMap changes
  (env vars are only read at startup).
- **Secrets aren't in the chart.** It only references them by name (`secretEnv`, `secretFiles`). Locally,
  `scripts/k8s-local-secrets.sh` creates them; on AWS a later step (e.g. External Secrets or sealed secrets) will.
- **Data stores stay raw YAML** (`infra/k8s/raw/postgres.yaml`, `redis.yaml`): they aren't "our services" and
  don't follow the conventions above. Third-party pieces (Traefik, Prometheus, Grafana) use their upstream charts
  with values in `infra/helm/platform/`.

## Consequences

- A new service = a values directory, no new templates. A template change affects every service: bump the chart
  version and run `helm template` for all of them.
- Found while migrating: Kubernetes injects `<SERVICE>_PORT=tcp://ip:port` env vars for every Service in the
  namespace, which broke the gateway's `REDIS_PORT`. The chart turns that off for every service.
- notification-service has values but isn't deployed in minikube (Kafka + MongoDB don't fit in local RAM). It
  is checked with `helm template` and runs with the full stack on AWS.
