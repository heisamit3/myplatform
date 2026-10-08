#!/usr/bin/env bash
# Creates the Secrets (and the database init ConfigMaps) that infra/k8s/raw/ expects, from the same local
# files compose uses, so nothing secret is ever committed. Idempotent: re-run after changing .env or the key.
#   Secret postgres-admin {password}   <- POSTGRES_PASSWORD in infra/compose/.env
#   Secret identity-db    {password}   <- IDENTITY_DB_PASSWORD in infra/compose/.env
#   Secret identity-jwt   {private.pem} <- infra/compose/secrets/jwt-private.pem (./scripts/gen-jwt-key.sh)
#   ConfigMap postgres-init            <- infra/compose/postgres/init/01-service-databases.sh
# For the events stack (infra/k8s/raw/events/):
#   Secret mongo-root      {password}      <- MONGO_ROOT_PASSWORD
#   Secret notification-db {password, uri} <- NOTIFICATION_DB_PASSWORD (uri = the service's connection string)
#   ConfigMap mongo-init                   <- infra/compose/mongo/init/01-service-users.js
# Usage: ./scripts/k8s-local-secrets.sh [--context <name>]   (default: the current context, which must be minikube)
#   --context eks-myplatform: the EKS cluster (called by scripts/k8s-aws-platform.sh). Only minikube or eks-* contexts.
set -euo pipefail
cd "$(dirname "$0")/.."

ctx="$(kubectl config current-context)"
if [ "${1:-}" = --context ]; then
  ctx="${2:?--context needs a name}"
fi
case "$ctx" in
  minikube | eks-*) ;;
  *) echo "refusing: kubectl context is '$ctx' (expected minikube or eks-*)" >&2; exit 1 ;;
esac
# Every call names the context, so a context switch in another terminal can't redirect it.
k() { kubectl --context "$ctx" "$@"; }

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
k apply -f infra/k8s/raw/00-namespace.yaml >/dev/null

# Values go through short-lived files, not --from-literal, so they don't appear in the process list.
# (Not <(...): kubectl is a Windows binary and can't open Git Bash's /dev/fd paths.)
umask 077
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
env_value POSTGRES_PASSWORD >"$tmp/postgres-password"
env_value IDENTITY_DB_PASSWORD >"$tmp/identity-db-password"
env_value MONGO_ROOT_PASSWORD >"$tmp/mongo-root-password"
env_value NOTIFICATION_DB_PASSWORD >"$tmp/notification-db-password"
# Generated hex passwords are URI-safe; one with ':' '@' '/' would need percent-encoding here.
printf 'mongodb://notification:%s@mongodb:27017/notification' "$(cat "$tmp/notification-db-password")" >"$tmp/notification-db-uri"

# "create --dry-run=client -o yaml | apply" = create or update.
apply_secret() {
  local name="$1"; shift
  kubectl -n "$ns" create secret generic "$name" "$@" --dry-run=client -o yaml | k apply -f - >/dev/null
  echo "secret/$name applied"
}
apply_secret postgres-admin --from-file=password="$(cygpath -w "$tmp/postgres-password")"
apply_secret identity-db --from-file=password="$(cygpath -w "$tmp/identity-db-password")"
apply_secret identity-jwt --from-file=private.pem="$key_file"
apply_secret mongo-root --from-file=password="$(cygpath -w "$tmp/mongo-root-password")"
apply_secret notification-db --from-file=password="$(cygpath -w "$tmp/notification-db-password")" \
  --from-file=uri="$(cygpath -w "$tmp/notification-db-uri")"

kubectl -n "$ns" create configmap postgres-init \
  --from-file=infra/compose/postgres/init/01-service-databases.sh --dry-run=client -o yaml | k apply -f - >/dev/null
echo "configmap/postgres-init applied"

kubectl -n "$ns" create configmap mongo-init \
  --from-file=infra/compose/mongo/init/01-service-users.js --dry-run=client -o yaml | k apply -f - >/dev/null
echo "configmap/mongo-init applied"
