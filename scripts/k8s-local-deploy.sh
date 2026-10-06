#!/usr/bin/env bash
# Deploys services to minikube with the shared chart (one Helm release per service, ADR 0016).
# Images must be loaded first (./scripts/minikube-load.sh), platform + secrets installed.
# Usage: ./scripts/k8s-local-deploy.sh [service...]   (default: identity-service api-gateway web)
set -euo pipefail
cd "$(dirname "$0")/.."

ctx="$(kubectl config current-context)"
[ "$ctx" = minikube ] || { echo "refusing: kubectl context is '$ctx', not minikube" >&2; exit 1; }

services=("$@")
[ ${#services[@]} -gt 0 ] || services=(identity-service api-gateway web)

for s in "${services[@]}"; do
  echo "== $s"
  # Argo CD owns it (ADR 0019): a manual Helm deploy would be reverted by selfHeal within seconds.
  if kubectl -n argocd get application "$s" >/dev/null 2>&1; then
    echo "refusing: Argo CD manages $s; deploy by pushing to main" >&2; exit 1
  fi
  # --wait: returns once the pods are Ready (or fails after the timeout, e.g. on a crash loop).
  helm upgrade --install "$s" infra/helm/service -n myplatform \
    -f "infra/helm/services/$s/values.yaml" -f "infra/helm/services/$s/values-local.yaml" \
    --wait --timeout 4m >/dev/null
  kubectl -n myplatform get deploy "$s" --no-headers
done
