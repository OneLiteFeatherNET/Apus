# Apus Helm Charts: Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apus lässt sich mit zwei `helm install`-Aufrufen ausrollen, und die Chart-Version bestimmt zwingend die Image-Version, die dabei zum Einsatz kommt.

**Architecture:** Zwei Charts unter `deploy/charts/`: `apus-operator` (die sechs CRDs, der Controller, cluster-weite RBAC) und `apus-platform` (API und Dashboard). Sie werden von Release Please im Root-Track mitversioniert und als OCI-Artefakte nach Harbor veröffentlicht. `runner`, `ingest` und `hosting` bekommen kein Chart — der Operator erzeugt sie aus Custom Resources; Helm reicht nur ihre Image-Referenzen durch.

**Tech Stack:** Helm 4, Kubernetes, Prometheus Operator (`ServiceMonitor`), GitHub Actions, Release Please.

## Global Constraints

- **Das Design steht in `docs/superpowers/specs/2026-08-13-helm-charts-design.md`.** Bei Widersprüchen zwischen diesem Plan und der Spec gilt die Spec; melde den Widerspruch.
- **Vorlage ist `helm/micronaut` im Cluster-Repository** (`OneLiteFeatherNET/Kubernetes-FLUX`, v0.5.2). Struktur, Label-Konventionen und `_helpers.tpl`-Aufbau werden von dort übernommen, damit die Charts sich vertraut anfühlen. Es ist **keine** Dependency — es liegt unpubliziert in einem anderen Repository.
- **Standard-Labels** nach Kubernetes-Konvention: `app.kubernetes.io/name`, `/instance`, `/version`, `/component`, `/part-of: apus`, `/managed-by: {{ .Release.Service }}`.
- **`image.tag` bleibt in allen Charts leer** und fällt auf `.Chart.AppVersion` zurück. Ein fest eingetragener Tag im Chart wäre genau der Drift, den dieses Design verhindern soll.
- **Kein `crds/`-Verzeichnis.** CRDs sind Templates mit `helm.sh/resource-policy: keep`.
- **Keine Mandanten, Quellen oder Karten** in den Charts (Spec §14, Design §10).
- **Non-root:** Java-Container laufen als uid 10001, der nginx-basierte UI-Container als uid 101 — das entspricht den in Phase 7 gebauten Images.
- Conventional Commits, keine Claude/AI-Attribution.
- `helm` (v4.2.2) und `kubectl` sind auf der Maschine verfügbar.

### Was bereits existiert

- Sechs Container-Images aus Phase 7: `apus/operator`, `apus/api`, `apus/ui`, `apus/runner`, `apus/ingest`, `apus/hosting`.
- `OperatorConfig` liest: `APUS_ROOK_NAMESPACE`, `APUS_CEPH_OBJECT_STORE`, `APUS_BUCKET_STORAGE_CLASS`, `APUS_RUNNER_IMAGE`, `APUS_INGEST_IMAGE`, `APUS_HOSTING_IMAGE`, `APUS_BUNDLE_BUCKET`, `APUS_BUNDLE_S3_ENDPOINT`, `APUS_BUNDLE_S3_REGION`, `APUS_BUNDLE_CREDENTIALS_SECRET`.
- `.github/workflows/release-please.yml` mit den Outputs `release_created`/`version` (Root, **ohne** Präfix) und `telemetry-addon--release_created`/`paper-worldpush--release_created`.
- **Noch nicht vorhanden:** `deploy/crds/` — das legt Phase 8 Task 1 an. Task 2 dieses Plans erzeugt es notfalls selbst; siehe dort.

---

### Task 1: Gerüst für `apus-operator`

**Files:**

- Create: `deploy/charts/apus-operator/Chart.yaml`
- Create: `deploy/charts/apus-operator/values.yaml`
- Create: `deploy/charts/apus-operator/.helmignore`
- Create: `deploy/charts/apus-operator/templates/_helpers.tpl`

**Interfaces:**

- Produces: die Helper `apus-operator.name`, `apus-operator.fullname`, `apus-operator.labels`, `apus-operator.selectorLabels`, `apus-operator.serviceAccountName`, `apus-operator.image`. Alle folgenden Tasks dieses Charts benutzen sie.

- [ ] **Schritt 1: Vorlage lesen**

