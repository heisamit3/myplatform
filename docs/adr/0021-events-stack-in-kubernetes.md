# 0021: Kafka, MongoDB and Mailpit in Kubernetes as plain StatefulSets/Deployments

Date: 2026-10-08 · Status: accepted

## Context

The full stack must run on EKS (Phase 7), and CLAUDE.md rules out MSK and other managed services: Kafka and
the databases run in-cluster. Until now only Postgres and Redis had Kubernetes manifests; notification-service
was checked with `helm template` only. Common options: an operator (Strimzi for Kafka, the MongoDB Community
Operator), Bitnami-style Helm charts, or hand-written manifests like `infra/k8s/raw/postgres.yaml`.

## Decision

- **Hand-written manifests in `infra/k8s/raw/events/`**: Kafka and MongoDB as StatefulSets with a PVC and a
  headless Service, Mailpit as a Deployment. Same images and settings as compose (ADR 0009). A separate
  directory, because `kubectl apply -f infra/k8s/raw/` doesn't recurse: a default minikube session stays small.
- **No operator.** Strimzi runs its own operator pod (a long-running JVM plus CRDs) to manage a single broker.
  That's real value for multi-broker clusters, rolling upgrades and topic CRDs, but here it would cost RAM and
  hide the parts worth explaining. Same reasoning for the MongoDB operator.
- **Kafka specifics:** KRaft single node, `controller.quorum.voters = 1@localhost:9093` (the pod's own DNS name
  only exists once it's Ready, which needs the quorum first). Data in a subdirectory of the volume, because a fresh
  EBS volume has `lost+found` at its root, which Kafka would try to load as a partition.
  Port probes only; the Kafka CLI would start a second JVM inside the 768Mi limit.
- **All non-root**, with `fsGroup` so a freshly provisioned volume is writable (minikube's hostPath volumes
  hide that; EBS doesn't). Mailpit runs as `nobody` with a read-only root filesystem.
- **Secrets** come from `.env` via `scripts/k8s-local-secrets.sh` (`mongo-root`, `notification-db` with the
  connection URI); MongoDB's init script is the compose one, mounted from a ConfigMap.

## Consequences

- Verified on minikube (3 GB, Argo CD off): register 201 → event → welcome email in Mailpit. 11 events that had
  waited in the outbox since Kafka was missing were delivered when it came up. A replay of the whole topic gave
  `duplicate, ignored` ×12 and no new emails. Cluster: 2.63 of 3 GiB.
- Topics are created by identity-service at startup. If Kafka starts later (fresh cluster), identity-service needs
  one restart. On EKS, Argo CD sync waves can order this (Phase 7).
- Single broker, single MongoDB node: no HA, fine for a demo. Moving to Strimzi later is a good interview story.
