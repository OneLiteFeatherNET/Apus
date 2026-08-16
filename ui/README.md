# Apus UI

Web frontend for Apus (design spec `docs/superpowers/specs/2026-08-08-apus-design.md`, §11.2):
Nuxt 4 in SPA mode (`ssr: false`), Vue 3, Tailwind 4, Nuxt UI, VueUse.

## Two applications

This directory is a pnpm workspace holding **two** applications over two shared Nuxt layers.
The split, and everything below that follows from it, is specified in
`docs/superpowers/specs/2026-08-15-ui-split-and-redesign-design.md`.

| Package | What it is | Image | Served at |
| --- | --- | --- | --- |
| `apps/app` | The tenant application: sources, maps, renders, hosting | `apus/ui` | `/` |
| `apps/console` | The management console: tenants, quotas, cluster-wide renders. `platform-admin` | `apus/console` | `/console/` |
| `layers/core` | OIDC session, typed API client, wire types, role helpers. No visual code | — | — |
| `layers/design` | Design tokens, the stylesheet, shared presentational components. No domain knowledge | — | — |

**`apus/ui` still means the tenant application**, not "both". Keeping the name spared every
existing deployment a rename; the console is the one that is new. If you are looking for the
image that serves `/console`, it is `apus/console`.

The point of the split is delivery, not tidiness: a tenant user is never served the console's
code. Note the honest limit of that — what leaves the tenant bundle is the platform *interface*
(the tenant list, both forms, the cluster render table, and every platform-only field). The
shared API client in `layers/core` still carries `createTenant`/`updateTenant`, because
`createApusApiClient()` returns one object literal and a bundler cannot shake a property out of
it. Nothing in the tenant app calls them, and the `api` module enforces `platform-admin` on
every request regardless — see "Role logic" below.

### The `#core` alias

Inside a layer, `~` resolves against the **consuming app's** `app/` directory, not the layer's.
So `import type { TenantResponse } from '~/utils/apiTypes'` in `apps/app` looks inside
`apps/app/app/utils/`, finds nothing, and fails the build with `UNLOADABLE_DEPENDENCY`. Nuxt's
auto-imports do cross layer boundaries, but they cannot stand in for an `import type`.

`layers/core/nuxt.config.ts` therefore publishes an alias, and everything reaching into the core
layer — including the layer's own composables — uses it:

```ts
import type { BlueMapMapResponse } from '#core/utils/apiTypes'
import { ApusApiError } from '#core/utils/apiErrors'
```

### Two applications, one OIDC client

Both applications are the same public client at the broker, on the same origin. Only the base
path differs, which means **two extra redirect URIs must be registered** alongside the tenant
app's, or signing in to the console fails with an invalid `redirect_uri` and nothing else to go
on:

```text
https://<host>/console/auth/callback
https://<host>/console/auth/silent-renew
```

The chart's `NOTES.txt` prints these on install. The construction lives in
`layers/core/app/utils/oidc.ts` (`buildOidcRedirectUris`) and is derived from
`useRuntimeConfig().app.baseURL`, not from `window.location.origin` — building it from the
origin alone would return an admin signing in to the console into the *tenant app*, silently and
with a working session.

## The design system

Tokens and components live in `layers/design`; both applications consume the same set and differ
only in accent and density. Four rules hold it together, and breaking any of them is how it
starts drifting back into a generic dashboard.

**1. Tokens are the only source of colour.** No hex literal, no Tailwind palette colour
(`text-blue-500`) in an app or a component. Everything resolves through the semantic tokens
(`bg-default`, `bg-muted`, `bg-elevated`, `text-muted`, `border-default`, `text-primary`) that
`app/assets/css/tokens.css` sets up.

Installing a palette there is not a matter of defining `--ui-color-*`: Nuxt UI generates those at
runtime from `appConfig.ui.colors` (see its `dist/runtime/plugins/colors.js`) and would overwrite
anything written by hand. The supported path is a named Tailwind ramp plus an `app.config.ts`
slot. **The neutral ramp is called `basalt`, not `neutral`** — that literal value is special-cased
by the plugin to Nuxt UI's own greyscale, and a ramp named `neutral` would silently never be used.

