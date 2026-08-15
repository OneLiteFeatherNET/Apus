# Apus — Helm Charts: Design

**As of:** 2026-08-13
**Status:** Draft for approval

Apus is rolled out via two Helm charts that live in the Apus repository, are versioned
together with the code, and are published as OCI artifacts to Harbor. They replace the
Kustomize base that the phase 8 plan previously called for.

---

## 1. Starting point

Apus currently has **no** deployment description. The phase 8 plan calls for a Kustomize
base under `deploy/base`; none of that has been built. So there is nothing to migrate.

The cluster repository (`Kubernetes-FLUX`) has two established patterns side by side:

- **In-house charts** live under `helm/<name>` (`leantime`, `micronaut`, `outline`, `shlink`)
  and are referenced via `HelmRelease` with `sourceRef: GitRepository helmcharts`.
- **Third-party charts** arrive as an OCI artifact via `OCIRepository`, for example the
  kube-prometheus-stack from `ghcr.io` with
  `layerSelector.mediaType: application/vnd.cncf.helm.chart.content.v1.tar+gzip`.

The second pattern is the path Apus takes: from the cluster repository's point of view,
Apus is not an in-house manifest but a versioned product with its own release cycle.

The existing `helm/micronaut` chart (v0.5.2) contains Deployment, Service, Ingress,
HTTPRoute, ConfigMap, Secret, ServiceAccount, RBAC, HPA, PDB, and ServiceMonitor. It serves
as a **template** for structure, label conventions, and `values.yaml` layout — it cannot be
used as a dependency, because it is unpublished and lives in the cluster repository.

---

## 2. Decisions

| Question | Decision | Rationale |
| --- | --- | --- |
| Helm or Kustomize | **Helm replaces Kustomize** | Two parallel deployment descriptions for the same components drift apart; the cluster repository already works with Helm anyway |
| Split | **Two charts**: `apus-operator`, `apus-platform` | The dividing line sits exactly where the design already puts it: the operator is the core and works on its own (Spec §14, Phase 2 "already fully usable for internal use"); API and UI are the surface on top of it |
| Location | **Apus repository**, `deploy/charts/`, OCI to Harbor | Chart and code are versioned together; the chart-to-image pairing cannot drift apart |
| CRDs | **As templates** with `helm.sh/resource-policy: keep` | Helm's `crds/` directory is never updated on `helm upgrade`; Apus' CRDs are generated and change with every phase |
| Tenants | **Not in the chart** | Tenants are operational data, not installation data (Spec §14). A `helm uninstall` must not sweep them away |

---

## 3. What the charts roll out — and what they don't

Of the six components, Helm installs only three. That is not a gap; it follows the
architecture:

| Component | Path into the cluster |
| --- | --- |
| `operator` | `apus-operator` — Deployment, cluster-wide RBAC |
| the six CRDs | `apus-operator` — templates with `resource-policy: keep` |
| `api` | `apus-platform` — Deployment, Service, Ingress, ServiceMonitor |
| `ui` | `apus-platform` — Deployment, Service, Ingress |
| `runner` | **created by the operator** from `BlueMapRender` (Job) |
| `ingest` | **created by the operator** from `WorldIngest` (Job) |
| `hosting` | **created by the operator** from `BlueMapHosting` (Deployment + Service + Ingress) |

For the last three, Helm only passes through the image reference — they appear in
`apus-operator`'s `values.yaml` as `images.runner`, `images.ingest`, `images.hosting` and end
up as `APUS_RUNNER_IMAGE`/`APUS_INGEST_IMAGE`/`APUS_HOSTING_IMAGE` in the operator
deployment. A dedicated chart for `hosting` would be conceptually wrong: it would create a
web server that the operator would then create a second time.

---

## 4. Chart `apus-operator`

The minimum Apus needs to work. Anyone running purely on `kubectl` and Git installs only
this chart.

```text
deploy/charts/apus-operator/
  Chart.yaml
  values.yaml
  values.schema.json
  .helmignore
  README.md
  templates/
    _helpers.tpl
    crds.yaml            # the six CRDs, resource-policy: keep
    deployment.yaml
    serviceaccount.yaml
    rbac.yaml             # ClusterRole + ClusterRoleBinding
    service.yaml          # only the metrics port
    servicemonitor.yaml   # optional, .Values.metrics.serviceMonitor.enabled
    NOTES.txt
```

The `values.yaml` surface, organized by what an operator actually needs to decide:

```yaml
image:
  repository: harbor.onelitefeather.dev/apus/operator
  tag: ""            # empty => .Chart.AppVersion
  pullPolicy: IfNotPresent

# The images the operator uses for the workloads it creates itself.
# The default for each is the same version as the operator itself.
images:
  runner:
    repository: harbor.onelitefeather.dev/apus/runner
    tag: ""
  ingest:
    repository: harbor.onelitefeather.dev/apus/ingest
    tag: ""
  hosting:
    repository: harbor.onelitefeather.dev/apus/hosting
    tag: ""

# Rook/Ceph, from which the operator obtains buckets and tenant users (Spec §9.1).
rook:
  namespace: rook-ceph
  cephObjectStore: ceph-objectstore
  bucketStorageClass: ceph-bucket

# The platform-wide bundle bucket (Spec §5) -- an installation prerequisite, not content.
bundles:
  bucket: apus-bundles
  s3Endpoint: ""
  s3Region: us-east-1
  credentialsSecret: apus-bundle-credentials

metrics:
  enabled: true
  port: 8080
  serviceMonitor:
    enabled: false

resources: {}
nodeSelector: {}
tolerations: []
affinity: []
podSecurityContext: {}
securityContext: {}
```

