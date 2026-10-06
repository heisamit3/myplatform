#!/usr/bin/env bash
# Validates the Kafka event contracts in contracts/events/:
#   - every *.schema.json compiles (JSON Schema 2020-12)
#   - every examples/<type>.v<N>.json is valid against <type>.v<N>.schema.json
#   - every invalid/<type>.v<N>.<case>.json is rejected (proves the schema is strict where it should be)
# Usage: ./scripts/check-event-schemas.sh   (needs Node; ajv-cli is fetched by npx)
set -euo pipefail
cd "$(dirname "$0")/../contracts/events"

# ajv <compile|validate> <schema> [args...]: the other schemas are passed as refs (-r) so $ref resolves.
# ajv refuses a schema loaded both as -s and -r, hence the exclusion.
ajv() {
  local cmd="$1" schema="$2"; shift 2
  local refs=() s
  for s in *.schema.json; do [ "$s" = "$schema" ] || refs+=(-r "$s"); done
  npx -y -p ajv-cli@5.0.0 -p ajv-formats@3.0.1 ajv "$cmd" -s "$schema" "${refs[@]}" "$@" \
    --spec=draft2020 -c ajv-formats --strict=true
}

# Compile first: a schema that doesn't load would make every "should be rejected" case pass.
for s in *.schema.json; do
  ajv compile "$s" >/dev/null 2>&1 || { echo "FAIL  $s does not compile"; ajv compile "$s" || true; exit 1; }
  echo "ok    $s compiles"
done

failed=0
for example in examples/*.json; do
  schema="$(basename "$example" .json).schema.json"
  if ajv validate "$schema" -d "$example" >/dev/null 2>&1; then
    echo "ok    $example"
  else
    echo "FAIL  $example should be valid"; ajv validate "$schema" -d "$example" || true; failed=1
  fi
done
for bad in invalid/*.json; do
  # invalid/identity.user.registered.v1.extra-field.json -> identity.user.registered.v1.schema.json
  schema="$(basename "$bad" .json | sed -E 's/(\.v[0-9]+)\..*$/\1/').schema.json"
  if ajv validate "$schema" -d "$bad" >/dev/null 2>&1; then
    echo "FAIL  $bad should be rejected"; failed=1
  else
    echo "ok    $bad rejected"
  fi
done
exit "$failed"