```bash
gh api repos/OneLiteFeatherNET/Kubernetes-FLUX/contents/helm/micronaut/templates/_helpers.tpl --jq '.content' | base64 -d
gh api repos/OneLiteFeatherNET/Kubernetes-FLUX/contents/helm/micronaut/.helmignore --jq '.content' | base64 -d
```

Übernimm die Struktur, nicht den Inhalt eins zu eins — die Namen tragen `apus-operator` statt `micronaut`.

- [ ] **Schritt 2: `Chart.yaml`**

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

`"0.0.0"` ist der Bootstrap-Wert; Task 9 setzt ihn auf die aktuelle Release-Version und trägt die Marker in `release-please-config.json` ein.

- [ ] **Schritt 3: `values.yaml`**

Genau die Oberfläche aus dem Design, §4:

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

- [ ] **Schritt 4: `_helpers.tpl`**

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

- [ ] **Schritt 5: `helm lint` läuft**

Run: `helm lint deploy/charts/apus-operator`
Expected: `1 chart(s) linted, 0 chart(s) failed`. Ein Chart ohne Templates ist zulässig; die Warnung über fehlende Templates ist in Ordnung, ein Fehler nicht.

- [ ] **Schritt 6: Der Image-Helper tut, was er soll**

```bash
helm template t deploy/charts/apus-operator --show-only templates/_helpers.tpl 2>/dev/null || true
```

`_helpers.tpl` rendert nichts Eigenes — die eigentliche Prüfung folgt in Task 3, sobald das Deployment den Helper benutzt. Notiere das im Report, statt einen Scheinbeleg zu konstruieren.

- [ ] **Schritt 7: Commit**

```bash
git add deploy/charts/apus-operator
git commit -m "feat(helm): scaffold the apus-operator chart"
```

---

### Task 2: CRDs als Template

**Files:**

- Create: `deploy/charts/apus-operator/templates/crds.yaml`
- Modify: `operator/build.gradle.kts` — nur falls `deploy/crds/` noch nicht existiert, siehe Schritt 1

**Interfaces:**

- Consumes: `deploy/crds/*.yaml`, die generierten CRD-Definitionen.
- Produces: die sechs CRDs als Chart-Ressourcen mit `helm.sh/resource-policy: keep`.

- [ ] **Schritt 1: Prüfen, ob die CRDs eingecheckt sind**

Run: `ls deploy/crds/*.yaml 2>/dev/null | wc -l`

- Ergebnis `6`: weiter mit Schritt 2.
- Ergebnis `0`: Phase 8 Task 1 ist noch nicht gelaufen. Hole das hier nach, aber **nur** den Teil, den dieser Task braucht:

```bash
./gradlew :operator:generateCrds
mkdir -p deploy/crds
cp operator/build/crds/*.yaml deploy/crds/
```

Vermerke im Report, dass du das getan hast — Phase 8 Task 1 legt zusätzlich den `syncCrds`-Task und `CrdsInSyncTest` an, was hier bewusst **nicht** dupliziert wird.

- [ ] **Schritt 2: Die tatsächlichen Dateinamen feststellen**

Run: `ls deploy/crds/`
Expected: sechs Dateien. Notiere die exakten Namen — Schritt 3 listet sie namentlich auf, ohne Glob, damit ein umbenanntes CRD auffällt statt still zu verschwinden.

- [ ] **Schritt 3: `templates/crds.yaml`**

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

**Achtung:** `.Files.Glob` liest nur Dateien **innerhalb** des Chart-Verzeichnisses. `deploy/crds/` liegt außerhalb. Löse das so:

Der Chart bekommt ein eigenes `crds/`-Verzeichnis (nicht Helms Sonderverzeichnis auf oberster Ebene, sondern ein normales Datenverzeichnis), das beim Bau aus `deploy/crds/` befüllt wird. Da Helm den Namen `crds/` auf Chart-Ebene reserviert, verwende **`files/crds/`**:

```gotemplate
{{- if .Values.crds.install }}
{{- range $path, $_ := .Files.Glob "files/crds/*.yaml" }}
---
{{ $.Files.Get $path | trim }}
{{- end }}
{{- end }}
```

und ergänze in jeder Datei die Annotation. Weil die generierten CRDs sie nicht mitbringen, patcht ein kleines Skript sie beim Kopieren ein — siehe Schritt 4.

