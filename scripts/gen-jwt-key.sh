#!/usr/bin/env bash
# Generates the local RS256 signing key for identity-service (PKCS#8 PEM, 2048-bit).
# The file is gitignored. It is never overwritten: delete it yourself to rotate (old tokens then stop verifying).
set -euo pipefail

dir="$(cd "$(dirname "$0")/.." && pwd)/infra/compose/secrets"
key="$dir/jwt-private.pem"

mkdir -p "$dir"
if [ -f "$key" ]; then
  echo "exists: $key"
else
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$key" 2>/dev/null
  chmod 600 "$key"
  echo "created: $key"
fi
echo "use: JWT_PRIVATE_KEY_PATH=$(cd "$dir" && pwd -W 2>/dev/null || pwd)/jwt-private.pem"
