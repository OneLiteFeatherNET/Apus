# Phase 3 — Task 3 Report: `HostingResourceBuilder`

> Note: the brief pointed at `.superpowers/sdd/2026-08-09-phase-3-hosting/task-3-brief.md`, which
> does not exist in this repository. The actual planning document is
> `docs/superpowers/plans/2026-08-09-phase-3-hosting.md` (Task 3 section); this report is filed at
> the analogous `docs/superpowers/reports/` location instead of the instructed `.superpowers/sdd/`
> path, which also does not exist here.

## Worktree base

Expected base commit `cd8cc5d` was not the worktree's initial HEAD (`5872a7c`, an older commit;
`api/BlueMapHosting.java` was missing). Ran `git reset --hard feat/phase-3-hosting` to land on
`cd8cc5d feat(operator): add BlueMapHosting CRD and multi-map hosting config builder`, which does
contain `BlueMapHosting`/`BlueMapHostingSpec`/`BlueMapHostingStatus` and the already-implemented
`BlueMapConfigBuilder.buildForHosting`. All work below is built on top of that commit.

## What was built

- `operator/src/main/java/net/onelitefeather/apus/operator/hosting/HostingResourceBuilder.java` —
  pure-function builder producing `Deployment`, `Service`, `Ingress`, and `Optional<Certificate>`
  from a `BlueMapHosting`, following `RenderJobBuilder`'s established shape (no client, owner
  references, shared `Labels`, `secretKeyRef` credentials).
- `operator/src/main/java/net/onelitefeather/apus/operator/hosting/Certificate.java` — a lean,
  single-file client-side model of cert-manager's `Certificate` (`cert-manager.io/v1`), nested
  `CertificateSpec`/`CertificateStatus` types instead of the three-file Rook pattern since only
  three leaf fields are ever set. Deliberately placed outside `...operator.api` so
  `CrdGeneratorMain`'s package filter (`net.onelitefeather.apus.operator.api` only) never picks it
  up — verified: `generateCrds` still emits exactly the same 6 CRDs as before this change, no
  `cert-manager.io` CRD among them.
- `operator/src/test/java/net/onelitefeather/apus/operator/hosting/HostingResourceBuilderTest.java`
  — 16 tests, written before the implementation (TDD), covering every point the plan calls out as
  "Tests, die zählen".

## Design decisions worth flagging

- **`OperatorConfig` has no `hostingImage` field.** The mandated signature
  `deployment(BlueMapHosting, String, String, OperatorConfig)` is implemented as specified, but
  since only `runnerImage`/`ingestImage` exist today and this task may not touch `OperatorConfig`,
  the container image is a local placeholder constant (`apus/hosting:dev`) with a Javadoc note
  that Task 4 must add `OperatorConfig.hostingImage()` and wire it through. The `config` parameter
  is already accepted so that change needs no signature edit later.
- **Readiness/liveness probe path** (`HostingResourceBuilder.PROBE_PATH = "/"`) is explicitly
  flagged in Javadoc as needing verification against Task 2's actual image, per the brief.
  Same treatment given informally to the ConfigMap mount path (`/config-src`), which Task 2's
  entrypoint also needs to agree on.
- **Webserver port fixed at 8100** (`HostingResourceBuilder.WEBSERVER_PORT`), matching
  `BlueMapConfigBuilder`'s hosting example and Task 2's documented `APUS_WEBSERVER_PORT` default,
  since neither `BlueMapHostingSpec` nor `OperatorConfig` carries a port field. Used consistently
  for the container port, the Service port/targetPort, the Ingress backend port, and both probes.
- **TLS secret name agreement**: `ingress()` and `certificate()` independently compute
  `"<hosting-name>-tls"` via a shared private helper (`tlsSecretName`) so the two always agree
  without one method calling the other — tested directly
  (`producesACertificateWhenTlsIsEnabledAndTheIngressReferencesItsSecret`).
- **S3 endpoint intentionally not passed as an env var** to the container: it's already baked
  into each map's `storages/<id>.conf` by `BlueMapConfigBuilder.buildForHosting` at ConfigMap-build
  time (a Task 4 concern), so the Deployment only injects the two credential env vars via
  `secretKeyRef` plus `APUS_WEBSERVER_PORT`.

## Test run

`./gradlew :operator:test` — BUILD SUCCESSFUL. `HostingResourceBuilderTest`: 16/16 passed, 0
failures, 0 errors. Full module test suite (all existing suites plus the new one) passed;
`generateCrds` still produces exactly 6 CRDs (Tenant, BlueMapMap, BlueMapRender, WorldSource,
WorldIngest, BlueMapHosting) — confirms `Certificate` was not picked up by the CRD generator.

`spotlessApply` run on `:operator`: no formatting changes needed beyond the new files themselves
(AGPL header applied automatically by Spotless's `licenseHeaderFile`).

## File restriction compliance

Only these files were created/modified:
- `operator/src/main/java/net/onelitefeather/apus/operator/hosting/HostingResourceBuilder.java` (new)
- `operator/src/main/java/net/onelitefeather/apus/operator/hosting/Certificate.java` (new)
- `operator/src/test/java/net/onelitefeather/apus/operator/hosting/HostingResourceBuilderTest.java` (new)
- This report (new, docs-only)

No other file was touched — `OperatorConfig.java`, anything under the repo-root `hosting/`
directory (Task 2's scope), and all other existing sources are untouched (`git status` confirms
only the two new `hosting/` package directories under `operator/`).

## Concerns for later tasks

- Task 4 must add `OperatorConfig.hostingImage()` (or equivalent) and update `deployment()`'s call
  site — currently a placeholder image string.
- Task 2 must confirm/correct `PROBE_PATH` and the ConfigMap mount path
  (`HostingResourceBuilder.CONFIG_MOUNT_PATH`, currently `/config-src`) against the real image.
- Task 4's reconciler must call `client.supports(Certificate.class)` before touching Certificate
  objects (per the plan) — `HostingResourceBuilder.certificate()` itself has no client and cannot
  perform that check; it only decides *whether* to build one from `spec.tls.enabled`.