- [ ] **Schritt 4: Kopier- und Patch-Skript**

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

- [ ] **Schritt 5: Skript ausführen und Ergebnis prüfen**

Run: `chmod +x deploy/charts/apus-operator/sync-crds.sh && deploy/charts/apus-operator/sync-crds.sh`
Expected: `copied 6 CRDs into the chart`

Run: `grep -c 'helm.sh/resource-policy: keep' deploy/charts/apus-operator/files/crds/*.yaml`
Expected: jede der sechs Dateien meldet `1`. Meldet eine `0`, hat das `awk`-Muster nicht gegriffen — dann liegt `metadata:` dort nicht am Zeilenanfang, und das Skript muss angepasst werden statt die Datei von Hand zu editieren.

- [ ] **Schritt 6: Rendern und prüfen**

Run: `helm template t deploy/charts/apus-operator | grep -c 'kind: CustomResourceDefinition'`
Expected: `6`

Run: `helm template t deploy/charts/apus-operator --set crds.install=false | grep -c 'kind: CustomResourceDefinition' || echo 0`
Expected: `0` — der Schalter greift.

Run: `helm template t deploy/charts/apus-operator | kubectl apply --dry-run=client -f - 2>&1 | grep -c 'created (dry run)'`
Expected: mindestens `6` — die gerenderten CRDs sind gültige Kubernetes-Objekte.

- [ ] **Schritt 7: Commit**

```bash
git add deploy/charts/apus-operator
git commit -m "feat(helm): ship the CRDs as templates that upgrade cleanly"
```

---

### Task 3: Operator-Deployment, ServiceAccount und RBAC

**Files:**

- Create: `deploy/charts/apus-operator/templates/serviceaccount.yaml`
- Create: `deploy/charts/apus-operator/templates/rbac.yaml`
- Create: `deploy/charts/apus-operator/templates/deployment.yaml`

**Interfaces:**

- Consumes: die Helper aus Task 1, die Umgebungsvariablen von `OperatorConfig`.

- [ ] **Schritt 1: Die tatsächlich benötigten Rechte aus dem Code ableiten**

Run: `grep -rhoE '\b(Job|Deployment|Service|Ingress|ConfigMap|Secret|Namespace|ResourceQuota|LimitRange|NetworkPolicy|ObjectBucketClaim|CephObjectStoreUser|Pod|Event)\b' operator/src/main/java --include='*.java' | sort -u`

Jeder Typ in der Ausgabe braucht eine Regel. Fehlt einer, äußert sich das zur Laufzeit als `Forbidden` mitten in einer Reconciliation — nicht beim Start.

- [ ] **Schritt 2: ServiceAccount**

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

- [ ] **Schritt 3: RBAC**

`templates/rbac.yaml`, umschlossen von `{{- if .Values.rbac.create }}`. Der Regelsatz ist der aus dem Phase-8-Plan, Task 2, Schritt 2 — übernimm ihn vollständig: eigene Custom Resources samt `/status` und `/finalizers`; `namespaces`, `resourcequotas`, `limitranges`; `networkpolicies`; `jobs`; `deployments`; `services`, `configmaps`; `ingresses`; `pods` und `pods/log` **nur lesend**; `objectbucketclaims`; `cephobjectstoreusers`; `secrets` **nur `get`/`list`/`watch`**; `events` mit `create`/`patch`.

Namen: `{{ include "apus-operator.fullname" . }}` für ClusterRole und ClusterRoleBinding, damit zwei Releases im selben Cluster nicht kollidieren.

- [ ] **Schritt 4: Deployment**

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

- [ ] **Schritt 5: Die Umgebungsvariablen gegen `OperatorConfig` gegenprüfen**

Run: `grep -oE 'APUS_[A-Z_]+' operator/src/main/java/net/onelitefeather/apus/operator/OperatorConfig.java | sort -u`

Vergleiche mit den zehn Variablen im Deployment. Eine im Code gelesene, im Chart fehlende Variable bekommt stillschweigend ihren Default — genau das soll das Chart verhindern. Eine im Chart gesetzte, im Code unbekannte Variable ist toter Ballast.

- [ ] **Schritt 6: Rendern und prüfen**

