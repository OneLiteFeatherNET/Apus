# Apus UI

Web frontend for Apus (design spec `docs/superpowers/specs/2026-08-08-apus-design.md`, §11.2):
Nuxt 4 in SPA mode (`ssr: false`), Vue 3, Tailwind 4, Nuxt UI, VueUse.

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

```bash
corepack enable   # or: npm install -g pnpm
pnpm install
pnpm dev           # local dev server
pnpm build          # production build: .output/server + .output/public, what the image ships
pnpm test            # vitest, unit tests (no build needed)
pnpm test:server      # builds, then tests the built Nitro server -- see "Serving the built SPA"
pnpm lint              # eslint, includes eslint-plugin-vuejs-accessibility
pnpm typecheck          # vue-tsc
```

Copy `.env.example` to `.env` for local development and fill in the three variables (API base
URL, OIDC issuer, OIDC client ID) — see "Configuration" below.

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

## Project layout

Nuxt 4's actual current default: an `app/` directory holds everything client-side
(`app/pages`, `app/components`, `app/composables`, `app/layouts`, `app/middleware`,
`app/plugins`, `app/utils`, `app.vue`). There is no `server/` directory — this SPA has no
Nitro API routes of its own. The container does run a Nitro server (see "Serving the built
SPA" below), but the only thing it serves is the SPA shell and the client bundle; nothing
per-request is application logic, which is what "Why no server-side session" below rests on.

```text
app/
  app.vue                       -- <UApp><NuxtLayout><NuxtPage/></NuxtLayout></UApp>
  layouts/default.vue           -- header + nav, wraps every page
  components/layout/
    AppHeader.vue                 -- branding, signed-in user, sign-out
    AppNav.vue                    -- nav links; shows "Platform" only for platform-admin
  pages/
    index.vue                    -- REQUIRED page: signed-in user + their tenant (§11.2 task scope)
    auth/callback.vue             -- OIDC Authorization Code redirect target
    auth/silent-renew.vue         -- OIDC silent-renew iframe target
  middleware/auth.global.ts      -- requires a session on every route but /auth/*
  plugins/oidc.client.ts         -- restores the in-memory session before first render
  composables/
    useAuth.ts                    -- oidc-client-ts wrapper: user, principal, login/logout
    useApiClient.ts                -- wires useAuth()'s token into createApusApiClient()
  utils/                          -- plain TypeScript, NO Nuxt auto-imports/composables used
    apiClient.ts                    -- createApusApiClient(): the typed client, see below
    apiTypes.ts                     -- one interface per Java response/request record
    apiErrors.ts                     -- ApusApiError
    sse.ts                            -- parseSseStream(): SSE framing over a fetch body reader
    role.ts                           -- UI-side role helpers (convenience only, see below)
    jwt.ts                             -- decodeJwtPayload(): unverified, display-only decode
tests/unit/                       -- vitest; mirrors app/utils/, one spec file per module
tests/server/                     -- vitest against the built Nitro server (needs a build)
```

`app/utils/*` is deliberately framework-agnostic (no `useRuntimeConfig`, no `$fetch`, no
`ref`/`computed` from Vue) so it can be unit-tested with plain Vitest and no Nuxt test harness
— see "Why plain Vitest" below. The two Nuxt-aware composables in `app/composables/` are thin
wrappers around it.

### Why plain Vitest, not `@nuxt/test-utils`

Everything with real logic (`apiClient.ts`, `role.ts`, `jwt.ts`, `sse.ts`) is plain TypeScript
with no Nuxt runtime dependency, so a full Nuxt test environment (module resolution, virtual
`#imports`, a mounted app) would only add startup cost and indirection for no benefit. `useAuth`
and `useApiClient` themselves are thin enough (a few lines of wiring) that they are exercised
indirectly through the pure functions they call, per the task brief's "pure presentation needs
no tests" — the composables' own logic content is effectively zero.

## Serving the built SPA

`ui/Dockerfile` runs `pnpm build` and copies the whole `.output` into
`gcr.io/distroless/nodejs24-debian12:nonroot`, where the container starts Nuxt's own Nitro
server (`nitro.preset: 'node-server'`, see `nuxt.config.ts`). The runtime image is a Node
binary plus that output: no shell, no package manager, and none of the OS-level TLS/PCRE/zlib
stack the nginx base used to carry.

Run it locally exactly as the container does:

```bash
pnpm build && PORT=8080 node .output/server/index.mjs
```

`.output/public` holds the client bundle; there is **no `index.html` on disk**. With
`ssr: false` Nitro renders the SPA shell per request from `.output/server`, which is why the
runtime config below is read from the environment rather than baked in.

### What this buys, and what it costs

- **Runtime configuration.** `NUXT_PUBLIC_API_BASE_URL`, `NUXT_PUBLIC_OIDC_ISSUER` and
  `NUXT_PUBLIC_OIDC_CLIENT_ID` now reach the shell Nitro renders, so one image works for every
  installation. That is what unblocks item 5 of
  `docs/superpowers/specs/2026-08-13-helm-charts-design.md` — with the old static image the
  empty OIDC values were frozen in at build time and *no installation could log in*. The
  `apus-platform` chart does not pass them yet; that is its own change.
- **No nginx.** Every CVE in OpenSSL, PCRE, zlib, the shell and the package manager used to be
  reported against this image for code the deployment never executes — nothing here terminates
  TLS, rewrites requests or proxies. nginx also needed a writable filesystem for its pid and
  cache, which is why the chart could not set `readOnlyRootFilesystem: true`. It now can.
- **But: an npm dependency tree in the image.** `.output/server/node_modules` carries the
  packages Nitro externalises (the Vue runtime and compiler, `@babel/parser`,
  `@iconify/utils`, and a handful more — see `.output/server/package.json`). An SCA scanner
  will walk those, where a dependency-free server would have given it nothing to find. This is
  the deliberate trade for running Nitro's own server; the versions are the ones
  `pnpm-lock.yaml` already pins.
- **And: memory.** Nitro is ~63 MiB idle and peaks near 115 MiB at concurrency 100, where
  nginx idled at a few MiB. The image caps V8's old space
  (`NODE_OPTIONS=--max-old-space-size=64`; ~153 MiB without it) so the footprint does not
  depend on the node's RAM, and `ui.resources` in the `apus-platform` chart is set to
  match — change the two together.

Not `vite preview` / `nuxt preview`: that is a development preview server, and serving
production traffic with it would put vite, rollup and esbuild — this module's largest
dependency tree — into the runtime image.

### The header contract

Nitro serves the SPA shell with **no `Cache-Control` at all**, which leaves browsers free to
cache it heuristically — the exact failure the retired `nginx.conf` guarded against, where a
deploy strands clients on HTML referencing hashed assets the new build no longer ships. The
`routeRules` in `nuxt.config.ts` therefore pin `no-store` on the shell and repeat Nitro's own
`immutable` on `/_nuxt/**` (which `/**` would otherwise override), and add `nosniff` to both.

`tests/server/nitro.spec.ts` spawns the built `node .output/server/index.mjs` — the container's
actual CMD — and holds that contract, plus the deep-link reload, the 304 on a conditional
asset request, a missing chunk staying a 404, and the SIGTERM shutdown. It needs a build, so it
runs as `pnpm test:server` (which builds first) rather than as part of `pnpm test`; CI runs
both.

Two differences from the old nginx behaviour are Nitro's and left as they are: a missing
non-`/_nuxt/` file answers 200 with the shell rather than 404, and `x-powered-by: Nuxt` is sent
on the shell (route rules cannot remove it — Nuxt sets it after they apply; stripping it needs
a Nitro plugin).

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
keep one in a pure SPA). Configuration is three environment variables
(`NUXT_PUBLIC_API_BASE_URL`, `NUXT_PUBLIC_OIDC_ISSUER`, `NUXT_PUBLIC_OIDC_CLIENT_ID`), matching
the design spec's instruction to keep the broker choice (§15: Keycloak vs. Zitadel, undecided)
out of code entirely.

