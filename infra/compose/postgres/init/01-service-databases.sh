#!/usr/bin/env bash
# One database + one login role per service ("database per service" on a shared local server).
# The postgres image runs this only when the data volume is empty. It is idempotent, so it can
# also be re-run by hand on an existing volume:
#   MSYS_NO_PATHCONV=1 docker exec myplatform-postgres-1 bash /docker-entrypoint-initdb.d/01-service-databases.sh
#
# Testing note: the image trusts connections from inside the postgres container (socket and
# 127.0.0.1), so test passwords from another container on the compose network:
#   docker run --rm --network myplatform_default -e PGPASSWORD=... <image> psql -h postgres -U identity
set -euo pipefail

# create_service_db <name> <password>
# Role and database share the name. Only that role may connect to that database.
create_service_db() {
  local name="$1" password="$2"
  if [ -z "$password" ]; then
    echo "create_service_db: empty password for '$name'" >&2
    exit 1
  fi
  echo "Ensuring role and database '$name'"
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -q \
    -v name="$name" -v pw="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'name')
  WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'name') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'name', :'pw') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'name', :'name')
  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'name') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'name') \gexec
SQL
}

# Service roles must not wander into the admin database either.
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -q -c 'REVOKE CONNECT ON DATABASE postgres FROM PUBLIC'

create_service_db identity "${IDENTITY_DB_PASSWORD:-}"
# Later: create_service_db ai "${AI_DB_PASSWORD:-}"  (plus CREATE EXTENSION vector in that database)
