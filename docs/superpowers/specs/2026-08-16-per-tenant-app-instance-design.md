# Apus — An Application Instance per Tenant: Design

**As of:** 2026-08-16
**Status:** Draft for approval

Every tenant gets its own deployed instance of the tenant application, served under its own path
on the existing host. This is the second of four subsystems the larger goal decomposes into; teams
and users through Entra, and impersonation, each get their own spec and are out of scope here.

## 0. What the investigation settled first

Four findings shaped everything below. Each was established by reading the source or the cluster,
not by reasoning about how these things usually work — and two of them contradicted the first
draft of this design.

**One image can serve any number of tenants.** `NUXT_APP_BASE_URL` is honoured by the built Nitro
server at runtime: started with `/t/acme/`, the server reports that prefix, serves its shell and
deep links under it, emits assets at `/t/acme/_nuxt/…`, and exposes `baseURL: "/t/acme/"` to the
client runtime config. Verified against the actual built image. This is what makes the subsystem
small: an instance per tenant is the same image with one more environment variable, not a
per-tenant build.

**Microsoft Entra allows wildcard redirect URIs here — and using one would break the login.**
Wildcards are permitted for registrations that sign in only work or school accounts, which is ours
(`signInAudience: AzureADMyOrg`). But Microsoft's documentation states that *"when a configured
wildcard URI matches a redirect URI, query strings and fragments in the redirect URI are
stripped"*. The authorization-code flow returns `code` and `state` in exactly that query string,
so a wildcard would strip the response the callback needs. **Every tenant address therefore needs
two explicit redirect URIs registered.** The 256-URI registration limit is the real ceiling on
tenants: roughly 128.

An earlier attempt to settle this empirically — pointing the authorize endpoint at a
wildcard-matched host — appeared to succeed. The control case, an entirely unrelated host,
appeared to succeed too: Entra renders its sign-in page before validating the redirect URI at all.
The test proved nothing, and the documentation is what settles it.

**Path ordering is safe, but not for the reason the platform chart gives.** This cluster's
ingress class is `cloudflare-tunnel` (`strrl.dev/cloudflare-tunnel-ingress-controller`), not
nginx. That controller flattens *every* Ingress object in the cluster into one list of Cloudflare
tunnel rules and sorts it globally — non-wildcard hosts first, then by hostname, then **by path
length descending** — before appending a `http_status:404` catch-all
(`pkg/cloudflare-controller/tunnel-client.go`, `sortIngressRules`). So `/t/acme` (7 characters)
sorts ahead of `/` (1) automatically, no matter which Ingress object declared it or in what order.
The platform chart's ingress template carries a comment warning that path order within the rule
list is load-bearing; that is true of nginx and remains good practice, but it is the controller's
global sort that actually makes a *separate* per-tenant Ingress work here.

**A pod in a tenant namespace must declare resource requests or it is never created.**
`TenantReconciler` puts a `ResourceQuota` on every tenant namespace constraining `requests.cpu`
and `requests.memory`, and the `LimitRange` beside it has no spec at all — confirmed on the live
cluster, where `bluemap-onelitefeather-dev`'s limit range reads `spec={"limits":null}`. A quota on
a compute resource makes that request mandatory for every pod, and an empty limit range supplies
no default to fall back on. A Deployment without explicit requests would be created happily and
then never produce a pod. The builder therefore sets requests unconditionally.

## 1. The address

`https://<host>/t/<tenant>/`, on the same host the platform already serves.

Chosen over a subdomain per tenant because it costs nothing operationally that the cluster does
not already do: no wildcard DNS record, no wildcard certificate, and — decisively — no CORS.
The `api` module deliberately configures none, so a tenant application on a different origin
could not call it without changing the API (see `ui/README.md`, "Why the console is same-origin").
The path scheme is also the one already proven here: the console has been served under `/console`
since 0.7.0.

The cost is that a tenant cannot have a domain of its own. That is a real limitation and the
reason the subdomain variant is written down here rather than dismissed — it becomes available if
and when the API grows a CORS configuration, and nothing in this design would have to be undone
to move.

## 2. What the operator creates

`TenantReconciler` already provisions the namespace `bluemap-<name>`, a compute quota, a limit
range, a push-token Secret and a Ceph object-store user. It gains a fifth responsibility, guarded
so that a platform which does not want per-tenant instances is unaffected:

| Resource | Name | In | Notes |
| --- | --- | --- | --- |
| `Deployment` | `apus-tenant-ui` | `bluemap-<name>` | one replica, the tenant-UI image, `NUXT_APP_BASE_URL=/t/<name>/`, explicit resource requests |
| `Service` | `apus-tenant-ui` | `bluemap-<name>` | ClusterIP, port `http` → container 8080 |
| `Ingress` | `apus-tenant-ui` | `bluemap-<name>` | one rule: `<host>` + path `/t/<name>`, `pathType: Prefix` |

All three carry the tenant name/UID labels the reconciler already stamps and the same owner
reference its `ResourceQuota` and `LimitRange` already use — a namespaced dependent of the
cluster-scoped `Tenant`, which is an ownership Kubernetes permits and this reconciler has relied
on since it was written.

