#!/usr/bin/env bash
# Copies locally built images from Docker Desktop into minikube's Docker daemon.
# Replaces `minikube image load`, which fails on recent Windows 11 builds in minikube 1.34
# ("exec: wmic: executable file not found"; wmic was removed from Windows).
# Usage: ./scripts/minikube-load.sh myplatform/identity-service:local [more images...]
set -euo pipefail
[ $# -gt 0 ] || { echo "usage: $0 <image> [image...]" >&2; exit 1; }

for image in "$@"; do
  echo "loading $image"
  # Left side: Docker Desktop's daemon. Right side (subshell): minikube's daemon via docker-env.
  docker save "$image" | ( eval "$(minikube docker-env --shell bash)"; docker load -q )
done
