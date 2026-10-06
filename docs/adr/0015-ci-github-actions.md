# 0015: CI with GitHub Actions: path-filtered workflows, one reusable image workflow, SHA tags

Date: 2026-10-06 · Status: accepted

## Context

The monorepo holds three deployable services, the web app and the contracts. CI should test only what
changed, publish an image per service that Argo CD can deploy later (Phase 6), and never touch a cluster.

## Decision

- **One workflow per service** (`identity-service.yml`, `api-gateway.yml`, `notification-service.yml`,
  `web.yml`, `contracts.yml`), each filtered by `paths:`. A service's filter includes the contracts its tests
  read (identity: `contracts/openapi/identity.yaml` + `contracts/events/`; notification: `contracts/events/`),
  so a contract change re-tests every consumer of that contract. The path list is written once and reused
  with a YAML anchor (`&paths` / `*paths`) for `push` and `pull_request`.
- **Test, then image.** The `test` job runs the same commands as locally (`./gradlew build`; `npm ci`, lint,
  typecheck, test, build), including the Testcontainers integration tests: GitHub's Ubuntu runners have Docker.
  The `image` job runs only after the tests pass (`needs: test`).
- **One reusable image workflow** (`_build-image.yml`, `on: workflow_call`), shared by all services, so
  registry, tagging and caching live in one place. It builds the service's existing multi-stage Dockerfile
  with Buildx and caches layers in the GitHub Actions cache (one scope per service).
- **Push only from `main`.** Pull requests build the image (proves the Dockerfile still works) without
  logging in or pushing. Images go to `ghcr.io/heisamit3/myplatform/<service>`.
- **Tag = full commit SHA, no `:latest`.** Tags are immutable, so a running pod maps to exactly one commit
  and a rollback is just a previous SHA. Phase 6 has CI write that SHA into the Helm values for Argo CD.
  `docker/metadata-action` also adds OCI labels (`org.opencontainers.image.source`, `revision`).
- **Least privilege.** `permissions: {}` at the top of each workflow. Jobs opt in to `contents: read`, and
  only the image job gets `packages: write`. Auth uses the built-in, short-lived `GITHUB_TOKEN` (no PAT).
  `actions/checkout` doesn't persist the token (`persist-credentials: false`).
- **Third-party actions pinned to a full commit SHA** with the version in a comment. A tag can be moved to
  malicious code; a SHA can't. Dependabot (planned, "Later upgrades") will bump them.
- **Concurrency:** a new commit on a PR cancels the older run. Runs on `main` are never cancelled, so every
  commit on `main` gets its image.
- Gradle caching uses `setup-gradle` with `cache-provider: basic` (the open-source GitHub Actions cache, not
  the commercial default). `setup-gradle` also validates `gradle-wrapper.jar` checksums.
- Workflows are linted locally with actionlint (in Docker, see CLAUDE.md) before pushing.

## Consequences

- Images are built twice in a sense: Gradle runs in the test job and again inside the Dockerfile. The image
  stays reproducible from its Dockerfile alone, and the build cache keeps the second run short.
- New GHCR packages are private. They're made public once in the package settings (GUI) so minikube/EKS can
  pull without an image pull secret.
- A change only to `_build-image.yml` triggers all three service workflows (it's in their path filters).
- Runners are amd64; images are `linux/amd64` only. EKS nodes in Phase 7 must be x86 (or add a platform then).