**The Ingress must live in the tenant's namespace, so it must be a per-tenant object.** This is
not a preference: an `Ingress` may only reference a `Service` in its own namespace, and each
tenant's Service is in `bluemap-<name>`. A single operator-owned Ingress listing every tenant's
path is therefore not available at any price. The per-tenant object is also the better outcome —
it is garbage-collected with the tenant, and the platform chart's ingress stays a static file
rather than something a controller writes to.

**No ingress annotations are set.** The tunnel controller defaults `backend-protocol` to `http`
(`well_known_annotations.go`), which is exactly what the platform ingress spells out explicitly,
and TLS terminates at Cloudflare's edge so there is no `tls` section and no cert-manager
annotation to add. A deployment onto an ingress class that needs annotations is out of scope; the
class name itself is configurable.

## 3. Configuration

`OperatorConfig` is built entirely from environment variables, so these follow that shape rather
than introducing a second mechanism. The chart renders them from a `tenantUi` value block.

| Env | Chart key | Meaning |
| --- | --- | --- |
| `APUS_TENANT_UI_HOST` | `tenantUi.host` | The host the paths hang off. **Empty disables the whole feature** |
| `APUS_TENANT_UI_IMAGE` | `tenantUi.image` | The tenant application image to run |
| `APUS_TENANT_UI_INGRESS_CLASS` | `tenantUi.ingressClassName` | Matches whatever the platform ingress uses |
| `APUS_TENANT_UI_API_BASE_URL` | `tenantUi.apiBaseUrl` | → `NUXT_PUBLIC_API_BASE_URL` |
| `APUS_TENANT_UI_OIDC_ISSUER` | `tenantUi.oidc.issuer` | → `NUXT_PUBLIC_OIDC_ISSUER` |
| `APUS_TENANT_UI_OIDC_CLIENT_ID` | `tenantUi.oidc.clientId` | → `NUXT_PUBLIC_OIDC_CLIENT_ID` |
| `APUS_TENANT_UI_OIDC_SCOPE` | `tenantUi.oidc.scope` | → `NUXT_PUBLIC_OIDC_SCOPE` |

The four `NUXT_PUBLIC_*` values are modelled one by one rather than as a free-form map, because a
map would have to be serialised through a single environment variable and would lose the schema,
the documentation and the ability to test each value. They are identical for every tenant — same
API, same issuer, same OIDC client — and only `NUXT_APP_BASE_URL` differs, which the operator
computes. None is a secret; every one of them reaches the served HTML by design.

**An empty host means the feature is off**, and off is the default. A tenant instance with no host
would have nothing to serve it, and an operator that created Deployments nobody could reach would
burn a pod per tenant for nothing.

## 4. The Entra step, which cannot be automated here

Each tenant instance needs two redirect URIs registered before anyone can sign in to it:

```text
https://<host>/t/<tenant>/auth/callback
https://<host>/t/<tenant>/auth/silent-renew
```

The operator cannot add them. Doing so would require Microsoft Graph application permissions on
the app registration — a security grant belonging to a different subsystem (teams and users) and a
decision that has not been made. Until it is, creating a tenant is a two-step operation: apply the
`Tenant`, then register its two URIs.

**A missing registration fails at sign-in, not at deploy time**, with `AADSTS50011` from the
broker and nothing in this cluster's logs. Everything about how this is surfaced follows from
that: the operator writes both URIs into `Tenant.status.redirectUris`, so `kubectl get tenant -o
yaml` answers the question, and the console's tenant view shows them with a copy action — telling
whoever just created a tenant what remains, at the moment they would otherwise walk away.

## 5. What the tenant application needs

Nothing. `buildOidcRedirectUris(origin, baseURL)` already derives the callback from the runtime
base path, and its unit tests already cover a nested prefix (`/admin/console/`) for exactly this
reason. The API base URL stays the origin, unchanged. No code in `apps/app` is aware that it is
one instance among several.

## 6. Tests

| What | How |
| --- | --- |
| Resource shape | Unit tests over a pure `TenantUiResourceBuilder`: base URL env is `/t/<name>/`, ingress path is `/t/<name>`, host and image come from config, labels and owner reference match the reconciler's |
| Resource requests | Asserted explicitly, with the quota finding named in the test — the one mistake here produces a Deployment that looks healthy and has no pods |
| Feature off | With no host configured, reconciling creates no Deployment, no Service and no Ingress, and sets no `redirectUris` — the default must be inert |
| Feature on | Reconciling creates all three, and `status.redirectUris` carries exactly the two URIs from §4 |
| Idempotence | Reconciling twice leaves one Deployment, matching how the namespace and quota paths already behave |
| Ownership | The owner reference is present on all three so Kubernetes garbage-collects them; asserted on the built objects |

## 7. Non-goals

- **No per-tenant branding or configuration of the instance.** Every instance runs the same image
  with the same public configuration. Per-tenant appearance would belong with the policy work.
- **No automatic Entra registration.** §4.
- **No subdomain or custom-domain addressing.** §1 — available later without migrating anything.
- **No change to the API.** The console gains one read-only display of the two redirect URIs;
  nothing else moves.
