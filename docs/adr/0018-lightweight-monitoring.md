# 0018: Lightweight metrics stack: Prometheus server + Grafana, annotation-based scraping

Date: 2026-10-06 · Status: accepted

## Context

CLAUDE.md puts metrics first (Prometheus + Grafana). The usual all-in-one install, kube-prometheus-stack, brings
the Prometheus Operator, Alertmanager, node-exporter and kube-state-metrics: around 1 GB, which doesn't fit next
to the app in a 2.5–3 GB minikube on an 8 GB laptop.

## Decision

- **Prometheus server only** (chart `prometheus-community/prometheus`), with Alertmanager, node-exporter,
  kube-state-metrics and Pushgateway off. No persistence locally (2 days of retention in an emptyDir).
- **Three scrape jobs, nothing else:**
  - `kubernetes-pods`: pods annotated `prometheus.io/scrape|port|path`. The shared chart (ADR 0016) and Traefik
    set these annotations. Pod labels become metric labels (`app_kubernetes_io_name`, `namespace`, `pod`).
  - `kubernetes-nodes-cadvisor`: container memory/CPU from the kubelet, filtered at scrape time to
    `container_memory_working_set_bytes` and `container_cpu_usage_seconds_total`.
  - `prometheus`: Prometheus scrapes itself.
- **Annotations, not ServiceMonitors:** no operator or CRDs needed. A new service is scraped as soon as it's
  deployed with the chart.
- **Latency percentiles from SLO buckets:** both Spring services publish `http.server.requests` with fixed buckets
  25 ms … 2 s (`management.metrics.distribution.slo`). That's ~8 series per route instead of ~70 for a full
  histogram, and enough for `histogram_quantile(0.95, ...)`. Probe and metrics endpoints are filtered out in the
  dashboard queries.
- **Grafana** (chart `grafana-community/grafana`; the chart moved out of `grafana/helm-charts`) with the datasource
  and the dashboard provisioned from files. The dashboard JSON lives in `infra/helm/platform/dashboards/` and is
  loaded via a ConfigMap. Nothing is configured by clicking. Locally, anonymous users can view; the admin password
  is generated into a Secret.
- **Runs only in dedicated K8s sessions:** `./scripts/k8s-local-platform.sh --monitoring`, with minikube's
  container limit raised to 3 GB in place (`docker update`), then uninstalled. On AWS it runs all the time.

## Consequences

- Measured: Prometheus 68 MiB, Grafana ~280 MiB (limit 320 Mi), Traefik 33 MiB. The whole cluster with the app
  and monitoring used 2.3 GiB.
- Grafana 13's first start runs storage migrations. It was OOMKilled at 192 Mi, and the chart's liveness probe
  (160 s) killed it mid-migration on the busy node. Fixed with a 320 Mi limit, a startup probe (up to 10 min) and
  `Recreate` (never two Grafanas starting at once).
- minikube with the Docker runtime reports cAdvisor memory per pod cgroup, without a `container` label: the
  dashboard groups by `pod`.
- No alerting yet. Alert rules (5xx ratio, p95, OOM restarts) can be added in `serverFiles.alerting_rules.yml`
  once Alertmanager has somewhere to send them (Phase 7).
- No dashboard for container memory against limits (that needs kube-state-metrics); the working-set panel plus the
  known limits in values files cover it for now.
