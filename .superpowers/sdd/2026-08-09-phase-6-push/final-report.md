# Phase 6 — Final report: closing the push path, tokens, and plugin/API drift

## Status

Done. All three loose ends (A, B, C) closed; spec brought up to date.

## A — Push/upload wiring (`ingest`)

`IngestConfig`/`IngestMain` now accept `push`/`upload` exactly like the pull sources:

- `SUPPORTED_SOURCE_TYPES` gained `push`/`upload`; the stale "have no connector yet"
  message was removed.
- New shared env-var contract for both staged-source types (only one runs per job, so one
  contract covers both): `APUS_SOURCE_STAGING_BUCKET` (required), `APUS_SOURCE_STAGING_ENDPOINT`,
  `APUS_SOURCE_STAGING_PREFIX`, `APUS_SOURCE_STAGING_ACCESS_KEY`, `APUS_SOURCE_STAGING_SECRET_KEY`,
  `APUS_SOURCE_STAGING_REGION` — documented in `ingest/README.md`.
- `IngestMain.selectConnector` now returns `PushSourceConnector`/`UploadSourceConnector` for
  those types.
- New end-to-end test `PushIngestEndToEndTest` (`:ingest:integrationTest`, real MinIO via
  Testcontainers): stages a zip in a staging prefix, runs `IngestMain.run` for both `push` and
  `upload`, and asserts a valid bundle + manifest + region file land in the destination bucket.
  This is the proof the push path now works end to end, not just that the connector classes work
  in isolation.

## B — Push-token generation (`operator`)

Tokens are **tenant-scoped**, not per-`WorldSource` — the design spec already settled this
("Service-Tokens sind mandantengebunden", §10.3), and the existing `FabricPushTokenRepository`
already resolves a token to a *namespace*, not a source, which only makes sense under that
reading.

- New `PushTokenSecrets` (operator, package `tenant`) is the single canonical definition of the
  Secret shape (label, data key, fixed name `apus-push-token`, `generate()` using `SecureRandom`
  + URL-safe base64, 256 bits).
- `TenantReconciler` creates this Secret once per tenant, alongside the namespace. Critically, it
  is **never regenerated** on later reconciles (no `createOr(update)` here) — a fresh random
  value on every resync would silently invalidate whatever `paper-worldpush` was already
  configured with. Ownership is checked the same way every other tenant resource is (name+UID
  labels), so a conflicting pre-existing Secret is refused rather than adopted.