**2. No continuous progress bars, anywhere.** Every proportion — render progress, storage quota —
is drawn as discrete cells by `CellMeter`. World data is a grid of regions and chunks and BlueMap
renders it as a tile pyramid, so progress in this product is squares completing. The cells do not
correspond to real tiles and must never be made to look as though they do: the API reports a
percentage and says nothing about which tiles are done.

`cellsFilled` and `displayPercent` (`layers/core/app/utils/pipeline.ts`) special-case both ends,
and the reason is worth keeping: 99.6% must not fill the last cell *or* print "100%", because a
full meter beside a running render sends someone looking for a map that does not exist yet.

**3. Prose is humanist, every machine value is monospace.** Identifiers, phases, percentages, byte
counts, bucket paths, versions and timestamps get `.apus-value`; sentences do not. Section labels
are `.apus-eyebrow`, and they name a resource kind — `SOURCE`, `MAP`, `RENDER`, `HOSTING` — which
is the CRD taxonomy rather than decoration. There are **no web fonts**: the images are distroless
and the pages make no external requests, so a downloaded typeface would break both.

**4. `layers/design` knows nothing about Apus's domain.** It imports exactly two things from
`layers/core` — `PipelineStage` and the cell arithmetic — and nothing else. A design change should
be reviewable without reading domain code.

Accents: **verdigris** (hue 168) for the tenant app, **lapis** (hue 268) for the console. Minecraft
models copper oxidising, and this product exists because worlds age and renders refresh them; the
hundred degrees between them is what makes the two applications unmistakable at a glance. Status
colours (success/warning/error/info) are Nuxt UI's own and are identical in both — a "Failed"
badge must look the same wherever an operator meets it, which matters more than matching them to
an accent.

Dark and light are both first-class; `@nuxtjs/color-mode` follows the system and falls back to
dark only when the visitor has expressed no preference.

### Why the console is same-origin

The `api` module configures no CORS: `api/src/main/resources/application.yml` sets up JWT
validation and nothing else, with no `micronaut.server.cors` block. A console on its own
hostname would therefore fail every API call at the preflight, and fixing that means changing
the API. Same origin also costs one fewer DNS record, one fewer certificate and one fewer client
registration.

So the console owns the path prefix `/console` via `app.baseURL`, and the chart's ingress routes
`/console` to it **without** a rewrite — the app already expects the prefix, which keeps the rule
portable across controllers that spell rewrites differently. Path order in that ingress is load
bearing: `/api`, then `/console`, then the `/` catch-all.

Re-check the CORS claim before assuming it still holds; if the API ever grows a CORS
configuration, a separate hostname becomes available and this section is the thing to revisit.

## Not part of the Gradle build, on purpose

This module lives beside the Java modules but is **not** included in `settings.gradle.kts` and
has no `build.gradle.kts`. It is a self-contained Node/pnpm project with its own toolchain
(Vite, Vitest, ESLint), none of which Gradle can drive without an extra plugin
(`gradle-node-plugin` or similar) whose only job would be shelling out to `pnpm` anyway.
Wiring that up would add a second build system's worth of dependency-locking and caching
concerns to Gradle's dependency graph for no benefit: nothing here produces a JAR another module
consumes, and nothing in the Java modules is a build input to this one. Build and test it
directly, as below.

## Building and testing

All of these run from `ui/`, the workspace root. One install covers every package.

```bash
corepack enable   # or: npm install -g pnpm
pnpm install
pnpm dev:app        # the tenant application, on :3000
pnpm dev:console     # the management console, on :3000/console/
pnpm build            # both apps: apps/*/.output/{server,public}, what the images ship
pnpm test              # vitest across the workspace (no build needed)
pnpm test:server        # builds each app, then tests its built Nitro server
pnpm lint                # eslint over the whole workspace, incl. eslint-plugin-vuejs-accessibility
pnpm typecheck            # vue-tsc, per app
```

`lint` is deliberately **not** fanned out with `pnpm -r`: one `eslint .` at the root is what
covers `layers/`, which belongs to no app's directory tree and would otherwise go unlinted. It
also means `ui/eslint.config.mjs` has to restate two things the config `@nuxt/eslint` generates
would otherwise provide — the parsers, and the exemption from `vue/multi-word-component-names`
for router-owned filenames. Those generated globs are written relative to `apps/app/.nuxt/`, so
from the workspace root they resolve outside the workspace and match nothing.

