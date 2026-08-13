# apus-platform

The Apus REST API and dashboard — lets you manage tenants, worlds and renders through a
web UI or `curl` instead of talking to the Kubernetes API directly.

This chart installs:

- The API `Deployment` and `Service`, plus its `ServiceAccount` and cluster-wide RBAC
  (`ClusterRole`/`ClusterRoleBinding`) needed to read and write the Apus custom resources
  (`Tenant`, `WorldSource`, `WorldIngest`, `BlueMapMap`, `BlueMapRender`, `BlueMapHosting`)
  and to tail render-job Pod logs as a Loki-less fallback.
- The UI `Deployment` and `Service` — a prebuilt static SPA served by an unprivileged nginx.
- Optionally, a single `Ingress` that routes `/api` to the API and `/` to the UI.
- Optionally, a Prometheus Operator `ServiceMonitor` for the API.

It assumes an installed `apus-operator`: the CRDs the API reads must already exist. See
the `apus-operator` chart for those.

## Installing

```bash
helm install apus-platform deploy/charts/apus-platform \
  --set auth.issuer=https://id.example.net \
  --set auth.jwksUri=https://id.example.net/oauth/v2/keys
```

Neither `auth.issuer` nor `auth.jwksUri` has a default, and both are enforced by
`values.schema.json` — see [Values schema](#values-schema) below. The API validates every
JWT against the issuer and fetches the signing keys from the JWKS URI; either one unset must
fail the install, not start an API that accepts unvalidated tokens or rejects every single
one of them.

To expose both workloads through a single host:

```bash
helm install apus-platform deploy/charts/apus-platform \
  --set auth.issuer=https://id.example.net \
  --set auth.jwksUri=https://id.example.net/oauth/v2/keys \
  --set ingress.enabled=true \
  --set ingress.host=apus.example.net \
  --set ingress.tls.enabled=true \
  --set ingress.tls.issuerRef.name=letsencrypt-prod
```

### Reinstalling under a different release name

This chart itself survives it: everything it creates is either namespaced or named after the
release, so a second install under another name collides with nothing. The `apus-operator`
chart it depends on does **not** — its CRDs are annotated `helm.sh/resource-policy: keep`,
survive `helm uninstall`, and keep the `meta.helm.sh/release-name` of whichever release
installed them first, so reinstalling *that* chart under a different name fails on every CRD.
See [Reinstalling under a different release name](../apus-operator/README.md#reinstalling-under-a-different-release-name)
in the operator chart for the two ways out (`kubectl annotate crd … --overwrite`, or
`--set crds.install=false`).

## Values

The table is derived from [`values.yaml`](./values.yaml); every key defined there is
listed here.

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `imagePullSecrets` | list | `[]` | Secrets used to pull the API and UI images. |
| `nameOverride` | string | `""` | Overrides `apus-platform.name`. |
| `fullnameOverride` | string | `""` | Overrides `apus-platform.fullname`. |
| `auth.issuer` | string | `""` | OIDC issuer the API validates tokens against. **Required** — enforced by `values.schema.json`, since an unset issuer would let the API start and accept unvalidated tokens. |
| `auth.jwksUri` | string | `""` | JWKS URI the API fetches signing keys from. **Required** — enforced by `values.schema.json` for the same reason as `auth.issuer`: without signing keys the API rejects every token at runtime instead of failing at install time. |
| `auth.audience` | string | `"apus"` | Not wired into the API's environment yet — audience validation is not implemented in `application.yml` (only issuer and JWKS URI are). Declared here so a schema addition has somewhere to point once it lands. |
| `api.image.repository` | string | `"harbor.onelitefeather.dev/apus/api"` | API container image repository. |
| `api.image.tag` | string | `""` | Image tag. Empty on purpose: falls back to `.Chart.AppVersion` so the chart version and the image version cannot drift apart. |
| `api.image.pullPolicy` | string | `"IfNotPresent"` | Image pull policy. |
| `api.replicaCount` | int | `1` | Number of API replicas. The API is stateless, safe to scale. |
| `api.podSecurityContext` | object | `{"runAsNonRoot": true, "runAsUser": 10001, "seccompProfile": {"type": "RuntimeDefault"}}` | Pod-level security context for the API. |
| `api.securityContext` | object | `{"allowPrivilegeEscalation": false, "readOnlyRootFilesystem": true, "capabilities": {"drop": ["ALL"]}}` | Container-level security context for the API. |
| `api.resources` | object | `{"requests": {"cpu": "200m", "memory": "512Mi"}, "limits": {"memory": "1Gi"}}` | Resource requests/limits for the API container. |
| `api.metrics.serviceMonitor.enabled` | bool | `false` | Creates a Prometheus Operator `ServiceMonitor` for the API. Defaults to `false` because the API does not export metrics yet — that lands in Phase 8 Task 5. Enabling it before then wires Prometheus to a 404. |
| `api.metrics.serviceMonitor.interval` | string | `"30s"` | Scrape interval used by the API `ServiceMonitor`. |
| `api.metrics.serviceMonitor.labels` | object | `{}` | Extra labels added to the API `ServiceMonitor`, e.g. to match a Prometheus instance's `serviceMonitorSelector`. |
| `api.serviceAccount.create` | bool | `true` | Creates a `ServiceAccount` for the API. |
| `api.serviceAccount.name` | string | `""` | Name of the API `ServiceAccount`. Defaults to `apus-platform.api.fullname` when empty. |
| `api.serviceAccount.annotations` | object | `{}` | Annotations added to the API `ServiceAccount`. |
| `api.rbac.create` | bool | `true` | Creates the `ClusterRole` and `ClusterRoleBinding` the API needs. |
| `api.podAnnotations` | object | `{}` | Extra annotations added to the API pod. |
| `api.podLabels` | object | `{}` | Extra labels added to the API pod. |
| `api.nodeSelector` | object | `{}` | Node selector for the API pod. |
| `api.tolerations` | list | `[]` | Tolerations for the API pod. |
| `api.affinity` | object | `{}` | Affinity rules for the API pod. |
| `ui.image.repository` | string | `"harbor.onelitefeather.dev/apus/ui"` | UI container image repository. |
| `ui.image.tag` | string | `""` | Image tag. Empty on purpose, same reasoning as `api.image.tag`. |
| `ui.image.pullPolicy` | string | `"IfNotPresent"` | Image pull policy. |
| `ui.replicaCount` | int | `2` | Number of UI replicas. The UI is a stateless static SPA, safe to scale. |
| `ui.podSecurityContext` | object | `{"runAsNonRoot": true, "runAsUser": 101, "seccompProfile": {"type": "RuntimeDefault"}}` | Pod-level security context for the UI. `runAsUser: 101`, not `10001` like the Java images — the unprivileged nginx base image runs as uid 101. |
| `ui.securityContext` | object | `{"allowPrivilegeEscalation": false, "readOnlyRootFilesystem": false, "capabilities": {"drop": ["ALL"]}}` | Container-level security context for the UI. `readOnlyRootFilesystem: false` — nginx writes its cache and pid below `/tmp` and `/var/cache`. |
| `ui.resources` | object | `{"requests": {"cpu": "50m", "memory": "64Mi"}, "limits": {"memory": "128Mi"}}` | Resource requests/limits for the UI container. |
| `ui.podAnnotations` | object | `{}` | Extra annotations added to the UI pod. |
| `ui.podLabels` | object | `{}` | Extra labels added to the UI pod. |
| `ui.nodeSelector` | object | `{}` | Node selector for the UI pod. |
| `ui.tolerations` | list | `[]` | Tolerations for the UI pod. |
| `ui.affinity` | object | `{}` | Affinity rules for the UI pod. |
| `ingress.enabled` | bool | `false` | Creates a single `Ingress` routing `/api` to the API and `/` to the UI. |
| `ingress.className` | string | `"nginx"` | `ingressClassName` on the `Ingress`. |
| `ingress.annotations` | object | `{}` | Extra annotations added to the `Ingress`. |
| `ingress.host` | string | `""` | Hostname the `Ingress` routes. Required when `ingress.enabled` is `true` (not enforced by the schema — the chart still renders without it, just with an empty host). |
| `ingress.tls.enabled` | bool | `false` | Enables TLS on the `Ingress` via cert-manager: adds the `cert-manager.io/cluster-issuer` annotation from `ingress.tls.issuerRef.name` and a `tls:` block. |
| `ingress.tls.secretName` | string | `""` | Secret cert-manager writes the certificate to. Defaults to `<fullname>-tls` when empty. |
| `ingress.tls.issuerRef.name` | string | `""` | Name of the cert-manager `ClusterIssuer` (or `Issuer`) to request certificates from. |
| `ingress.tls.issuerRef.kind` | string | `"ClusterIssuer"` | Declared for completeness; the chart always emits the `cert-manager.io/cluster-issuer` annotation regardless of this value — set a namespaced `Issuer` up via `ingress.annotations` instead if you need one. |

## Values schema

`values.schema.json` enforces only what has no sensible default:

- `auth.issuer` must be a non-empty, valid URI.
- `auth.jwksUri` must be a non-empty, valid URI.

Everything else (image repositories, resource sizes, replica counts, ingress host, …) has a
working default and is left unenforced.

## After installing

See the post-install notes (`helm install` output, or `helm get notes <release>`) for how
to check both workloads came up and how to reach the dashboard.
