# Apus Helm Charts: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apus can be rolled out with two `helm install` calls, and the chart version determines which image version gets used.

**Architecture:** Two charts under `deploy/charts/`: `apus-operator` (the six CRDs, the controller, cluster-wide RBAC) and `apus-platform` (API and dashboard). They are versioned together by Release Please in the root track and published as OCI artifacts to Harbor. `runner`, `ingest`, and `hosting` get no chart of their own — the operator creates them from custom resources; Helm only passes their image references through.

**Tech Stack:** Helm 4, Kubernetes, Prometheus Operator (`ServiceMonitor`), GitHub Actions, Release Please.

## Global Constraints

- **The design is in `docs/superpowers/specs/2026-08-13-helm-charts-design.md`.** Where this plan and the spec disagree, the spec wins; report the discrepancy.
- **The template is `helm/micronaut` in the cluster repository** (`OneLiteFeatherNET/Kubernetes-FLUX`, v0.5.2). Structure, label conventions, and `_helpers.tpl` layout are taken from there, so the charts feel familiar. It is **not** a dependency — it lives unpublished in a different repository.
- **Standard labels** following Kubernetes convention: `app.kubernetes.io/name`, `/instance`, `/version`, `/component`, `/part-of: apus`, `/managed-by: {{ .Release.Service }}`.
- **`image.tag` stays empty in every chart** and falls back to `.Chart.AppVersion`. A hardcoded tag in the chart would be exactly the drift this design is meant to prevent.
- **No `crds/` directory.** CRDs are templates with `helm.sh/resource-policy: keep`.
- **No tenants, sources, or maps** in the charts (Spec §14, Design §10).
- **Non-root:** Java containers run as uid 10001, the nginx-based UI container as uid 101 — matching the images built in phase 7.
- Conventional Commits, no Claude/AI attribution.
- `helm` (v4.2.2) and `kubectl` are available on the machine.

### What already exists

- Six container images from phase 7: `apus/operator`, `apus/api`, `apus/ui`, `apus/runner`, `apus/ingest`, `apus/hosting`.
- `OperatorConfig` reads: `APUS_ROOK_NAMESPACE`, `APUS_CEPH_OBJECT_STORE`, `APUS_BUCKET_STORAGE_CLASS`, `APUS_RUNNER_IMAGE`, `APUS_INGEST_IMAGE`, `APUS_HOSTING_IMAGE`, `APUS_BUNDLE_BUCKET`, `APUS_BUNDLE_S3_ENDPOINT`, `APUS_BUNDLE_S3_REGION`, `APUS_BUNDLE_CREDENTIALS_SECRET`.
- `.github/workflows/release-please.yml` with the outputs `release_created`/`version` (root, **without** a prefix) and `telemetry-addon--release_created`/`paper-worldpush--release_created`.
- **Not yet present:** `deploy/crds/` — phase 8 Task 1 creates it. Task 2 of this plan creates it itself if needed; see there.

---

### Task 1: Scaffolding for `apus-operator`

**Files:**

- Create: `deploy/charts/apus-operator/Chart.yaml`
- Create: `deploy/charts/apus-operator/values.yaml`
- Create: `deploy/charts/apus-operator/.helmignore`
- Create: `deploy/charts/apus-operator/templates/_helpers.tpl`

**Interfaces:**

- Produces: the helpers `apus-operator.name`, `apus-operator.fullname`, `apus-operator.labels`, `apus-operator.selectorLabels`, `apus-operator.serviceAccountName`, `apus-operator.image`. Every later task on this chart uses them.

- [ ] **Step 1: Read the template**

```bash
gh api repos/OneLiteFeatherNET/Kubernetes-FLUX/contents/helm/micronaut/templates/_helpers.tpl --jq '.content' | base64 -d
gh api repos/OneLiteFeatherNET/Kubernetes-FLUX/contents/helm/micronaut/.helmignore --jq '.content' | base64 -d
```

Take over the structure, not the content verbatim — the names carry `apus-operator` instead of `micronaut`.

- [ ] **Step 2: `Chart.yaml`**

```yaml
apiVersion: v2
name: apus-operator
description: The Apus operator and its custom resource definitions — renders Minecraft worlds with BlueMap on Kubernetes
type: application
# Both markers are rewritten by release-please in the root track, so the chart
# version and the images it deploys always come from the same release.
version: "0.0.0" # x-release-please-version
appVersion: "0.0.0" # x-release-please-version
home: https://github.com/OneLiteFeatherNET/Apus
sources:
  - https://github.com/OneLiteFeatherNET/Apus
maintainers:
  - name: OneLiteFeather
    url: https://onelitefeather.net
keywords:
  - minecraft
  - bluemap
  - operator
```

`"0.0.0"` is the bootstrap value; Task 9 sets it to the current release version and adds the markers to `release-please-config.json`.