- `TenantStatus` gained `pushTokenSecret` (the Secret's fixed, non-secret *name* only). The token
  value itself never appears in status, an event, or a log line.
- `api`'s `FabricPushTokenRepository` now delegates its constants to `PushTokenSecrets` instead
  of duplicating them (removes exactly the kind of drift risk this whole phase report is about).

**RBAC, documented but not implemented as YAML** (no manifest/Helm/Kustomize infrastructure
exists anywhere in this repo to hang it on): `FabricPushTokenRepository`'s Javadoc now spells out
that its current `list()`-by-label lookup, unavoidably, needs `get`/`list` on **all** Secrets
cluster-wide (Kubernetes RBAC cannot filter by label) — broader than ideal — and documents the
concrete narrower alternative (enumerate tenants via the already-listable `Tenant` CR, then `get`
the fixed-name Secret per namespace, letting RBAC restrict to `resourceNames: ["apus-push-token"]`
+ `get` only) as a deliberate follow-up, not implemented now to avoid an invasive rewrite of
already-tested code under this task's scope. Flagged as a concern below and as open item 9 in the
spec.

## C — Plugin/API alignment (`paper-worldpush` ↔ `api`)

Token transport was already consistent (path segment both sides; only `config.yml`'s comment
wrongly said "bearer token" — fixed). The real break was the **request body**: the plugin sent
`{tenant, worldName, fileCount, bytesUploaded}`, but `PushController`/`PushReportRequest` only
ever deserializes `{sourceName, version}` — every real push report would have 400'd. Fixed:

- New required `world-source-name` config key (`WorldPushConfig.sourceName()`) — the target
  `push`-type `WorldSource`'s name, since a token alone is tenant-, not source-, scoped.
- `PushCycleRunner` now generates a timestamp-style `version` per cycle (injectable `Clock` for
  tests) and `PushSummary`/`HttpPushNotifier` send exactly `{"sourceName", "version"}` on the
  wire.
- New `HttpPushNotifierTest` (JDK `HttpServer` stub, matching this repo's established pattern)
  locks in the correct path-segment token and JSON body shape.

## Spec (`docs/superpowers/specs/2026-08-08-apus-design.md`)

- New §0 "Stand der Umsetzung" at the top: all six phases built, sharding deliberately not built
  (references §14 Phase 4), and the three open items (identity broker unselected, OIDC never
  tested against a real broker, `paper-worldpush`'s save window untested).
- §4 module table: Java 21 → 25 everywhere (root `build.gradle.kts` toolchain applies to every
  subproject uniformly); `world-ingest`/`runner-image` → actual dir names `ingest`/`runner`;
  added `hosting` (Dockerfile-only, no Gradle module) and corrected `api`/`ui` to their current
  form; corrected `operator`'s stack (JOSDK + fabric8, no Micronaut).
- §13.2: CRD-generation note marked done (describes the `crdgen` source set); test-coverage table
  corrected per module, including the new push/upload/E2E tests and the two still-open gaps
  (identity broker, Paper save window).
- §15: items 1 (connector order) and 4 (CRD generation) marked resolved; item 2 (bucket
  notifications) corrected — neither notifications nor polling was built, a direct completion
  callback from the writer was, which is now documented; item 3 (identity broker) confirmed still
  open; new items 8 (Paper save window untested) and 9 (push-token RBAC broader than ideal).

## Verification

- `./gradlew build -x :runner:test -x :operator:integrationTest -x :ingest:integrationTest -x :api:integrationTest` — green.
- `:ingest:test` + `:ingest:integrationTest` — green, including the new `PushIngestEndToEndTest` (push and upload, parameterized).
- `:operator:test` (incl. 5 new `TenantReconciler` push-token tests) + `:operator:integrationTest` — green.
- `:api:test` + `:api:integrationTest` — green (existing `FabricPushTokenRepositoryTest`/`PushControllerTest` pass unchanged against the now-shared constants).
- `:paper-worldpush:test` — green, including the new `HttpPushNotifierTest`.
- `:runner:integrationTest` — **still red**, but not from this phase's work: `IngestRenderContractTest` was missing `APUS_BUNDLE_SOURCE_NAME` entirely (a required field since before phase 6; fixed as a drive-by) and, after that, fails a second, unrelated assertion — its hardcoded expected bucket-listing omits `level.dat`, which `BundleWriter` has included in every bundle for longer than this test's expectation has been stale. Pre-existing, unrelated to push/upload/tokens; left as a flagged concern rather than fixed under this task's scope.

All started Testcontainers (MinIO, k3s) were torn down by the test framework itself; no
containers were left running. No `isukuverlagcms-*` containers were touched.

## Concerns

- `runner:integrationTest`'s `IngestRenderContractTest` has a second, pre-existing failure
  (stale expected bucket listing vs. `BundleWriter`'s actual `level.dat` inclusion) unrelated to
  this phase — needs its own fix.
- Push-token RBAC: the working implementation still needs cluster-wide Secret read for the api
  ServiceAccount (see B above); the narrower `Tenant`-enumeration approach is documented but not
  built.
- No Kubernetes manifest/Helm/Kustomize directory exists anywhere in this repo — every RBAC
  requirement found this phase (this one, and the pre-existing one `FabricPushTokenRepository`
  already flagged) is documented in Javadoc only, with nothing to actually apply on a cluster.
- The deeper shape mismatch between how `paper-worldpush` stages data (many individual raw region
  files dropped incrementally under a prefix, no single "version" blob) and what
  `AbstractStagedSourceConnector.fetch()` expects to read (one object at `prefix + version.id()`,
  archive or raw) was not resolved — fixing the wire *request* makes the HTTP call succeed, but
  the ingest job it triggers would still try to `getObject` a single key that was never written
  this way. This is a real design gap between `paper-worldpush` and the `push` ingest connector,
  bigger than the auth/wire-format alignment this task asked for; flagged for a dedicated design
  pass rather than patched here.
