#!/usr/bin/env bash
# Creates the Secrets (and the Postgres init ConfigMap) that infra/k8s/raw/ expects, from the same local
# files compose uses, so nothing secret is ever committed. Idempotent: re-run after changing .env or the key.
#   Secret postgres-admin {password}   <- POSTGRES_PASSWORD in infra/compose/.env
#   Secret identity-db    {password}   <- IDENTITY_DB_PASSWORD in infra/compose/.env
#   Secret identity-jwt   {private.pem} <- infra/compose/secrets/jwt-private.pem (./scripts/gen-jwt-key.sh)
#   ConfigMap postgres-init            <- infra/compose/postgres/init/01-service-databases.sh
# Usage: ./scripts/k8s-local-secrets.sh   (only against minikube)
set -euo pipefail
cd "$(dirname "$0")/.."

ctx="$(kubectl config current-context)"
[ "$ctx" = minikube ] || { echo "refusing: kubectl context is '$ctx', not minikube" >&2; exit 1; }

env_file=infra/compose/.env
key_file=infra/compose/secrets/jwt-private.pem
[ -f "$env_file" ] || { echo "missing $env_file (cp infra/compose/.env.example $env_file)" >&2; exit 1; }
[ -f "$key_file" ] || { echo "missing $key_file (./scripts/gen-jwt-key.sh)" >&2; exit 1; }

# env_value NAME: the value of NAME= in the .env file (without echoing it).
env_value() {
  local v
  v="$(grep -E "^$1=" "$env_file" | tail -1 | cut -d= -f2-)"
  [ -n "$v" ] || { echo "$1 is empty in $env_file" >&2; exit 1; }
  printf '%s' "$v"
}

ns=myplatform
kubectl apply -f infra/k8s/raw/00-namespace.yaml >/dev/null

# Values go through short-lived files, not --from-literal, so they don't appear in the process list.
# (Not <(...): kubectl is a Windows binary and can't open Git Bash's /dev/fd paths.)
umask 077
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
env_value POSTGRES_PASSWORD >"$tmp/postgres-password"
env_value IDENTITY_DB_PASSWORD >"$tmp/identity-db-password"

# "create --dry-run=client -o yaml | apply" = create or update.
apply_secret() {
  local name="$1"; shift
  kubectl -n "$ns" create secret generic "$name" "$@" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  echo "secret/$name applied"
}
apply_secret postgres-admin --from-file=password="$(cygpath -w "$tmp/postgres-password")"
apply_secret identity-db --from-file=password="$(cygpath -w "$tmp/identity-db-password")"
apply_secret identity-jwt --from-file=private.pem="$key_file"

kubectl -n "$ns" create configmap postgres-init \
  --from-file=infra/compose/postgres/init/01-service-databases.sh --dry-run=client -o yaml | kubectl apply -f - >/dev/null
echo "configmap/postgres-init applied"