Run: `helm template t deploy/charts/apus-operator | kubectl apply --dry-run=client -f - 2>&1 | tail -5`
Expected: keine Fehler.

Run: `helm template t deploy/charts/apus-operator --set image.tag="" | grep 'image:'`
Expected: Alle vier Image-Referenzen tragen die `appVersion` als Tag, nicht `:` allein und nicht `latest`.

Run: `helm template t deploy/charts/apus-operator --set images.runner.tag=1.2.3 | grep APUS_RUNNER_IMAGE -A1`
Expected: `...apus/runner:1.2.3` — der Override greift, ohne die anderen zu beeinflussen.

- [ ] **Schritt 7: Commit**

```bash
git add deploy/charts/apus-operator
git commit -m "feat(helm): deploy the operator with its service account and RBAC"
```

---

### Task 4: Metrics-Service, ServiceMonitor, NOTES und Schema

**Files:**

- Create: `deploy/charts/apus-operator/templates/service.yaml`
- Create: `deploy/charts/apus-operator/templates/servicemonitor.yaml`
- Create: `deploy/charts/apus-operator/templates/NOTES.txt`
- Create: `deploy/charts/apus-operator/values.schema.json`
- Create: `deploy/charts/apus-operator/README.md`

- [ ] **Schritt 1: Service und ServiceMonitor**

Beide umschlossen von `{{- if .Values.metrics.enabled }}` bzw. zusätzlich `.Values.metrics.serviceMonitor.enabled`. Der Service ist `ClusterIP` mit dem einen Port `metrics`; der ServiceMonitor selektiert auf `apus-operator.selectorLabels` und scrapt Pfad `/metrics` im Intervall aus den Werten.

**Hinweis:** Der Operator exportiert seine Metriken erst nach Phase 8 Task 4. Bis dahin liefert der Endpunkt nichts — der ServiceMonitor ist deshalb per Default `false`. Schreibe das in den Kommentar über dem Template, damit niemand ihn einschaltet und sich über leere Panels wundert.

- [ ] **Schritt 2: `values.schema.json`**

Erzwinge nur, was ohne sinnvollen Default nicht funktioniert:

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

`replicaCount` ist auf genau `1` beschränkt: Zwei Operator-Instanzen würden dieselben Ressourcen gleichzeitig reconcilen.

- [ ] **Schritt 3: Das Schema greift wirklich**

Run: `helm template t deploy/charts/apus-operator --set bundles.s3Endpoint="" 2>&1 | tail -3`
Expected: FEHLER, der `s3Endpoint` nennt. Läuft es durch, ist das Schema wirkungslos und der Task nicht fertig.

Run: `helm template t deploy/charts/apus-operator --set replicaCount=2 2>&1 | tail -3`
Expected: FEHLER wegen `maximum`.

Run: `helm template t deploy/charts/apus-operator --set bundles.s3Endpoint=http://rook-ceph-rgw.rook-ceph.svc >/dev/null && echo OK`
Expected: `OK`

- [ ] **Schritt 4: `NOTES.txt` und `README.md`**

`NOTES.txt` sagt nach der Installation, was als Nächstes zu tun ist: dass noch kein Mandant existiert und wie man einen anlegt (`kubectl apply` mit einem Minimal-`Tenant`), und dass `apus-platform` die Oberfläche nachliefert.

`README.md` dokumentiert die Werte-Tabelle. Erzeuge sie nicht von Hand aus dem Kopf, sondern aus `values.yaml`, damit sie vollständig ist.

- [ ] **Schritt 5: `helm lint` mit Werten**

Run: `helm lint deploy/charts/apus-operator --set bundles.s3Endpoint=http://example`
Expected: 0 failed.

- [ ] **Schritt 6: Commit**

```bash
git add deploy/charts/apus-operator
git commit -m "feat(helm): add metrics wiring, values schema and operator chart docs"
```

---

### Task 5: Chart `apus-platform` — Gerüst und API

**Files:**

- Create: `deploy/charts/apus-platform/Chart.yaml`
- Create: `deploy/charts/apus-platform/values.yaml`
- Create: `deploy/charts/apus-platform/.helmignore`
- Create: `deploy/charts/apus-platform/templates/_helpers.tpl`
- Create: `deploy/charts/apus-platform/templates/api-deployment.yaml`
- Create: `deploy/charts/apus-platform/templates/api-service.yaml`
- Create: `deploy/charts/apus-platform/templates/api-rbac.yaml`

