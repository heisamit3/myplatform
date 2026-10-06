#!/usr/bin/env bash
# Installs the platform pieces the app runs on in minikube (idempotent; re-run after a version bump):
#   Gateway API CRDs + Traefik (edge)          always
#   Prometheus + Grafana (namespace monitoring) with --monitoring
#   Argo CD (namespace argocd) + the root Application  with --argocd
# Usage: ./scripts/k8s-local-platform.sh [--monitoring] [--argocd]
set -euo pipefail
cd "$(dirname "$0")/.."

ctx="$(kubectl config current-context)"
[ "$ctx" = minikube ] || { echo "refusing: kubectl context is '$ctx', not minikube" >&2; exit 1; }

# Pinned versions. Gateway API = the version Traefik is built against (its go.mod).
GATEWAY_API_VERSION=v1.6.1
TRAEFIK_CHART_VERSION=41.6.1        # Traefik v3.7.13
PROMETHEUS_CHART_VERSION=29.35.0    # Prometheus v3.15.0
GRAFANA_CHART_VERSION=13.2.7        # Grafana 13.2.3 (chart moved to grafana-community)
ARGOCD_CHART_VERSION=10.9.6         # Argo CD v3.5.3

monitoring=false argocd=false
for arg in "$@"; do
  case "$arg" in
    --monitoring) monitoring=true ;;
    --argocd) argocd=true ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

helm repo add traefik https://traefik.github.io/charts >/dev/null 2>&1 || true
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo add grafana-community https://grafana-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo add argo https://argoproj.github.io/argo-helm >/dev/null 2>&1 || true
helm repo update traefik prometheus-community grafana-community argo >/dev/null

# CRDs = new object types (Gateway, HTTPRoute, ...) that Kubernetes doesn't ship; the Traefik chart doesn't include them.
echo "== Gateway API CRDs $GATEWAY_API_VERSION"
kubectl apply --server-side -f "https://github.com/kubernetes-sigs/gateway-api/releases/download/$GATEWAY_API_VERSION/standard-install.yaml" >/dev/null

echo "== Traefik $TRAEFIK_CHART_VERSION"
helm upgrade --install traefik traefik/traefik --version "$TRAEFIK_CHART_VERSION" \
  -n traefik --create-namespace -f infra/helm/platform/traefik-values.yaml --wait --timeout 5m >/dev/null

if $monitoring; then
  echo "== Prometheus $PROMETHEUS_CHART_VERSION"
  helm upgrade --install prometheus prometheus-community/prometheus --version "$PROMETHEUS_CHART_VERSION" \
    -n monitoring --create-namespace -f infra/helm/platform/prometheus-values.yaml --wait --timeout 5m >/dev/null
  echo "== Grafana $GRAFANA_CHART_VERSION"
  kubectl -n monitoring create configmap grafana-dashboards \
    --from-file=infra/helm/platform/dashboards/ --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  helm upgrade --install grafana grafana-community/grafana --version "$GRAFANA_CHART_VERSION" \
    -n monitoring -f infra/helm/platform/grafana-values.yaml --wait --timeout 5m >/dev/null
fi

if $argocd; then
  echo "== Argo CD $ARGOCD_CHART_VERSION"
  helm upgrade --install argocd argo/argo-cd --version "$ARGOCD_CHART_VERSION" \
    -n argocd --create-namespace -f infra/helm/platform/argocd-values.yaml --wait --timeout 6m >/dev/null
  # The only thing applied by hand: the root Application. Argo CD creates everything else from Git (app of apps).
  kubectl apply -f infra/argocd/local/root.yaml >/dev/null
fi
helm list -A