To work on a single package, filter: `pnpm --filter @apus/ui-console test:server`.

Copy `.env.example` to `apps/app/.env` and/or `apps/console/.env` for local development and fill
in the three variables (API base URL, OIDC issuer, OIDC client ID) — Nuxt reads the `.env` next
to the `nuxt.config.ts` it is starting. See "Configuration" below.

## Versions (pinned, verified against npm on 2026-08-09)

| Package | Version | Why this one |
| --- | --- | --- |
| `nuxt` | 4.5.2 | current stable Nuxt 4 |
| `vue` | 3.5.41 | pulled in by Nuxt 4 |
| `@nuxt/ui` | 4.10.0 | current stable; bundles its own Tailwind 4 wiring |
| `tailwindcss` | 4.3.3 | `@nuxt/ui`'s declared peer (`^4.0.0`) |
| `@vueuse/nuxt` / `@vueuse/core` | 14.4.0 | matches what `@nuxt/ui` itself depends on |
| `@nuxt/eslint` | 1.17.0 | flat-config ESLint integration, house standard (`launchpad`) |
| `eslint` | 10.8.1 | current stable major |
| `eslint-plugin-vuejs-accessibility` | 2.5.0 | binding requirement, design spec §11.2 |
| `typescript` | 6.0.3 | current stable **6.x**, not the newer `7.0.2` — `@nuxt/ui`'s declared peer range is `^5.6.3 \|\| ^6.0.0` and does not (yet) include 7 |
| `vue-tsc` | 3.3.9 | matches `typescript` 6.x (peer: `>=5.0.0`) |
| `vitest` | 4.1.10 | current stable |
| `@vue/test-utils` | 2.4.11 | current stable |
| `happy-dom` | 20.11.2 | vitest environment |
| `oidc-client-ts` | 3.5.0 | see "Authentication" below |

All pinned exactly (no `^`/`~`), matching this repository's own convention in
`settings.gradle.kts` of exact-pinning dependency versions with a comment on why. There is no
Renovate config for this repo yet (out of scope for this task); until there is, bumps are
manual — check each package's current npm version before raising the pin, the same way this
table was built.

Since the workspace split, each package declares what it actually uses, so a version appears in
more than one `package.json`: both apps and `layers/design` name `@nuxt/ui`, both apps name
`nuxt`/`vue`/`vitest`. **Bump them together.** pnpm's non-flat `node_modules` is why an app
cannot simply borrow the design layer's copy for type resolution — a package that writes
`import type { FormSubmitEvent } from '@nuxt/ui'` has to depend on it.

## Project layout

Nuxt 4's actual current default applies inside every package here: an `app/` directory holds
everything client-side (`app/pages`, `app/components`, `app/composables`, `app/layouts`,
`app/middleware`, `app/plugins`, `app/utils`, `app.vue`). A layer uses the same convention as an
app, which is what lets `layers/core` contribute middleware and a plugin rather than only
functions.

