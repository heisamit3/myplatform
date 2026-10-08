#!/usr/bin/env bash
# Creates Secret monitoring/grafana-admin {admin-user, admin-password} with a random password, once.
# Grafana uses it via admin.existingSecret (grafana-values.yaml). Otherwise the chart generates the password with
# Helm's `lookup`, which Argo CD doesn't support: every sync would render a new password, change the pod's
# checksum annotation and restart Grafana in a loop (ADR 0022).
# Called by k8s-local-platform.sh --monitoring and k8s-aws-platform.sh. Idempotent: an existing Secret is kept.
# Usage: ./scripts/k8s-grafana-admin.sh <context>   (minikube or eks-*)
# Password: kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d
set -euo pipefail

ctx="${1:?usage: $0 <context>}"
case "$ctx" in
  minikube | eks-*) ;;
  *) echo "refusing: context '$ctx' (expected minikube or eks-*)" >&2; exit 1 ;;
esac
k() { kubectl --context "$ctx" "$@"; }

k get namespace monitoring >/dev/null 2>&1 || k create namespace monitoring >/dev/null
if k -n monitoring get secret grafana-admin >/dev/null 2>&1; then
  echo "secret/grafana-admin exists (kept)"
  exit 0
fi

# Through a short-lived file, not --from-literal, so the password isn't in the process list.
umask 077
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
printf admin >"$tmp/admin-user"
openssl rand -hex 20 | tr -d '\n' >"$tmp/admin-password"
k -n monitoring create secret generic grafana-admin \
  --from-file=admin-user="$(cygpath -w "$tmp/admin-user")" \
  --from-file=admin-password="$(cygpath -w "$tmp/admin-password")" >/dev/null
echo "secret/grafana-admin created"
