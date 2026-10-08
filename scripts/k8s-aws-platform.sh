#!/usr/bin/env bash
# Bootstraps GitOps on the EKS cluster (ADR 0022): Argo CD, the Secrets, and the root Application.
# Argo CD then installs everything else from Git (infra/argocd/aws/apps/): Gateway API CRDs, StorageClass,
# Traefik, Prometheus/Grafana, Postgres/Redis, Kafka/MongoDB/Mailpit, the services, in sync-wave order.
# Run after `terraform apply` + the kubeconfig command (terraform output -raw kubeconfig_command). Idempotent.
# Usage: ./scripts/k8s-aws-platform.sh [context]   (default: eks-myplatform)
set -euo pipefail
cd "$(dirname "$0")/.."

ctx="${1:-eks-myplatform}"
case "$ctx" in
  eks-*) ;;
  *) echo "refusing: '$ctx' is not an eks-* context" >&2; exit 1 ;;
esac
k() { kubectl --context "$ctx" "$@"; }
k version >/dev/null || { echo "can't reach $ctx" >&2; exit 1; }

ARGOCD_CHART_VERSION=10.9.6   # Argo CD v3.5.3, same as scripts/k8s-local-platform.sh

helm repo add argo https://argoproj.github.io/argo-helm >/dev/null 2>&1 || true
helm repo update argo >/dev/null

echo "== Argo CD $ARGOCD_CHART_VERSION"
helm --kube-context "$ctx" upgrade --install argocd argo/argo-cd --version "$ARGOCD_CHART_VERSION" \
  -n argocd --create-namespace -f infra/helm/platform/argocd-values.yaml --wait --timeout 8m >/dev/null

echo "== Secrets (from infra/compose/.env + the JWT key; never in Git)"
./scripts/k8s-local-secrets.sh --context "$ctx"
./scripts/k8s-grafana-admin.sh "$ctx"

echo "== Root Application"
k apply -f infra/argocd/aws/root.yaml >/dev/null
echo "Argo CD now syncs infra/argocd/aws/apps/. Watch: kubectl --context $ctx -n argocd get applications"