```text
package.json                      -- workspace root: fan-out scripts, the shared lint toolchain
pnpm-workspace.yaml               -- packages: apps/*, layers/*
eslint.config.mjs                 -- one flat config for the whole workspace (see above)

layers/core/                      -- @apus/ui-core. No Vue components, no CSS, no @nuxt/ui
  nuxt.config.ts                    -- publishes the #core alias, registers nothing else
  app/pages/auth/callback.vue       -- OIDC Authorization Code redirect target
  app/pages/auth/silent-renew.vue    -- OIDC silent-renew iframe target
  app/middleware/auth.global.ts      -- requires a session on every route but /auth/*
  app/plugins/oidc.client.ts          -- restores the in-memory session before first render
  app/composables/
    useAuth.ts                        -- oidc-client-ts wrapper: user, principal, login/logout
    useApiClient.ts                    -- wires useAuth()'s token into createApusApiClient()
  app/utils/                     -- plain TypeScript, NO Nuxt auto-imports/composables used
    apiClient.ts                   -- createApusApiClient(): the typed client, see below
    apiTypes.ts                     -- one interface per Java response/request record
    apiErrors.ts                     -- ApusApiError
    sse.ts                            -- parseSseStream(): SSE framing over a fetch body reader
    oidc.ts                            -- buildOidcRedirectUris(): base-path-aware redirect URIs
    role.ts                             -- UI-side role helpers (convenience only, see below)
    jwt.ts                               -- decodeJwtPayload(): unverified, display-only decode
  tests/unit/                     -- vitest; mirrors app/utils/, one spec file per module

layers/design/                    -- @apus/ui-design. Knows nothing about Apus's domain
  nuxt.config.ts                    -- registers @nuxt/ui and @vueuse/nuxt, wires the stylesheet
  app/assets/css/main.css

apps/app/                         -- @apus/ui-app, image apus/ui
  nuxt.config.ts                    -- extends both layers
  app/app.vue                        -- <UApp><NuxtLayout><NuxtPage/></NuxtLayout></UApp>
  app/layouts/default.vue             -- header + nav, wraps every page
  app/components/layout/
    AppHeader.vue                      -- branding, signed-in user, console link, sign-out
    AppNav.vue                          -- nav links; no Platform entry, that is another app
  app/components/tenant/**
  app/pages/index.vue                -- the signed-in user and their tenant
  app/pages/tenant/**
  tests/nuxt/                       -- component tests in a real Nuxt context
  tests/server/                      -- against the built Nitro server (needs a build)

apps/console/                     -- @apus/ui-console, image apus/console, baseURL /console/
  nuxt.config.ts, app/**, tests/**   -- same shape; app/pages/index.vue is the platform view

Dockerfile.app, Dockerfile.console
```

There is no `server/` directory in either app — neither SPA has Nitro API routes of its own.
The containers do run a Nitro server (see "Serving the built SPA" below), but the only thing it
serves is the SPA shell and the client bundle; nothing per-request is application logic, which
is what "Why no server-side session" below rests on.

`layers/core/app/utils/*` is deliberately framework-agnostic (no `useRuntimeConfig`, no `$fetch`,
no `ref`/`computed` from Vue) so it can be unit-tested with plain Vitest and no Nuxt test harness
— see "Why plain Vitest" below. The two Nuxt-aware composables in `layers/core/app/composables/`
are thin wrappers around it.

### Why plain Vitest, not `@nuxt/test-utils`

Everything with real logic (`apiClient.ts`, `role.ts`, `jwt.ts`, `sse.ts`) is plain TypeScript
with no Nuxt runtime dependency, so a full Nuxt test environment (module resolution, virtual
`#imports`, a mounted app) would only add startup cost and indirection for no benefit. `useAuth`
and `useApiClient` themselves are thin enough (a few lines of wiring) that they are exercised
indirectly through the pure functions they call, per the task brief's "pure presentation needs
no tests" — the composables' own logic content is effectively zero.

## Serving the built SPA

`ui/Dockerfile.app` and `ui/Dockerfile.console` are siblings differing only in which workspace
package they build. Each installs with `pnpm install --frozen-lockfile --filter <app>...` — the
trailing `...` is what pulls in the two layers without the file naming them — then copies that
app's `.output` into `gcr.io/distroless/nodejs24-debian12:nonroot`, where the container starts
Nuxt's own Nitro server (`nitro.preset: 'node-server'`). There is no `index.html` on disk —
with `ssr: false` Nitro renders the SPA shell per request, which is what makes the runtime
config below work.

```bash
# exactly what each container does
pnpm --filter @apus/ui-app build && PORT=8080 node apps/app/.output/server/index.mjs
pnpm --filter @apus/ui-console build && PORT=8080 node apps/console/.output/server/index.mjs
```

**`app.baseURL` moves the URL, not the layout on disk.** The console's assets stay at
`.output/public/_nuxt` and Nitro mounts them under the prefix, so they are fetched from
`/console/_nuxt/…`. Its bare root answers `302` to `/console/` — which is why the chart's
probes point at `/console/` and not `/`.

