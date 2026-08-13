# apus-operator

The Apus operator and its custom resource definitions — renders Minecraft worlds with
BlueMap on Kubernetes.

This chart installs:

- The six Apus CRDs (`Tenant`, `WorldSource`, `WorldIngest`, `BlueMapMap`, `BlueMapRender`,
  `BlueMapHosting`), shipped as templates so `helm upgrade` actually updates their schema.
- The operator `Deployment` (a single, non-scalable replica; see `replicaCount` below).
- Cluster-wide RBAC (`ClusterRole`/`ClusterRoleBinding`) the operator needs to own its
  CRDs and to create the Jobs, Deployments, Services and Ingresses that render, ingest
  and host worlds.
- Optionally, a metrics `Service` and a Prometheus Operator `ServiceMonitor`.

It does **not** install a user interface. See the `apus-platform` chart for the REST API
and dashboard.

## Installing

```bash
helm install apus-operator deploy/charts/apus-operator \
  --set bundles.s3Endpoint=http://rook-ceph-rgw.rook-ceph.svc
```

`bundles.s3Endpoint` has no default and is enforced by `values.schema.json` — see
[Values](#values) below.

## Values

The table is derived from [`values.yaml`](./values.yaml); every key defined there is
listed here.

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `image.repository` | string | `"harbor.onelitefeather.dev/apus/operator"` | Operator container image repository. |
| `image.tag` | string | `""` | Image tag. Empty on purpose: falls back to `.Chart.AppVersion` so the chart version and the image version cannot drift apart. Override only to pin a hotfix image. |
| `image.pullPolicy` | string | `"IfNotPresent"` | Image pull policy. |
| `imagePullSecrets` | list | `[]` | Secrets used to pull the operator image. |
| `nameOverride` | string | `""` | Overrides `apus-operator.name`. |
| `fullnameOverride` | string | `""` | Overrides `apus-operator.fullname`. |
| `images.runner.repository` | string | `"harbor.onelitefeather.dev/apus/runner"` | Image the operator references when it builds render Jobs. Not deployed by this chart. |
| `images.runner.tag` | string | `""` | Falls back to `.Chart.AppVersion`, same as `image.tag`. |
| `images.ingest.repository` | string | `"harbor.onelitefeather.dev/apus/ingest"` | Image the operator references when it builds ingest Jobs. Not deployed by this chart. |
| `images.ingest.tag` | string | `""` | Falls back to `.Chart.AppVersion`, same as `image.tag`. |
| `images.hosting.repository` | string | `"harbor.onelitefeather.dev/apus/hosting"` | Image the operator references when it builds hosting Deployments. Not deployed by this chart. |
| `images.hosting.tag` | string | `""` | Falls back to `.Chart.AppVersion`, same as `image.tag`. |
| `crds.install` | bool | `true` | Installs the six CRDs as templates. Set to `false` only if your organisation manages CRDs separately. |
| `rook.namespace` | string | `"rook-ceph"` | Namespace of the Rook-Ceph deployment the operator provisions buckets against. |
| `rook.cephObjectStore` | string | `"ceph-objectstore"` | Name of the `CephObjectStore` used for per-tenant buckets. |
| `rook.bucketStorageClass` | string | `"ceph-bucket"` | Storage class used for `ObjectBucketClaim`s the operator creates. |
| `bundles.bucket` | string | `"apus-bundles"` | Bucket that holds render bundles shared across tenants. |
| `bundles.s3Endpoint` | string | `""` | S3 endpoint of the bundle bucket. **Required** — enforced by `values.schema.json`, since a wrong or empty endpoint makes every ingest fail at runtime instead of at install time. |
| `bundles.s3Region` | string | `"us-east-1"` | S3 region of the bundle bucket. |
| `bundles.credentialsSecret` | string | `"apus-bundle-credentials"` | Secret holding the bundle bucket credentials. |
| `metrics.enabled` | bool | `true` | Exposes the operator's metrics port on the Deployment and creates the metrics `Service`. |
| `metrics.port` | int | `8080` | Container and Service port for metrics. |
| `metrics.serviceMonitor.enabled` | bool | `false` | Creates a Prometheus Operator `ServiceMonitor`. Defaults to `false` because the operator does not export metrics yet — that lands in Phase 8 Task 4. Enabling it before then wires Prometheus to an endpoint with no data. |
| `metrics.serviceMonitor.interval` | string | `"30s"` | Scrape interval used by the `ServiceMonitor`. |
| `metrics.serviceMonitor.labels` | object | `{}` | Extra labels added to the `ServiceMonitor`, e.g. to match a Prometheus instance's `serviceMonitorSelector`. |
| `serviceAccount.create` | bool | `true` | Creates a `ServiceAccount` for the operator. |
| `serviceAccount.name` | string | `""` | Name of the `ServiceAccount`. Defaults to `apus-operator.fullname` when empty. |
| `serviceAccount.annotations` | object | `{}` | Annotations added to the `ServiceAccount`. |
| `rbac.create` | bool | `true` | Creates the `ClusterRole` and `ClusterRoleBinding` the operator needs. |
| `replicaCount` | int | `1` | Number of operator replicas. Fixed to exactly `1` by `values.schema.json` — two instances would reconcile the same resources concurrently. |
| `podAnnotations` | object | `{}` | Extra annotations added to the operator pod. |
| `podLabels` | object | `{}` | Extra labels added to the operator pod. |
| `podSecurityContext` | object | `{"runAsNonRoot": true, "runAsUser": 10001, "seccompProfile": {"type": "RuntimeDefault"}}` | Pod-level security context. |
| `securityContext` | object | `{"allowPrivilegeEscalation": false, "readOnlyRootFilesystem": true, "capabilities": {"drop": ["ALL"]}}` | Container-level security context. |
| `resources` | object | `{"requests": {"cpu": "100m", "memory": "256Mi"}, "limits": {"memory": "512Mi"}}` | Resource requests/limits for the operator container. |
| `nodeSelector` | object | `{}` | Node selector for the operator pod. |
| `tolerations` | list | `[]` | Tolerations for the operator pod. |
| `affinity` | object | `{}` | Affinity rules for the operator pod. |

## Values schema

`values.schema.json` enforces only what has no sensible default:

- `bundles.s3Endpoint` must be a non-empty string.
- `replicaCount` must be exactly `1`.
- `image.pullPolicy`, if set, must be `Always`, `IfNotPresent` or `Never`.

## After installing

See the post-install notes (`helm install` output, or `helm get notes <release>`) for how
to create your first `Tenant`.