**Interfaces:**

- Produces: Helper analog zu Task 1, aber mit Komponenten-Suffix: `apus-platform.api.fullname`, `apus-platform.ui.fullname`, `apus-platform.labels`, `apus-platform.componentLabels` (nimmt den Komponentennamen als Argument).

- [ ] **Schritt 1: Gerüst analog zu Task 1**

`Chart.yaml` wie dort, Name `apus-platform`, Beschreibung „The Apus REST API and dashboard". Beide Versionsmarker mit `"0.0.0"`.

Die Helper brauchen eine Erweiterung, weil dieses Chart **zwei** Workloads enthält:

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

Ohne `component` im Selector würden API- und UI-Deployment einander die Pods wegnehmen — beide hätten denselben Selector.

- [ ] **Schritt 2: `values.yaml`**

Zwei Blöcke `api:` und `ui:`, jeweils mit `image`, `replicaCount`, `resources`, `podSecurityContext`, `securityContext`, plus gemeinsam `ingress:` und `auth:`:

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

- [ ] **Schritt 3: API-Deployment**

Wie das Operator-Deployment, aber mit `strategy: RollingUpdate` (die API ist zustandslos und darf parallel laufen), Port 8080, den Auth-Umgebungsvariablen und Probes:

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

- [ ] **Schritt 4: Prüfen, dass die Health-Endpunkte existieren**

Run: `grep -rn 'micronaut-management' api/build.gradle.kts; grep -rn -A3 'endpoints:' api/src/main/resources/application.yml`

Fehlt `micronaut-management` oder ist `/health` nicht aktiviert, laufen die Probes ins Leere und der Pod wird endlos neu gestartet. Ist das der Fall: Probes **weglassen**, im Report vermerken und auf Phase 8 Task 5 verweisen, der die Abhängigkeit einführt. Rate nicht.

- [ ] **Schritt 5: API-RBAC**

ClusterRole mit den Custom Resources und der Secret-Regel. Prüfe zuerst, welche der beiden Fassungen gilt:

Run: `grep -n 'resolveNamespace' -A20 api/src/main/java/net/onelitefeather/apus/api/rest/push/FabricPushTokenRepository.java | head -30`

- Sucht der Code weiterhin per Label über alle Namespaces: breite Regel (`secrets`, `get`/`list`) **mit** Kommentar, der auf Spec §15 Punkt 9 und Phase 9 Task 2 verweist.
- Enumeriert er Tenants und liest ein Secret mit festem Namen: verengte Regel mit `resourceNames: ["apus-push-token"]`, `verbs: ["get"]`.

- [ ] **Schritt 6: Rendern und prüfen**

Run: `helm template t deploy/charts/apus-platform --set auth.issuer=https://id.example.net | kubectl apply --dry-run=client -f - 2>&1 | tail -3`
Expected: keine Fehler.

Run: `helm template t deploy/charts/apus-platform --set auth.issuer=https://id.example.net | grep -A3 'matchLabels'`
Expected: Der Selector enthält `app.kubernetes.io/component`.

- [ ] **Schritt 7: Commit**

```bash
git add deploy/charts/apus-platform
git commit -m "feat(helm): add the apus-platform chart with the API deployment"
```

---

### Task 6: UI, Ingress, Schema und Doku für `apus-platform`

**Files:**

- Create: `deploy/charts/apus-platform/templates/ui-deployment.yaml`
- Create: `deploy/charts/apus-platform/templates/ui-service.yaml`
- Create: `deploy/charts/apus-platform/templates/api-servicemonitor.yaml`
- Create: `deploy/charts/apus-platform/templates/ingress.yaml`
- Create: `deploy/charts/apus-platform/templates/NOTES.txt`
- Create: `deploy/charts/apus-platform/values.schema.json`
- Create: `deploy/charts/apus-platform/README.md`

- [ ] **Schritt 1: UI-Deployment und -Service**

Port 8080 (die unprivilegierte nginx-Basis lauscht dort), `runAsUser: 101`, `readOnlyRootFilesystem: false`. Readiness-Probe auf `/` — die UI ist statisch, ein 200 auf der Wurzel ist ein ausreichendes Signal.

