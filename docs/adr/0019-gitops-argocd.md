# 0019: GitOps with Argo CD: app of apps, CI writes the image tag to Git

Date: 2026-10-07 · Status: accepted

## Context

Until now a deploy was `helm upgrade` from my laptop (`scripts/k8s-local-deploy.sh`) with locally built images.
CLAUDE.md says CD is GitOps with Argo CD and CI never runs `kubectl` against a cluster. The laptop has 8 GB RAM,
so Argo CD must be small, and the images CI pushes (`ghcr.io/heisamit3/myplatform/<svc>:<commit SHA>`, public)
are what should run.

## Decision

- **Argo CD from the upstream Helm chart** (`argo/argo-cd` 10.9.6 = v3.5.3), values in
  `infra/helm/platform/argocd-values.yaml`, installed by `scripts/k8s-local-platform.sh --argocd`. Only the
  controller, repo-server, server and redis run (memory limits 256/192/96/48 Mi); Dex, notifications and the
  ApplicationSet controller are off. Git is polled every 60 s; no webhook, GitHub can't reach minikube.
- **App of apps.** The only hand-applied object is `infra/argocd/local/root.yaml`. It syncs
  `infra/argocd/local/apps/`, which holds one `Application` per service. Adding a service is a Git commit.
  AWS gets its own directory (`infra/argocd/aws/`) in Phase 7.
- **Each Application = shared chart + values layers** (ADR 0016): `values.yaml` → `values-<env>.yaml` →
  `values-image.yaml`. Automated sync with `prune` (removed from Git = removed from the cluster) and `selfHeal`
  (a manual `kubectl edit` is reverted).
- **`values-image.yaml` per service is owned by CI.** After the image is pushed on `main`, the `bump-tag` job in
  `_build-image.yml` sets the tag to the commit SHA and pushes a bot commit with `[skip ci]`. It retries on a
  rejected push (other services' runs push at the same time) and skips if `main` already holds a newer SHA, so a
  slow old run can't roll a service back. One small file per service means bot commits never conflict with
  hand edits of the real config.
- **values-local.yaml keeps the locally built image** (`local`, `pullPolicy: Never`), so
  `scripts/k8s-local-deploy.sh` still works for a quick inner loop without Argo CD. With Argo CD running,
  selfHeal would undo such a manual deploy, so the two aren't used at the same time.

## Consequences

- Deploy = merge to `main`. Rollback = `git revert` of the bot commit (Argo CD's own rollback works too, but
  turns auto-sync off until Git catches up).
- The deployed version of every service is visible in Git history, and every pod maps to one commit.
- The image job's caller now grants `contents: write` (a reusable workflow can't get more than its caller gives).
  The build itself still checks out read-only; only `bump-tag` uses the write permission.
- Not managed by Argo CD yet: the raw data stores and Gateway (`infra/k8s/raw/`) and Secrets
  (`scripts/k8s-local-secrets.sh`). Secrets in Git need Sealed Secrets or External Secrets (Phase 7).
- Argo CD costs ~0.5 GB, so it only runs in a dedicated K8s session with minikube at 3 GB.