### Token storage — binding requirement, and the reasoning

**Tokens are held in memory only** (`oidc-client-ts`'s `InMemoryWebStorage`, wired in
`app/composables/useAuth.ts`), never in `localStorage` and — one step further than the library's
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
Keycloak client scope mapper vs. a Zitadel action, for instance). `app/utils/role.ts` hardcodes
the claim names `roles` and `organization` to match what the `api` module already expects
(`PrincipalResolver.ROLES_CLAIM`/`TENANT_CLAIM`) — the broker must be configured to put them
there, in the *access* token specifically (the token this UI sends as the bearer credential and
the one the API actually validates), not only the ID token. Silent renew via a hidden iframe
also assumes the broker allows being framed for that purpose (no `X-Frame-Options: DENY` on its
authorize endpoint) — most modern brokers support this for a registered redirect URI, but it is
worth a five-minute check against whichever broker Phase 5 lands on, before relying on it.

## Typed API client (task 3 requirement) — structure for the two follow-up agents

`app/utils/apiClient.ts` exports `createApusApiClient(options)`, a factory (not a class you
`new`) returning an object with one method per endpoint. `app/composables/useApiClient.ts` is
the Nuxt-facing entry point — call `useApiClient()` from a page/component and it is already
wired to the current access token and configured API base URL.