- [ ] **Schritt 2: Ingress**

Ein Host, zwei Pfade: `/api` auf den API-Service, `/` auf den UI-Service. `pathType: Prefix`. Reihenfolge beachten — `/api` muss vor `/` stehen, sonst schluckt der Catch-all die API.

TLS über `cert-manager`, wenn `ingress.tls.enabled`; dann die Annotation `cert-manager.io/cluster-issuer` aus `issuerRef`.

- [ ] **Schritt 3: `values.schema.json` mit dem Pflicht-Issuer**

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

- [ ] **Schritt 4: Beweisen, dass der Issuer erzwungen wird**

Run: `helm template t deploy/charts/apus-platform 2>&1 | tail -3`
Expected: FEHLER, der `issuer` nennt. **Läuft das durch, ist der wichtigste Sicherheitsaspekt dieses Charts wirkungslos** — dann ist der Task nicht fertig.

Run: `helm template t deploy/charts/apus-platform --set auth.issuer=https://id.example.net >/dev/null && echo OK`
Expected: `OK`

- [ ] **Schritt 5: Ingress-Reihenfolge prüfen**

Run: `helm template t deploy/charts/apus-platform --set auth.issuer=https://id.example.net --set ingress.enabled=true --set ingress.host=apus.example.net | grep -A2 'paths:'`
Expected: `/api` erscheint vor `/`.

- [ ] **Schritt 6: Commit**

```bash
git add deploy/charts/apus-platform
git commit -m "feat(helm): add the dashboard, ingress and values schema to apus-platform"
```

---

### Task 7: Versionierung und Veröffentlichung

**Files:**

- Modify: `release-please-config.json`
- Modify: `deploy/charts/apus-operator/Chart.yaml` (Bootstrap-Version)
- Modify: `deploy/charts/apus-platform/Chart.yaml` (Bootstrap-Version)
- Modify: `.github/workflows/release-please.yml`

**Interfaces:**

- Consumes: die Outputs `release_created` und `version` des `release-please`-Jobs. **Ohne** `.--`-Präfix — das Root-Paket ist die Ausnahme von der Präfix-Regel.

- [ ] **Schritt 1: Aktuelle Version feststellen**

Run: `python3 -c "import json;print(json.load(open('.release-please-manifest.json'))['.'])"`

Trage diesen Wert als `version` und `appVersion` in beide `Chart.yaml` ein, statt `"0.0.0"` stehen zu lassen — sonst bumpt Release Please von einer Version, die nie existiert hat.

- [ ] **Schritt 2: `extra-files` ergänzen**

Im Root-Paket von `release-please-config.json`:

```json
"extra-files": [
  { "type": "generic", "path": "build.gradle.kts" },
  { "type": "generic", "path": "deploy/charts/apus-operator/Chart.yaml" },
  { "type": "generic", "path": "deploy/charts/apus-platform/Chart.yaml" }
]
```

Für das Root-Paket (`.`) werden die Pfade **nicht** mit dem Paketpfad präfixiert; sie gelten repo-relativ. Für die beiden Komponenten-Pakete wäre das anders — hier ist es korrekt so.

- [ ] **Schritt 3: Publish-Job anhängen**

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

- [ ] **Schritt 4: YAML und JSON validieren**

Run: `python3 -c "import yaml,json; yaml.safe_load(open('.github/workflows/release-please.yml')); json.load(open('release-please-config.json')); print('ok')"`
Expected: `ok`

- [ ] **Schritt 5: Verpacken lokal beweisen**

Run: `helm package deploy/charts/apus-operator -d /tmp && helm package deploy/charts/apus-platform -d /tmp && ls -la /tmp/apus-*.tgz`
Expected: zwei Archive, deren Dateinamen die Version aus Schritt 1 tragen.

Run: `helm show chart /tmp/apus-operator-*.tgz | grep -E '^(version|appVersion)'`
Expected: beide gleich der Version aus Schritt 1.

**Der Push selbst ist hier nicht zu testen.** Die Registry lehnt derzeit auch Image-Pushes ab (`empty challenge header`, siehe Design §11 Punkt 1). Vermerke das im Report; ein fehlgeschlagener Push-Versuch ist kein Fehler dieses Tasks.

- [ ] **Schritt 6: Commit**