- [ ] **Step 3: `values.yaml`**

Exactly the surface from the design, §4:

```yaml
image:
  repository: harbor.onelitefeather.dev/apus/operator
  # Empty on purpose: falls back to .Chart.AppVersion so the chart version and the
  # image version cannot drift apart. Override only to pin a hotfix image.
  tag: ""
  pullPolicy: IfNotPresent

imagePullSecrets: []
nameOverride: ""
fullnameOverride: ""

# The images the operator uses for the workloads it creates itself (renders, ingests,
# hosting). They are not deployed by this chart -- the operator builds Jobs and
# Deployments from custom resources and needs to know which image to reference.
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

crds:
  # The six CRDs ship as templates so that `helm upgrade` actually updates them.
  # Set to false only if your organisation manages CRDs separately.
  install: true

rook:
  namespace: rook-ceph
  cephObjectStore: ceph-objectstore
  bucketStorageClass: ceph-bucket

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
    interval: 30s
    labels: {}

serviceAccount:
  create: true
  name: ""
  annotations: {}

rbac:
  create: true

replicaCount: 1

podAnnotations: {}
podLabels: {}

podSecurityContext:
  runAsNonRoot: true
  runAsUser: 10001
  seccompProfile:
    type: RuntimeDefault

securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: ["ALL"]

resources:
  requests:
    cpu: 100m
    memory: 256Mi
  limits:
    memory: 512Mi

nodeSelector: {}
tolerations: []
affinity: {}
```

- [ ] **Step 4: `_helpers.tpl`**

```gotemplate
{{- define "apus-operator.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "apus-operator.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "apus-operator.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "apus-operator.labels" -}}
helm.sh/chart: {{ include "apus-operator.chart" . }}
{{ include "apus-operator.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: apus
{{- end }}

{{- define "apus-operator.selectorLabels" -}}
app.kubernetes.io/name: {{ include "apus-operator.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "apus-operator.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "apus-operator.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Resolves an image reference, defaulting the tag to the chart's appVersion.
Usage: {{ include "apus-operator.image" (dict "image" .Values.image "ctx" .) }}
*/}}
{{- define "apus-operator.image" -}}
{{- $tag := .image.tag | default .ctx.Chart.AppVersion -}}
{{- printf "%s:%s" .image.repository $tag -}}
{{- end }}
```

- [ ] **Step 5: `helm lint` passes**

Run: `helm lint deploy/charts/apus-operator`
Expected: `1 chart(s) linted, 0 chart(s) failed`. A chart without templates is allowed; the warning about missing templates is fine, an error is not.

- [ ] **Step 6: The image helper does what it should**

```bash
helm template t deploy/charts/apus-operator --show-only templates/_helpers.tpl 2>/dev/null || true
```

`_helpers.tpl` does not render anything on its own — the actual check follows in Task 3, once the deployment uses the helper. Note this in the report instead of manufacturing fake proof.

- [ ] **Step 7: Commit**

```bash
git add deploy/charts/apus-operator
git commit -m "feat(helm): scaffold the apus-operator chart"
```

---

### Task 2: CRDs as templates

**Files:**

- Create: `deploy/charts/apus-operator/templates/crds.yaml`
- Modify: `operator/build.gradle.kts` — only if `deploy/crds/` does not exist yet, see step 1

**Interfaces:**

- Consumes: `deploy/crds/*.yaml`, the generated CRD definitions.
- Produces: the six CRDs as chart resources with `helm.sh/resource-policy: keep`.

- [ ] **Step 1: Check whether the CRDs are checked in**

Run: `ls deploy/crds/*.yaml 2>/dev/null | wc -l`

- Result `6`: continue with step 2.
- Result `0`: phase 8 Task 1 has not run yet. Catch up on it here, but **only** the part this task needs:

```bash
./gradlew :operator:generateCrds
mkdir -p deploy/crds
cp operator/build/crds/*.yaml deploy/crds/
```

Note in the report that you did this — phase 8 Task 1 additionally sets up the `syncCrds` task and `CrdsInSyncTest`, which is deliberately **not** duplicated here.

- [ ] **Step 2: Determine the actual file names**

Run: `ls deploy/crds/`
Expected: six files. Note the exact names — step 3 lists them by name, without a glob, so a renamed CRD stands out instead of silently vanishing.

- [ ] **Step 3: `templates/crds.yaml`**

```gotemplate
{{- if .Values.crds.install }}
{{- /*
The CRDs ship as templates rather than in Helm's crds/ directory on purpose: Helm
installs that directory once and never touches it again, so `helm upgrade` would
silently leave an old schema in place while the new operator reads fields it does
not know. resource-policy: keep makes uninstall keep them, so removing the chart
does not delete every Tenant, BlueMapMap and BlueMapHosting in the cluster.
*/ -}}
{{- range $path, $_ := .Files.Glob "crds/*.yaml" }}
{{- $crd := $.Files.Get $path | fromYaml }}
---
{{ $.Files.Get $path | trim }}
{{- end }}
{{- end }}
```