**Configuration is read at runtime.** Every `runtimeConfig.public` key maps to `NUXT_PUBLIC_` +
the key in SCREAMING_SNAKE_CASE (`apiBaseUrl` ← `NUXT_PUBLIC_API_BASE_URL`). The
`apus-platform` chart passes them generically through `ui.env` / `console.env` (a map handed to
the container verbatim) and the matching `envFrom`, so a new key needs no chart change. Both
applications read the same values:

```yaml
ui:
  env: &uiEnv
    # The origin only -- no /api suffix. See below.
    NUXT_PUBLIC_API_BASE_URL: https://apus.example.net
    NUXT_PUBLIC_OIDC_ISSUER: https://id.example.net/realms/apus
    NUXT_PUBLIC_OIDC_CLIENT_ID: apus-ui
    NUXT_PUBLIC_OIDC_SCOPE: openid profile email
console:
  env: *uiEnv
```

**`NUXT_PUBLIC_API_BASE_URL` is an origin, not an API root.** Every method of the typed client
already asks for a path beginning with `/api` — `createApusApiClient` concatenates
`baseUrl + '/api/tenants'` — so a `/api` suffix here produces `/api/api/tenants`. An ingress
routing `/api` by prefix forwards that happily, the API has no such route, and Micronaut's
security filter answers a bare `403`. That is indistinguishable from a missing `platform-admin`
role at the point where you read it, which is what makes the mistake expensive: this example
carried the suffix until it cost an afternoon in production.

None of these are secrets: this is a public OIDC client, and every value ends up in the served
HTML by design. `NUXT_PUBLIC_OIDC_CLIENT_ID` is deliberately the same for both — see "Two
applications, one OIDC client" above for the two redirect URIs that requires.

**The header contract.** Nitro sends no `Cache-Control` on the shell at all, so `routeRules` in
each app's `nuxt.config.ts` pin `no-store` there and repeat Nitro's `immutable` on `/_nuxt/**` —
without that, a deploy strands browsers on HTML referencing hashed assets the new build no
longer ships. Each app's `tests/server/nitro.spec.ts` spawns its built server and holds it; they
need a build, so they run as `pnpm test:server`. The console's copy additionally pins the
prefix, in both the shell and the asset URLs, because a build that lost it would 404 behind the
ingress rule with nothing in any log to say why.

Why Nitro rather than nginx or `vite preview`, the measured memory and dependency trade-offs,
the full variable and chart-value reference and the operational how-to are documented
internally: engineering wiki, space *Entwicklung*, "Apus UI — Auslieferung &
Runtime-Konfiguration".

### Why no server-side session

`ssr: false` means there is no reliable backend component to hold a confidential OIDC client
or a session cookie behind. The Nitro server in the image (see "Serving the built SPA" above)
does not change that: it renders the SPA shell and serves assets, it holds no session store,
and this module deliberately has no `server/` directory to put one in. That
ruled out `nuxt-oidc-auth` (built around a server-side session) and shaped the client-only,
public-client design in `useAuth.ts` below.

## Authentication (task 2 requirement)

**Library: `oidc-client-ts`.** The maintained, TypeScript-native successor to `oidc-client`,
widely used, and — critically for the storage decision below — it exposes a pluggable
`userStore` instead of hardcoding `localStorage`. No Nuxt-specific OIDC module was used; see
"Why no server-side session" above for why the usual Nuxt choice (`nuxt-oidc-auth`) does not fit
this deployment shape.

Flow: Authorization Code + PKCE, public client (no client secret — there is nowhere safe to
keep one in a pure SPA). Configuration is four environment variables
(`NUXT_PUBLIC_API_BASE_URL`, `NUXT_PUBLIC_OIDC_ISSUER`, `NUXT_PUBLIC_OIDC_CLIENT_ID`,
`NUXT_PUBLIC_OIDC_SCOPE`), matching the design spec's instruction to keep the broker choice
out of code entirely. They are read at runtime, not baked in — see "Runtime configuration"
above for how the deployment supplies them.

### The scope is broker-specific, and getting it wrong fails silently

`NUXT_PUBLIC_OIDC_SCOPE` defaults to `openid profile email`, which is right for a broker that
treats the OIDC scopes as enough to mint an access token for its own APIs — Keycloak and Zitadel
both do.