Every request/response type in `app/utils/apiTypes.ts` is a direct mirror of the Java record it
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

// Live streams (SSE) -- see app/utils/sse.ts for why these use `fetch`, not `EventSource`
await api.streamRenderEvents(id, { onMessage, onClose, onError }, abortSignal)  // RenderProgressEvent
await api.streamRenderLogs(id, { onMessage, onClose, onError }, abortSignal)     // raw log line (string)
```

**Error handling:** every failure — a non-2xx response *and* a network/`fetch` failure alike —
comes out as one type, `ApusApiError` (`app/utils/apiErrors.ts`), with `status` (0 for a network
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

`app/utils/role.ts` mirrors the four roles from design spec §10.3
(`platform-admin`/`tenant-owner`/`tenant-operator`/`tenant-viewer`) and the exact same gating
logic the api module applies in
`api/src/main/java/net/onelitefeather/apus/api/security/ApusPrincipal.java` and
`api/src/main/java/net/onelitefeather/apus/api/rest/support/TenantAccess.java`
(`isPlatformAdmin`, `canWriteTenant`, `canReadTenant`).

**This exists purely to decide what the UI shows.** It enforces nothing, and it must never be
extended as though it did. The api module is the sole enforcement point (design spec §10.3:
"the backend is the enforcement point") and re-checks every one of these on every request,
regardless of what a compromised or simply out-of-date client renders. Concretely: hiding the
"Platform" nav link from a non-`platform-admin` user is a convenience so they are not staring at
a dashboard that will 403 on every call — it is not, and must not become, the reason `GET
/api/tenants` is safe to expose. If a future change here ever reads like "and therefore the
request is safe to send", that is the signal something has gone wrong; stop and re-read this
section.

## Base layout and pages (task 5 requirement)

`app/layouts/default.vue` + `app/components/layout/{AppHeader,AppNav}.vue`: header with branding
and sign-out, nav that conditionally shows a "Platform" link via `isPlatformAdmin()` (see above).
The link target (`/platform`) does not have a page behind it yet — that dashboard is the next
task's scope, not this one's.

`app/pages/index.vue` is the one page this task ships: the signed-in user's subject, email,
tenant, and roles. Nothing else — both dashboard levels (design spec §11.2: platform and tenant)
are explicitly out of scope here.

## Tests (task 6 requirement)

`pnpm test` runs Vitest against `tests/unit/**/*.spec.ts` (47 tests as of this task). Covered,
per the task brief's "what carries logic" standard:

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

Not tested, per the same standard ("pure presentation needs no tests"): the `.vue`
files themselves (layout, nav, the account page) and the two thin composables
(`useAuth`/`useApiClient`), whose own logic content is close to zero — see "Why plain Vitest"
above.