**CRDs.** `templates/crds.yaml` is generated at chart-build time from `deploy/crds/` (the
generator output checked in during phase 8), each resource with

```yaml
metadata:
  annotations:
    helm.sh/resource-policy: keep
```

This means they get updated along with `helm upgrade`, but are kept on `helm uninstall` —
otherwise uninstalling the chart would delete every `Tenant`, `BlueMapMap`, and
`BlueMapHosting` resource in the cluster along with it.

A `crds.install: true` switch lets you turn them off if an organization manages CRDs
separately. The default is `true`.

**RBAC.** The ClusterRole is the one from the phase 8 plan (Task 2), unchanged in scope: the
project's own custom resources including status and finalizers; Namespaces/ResourceQuotas/
LimitRanges and NetworkPolicies for tenants; Jobs/Deployments/Services/ConfigMaps/Ingresses
for the created workloads; `pods` and `pods/log` read-only for progress tracking;
`objectbucketclaims` and `cephobjectstoreusers` for Rook; `secrets` **read-only**; `events`
writable.

---

## 5. Chart `apus-platform`

REST API and dashboard. Requires an installed `apus-operator` — the CRDs must exist before
the API can read them.

```text
deploy/charts/apus-platform/
  Chart.yaml
  values.yaml
  values.schema.json
  .helmignore
  README.md
  templates/
    _helpers.tpl
    api-deployment.yaml
    api-service.yaml
    api-rbac.yaml
    api-servicemonitor.yaml
    ui-deployment.yaml
    ui-service.yaml
    ingress.yaml
    NOTES.txt
```

The `api-rbac.yaml` carries the narrowed permission from phase 9: `secrets` only with
`resourceNames: ["apus-push-token"]` and `verbs: ["get"]`. If phase 9 has not yet been
implemented by the time this chart is built, it carries today's broader rule instead — with
a comment pointing to §15 item 9, so the narrowing does not get forgotten.

In addition to the usual image/resource/ingress blocks, `values.yaml` covers the identity
broker:

```yaml
auth:
  issuer: ""          # required value, without which the API will not start
  jwksUri: ""
  audience: apus
```

`issuer` deliberately has **no** default: a half-configured deployment must fail at startup,
not accept tokens unchecked. `values.schema.json` enforces this, so `helm install` without an
issuer aborts with an understandable message instead of a CrashLoop.

---

## 6. Versioning and publishing

Both charts are versioned together by Release Please, in the root track — the same version
in which the images are also produced. `Chart.yaml` gets one marker each:

```yaml
version: 0.2.1     # x-release-please-version
appVersion: "0.2.1" # x-release-please-version
```

and `release-please-config.json` gets one `extra-files` entry each in the root package. This
means: `apus-operator-0.3.0` references `apus/operator:0.3.0`, because `image.tag` stays
empty and falls back to `.Chart.AppVersion`. The pairing cannot drift apart.

Publishing follows the pattern used for the images, in the same `release-please.yml`, gated
on `root-released`:

```bash
helm package deploy/charts/apus-operator
helm push apus-operator-<version>.tgz oci://<harbor>/apus/charts
```

A central, reusable workflow for this does **not** exist in the OLF catalog — it only has
`docker-publish`, `gradle-*`, `markdown-lint`, `pr-lint`, `close-invalid-prs`, and
`release-please`. Apus therefore initially gets a job of its own within the repo. As soon as
a second OLF project publishes charts, it belongs as `helm-publish.yml` in the `workflows`
repository; the repo's own job is then replaced with that.

**Open, and to be resolved before the first chart push:** the image push to Harbor currently
fails with `empty challenge header` (registry authentication). As long as that is unresolved,
a chart push will fail too — both go to the same registry.

---

## 7. Integration into the cluster repository

Following the pattern already in use there for the kube-prometheus-stack:

```yaml
apiVersion: source.toolkit.fluxcd.io/v1
kind: OCIRepository
metadata:
  name: apus-operator
  namespace: flux-system
spec:
  interval: 5m
  layerSelector:
    mediaType: application/vnd.cncf.helm.chart.content.v1.tar+gzip
    operation: copy
  url: oci://harbor.onelitefeather.dev/apus/charts/apus-operator
  ref:
    semver: "=0.3.0"
```

plus one `HelmRelease` per chart under `apps/base/apus/`. The cluster-specific values
(registry host, Rook names, hostnames, issuer) live there in `values:` — not in the chart.
Renovate keeps the `semver` pins current, as it does for the other OCI sources.