**Microsoft Entra does not.** Asking Entra for only those three returns an access token addressed
to Microsoft Graph: a different audience, a different issuer (`sts.windows.net`, not the v2.0
issuer the API validates against), and none of Apus's app roles. Every API call 401s, the UI has
no way to explain why, and nothing in the broker's own logs looks wrong. Entra needs its API scope
named explicitly:

```bash
NUXT_PUBLIC_OIDC_SCOPE="api://<client-id>/access_as_user openid profile email"
```

Two Entra-side settings belong with it. The app registration's
`api.requestedAccessTokenVersion` must be `2`, or the access token carries the v1 issuer and fails
issuer validation for the same invisible reason. And roles reach the token as **app roles**
(`appRoles` with `value: platform-admin`, `tenant-owner`, `tenant-operator`, `tenant-viewer`),
assigned to users or groups — Entra emits those under the `roles` claim, which is what
`PrincipalResolver` reads.

**Still open on Entra: the `organization` claim.** `PrincipalResolver.TENANT_CLAIM` expects a
string claim naming the caller's tenant, and Entra does not emit one without a claims-mapping
policy or a directory extension attribute. Until that is configured, an Entra-issued token
resolves to `tenant: null` — which is enough for the management console (`platform-admin` needs
only `roles`) but leaves the tenant application with no tenant to read.

### Token storage — binding requirement, and the reasoning

**Tokens are held in memory only** (`oidc-client-ts`'s `InMemoryWebStorage`, wired in
`layers/core/app/composables/useAuth.ts`), never in `localStorage` and — one step further than the library's
own default — never in `sessionStorage` either.

Why: an XSS bug anywhere on this page can read anything in `localStorage`/`sessionStorage` at
any time for as long as that data sits there, which for a bearer JWT means full API access to
everything the signed-in user's tenant can see, for as long as the token is valid (typically
minutes to hours) — and `localStorage` specifically survives tab close and even browser
restart, so a *stored* XSS elsewhere on the same origin could scrape it well after the
originating page load. A plain in-memory object is not reachable by that class of bug at all
unless the malicious script is executing at the exact moment the token is in use — the smallest
exposure window achievable without a confidential backend to hold a session behind (which, per
"Why no server-side session" above, does not exist here).

The cost: a hard page reload loses the in-memory token, since nothing persists it. This is
absorbed by `automaticSilentRenew: true` plus an explicit `signinSilent()` call on every route
load with no session (`app/middleware/auth.global.ts`) — a hidden iframe re-authenticates
against the broker's own session, which lives in an httpOnly cookie the broker controls and this
page's JavaScript cannot read regardless. If that broker session has also expired, the user sees
an interactive login redirect — the same as a first visit, not a broken app.

**Known caveat, to verify once the broker is chosen (design spec §15):** which claims carry
roles and the tenant/organization depends on the broker's own claim-mapping configuration (a
Keycloak client scope mapper vs. a Zitadel action, for instance). `layers/core/app/utils/role.ts` hardcodes
the claim names `roles` and `organization` to match what the `api` module already expects
(`PrincipalResolver.ROLES_CLAIM`/`TENANT_CLAIM`) — the broker must be configured to put them
there, in the *access* token specifically (the token this UI sends as the bearer credential and
the one the API actually validates), not only the ID token. Silent renew via a hidden iframe
also assumes the broker allows being framed for that purpose (no `X-Frame-Options: DENY` on its
authorize endpoint) — most modern brokers support this for a registered redirect URI, but it is
worth a five-minute check against whichever broker Phase 5 lands on, before relying on it.

## Typed API client (task 3 requirement) — structure for the two follow-up agents

`layers/core/app/utils/apiClient.ts` exports `createApusApiClient(options)`, a factory (not a class you
`new`) returning an object with one method per endpoint. `layers/core/app/composables/useApiClient.ts` is
the Nuxt-facing entry point — call `useApiClient()` from a page/component and it is already
wired to the current access token and configured API base URL.

