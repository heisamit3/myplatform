# Kubernetes objects used in myplatform

> **Draft by Claude Code, to be rewritten in my own words** (roadmap: interview prep). Each entry: what it is,
> where we use it, and the "why" I should be able to explain. Files: `infra/k8s/raw/`, `infra/helm/`.

## Workloads

**Pod**: the smallest thing Kubernetes runs: one or more containers that share a network address and volumes.
We never create Pods directly; controllers below create and replace them.

**Deployment**: keeps N identical, interchangeable pods running and replaces them gradually when the image or
config changes (rolling update; Grafana uses `Recreate` instead). Used for every service, Redis, Traefik, Grafana.
*Why:* self-healing (a crashed pod is replaced), zero-downtime updates, `rollout undo`.

**StatefulSet**: like a Deployment, but each pod has a stable name (`postgres-0`) and its own disk that follows it
across restarts. Used for Postgres. *Why not a Deployment:* a database's pods aren't interchangeable; its data must
stay attached to the same identity.

## Networking

**Service**: a stable virtual IP + DNS name (`identity-service`) in front of whichever pods match its label
selector. Only *ready* pods receive traffic. `ClusterIP` = reachable only inside the cluster.

**Headless Service** (`clusterIP: None`): no virtual IP; DNS returns the pod IPs directly. A StatefulSet needs one
for its stable per-pod DNS names (`postgres-0.postgres`).

**Gateway API** (CRDs installed separately, v1.6.1):
- **GatewayClass** `traefik`: "this controller (Traefik) implements Gateways of this class".
- **Gateway** `myplatform`: a listener the edge proxy opens (HTTP on port 8000).
- **HTTPRoute**: "requests matching these paths go to this Service". api-gateway owns `/auth`, `/me`, `/orgs`;
  web owns `/`. The most specific match wins.
*Why not Ingress:* Gateway API splits infrastructure (class, gateway) from app routing (routes), is more expressive,
and ingress-nginx has been retired.

## Configuration

**ConfigMap**: non-secret key/value settings, injected as env vars (`envFrom`). The chart hashes it into a pod
annotation, so a config change rolls the pods (env vars are only read at startup).

**Secret**: same idea for sensitive values (DB passwords, JWT private key), mounted as env vars or read-only files.
Only base64-encoded, not encrypted by default: access is restricted by RBAC. Never committed: created by
`scripts/k8s-local-secrets.sh` from local files.

**Namespace**: a named group of objects (`myplatform`, `traefik`, `monitoring`). Names are unique per namespace,
and DNS short names (`postgres`) resolve within it.

## Storage

**PersistentVolumeClaim (PVC)**: a request for disk ("1Gi, ReadWriteOnce"). The **StorageClass** (minikube's
`standard`) provisions a **PersistentVolume** to satisfy it. Deleting the pod or StatefulSet keeps the PVC and data.

**emptyDir**: scratch space that lives as long as the pod (our `/tmp` on a read-only root filesystem).

## Health and resources

**Probes**: the kubelet calls these on every container:
- *startup* (`/health`): gives a slow starter (JVM, Grafana) time; the other probes wait until it passes once.
- *liveness* (`/health`): failing = restart the container. Never checks the DB, or a DB outage restarts everything.
- *readiness* (`/ready`): failing = remove the pod from its Service, no restart. Checks the DB. Verified: Postgres
  down → identity-service `0/1`, no endpoints, 0 restarts.

**Requests / limits**: the request is what the scheduler reserves on a node; the limit is the cap. Above the
memory limit the container is OOM-killed (seen with Grafana at 192 Mi). We set memory request = limit and no CPU limit.

**securityContext**: `runAsNonRoot` + a numeric UID, no privilege escalation, all Linux capabilities dropped,
read-only root filesystem, seccomp `RuntimeDefault`.

## Packaging

**Helm chart**: templates + default values; a **release** is one installed copy (`identity-service`). Our one
shared chart serves every service; values files layer service and environment settings. `helm rollback` returns
to an earlier revision.

**CRD (CustomResourceDefinition)**: teaches the API server a new object type (Gateway, HTTPRoute). A controller
(Traefik) watches those objects and acts on them: the "operator pattern".

## Gotchas met so far

- No `depends_on`: identity-service crash-looped until Postgres was up, then recovered by itself.
- Service links: Kubernetes injected `REDIS_PORT=tcp://...` into every pod and broke the gateway's config →
  `enableServiceLinks: false`.
- `imagePullPolicy: Never` + `minikube-load.sh`: the local images never touch a registry.