```bash
git add release-please-config.json deploy/charts .github/workflows/release-please.yml
git commit -m "feat(helm): version the charts with the release and publish them to Harbor"
```

---

### Task 8: Charts im PR-Build prüfen

**Files:**

- Modify: `.github/workflows/build-pr.yml`

- [ ] **Schritt 1: Job ergänzen**

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

- [ ] **Schritt 2: Path-Filter erweitern**

Der `code`-Filter des Gradle-Jobs bleibt unberührt. Der neue `helm`-Job braucht keinen Filter — er läuft in Sekunden.

- [ ] **Schritt 3: Die Schema-Gegenprobe lokal nachstellen**

Run: `helm template t deploy/charts/apus-platform >/dev/null 2>&1; echo "exit=$?"`
Expected: `exit=1` — genau die Bedingung, auf die der CI-Schritt prüft.

- [ ] **Schritt 4: YAML validieren und committen**

Run: `python3 -c "import yaml;yaml.safe_load(open('.github/workflows/build-pr.yml'));print('ok')"`

```bash
git add .github/workflows/build-pr.yml
git commit -m "ci: lint, render and schema-check the Helm charts on pull requests"
```

---

### Task 9: Phase-8-Plan und Design-Spec nachziehen

**Files:**

- Modify: `docs/superpowers/plans/2026-08-12-phase-8-deployment-und-observability.md`
- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Schritt 1: Phase-8-Plan anpassen**

Nach Design §9:

- **Task 1** (CRDs einchecken) bleibt wortgleich — die Charts konsumieren `deploy/crds/`.
- **Task 2 und 3** (Kustomize-Basis für Operator, API, UI) werden ersetzt durch einen Verweis auf `docs/superpowers/plans/2026-08-13-helm-charts.md`. Lösche die Task-Inhalte, ersetze sie durch einen kurzen Absatz, der erklärt, dass Helm den Kustomize-Ansatz abgelöst hat und wo die Arbeit jetzt steht. Nummeriere die verbleibenden Tasks **nicht** um — das würde alle Querverweise brechen.
- **Task 6** (Scrape-Konfiguration): Die beiden `ServiceMonitor` sind jetzt Chart-Templates. Der `PodMonitor` für Render-Pods bleibt als eigenständige Aufgabe, weil er Pods in Mandanten-Namespaces selektiert, die kein Chart kennt.
- **Task 7** (Dashboards): Die ConfigMap wandert als optionale `dashboards.enabled`-Ressource ins `apus-platform`-Chart.
- **Task 8** (k3s-E2E): installiert künftig die Charts statt einzelner Manifeste; ergänze einen Schritt, der `helm upgrade` von der vorigen auf die aktuelle Chart-Version prüft, weil das die Eigenschaft belegt, wegen der die CRDs Templates sind.
- **Global Constraints** des Phase-8-Plans: Der Satz zur Kustomize-Basis wird auf Helm umgeschrieben.

- [ ] **Schritt 2: Design-Spec §0 ergänzen**

Ein Absatz, dass Apus über zwei Helm Charts ausgerollt wird, mit Verweis auf
`docs/superpowers/specs/2026-08-13-helm-charts-design.md`. Keine Wiederholung der Details.

- [ ] **Schritt 3: Markdown-Lint**

Run: `npx markdownlint-cli2 docs/superpowers/plans/2026-08-12-phase-8-deployment-und-observability.md docs/superpowers/specs/2026-08-08-apus-design.md`
Expected: 0 issues.

- [ ] **Schritt 4: Commit**

```bash
git add docs/
git commit -m "docs: replace the Kustomize tasks in the phase 8 plan with the Helm charts"
```

---

## Was dieser Plan nicht abdeckt

- **Die Einbindung ins Cluster-Repository** (`OCIRepository` plus `HelmRelease` unter `apps/base/apus/`). Sie gehört ins Cluster-Repository, nicht hierher, und setzt voraus, dass die Charts einmal veröffentlicht wurden. Design §7 beschreibt das Zielbild.
- **Die Harbor-Authentifizierung.** Der Chart-Push wird scheitern, solange der Image-Push mit `empty challenge header` scheitert. Das ist ein Betriebsproblem, kein Chart-Problem.
- **Ein Umbrella-Chart** über beide — bewusst nicht, siehe Design §10.