**Careful:** `.Files.Glob` only reads files **inside** the chart directory. `deploy/crds/` lives outside it. Solve it like this:

The chart gets its own `crds/` directory (not Helm's special top-level directory, but a plain data directory) that gets populated from `deploy/crds/` at build time. Because Helm reserves the name `crds/` at the chart level, use **`files/crds/`** instead:

```gotemplate
{{- if .Values.crds.install }}
{{- range $path, $_ := .Files.Glob "files/crds/*.yaml" }}
---
{{ $.Files.Get $path | trim }}
{{- end }}
{{- end }}
```

and add the annotation to every file. Because the generated CRDs don't come with it, a small script patches it in during copying — see step 4.

- [ ] **Step 4: Copy-and-patch script**

`deploy/charts/apus-operator/sync-crds.sh`:

```bash
#!/usr/bin/env bash
# Copies the generated CRDs into the chart and annotates them so that `helm uninstall`
# keeps them. Run after ./gradlew :operator:generateCrds whenever a CRD changes.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
src="$root/deploy/crds"
dst="$(dirname "$0")/files/crds"

mkdir -p "$dst"
rm -f "$dst"/*.yaml

for f in "$src"/*.yaml; do
  name="$(basename "$f")"
  # yq is not a dependency of this repo; the annotation is inserted with awk so the
  # script needs nothing beyond coreutils.
  awk '
    /^metadata:/ && !done {
      print
      print "  annotations:"
      print "    helm.sh/resource-policy: keep"
      done = 1
      next
    }
    { print }
  ' "$f" > "$dst/$name"
done

echo "copied $(ls -1 "$dst"/*.yaml | wc -l) CRDs into the chart"
```

- [ ] **Step 5: Run the script and check the result**

Run: `chmod +x deploy/charts/apus-operator/sync-crds.sh && deploy/charts/apus-operator/sync-crds.sh`
Expected: `copied 6 CRDs into the chart`

Run: `grep -c 'helm.sh/resource-policy: keep' deploy/charts/apus-operator/files/crds/*.yaml`
Expected: each of the six files reports `1`. If one reports `0`, the `awk` pattern didn't match — then `metadata:` isn't at the start of the line there, and the script needs to be adjusted rather than the file edited by hand.

- [ ] **Step 6: Render and check**

Run: `helm template t deploy/charts/apus-operator | grep -c 'kind: CustomResourceDefinition'`
Expected: `6`

Run: `helm template t deploy/charts/apus-operator --set crds.install=false | grep -c 'kind: CustomResourceDefinition' || echo 0`
Expected: `0` — the switch takes effect.

Run: `helm template t deploy/charts/apus-operator | kubectl apply --dry-run=client -f - 2>&1 | grep -c 'created (dry run)'`
Expected: at least `6` — the rendered CRDs are valid Kubernetes objects.

- [ ] **Step 7: Commit**

```bash
git add deploy/charts/apus-operator
git commit -m "feat(helm): ship the CRDs as templates that upgrade cleanly"
```

---

### Task 3: Operator deployment, service account, and RBAC

**Files:**

- Create: `deploy/charts/apus-operator/templates/serviceaccount.yaml`
- Create: `deploy/charts/apus-operator/templates/rbac.yaml`
- Create: `deploy/charts/apus-operator/templates/deployment.yaml`

**Interfaces:**

- Consumes: the helpers from Task 1, the environment variables from `OperatorConfig`.

- [ ] **Step 1: Derive the actually required permissions from the code**

Run: `grep -rhoE '\b(Job|Deployment|Service|Ingress|ConfigMap|Secret|Namespace|ResourceQuota|LimitRange|NetworkPolicy|ObjectBucketClaim|CephObjectStoreUser|Pod|Event)\b' operator/src/main/java --include='*.java' | sort -u`

Every type in the output needs a rule. If one is missing, it shows up at runtime as `Forbidden` in the middle of a reconciliation — not at startup.

- [ ] **Step 2: ServiceAccount**

```gotemplate
{{- if .Values.serviceAccount.create }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "apus-operator.serviceAccountName" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "apus-operator.labels" . | nindent 4 }}
  {{- with .Values.serviceAccount.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end }}
```

- [ ] **Step 3: RBAC**

`templates/rbac.yaml`, wrapped in `{{- if .Values.rbac.create }}`. The rule set is the one from the phase 8 plan, Task 2, step 2 — take it over in full: the project's own custom resources including `/status` and `/finalizers`; `namespaces`, `resourcequotas`, `limitranges`; `networkpolicies`; `jobs`; `deployments`; `services`, `configmaps`; `ingresses`; `pods` and `pods/log` **read-only**; `objectbucketclaims`; `cephobjectstoreusers`; `secrets` **only `get`/`list`/`watch`**; `events` with `create`/`patch`.

Names: `{{ include "apus-operator.fullname" . }}` for ClusterRole and ClusterRoleBinding, so that two releases in the same cluster don't collide.

- [ ] **Step 4: Deployment**

```gotemplate
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "apus-operator.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "apus-operator.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  strategy:
    # The operator holds a lease-free single-writer position: two instances would
    # reconcile the same resources concurrently. Recreate, never RollingUpdate.
    type: Recreate
  selector:
    matchLabels:
      {{- include "apus-operator.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "apus-operator.labels" . | nindent 8 }}
        {{- with .Values.podLabels }}{{- toYaml . | nindent 8 }}{{- end }}
      {{- with .Values.podAnnotations }}
      annotations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
    spec:
      serviceAccountName: {{ include "apus-operator.serviceAccountName" . }}
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      securityContext:
        {{- toYaml .Values.podSecurityContext | nindent 8 }}
      containers:
        - name: operator
          image: {{ include "apus-operator.image" (dict "image" .Values.image "ctx" .) }}
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          securityContext:
            {{- toYaml .Values.securityContext | nindent 12 }}
          {{- if .Values.metrics.enabled }}
          ports:
            - name: metrics
              containerPort: {{ .Values.metrics.port }}
              protocol: TCP
          {{- end }}
          env:
            - name: APUS_ROOK_NAMESPACE
              value: {{ .Values.rook.namespace | quote }}
            - name: APUS_CEPH_OBJECT_STORE
              value: {{ .Values.rook.cephObjectStore | quote }}
            - name: APUS_BUCKET_STORAGE_CLASS
              value: {{ .Values.rook.bucketStorageClass | quote }}
            - name: APUS_RUNNER_IMAGE
              value: {{ include "apus-operator.image" (dict "image" .Values.images.runner "ctx" .) | quote }}
            - name: APUS_INGEST_IMAGE
              value: {{ include "apus-operator.image" (dict "image" .Values.images.ingest "ctx" .) | quote }}
            - name: APUS_HOSTING_IMAGE
              value: {{ include "apus-operator.image" (dict "image" .Values.images.hosting "ctx" .) | quote }}
            - name: APUS_BUNDLE_BUCKET
              value: {{ .Values.bundles.bucket | quote }}
            - name: APUS_BUNDLE_S3_ENDPOINT
              value: {{ .Values.bundles.s3Endpoint | quote }}
            - name: APUS_BUNDLE_S3_REGION
              value: {{ .Values.bundles.s3Region | quote }}
            - name: APUS_BUNDLE_CREDENTIALS_SECRET
              value: {{ .Values.bundles.credentialsSecret | quote }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          volumeMounts:
            # readOnlyRootFilesystem is on; the JVM still needs a writable temp dir.
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
      {{- with .Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
```

- [ ] **Step 5: Cross-check the environment variables against `OperatorConfig`**

Run: `grep -oE 'APUS_[A-Z_]+' operator/src/main/java/net/onelitefeather/apus/operator/OperatorConfig.java | sort -u`

Compare with the ten variables in the deployment. A variable read by the code but missing from the chart silently gets its default — which is exactly what the chart is meant to prevent. A variable set in the chart but unknown to the code is dead weight.

- [ ] **Step 6: Render and check**

Run: `helm template t deploy/charts/apus-operator | kubectl apply --dry-run=client -f - 2>&1 | tail -5`
Expected: no errors.

Run: `helm template t deploy/charts/apus-operator --set image.tag="" | grep 'image:'`
Expected: all four image references carry `appVersion` as the tag, neither `:` alone nor `latest`.

Run: `helm template t deploy/charts/apus-operator --set images.runner.tag=1.2.3 | grep APUS_RUNNER_IMAGE -A1`
Expected: `...apus/runner:1.2.3` — the override takes effect without affecting the others.

- [ ] **Step 7: Commit**

```bash
git add deploy/charts/apus-operator
git commit -m "feat(helm): deploy the operator with its service account and RBAC"
```

---

### Task 4: Metrics service, ServiceMonitor, NOTES, and schema

**Files:**

- Create: `deploy/charts/apus-operator/templates/service.yaml`
- Create: `deploy/charts/apus-operator/templates/servicemonitor.yaml`
- Create: `deploy/charts/apus-operator/templates/NOTES.txt`
- Create: `deploy/charts/apus-operator/values.schema.json`
- Create: `deploy/charts/apus-operator/README.md`

- [ ] **Step 1: Service and ServiceMonitor**

Both wrapped in `{{- if .Values.metrics.enabled }}` and, additionally, `.Values.metrics.serviceMonitor.enabled`. The service is `ClusterIP` with the single `metrics` port; the ServiceMonitor selects on `apus-operator.selectorLabels` and scrapes path `/metrics` at the interval from the values.

**Note:** The operator only exports its metrics after phase 8 Task 4. Until then the endpoint returns nothing — that's why the ServiceMonitor defaults to `false`. Say so in the comment above the template, so nobody enables it and then wonders about empty panels.

- [ ] **Step 2: `values.schema.json`**

Only enforce what won't work without a sensible default:

```json
{
  "$schema": "https://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["image", "images", "rook", "bundles"],
  "properties": {
    "image": {
      "type": "object",
      "required": ["repository"],
      "properties": {
        "repository": { "type": "string", "minLength": 1 },
        "tag": { "type": "string" },
        "pullPolicy": { "enum": ["Always", "IfNotPresent", "Never"] }
      }
    },
    "crds": {
      "type": "object",
      "properties": { "install": { "type": "boolean" } }
    },
    "bundles": {
      "type": "object",
      "required": ["bucket", "s3Endpoint"],
      "properties": {
        "bucket": { "type": "string", "minLength": 1 },
        "s3Endpoint": {
          "type": "string",
          "minLength": 1,
          "description": "S3 endpoint of the bundle bucket. No default exists -- a wrong or empty endpoint makes every ingest fail at runtime instead of at install time."
        }
      }
    },
    "replicaCount": { "type": "integer", "minimum": 1, "maximum": 1 }
  }
}
```

`replicaCount` is limited to exactly `1`: two operator instances would reconcile the same resources at the same time.

- [ ] **Step 3: Prove the schema actually takes effect**

Run: `helm template t deploy/charts/apus-operator --set bundles.s3Endpoint="" 2>&1 | tail -3`
Expected: an ERROR naming `s3Endpoint`. If it goes through, the schema has no effect and the task is not done.

Run: `helm template t deploy/charts/apus-operator --set replicaCount=2 2>&1 | tail -3`
Expected: an ERROR because of `maximum`.

Run: `helm template t deploy/charts/apus-operator --set bundles.s3Endpoint=http://rook-ceph-rgw.rook-ceph.svc >/dev/null && echo OK`
Expected: `OK`

- [ ] **Step 4: `NOTES.txt` and `README.md`**

`NOTES.txt` tells the operator, right after installation, what to do next: that no tenant exists yet and how to create one (`kubectl apply` with a minimal `Tenant`), and that `apus-platform` adds the UI on top.

`README.md` documents the values table. Generate it, don't write it from memory by hand, from `values.yaml`, so it's complete.

- [ ] **Step 5: `helm lint` with values**

Run: `helm lint deploy/charts/apus-operator --set bundles.s3Endpoint=http://example`
Expected: 0 failed.

- [ ] **Step 6: Commit**

```bash
git add deploy/charts/apus-operator
git commit -m "feat(helm): add metrics wiring, values schema and operator chart docs"
```

---

### Task 5: Chart `apus-platform` — scaffolding and API

**Files:**

- Create: `deploy/charts/apus-platform/Chart.yaml`
- Create: `deploy/charts/apus-platform/values.yaml`
- Create: `deploy/charts/apus-platform/.helmignore`
- Create: `deploy/charts/apus-platform/templates/_helpers.tpl`
- Create: `deploy/charts/apus-platform/templates/api-deployment.yaml`
- Create: `deploy/charts/apus-platform/templates/api-service.yaml`
- Create: `deploy/charts/apus-platform/templates/api-rbac.yaml`

**Interfaces:**

- Produces: helpers analogous to Task 1, but with a component suffix: `apus-platform.api.fullname`, `apus-platform.ui.fullname`, `apus-platform.labels`, `apus-platform.componentLabels` (takes the component name as an argument).

- [ ] **Step 1: Scaffolding analogous to Task 1**

`Chart.yaml` as there, name `apus-platform`, description "The Apus REST API and dashboard". Both version markers set to `"0.0.0"`.

The helpers need an extension, because this chart contains **two** workloads:

```gotemplate
{{- define "apus-platform.componentLabels" -}}
{{- $ctx := .ctx -}}
helm.sh/chart: {{ include "apus-platform.chart" $ctx }}
app.kubernetes.io/name: {{ include "apus-platform.name" $ctx }}
app.kubernetes.io/instance: {{ $ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
app.kubernetes.io/version: {{ $ctx.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ $ctx.Release.Service }}
app.kubernetes.io/part-of: apus
{{- end }}

{{- define "apus-platform.componentSelectorLabels" -}}
app.kubernetes.io/name: {{ include "apus-platform.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}
```

Without `component` in the selector, the API and UI deployments would steal each other's pods — both would have the same selector.

- [ ] **Step 2: `values.yaml`**

Two blocks, `api:` and `ui:`, each with `image`, `replicaCount`, `resources`, `podSecurityContext`, `securityContext`, plus the shared `ingress:` and `auth:`:

```yaml
auth:
  # No default on purpose. The API validates JWTs against this issuer; an empty value
  # must fail the install rather than let the API start and accept unvalidated tokens.
  issuer: ""
  jwksUri: ""
  audience: apus

api:
  image:
    repository: harbor.onelitefeather.dev/apus/api
    tag: ""
    pullPolicy: IfNotPresent
  replicaCount: 1
  podSecurityContext:
    runAsNonRoot: true
    runAsUser: 10001
    seccompProfile:
      type: RuntimeDefault
  securityContext:
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    capabilities:
      drop: ["ALL"]
  resources:
    requests:
      cpu: 200m
      memory: 512Mi
    limits:
      memory: 1Gi
  metrics:
    serviceMonitor:
      enabled: false

ui:
  image:
    repository: harbor.onelitefeather.dev/apus/ui
    tag: ""
    pullPolicy: IfNotPresent
  replicaCount: 2
  podSecurityContext:
    runAsNonRoot: true
    # The unprivileged nginx image runs as uid 101, not 10001 like the Java images.
    runAsUser: 101
    seccompProfile:
      type: RuntimeDefault
  securityContext:
    allowPrivilegeEscalation: false
    # nginx writes its cache and pid below /tmp and /var/cache; not read-only.
    readOnlyRootFilesystem: false
    capabilities:
      drop: ["ALL"]
  resources:
    requests:
      cpu: 50m
      memory: 64Mi
    limits:
      memory: 128Mi

ingress:
  enabled: false
  className: nginx
  annotations: {}
  host: ""
  tls:
    enabled: false
    secretName: ""
    issuerRef:
      name: ""
      kind: ClusterIssuer
```

- [ ] **Step 3: API deployment**

Like the operator deployment, but with `strategy: RollingUpdate` (the API is stateless and may run in parallel), port 8080, the auth environment variables, and probes:

```yaml
          readinessProbe:
            httpGet:
              path: /health/readiness
              port: http
            initialDelaySeconds: 10
          livenessProbe:
            httpGet:
              path: /health/liveness
              port: http
            initialDelaySeconds: 30
```

- [ ] **Step 4: Check that the health endpoints exist**

Run: `grep -rn 'micronaut-management' api/build.gradle.kts; grep -rn -A3 'endpoints:' api/src/main/resources/application.yml`

If `micronaut-management` is missing or `/health` is not enabled, the probes hit nothing and the pod restarts forever. If that's the case: **drop** the probes, note it in the report, and point to phase 8 Task 5, which introduces the dependency. Don't guess.

- [ ] **Step 5: API RBAC**

ClusterRole with the custom resources and the secret rule. First check which of the two versions applies:

Run: `grep -n 'resolveNamespace' -A20 api/src/main/java/net/onelitefeather/apus/api/rest/push/FabricPushTokenRepository.java | head -30`

- If the code still looks up by label across all namespaces: the broad rule (`secrets`, `get`/`list`) **with** a comment pointing to Spec §15 item 9 and phase 9 Task 2.
- If it enumerates tenants and reads a secret with a fixed name: the narrowed rule with `resourceNames: ["apus-push-token"]`, `verbs: ["get"]`.

- [ ] **Step 6: Render and check**

Run: `helm template t deploy/charts/apus-platform --set auth.issuer=https://id.example.net | kubectl apply --dry-run=client -f - 2>&1 | tail -3`
Expected: no errors.

Run: `helm template t deploy/charts/apus-platform --set auth.issuer=https://id.example.net | grep -A3 'matchLabels'`
Expected: the selector contains `app.kubernetes.io/component`.

- [ ] **Step 7: Commit**

```bash
git add deploy/charts/apus-platform
git commit -m "feat(helm): add the apus-platform chart with the API deployment"
```

---

### Task 6: UI, ingress, schema, and docs for `apus-platform`

**Files:**

- Create: `deploy/charts/apus-platform/templates/ui-deployment.yaml`
- Create: `deploy/charts/apus-platform/templates/ui-service.yaml`
- Create: `deploy/charts/apus-platform/templates/api-servicemonitor.yaml`
- Create: `deploy/charts/apus-platform/templates/ingress.yaml`
- Create: `deploy/charts/apus-platform/templates/NOTES.txt`
- Create: `deploy/charts/apus-platform/values.schema.json`
- Create: `deploy/charts/apus-platform/README.md`

- [ ] **Step 1: UI deployment and service**

Port 8080 (the unprivileged nginx base listens there), `runAsUser: 101`, `readOnlyRootFilesystem: false`. Readiness probe on `/` — the UI is static, a 200 at the root is a sufficient signal.

- [ ] **Step 2: Ingress**

One host, two paths: `/api` to the API service, `/` to the UI service. `pathType: Prefix`. Mind the order — `/api` must come before `/`, otherwise the catch-all swallows the API.

TLS via `cert-manager` when `ingress.tls.enabled`; then the `cert-manager.io/cluster-issuer` annotation from `issuerRef`.

- [ ] **Step 3: `values.schema.json` with the required issuer**

```json
{
  "$schema": "https://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["auth", "api", "ui"],
  "properties": {
    "auth": {
      "type": "object",
      "required": ["issuer"],
      "properties": {
        "issuer": {
          "type": "string",
          "minLength": 1,
          "format": "uri",
          "description": "OIDC issuer the API validates tokens against. Deliberately has no default: an unset issuer must fail the install, never start an API that accepts unvalidated tokens."
        }
      }
    }
  }
}
```

- [ ] **Step 4: Prove the issuer is enforced**

Run: `helm template t deploy/charts/apus-platform 2>&1 | tail -3`
Expected: an ERROR naming `issuer`. **If this goes through, the most important security aspect of this chart has no effect** — then the task is not done.

Run: `helm template t deploy/charts/apus-platform --set auth.issuer=https://id.example.net >/dev/null && echo OK`
Expected: `OK`

- [ ] **Step 5: Check ingress ordering**

Run: `helm template t deploy/charts/apus-platform --set auth.issuer=https://id.example.net --set ingress.enabled=true --set ingress.host=apus.example.net | grep -A2 'paths:'`
Expected: `/api` appears before `/`.

- [ ] **Step 6: Commit**

```bash
git add deploy/charts/apus-platform
git commit -m "feat(helm): add the dashboard, ingress and values schema to apus-platform"
```

---

### Task 7: Versioning and publishing

**Files:**

- Modify: `release-please-config.json`
- Modify: `deploy/charts/apus-operator/Chart.yaml` (bootstrap version)
- Modify: `deploy/charts/apus-platform/Chart.yaml` (bootstrap version)
- Modify: `.github/workflows/release-please.yml`

**Interfaces:**

- Consumes: the outputs `release_created` and `version` of the `release-please` job. **Without** the `.--` prefix — the root package is the exception to the prefix rule.

- [ ] **Step 1: Determine the current version**

Run: `python3 -c "import json;print(json.load(open('.release-please-manifest.json'))['.'])"`

Enter this value as `version` and `appVersion` in both `Chart.yaml` files, instead of leaving `"0.0.0"` in place — otherwise Release Please would bump from a version that never existed.

- [ ] **Step 2: Add `extra-files`**

In the root package of `release-please-config.json`:

```json
"extra-files": [
  { "type": "generic", "path": "build.gradle.kts" },
  { "type": "generic", "path": "deploy/charts/apus-operator/Chart.yaml" },
  { "type": "generic", "path": "deploy/charts/apus-platform/Chart.yaml" }
]
```

For the root package (`.`), the paths are **not** prefixed with the package path; they are repo-relative. For the two component packages that would be different — here it is correct as is.

- [ ] **Step 3: Attach the publish job**

```yaml
  publish-charts:
    needs: [release-please, publish-ui]
    # Last link of the publish chain (see the concurrency note above). Charts go to the
    # same registry as the images, so they share its serialisation constraint.
    if: ${{ !cancelled() && needs.release-please.outputs.root-released == 'true' }}
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v5
      - uses: azure/setup-helm@v4
      - name: Package and push charts
        env:
          HARBOR_REGISTRY: ${{ secrets.HARBOR_REGISTRY }}
          HARBOR_USERNAME: ${{ secrets.HARBOR_USERNAME }}
          HARBOR_PASSWORD: ${{ secrets.HARBOR_PASSWORD }}
          VERSION: ${{ needs.release-please.outputs.root-version }}
        run: |
          printf '%s' "${HARBOR_PASSWORD}" | \
            helm registry login "${HARBOR_REGISTRY}" -u "${HARBOR_USERNAME}" --password-stdin
          for chart in apus-operator apus-platform; do
            helm package "deploy/charts/${chart}"
            helm push "${chart}-${VERSION}.tgz" "oci://${HARBOR_REGISTRY}/apus/charts"
          done
```

- [ ] **Step 4: Validate YAML and JSON**

Run: `python3 -c "import yaml,json; yaml.safe_load(open('.github/workflows/release-please.yml')); json.load(open('release-please-config.json')); print('ok')"`
Expected: `ok`

- [ ] **Step 5: Prove packaging locally**

Run: `helm package deploy/charts/apus-operator -d /tmp && helm package deploy/charts/apus-platform -d /tmp && ls -la /tmp/apus-*.tgz`
Expected: two archives whose file names carry the version from step 1.

Run: `helm show chart /tmp/apus-operator-*.tgz | grep -E '^(version|appVersion)'`
Expected: both equal to the version from step 1.

**The push itself is not to be tested here.** The registry currently also rejects image pushes (`empty challenge header`, see Design §11 item 1). Note this in the report; a failed push attempt is not a failure of this task.

- [ ] **Step 6: Commit**

```bash
git add release-please-config.json deploy/charts .github/workflows/release-please.yml
git commit -m "feat(helm): version the charts with the release and publish them to Harbor"
```

---

### Task 8: Check the charts in the PR build

**Files:**

- Modify: `.github/workflows/build-pr.yml`

- [ ] **Step 1: Add the job**

```yaml
  helm:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: azure/setup-helm@v4
      - name: Lint charts
        run: |
          helm lint deploy/charts/apus-operator --set bundles.s3Endpoint=http://example
          helm lint deploy/charts/apus-platform --set auth.issuer=https://id.example.net
      - name: Render charts
        run: |
          helm template t deploy/charts/apus-operator --set bundles.s3Endpoint=http://example > /tmp/operator.yaml
          helm template t deploy/charts/apus-platform --set auth.issuer=https://id.example.net > /tmp/platform.yaml
      - name: The values schema actually rejects missing required values
        run: |
          # A schema that never rejects anything is worse than none: it looks like a guard.
          if helm template t deploy/charts/apus-platform >/dev/null 2>&1; then
            echo "values.schema.json did not reject a missing auth.issuer" >&2
            exit 1
          fi
          if helm template t deploy/charts/apus-operator --set bundles.s3Endpoint="" >/dev/null 2>&1; then
            echo "values.schema.json did not reject an empty bundles.s3Endpoint" >&2
            exit 1
          fi
      - name: Validate against the Kubernetes API schema
        run: |
          kubectl apply --dry-run=client -f /tmp/operator.yaml
          kubectl apply --dry-run=client -f /tmp/platform.yaml
```

- [ ] **Step 2: Extend the path filter**

The `code` filter of the Gradle job stays untouched. The new `helm` job needs no filter — it runs in seconds.

- [ ] **Step 3: Reproduce the schema counter-check locally**

Run: `helm template t deploy/charts/apus-platform >/dev/null 2>&1; echo "exit=$?"`
Expected: `exit=1` — exactly the condition the CI step checks for.

- [ ] **Step 4: Validate YAML and commit**

Run: `python3 -c "import yaml;yaml.safe_load(open('.github/workflows/build-pr.yml'));print('ok')"`

```bash
git add .github/workflows/build-pr.yml
git commit -m "ci: lint, render and schema-check the Helm charts on pull requests"
```

---

### Task 9: Follow up in the phase 8 plan and the design spec

**Files:**

- Modify: `docs/superpowers/plans/2026-08-12-phase-8-deployment-und-observability.md`
- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Step 1: Adjust the phase 8 plan**

Per Design §9:

- **Task 1** (check in CRDs) stays word for word — the charts consume `deploy/crds/`.
- **Tasks 2 and 3** (Kustomize base for operator, API, UI) are replaced with a reference to `docs/superpowers/plans/2026-08-13-helm-charts.md`. Delete the task content, replace it with a short paragraph explaining that Helm has replaced the Kustomize approach and where the work now stands. Do **not** renumber the remaining tasks — that would break every cross-reference.
- **Task 6** (scrape configuration): the two `ServiceMonitor` resources are now chart templates. The `PodMonitor` for render pods stays a standalone task, because it selects pods in tenant namespaces that no chart knows about.
- **Task 7** (dashboards): the ConfigMap moves into the `apus-platform` chart as an optional `dashboards.enabled` resource.
- **Task 8** (k3s E2E): will install the charts instead of individual manifests going forward; add a step that verifies `helm upgrade` from the previous chart version to the current one, because that's what proves the property the CRDs are templates for.
- **Global Constraints** of the phase 8 plan: the sentence about the Kustomize base gets rewritten for Helm.

- [ ] **Step 2: Add to design spec §0**

A paragraph stating that Apus is rolled out via two Helm charts, with a reference to
`docs/superpowers/specs/2026-08-13-helm-charts-design.md`. No repetition of the details.

- [ ] **Step 3: Markdown lint**

Run: `npx markdownlint-cli2 docs/superpowers/plans/2026-08-12-phase-8-deployment-und-observability.md docs/superpowers/specs/2026-08-08-apus-design.md`
Expected: 0 issues.

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "docs: replace the Kustomize tasks in the phase 8 plan with the Helm charts"
```

---

## What this plan does not cover

- **Integration into the cluster repository** (`OCIRepository` plus `HelmRelease` under `apps/base/apus/`). That belongs in the cluster repository, not here, and requires the charts to have been published once. Design §7 describes the target state.
- **Harbor authentication.** The chart push will fail as long as the image push fails with `empty challenge header`. That's an operational problem, not a chart problem.
- **An umbrella chart** over both — deliberately not, see Design §10.
