# 0022: GitOps on EKS: one root app for platform + data + services, sync waves, no load balancer

Date: 2026-10-08 · Status: accepted

## Context

On minikube, a script installs the platform (Gateway API CRDs, Traefik) and the data stores are applied by hand;
Argo CD only deploys the services (ADR 0019). The EKS cluster is created fresh every session (ADR 0020), so
everything should come up from Git with as few manual steps as possible, in the right order (CRDs before Gateways,
StorageClass before PVCs, Kafka before identity-service, which creates its topic at startup).

## Decision

- **Bootstrap = 3 things** (`scripts/k8s-aws-platform.sh`): Argo CD (same chart and values as minikube), the
  Secrets (`k8s-local-secrets.sh --context eks-…`, from `.env`), and `infra/argocd/aws/root.yaml`.
  Everything else is an Application in `infra/argocd/aws/apps/`.
- **Sync waves on the child Applications:** -4 Gateway API CRDs (upstream repo, pinned tag) and the gp3
  StorageClass → -3 Traefik, Prometheus, Grafana + its dashboards ConfigMap (upstream charts + our values files,
  multi-source) → -2 `infra/k8s/raw/` (namespace, Gateway, Postgres, Redis) → -1 `infra/k8s/raw/events/` (Kafka, MongoDB, Mailpit) → 0 the four services.
  Argo CD only waits for a wave to be *healthy* if it can judge an Application's health, so `argocd-values.yaml`
  adds the health check from the Argo CD docs (removed from the defaults in 1.8).
- **Same manifests as minikube.** No AWS copies of the data stores; only `values-aws.yaml` per service
  (today: the browser origin and email link) and the StorageClass differ.
- **gp3 StorageClass as the default**, `WaitForFirstConsumer` (the volume is created in the AZ where the pod lands),
  `reclaimPolicy: Delete` (deleting the PVC deletes the EBS volume; the teardown relies on it).
  EKS 1.30+ has no default class, so without it every PVC stays Pending.
- **No LoadBalancer.** Traefik stays `ClusterIP`; the app is reached with `kubectl port-forward` like on minikube.
  An NLB costs ~$0.02/h plus public IPs and is the classic teardown leftover. A public URL (NLB + DNS + TLS) can be
  added for a demo later; the teardown script already handles LoadBalancer Services.
- **Dashboards as a kustomize `configMapGenerator`** (`infra/helm/platform/dashboards/kustomization.yaml`):
  Argo CD syncs it on EKS, `kubectl apply -k` uses it on minikube. One source instead of a script-built ConfigMap.
- **Grafana's admin password from our own Secret** (`admin.existingSecret: grafana-admin`, created once with a random
  password by `scripts/k8s-grafana-admin.sh`). The chart's default keeps its generated password stable with Helm's
  `lookup`, which Argo CD doesn't run. Every sync would render a new password, change the pod's `checksum/secret`
  and restart Grafana in a loop. Reproduced with two `helm template` runs (different checksums); with
  `existingSecret`, the Prometheus, Traefik and Grafana renders are identical run to run.
- **CRDs are never pruned** (`prune: false` on that app): deleting a CRD deletes every object of that type.
- **Teardown deletes the root app first.** While it exists, selfHeal recreates every child Application the
  script deletes.

## Consequences

- From `terraform apply` to a running app: kubeconfig command → `./scripts/k8s-aws-platform.sh` → wait for
  `kubectl -n argocd get applications` to show all Synced/Healthy.
- Checked without a cluster: `helm template` with the AWS values + kubeconform (`-strict`, CRD schemas for
  Application/Gateway/HTTPRoute) on all 36 + 16 (monitoring) resources; a broken Application is rejected;
  every referenced path exists. Not yet run on EKS.
- Secrets are still created from my laptop. Sealed Secrets or External Secrets (AWS Secrets Manager) would put
  them under GitOps too; later upgrade.
- The monitoring changes are shared with minikube and were verified there: admin login 200 (wrong password 401),
  dashboard provisioned, datasource healthy; re-running keeps the Secret and the Grafana pod.
- The local health-check addition also applies to minikube's Argo CD (harmless: the local apps have no waves).