Every request/response type in `layers/core/app/utils/apiTypes.ts` is a direct mirror of the Java record it
is named after, with the exact source file cited in a comment on each type — read those Java
files (`api/src/main/java/net/onelitefeather/apus/api/rest/**`,
`api/src/main/java/net/onelitefeather/apus/api/events/RenderProgress.java`) before extending
this client, rather than guessing at a field.

```ts
const api = useApiClient()

// Platform level (platform-admin only, enforced server-side — see "Role logic" below)
await api.listTenants()                          // TenantResponse[]
await api.createTenant(body)                      // TenantResponse

// Tenant level (caller's own tenant only, resolved server-side from the token)
await api.listSources()                            // WorldSourceResponse[]
await api.createSource(body)                         // WorldSourceResponse
await api.listMaps()                                  // BlueMapMapResponse[]
await api.getMap(id)                                   // BlueMapMapResponse
await api.triggerRender(id, { force: false })           // BlueMapRenderResponse
await api.listRenders()                                  // BlueMapRenderResponse[]
await api.getRender(id)                                   // BlueMapRenderResponse
await api.listHostings()                                   // BlueMapHostingResponse[]

// Live streams (SSE) -- see layers/core/app/utils/sse.ts for why these use `fetch`, not `EventSource`
await api.streamRenderEvents(id, { onMessage, onClose, onError }, abortSignal)  // RenderProgressEvent
await api.streamRenderLogs(id, { onMessage, onClose, onError }, abortSignal)     // raw log line (string)
```

**Error handling:** every failure — a non-2xx response *and* a network/`fetch` failure alike —
comes out as one type, `ApusApiError` (`layers/core/app/utils/apiErrors.ts`), with `status` (0 for a network
failure that never got an HTTP response), `message` (parsed from the response body's `message`
field when present — matches `BadRequestExceptionHandler`'s `{"message": "..."}`  — otherwise a
sane per-status default), and `body` (the raw parsed error body, if any). `403`/`404` from the
api module carry no body at all by design (see `ForbiddenExceptionHandler`/
`NotFoundExceptionHandler`'s Javadoc — a 404 is also what a resource in a *different* tenant's
namespace returns, deliberately indistinguishable from "does not exist"), so do not assume every
`ApusApiError` has a parseable `body`.

`api/src/main/java/net/onelitefeather/apus/api/rest/support/*ExceptionHandler.java` is the
source of truth for this mapping; re-check it if the api module adds a new error shape.

**Not yet in the api module, therefore not in this client:** `POST /api/uploads` and
`POST /api/push/{token}` from the design spec's §11.1 endpoint table are Phase 6 work (push
sources, §14) and have no controller yet — nothing to point a client method at.

## Role logic (task 4 requirement) — convenience only, read before extending

`layers/core/app/utils/role.ts` mirrors the four roles from design spec §10.3
(`platform-admin`/`tenant-owner`/`tenant-operator`/`tenant-viewer`) and the exact same gating
logic the api module applies in
`api/src/main/java/net/onelitefeather/apus/api/security/ApusPrincipal.java` and
`api/src/main/java/net/onelitefeather/apus/api/rest/support/TenantAccess.java`
(`isPlatformAdmin`, `canWriteTenant`, `canReadTenant`).

**This exists purely to decide what the UI shows.** It enforces nothing, and it must never be
extended as though it did. The api module is the sole enforcement point (design spec §10.3:
"the backend is the enforcement point") and re-checks every one of these on every request,
regardless of what a compromised or simply out-of-date client renders. Concretely: hiding the
"Platform console" link from a non-`platform-admin` user is a convenience so they are not sent
to an application that will 403 on every call — it is not, and must not become, the reason `GET
/api/tenants` is safe to expose. The same holds one level up: shipping the console as a separate
image keeps its code off a tenant's machine, and that is a delivery property, not an access
control. If a future change here ever reads like "and therefore the request is safe to send",
that is the signal something has gone wrong; stop and re-read this section.

## Layouts and pages