---

## 8. Verification

| Level | Approach |
| --- | --- |
| Static | `helm lint` and `helm template` for both charts in the PR build; the rendered result through `kubectl apply --dry-run=client` |
| Schema | `helm template` without `auth.issuer` must **fail** — otherwise `values.schema.json` isn't taking effect |
| Value matrix | `helm template` with default values, with all switches on (`metrics.serviceMonitor`, `ingress`), and with `crds.install: false` |
| Installation | The k3s integration test from phase 8 Task 8 will install the chart instead of applying manifests individually — so the rollout path itself is tested, not just its result |
| Upgrade | `helm upgrade` from the previous chart version to the current one, in the same k3s test, to prove that the CRDs actually get updated along with it |

The upgrade test is the most important item in the table: it checks exactly the property
that is the reason CRDs live as templates instead of in the `crds/` directory.

---

## 9. Impact on the phase 8 plan

`docs/superpowers/plans/2026-08-12-phase-8-deployment-und-observability.md` will be adjusted:

- **Task 1 (check in CRDs)** stays unchanged — the charts consume `deploy/crds/`.
- **Tasks 2 and 3** (Kustomize base for operator, API, and UI) are replaced by the two
  charts.
- **Task 6 (scrape configuration)** partly moves into the charts: the `ServiceMonitor` for
  operator and API become templates. The `PodMonitor` for the render pods created by the
  operator stays separate, because it selects pods in tenant namespaces that no chart knows
  about.
- **Task 7 (dashboards)** stays, but moves into the `apus-platform` chart as an optional
  ConfigMap (`dashboards.enabled`).
- **Task 8 (k3s E2E)** will install the chart going forward.
- Tasks 4 and 5 (metrics in operator and API) are unaffected.

---

## 10. Non-goals

- **No chart for `runner`, `ingest`, or `hosting`.** They are created by the operator.
- **No tenants, sources, or maps in the chart.** Operational data, not installation data.
- **No umbrella chart** over both. Anyone who wants both installs two releases; an umbrella
  chart would introduce a third version that would need to be kept in sync with the other
  two.
- **No migration.** There is no existing Kustomize installation.

---

## 11. Open points

1. **Harbor authentication.** The image push failed with `empty challenge header` until
   `docker-publish.yml` switched to `regctl registry login --skip-check` — the anonymous
   connectivity ping before the actual push turned out to be the cause. The chart push goes
   to the same registry and now avoids the ping the same way: no `helm registry login`;
   instead, the credential file is written directly and passed to `helm push` via
   `--registry-config` (`release-please.yml`). **Not tested against the real registry** —
   locally, only that `helm push` with a hand-written credential file and no prior login
   succeeds against a registry using basic auth has been demonstrated. Whether Harbor is
   itself satisfied at push time will only be shown by the first release run.
2. **Harbor project for charts.** Whether `apus/charts` lives as a repository path within the
   existing `apus` project or gets its own Harbor project is an operational decision.
3. **Chart publishing in the central catalog.** Initially a repo-owned job; inclusion in
   `OneLiteFeatherNET/workflows` is due as soon as a second project publishes charts.
4. **`values.schema.json` scope.** Issuer and JWKS URI are set as required fields — both come
   from the environment in `application.yml` with no default, so an empty value would either
   let the API accept unchecked tokens or reject every token for lack of signing keys.
   Whether further values (Rook names, bundle bucket) should also be enforced depends on
   whether a sensible default exists.
5. **The dashboard is not configurable at all.** `ui/nuxt.config.ts` sets `oidcIssuer` and
   `oidcClientId` to `''`, `ui/Dockerfile` runs `pnpm generate` without build arguments, and
   only `.output/public` makes it into the nginx image. This freezes the empty OIDC values
   into the published image: `NUXT_PUBLIC_*` only takes effect at runtime with a Nitro
   server, which this image does not contain. Consequence: **no installation can log in**,
   regardless of what is in the chart — `apus-platform` deliberately only passes the values
   through to the API today; the UI deployment does not get them, because it could not read
   them. This is not a chart problem but a UI/build problem: the fix changes how the UI is
   built (build args plus `pnpm generate` per installation, or a runtime-loaded `config.json`
   next to `index.html`, or a Nitro server in the image after all). Only after that is there
   anything to wire up in the chart at all. This blocks any real deployment of the dashboard.
6. **No image pull secret for the workloads the operator creates.** The render and ingest
   jobs, as well as the hosting deployments the operator builds, carry neither an
   `imagePullSecrets` nor a service account — yet the charts default the associated images to
   a private Harbor project. On a cluster without node-wide registry credentials, every
   render job is therefore stuck in `ImagePullBackOff`, while the operator and API themselves
   run fine (the chart sets their pull secret). The fix belongs in the operator code (the
   resource builders in `render`, `ingest`, `hosting`), not in the charts; the charts can only
   support it by passing the name of the secret or service account through as a value to the
   operator configuration. `imagePullSecrets` in `values.yaml` today covers only the pods that
   the charts create themselves.