Each application owns its own shell. `apps/app/app/layouts/default.vue` +
`app/components/layout/{AppHeader,AppNav}.vue`: header with branding and sign-out, nav with
Account and Tenant, plus one `isPlatformAdmin()`-gated anchor to `/console/` (a plain `<a>`, not
`<ULink to>` — this router knows nothing about the other application's routes).
`apps/console/app/components/layout/ConsoleHeader.vue` is the mirror image, with a link back.

The tenant app is organised around **worlds**, not around the API's resources. A world is a
`BlueMapMap` joined with the source feeding it, the renders that produced it and the hosting that
serves it — a join `buildWorlds` (`layers/core/app/utils/worlds.ts`) does client-side over four
existing list calls, because every one of those relationships is already carried in the response
bodies. `/` lists them with their end-to-end state; `/worlds/[name]` shows one whole.

```text
/                    Worlds -- the entry point
/worlds/[name]       One world: pipeline, public URL, live render, history, configuration
/sources             Sources, and /sources/new -- the guided four-step connect flow
/renders             History across all worlds, and /renders/[id] -- live progress and log
/hosting             Published addresses
/account             The signed-in user and their roles
```

The console has `/`, `/tenants`, `/tenants/new`, `/tenants/[name]` and `/renders`.

**Screens must not offer what the API cannot do.** There is no endpoint to create a map or a
hosting — they are declared through GitOps — so the empty states say what happens next and who
does it, rather than showing a button that would have to fail. Adding one later means adding the
endpoint first.

## Tests

`pnpm test` fans out across the workspace: `layers/core`'s plain-Vitest unit specs (135 tests)
plus each app's Nuxt-context component tests. Covered, per the "what carries logic" standard:

- `apiClient.spec.ts` — request/response wiring (auth header, JSON body, URL-encoding, 204
  handling), every documented error shape (400 with a body, 403/404 without one, a raw network
  failure, a non-JSON error body), and both SSE streaming methods (JSON-parsed events vs.
  raw-string log lines, plus a failed-to-open stream).
- `role.spec.ts` — claim parsing (recognised vs. unknown roles, case/whitespace normalisation,
  blank/missing tenant → `null`) and all three derived predicates, including the two
  API-mirrored edge cases (`platform-admin` excluded from `canWriteTenant`,
  `platform-admin` alone failing `canReadTenant`).
- `sse.spec.ts` — the SSE framer directly: single/multiple events, an event split across chunk
  boundaries, multi-`data:`-line joining, a trailing event with no final blank line, comment
  lines ignored, an empty stream.
- `jwt.spec.ts` — the unverified JWT payload decoder: normal decode, non-ASCII claim values,
  an unpadded base64url payload, and the two rejection paths.
- `oidc.spec.ts` — `buildOidcRedirectUris` for both applications' base paths, plus the
  slash-normalisation cases (missing trailing slash, empty base, trailing slash on the origin,
  a nested prefix).
- `pipeline.spec.ts` — the cell arithmetic, both ends included: 99.6% must leave a cell empty and
  print 99%, 0.4% must light one and print 1%, and a one-cell meter must still tell finished from
  started.
- `worlds.spec.ts` — the world join: the full chain, a `sourceRef` naming something the caller
  cannot see, a map with no source at all, renders belonging to no map, a render with no
  `startTime` to sort by, a hosting that lists the map but is not serving, and the orderings that
  keep the list from reshuffling between polls.

Some things do get a real Nuxt context, because nothing else catches them. Each app has a
`tests/nuxt/defaultLayout.nuxt.spec.ts`: a component referenced under its bare filename rather
than the directory-prefixed name Nuxt registers (`<AppHeader />` where only `LayoutAppHeader`
exists) compiles fine, renders as an empty custom element, and is invisible to both `vue-tsc`
and `nuxt build`. The tenant app shipped exactly that bug once.

`apps/app/tests/nuxt/appHeaderConsoleLink.nuxt.spec.ts` additionally mocks `useAuth` with a
`platform-admin` principal. That mock is not incidental: both the removed `/platform` link and
the new console link are role-gated, so without a signed-in principal neither renders and the
assertions would pass for entirely the wrong reason.

`pnpm test:server` builds each app and runs its Nitro spec — 7 cases for the tenant app, 8 for
the console (the extra one pins the bare root's `302`).

Not tested, per the same standard ("pure presentation needs no tests"): the remaining `.vue`
files and the two thin composables (`useAuth`/`useApiClient`), whose own logic content is close
to zero — see "Why plain Vitest" above.
