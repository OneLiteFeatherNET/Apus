# Apus UI — Split and Redesign: Design

**As of:** 2026-08-15
**Status:** Draft for approval

Today `ui/` is one Nuxt 4 SPA that carries two products at once: a tenant-facing application
(`/tenant/*`) and a platform management console (`/platform/*`). This design splits them into
two independently built and independently deployed applications sharing a common core and a
common design system, and replaces the current unstyled Nuxt UI defaults with a deliberate
Tailwind 4 design whose structure follows the way each audience actually works.

Scope decisions taken up front, in the brainstorming session that produced this document:

1. **Two applications, two images, two deployments.** A tenant user must never be served the
   console's code.
2. **Hybrid component basis.** Nuxt UI stays for the accessibility-expensive primitives
   (dialog, select/combobox, toast, tooltip, dropdown, tabs). Everything visually load-bearing
   — shell, navigation, cards, tables, status, progress, empty states — is built as our own
   Tailwind 4 components in a shared design layer.
3. **World-centric application, resource views behind it.** The tenant app's entry point becomes
   the list of worlds with end-to-end status, not four unrelated CRUD tables.
4. **No new functionality, no API changes.** Everything below is composed from endpoints that
   exist today.

---

## 1. Starting point

```text
ui/                            one pnpm project, one image (apus/ui), one Deployment
  app/pages/index.vue            account page
  app/pages/tenant/*             sources, maps, renders, hosting     -- tenant roles
  app/pages/platform/index.vue   tenants, quotas, cluster renders    -- platform-admin
  app/components/{layout,tenant,platform}/**
  app/{composables,utils,middleware,plugins}/**   shared by both
  app/assets/css/main.css        `@import "tailwindcss"; @import "@nuxt/ui";` -- nothing else
```

Roughly 1,700 lines of Vue. The split line is already visible in the directory names; what is
missing is a build boundary, a delivery boundary, and any design at all.

### 1.1 What the API actually offers

The redesign is bounded by the `api` module's current surface. Verified against
`api/src/main/java/net/onelitefeather/apus/api/rest/**`:

| Resource | Read | Write |
| --- | --- | --- |
| Tenants | `GET /api/tenants` | `POST /api/tenants`, `PATCH /api/tenants/{name}` — `platform-admin` |
| World sources | `GET /api/sources` | `POST /api/sources` |
| Maps | `GET /api/maps`, `GET /api/maps/{id}` | `POST /api/maps/{id}/render` |
| Renders | `GET /api/renders`, `GET /api/renders/{id}`, `GET /api/renders/cluster` | — |
| Renders (live) | SSE `…/events`, SSE `…/logs` | — |
| Hostings | `GET /api/hostings` | — |

Two consequences that shape everything downstream:

- **Maps and hostings cannot be created from a UI.** They are declared through GitOps and
  reconciled by the operator. A guided "add a world" wizard that creates a map is therefore not
  buildable without API work, which point 4 above excludes. What *is* buildable is a guided
  **"connect a source"** flow (`POST /api/sources`, with four type-specific shapes) plus honest,
  explanatory states for the stages the user cannot themselves trigger.
- **The join that makes a world-centric UI possible already exists in the response bodies.**
  `BlueMapMapResponse.source.sourceRef` names a `WorldSourceResponse.name`;
  `BlueMapRenderResponse.mapRef` names a map; `BlueMapHostingResponse.maps[]` lists map names
  and carries the public `url`. A world view model is a pure client-side join over four list
  calls — no new endpoint, and a pure function that unit-tests without a browser.

### 1.2 The API has no CORS configuration

`api/src/main/resources/application.yml` configures JWT validation and nothing else; there is
no `micronaut.server.cors` block. A console served from a *different* hostname would therefore
fail every request at the preflight, and fixing that means editing the API — excluded by scope.

**The console is served from the same origin, under the path prefix `/console`.** This is not a
compromise forced only by CORS: it also avoids a second DNS record, a second certificate, and a
second OIDC client registration, and it keeps the API base URL identical for both applications.
Origin-level isolation between the two apps was never the requirement; code-delivery separation
was, and a separate bundle behind a separate Deployment delivers exactly that.

---

## 2. Repository layout

`ui/` becomes a pnpm workspace with two applications and two Nuxt layers.

```text
ui/
  package.json                 workspace root: fan-out scripts, shared dev toolchain
  pnpm-workspace.yaml          packages: ['apps/*', 'layers/*']
  eslint.config.mjs            one config for the whole workspace
  .nvmrc, tsconfig.json

  layers/
    core/                      @apus/ui-core -- domain, auth, transport. No visual code.
      nuxt.config.ts
      app/composables/{useAuth,useApiClient}.ts
      app/middleware/auth.global.ts
      app/plugins/oidc.client.ts
      app/pages/auth/{callback,silent-renew}.vue
      app/utils/{apiClient,apiTypes,apiErrors,sse,sseController,role,jwt,
                 formatTimestamp,renderProgress,storageUsage,domainValidation,worlds}.ts
      tests/unit/**

    design/                    @apus/ui-design -- tokens and presentational components.
      nuxt.config.ts           registers @nuxt/ui and the stylesheet
      app/assets/css/{tokens.css,main.css}
      app/components/**        AppShell, PageHeader, StatusPill, DataTable, StatTile,
                               EmptyState, PipelineRail, ProgressMeter, LogConsole,
                               MetaList, Toolbar, ConnectionState, CopyField
      tests/nuxt/**

  apps/
    app/                       @apus/ui-app -- the tenant application (image apus/ui)
      nuxt.config.ts           extends ['../../layers/core', '../../layers/design']
      app/pages/**, app/components/**, tests/**
    console/                   @apus/ui-console -- management (image apus/console)
      nuxt.config.ts           same extends, plus app.baseURL '/console/'
      app/pages/**, app/components/**, tests/**

  Dockerfile.app
  Dockerfile.console
```

**Why Nuxt layers rather than plain workspace packages.** Auto-imports, `~/utils`, component
resolution, middleware and plugins all work across a layer exactly as they do inside an app;
a plain package would force explicit imports everywhere and would not let a layer contribute
`auth.global.ts` or the OIDC plugin. `extends` is the mechanism Nuxt provides for precisely
this. An app can still override any layer file by placing a file at the same path — the app's
copy wins — which gives us a per-app escape hatch without conditional code in the layer.

**Why two layers rather than one.** `core` has no visual content and no dependency on `@nuxt/ui`
or Tailwind; it is the piece we would want in a third surface (a status page, a CLI-adjacent
tool) without dragging a design system along. `design` has no knowledge of Apus's domain — it
knows about surfaces, status vocabulary and tables, not about renders. Keeping the boundary
means each is understandable and testable on its own, and a design change cannot silently
depend on a domain type.

**Naming.** The tenant application keeps the image name `apus/ui` and the chart's `ui` key: from
an operator's point of view it stays "the UI users see", and existing deployments keep working
without a rename. The console is new: image `apus/console`, chart key `console`.

---

## 3. Delivery

### 3.1 Images

Two Dockerfiles, both built from the repository root as context (matching what
`release-please.yml` already does for `ui/Dockerfile`), both installing the workspace once and
building a single app:

```dockerfile
COPY ui/package.json ui/pnpm-lock.yaml ui/pnpm-workspace.yaml ./
COPY ui/apps/app/package.json ./apps/app/
COPY ui/layers/ ./layers/
RUN pnpm install --frozen-lockfile --filter @apus/ui-app...
COPY ui/ ./
RUN pnpm --filter @apus/ui-app build
```

Runtime stage is unchanged from today's: `gcr.io/distroless/nodejs24-debian12:nonroot`, uid
65532, `PORT=8080`, `HOST=0.0.0.0`, `NODE_OPTIONS=--max-old-space-size=64`, CMD on the app's own
`.output/server/index.mjs`. The console gets the same shape.

### 3.2 Runtime configuration

Both applications read the same three `NUXT_PUBLIC_*` variables at runtime
(`API_BASE_URL`, `OIDC_ISSUER`, `OIDC_CLIENT_ID`) — the mechanism documented in `ui/README.md`
is unchanged, it just applies twice.

The console's OIDC redirect target moves to `/console/auth/callback` (and
`/console/auth/silent-renew`). Because it is the same origin and the same public client, this
is **one extra redirect URI to register at the broker**, not a second client. This is an
operator-visible prerequisite and belongs in the chart's `NOTES.txt` and in `ui/README.md`.

### 3.3 Chart and ingress

`apus-platform` gains a `console:` block mirroring `ui:` (image, `replicaCount: 1` — the
audience is a handful of admins, not tenants — resources, security context, env, envFrom,
scheduling) plus `console.enabled` (default `true`), and a `console-deployment.yaml` /
`console-service.yaml` pair modelled on the existing `ui-*` templates.

The ingress gains one rule, placed **between** `/api` and `/`, because the controller evaluates
paths in the order they appear and `/` would otherwise swallow it:

```yaml
- path: /api      -> api service
- path: /console  -> console service      # new
- path: /         -> ui service
```

The console's Nuxt config sets `app.baseURL: '/console/'` so its own asset URLs and router
resolve under the prefix; no ingress rewrite annotation is needed, which keeps the rule
controller-agnostic. `routeRules` (the `no-store` shell / `immutable` `/_nuxt/**` contract that
`tests/server/nitro.spec.ts` holds) are repeated for the console under its prefix.

When `console.enabled` is `false`, neither the Deployment, the Service, nor the ingress rule is
rendered.

### 3.4 CI and release

- `build-pr.yml`'s `ui` job stays a single job: `pnpm install` once at the workspace root, then
  `pnpm lint`, `pnpm typecheck`, `pnpm test` fanned out across the workspace with `-r`, then
  `pnpm test:server` for both apps.
- `release-please.yml` gains a `publish-console` job cloned from `publish-ui` (same standalone
  gate, `image-name: apus/console`, `dockerfile: ui/Dockerfile.console`), and
  `publish-charts`'s `needs` list grows by one.

---

## 4. The design system

The design lives in `layers/design` and is one system with two personalities. Both applications
share every neutral, every status colour, every type step and every component; they differ in
accent hue and information density. That is deliberate: a "render failed" badge must look
identical wherever an operator sees it, while the surrounding surface must make it obvious at a
glance whether they are in the tenant application or in the platform console.

### 4.1 Tokens

Tailwind 4's `@theme` in `tokens.css`, expressed in `oklch()` so both the accent ramp and the
dark/light pair derive from a small number of variables rather than a hand-maintained palette:

```css
@theme {
  --accent-h: 200;                       /* overridden per app, see below */

  --color-accent-500: oklch(0.68 0.13 var(--accent-h));
  /* …400/600/700 derived by lightness only, so both apps get a coherent ramp free */

  --color-surface-0 …-2                  /* page, card, raised */
  --color-border-subtle / -strong
  --color-text-primary / -secondary / -muted
  --color-ok / -warn / -err / -info      /* identical in both apps, by design */

  --radius-card: 0.875rem;
  --font-sans: system-ui, …;
  --font-mono: ui-monospace, …;
}
```

- **App accent:** `--accent-h: 190` — a cyan-teal. It reads as the product surface: maps, water,
  live progress.
- **Console accent:** `--accent-h: 285` — a violet. Distinct at a glance from the app, and
  distinct from the warning amber, so it never competes with status semantics.

**No web fonts.** A system font stack plus `ui-monospace` for identifiers, versions, bucket
paths and log lines. Distinctiveness comes from the type scale, the spacing rhythm and the
surface treatment, not from a downloaded typeface — which also keeps the distroless image free
of font assets and the page free of an external request.

**Dark and light, dark by default.** Both palettes are defined; `@nuxtjs/color-mode` (already
present via Nuxt UI) carries the preference. The operations domain is looked at on dark screens
next to a dark BlueMap, and the console's density reads better dark; a light theme is a first
class citizen all the same, not an afterthought that inverts badly.

### 4.2 Component inventory

Shared, in `layers/design`:

| Component | Purpose |
| --- | --- |
| `AppShell` | Page frame: skip link, `<header>`, optional `<nav aria-label>`, `<main>`, focus reset on route change |
| `PageHeader` | Title, one-line explanation, primary action slot, breadcrumb slot |
| `StatusPill` | The single status vocabulary: CR phases and condition states → one colour/label mapping |
| `PipelineRail` | The signature element (see §5.2) |
| `ProgressMeter` | Percent, ETA, `degraded` marker; tabular numerals, monotonic, does not jump backwards |
| `DataTable` | Dense table: sticky header, sortable columns, row link, per-row action slot, skeleton and empty states built in |
| `StatTile` | A single number with a label and an optional trend/quota bar |
| `EmptyState` | Illustration slot, one sentence of explanation, at most one primary action |
| `LogConsole` | Monospace log surface, dark in both themes, follow-tail and wrap toggles |
| `ConnectionState` | Live/reconnecting/disconnected indicator for SSE-backed views |
| `MetaList` | Label/value pairs for resource metadata, with `CopyField` for identifiers and URLs |
| `Toolbar` | Filter/search/action row above a table |

Nuxt UI keeps `UModal`, `USelectMenu`, `UInput`, `UForm`, `UToast`, `UTooltip`, `UDropdownMenu`,
`UTabs`, `UPopover` — the components where getting focus management, ARIA wiring and keyboard
behaviour right by hand would be a large, uninteresting cost. They are themed through the token
set above so they do not read as a different system.

### 4.3 Accessibility

`eslint-plugin-vuejs-accessibility` stays in the lint gate. Beyond it, the shell components
carry the parts a linter cannot check: a skip link, one `<main>` per page, labelled navigation
landmarks, focus moved to the page heading on client-side navigation, and a visible focus ring
defined once in the token layer rather than per component. Status is never communicated by
colour alone — every `StatusPill` carries a label, every `ProgressMeter` a numeric readout.

---

## 5. The tenant application

### 5.1 Information architecture

```text
/                    Worlds        -- the entry point: every world, end-to-end status
/worlds/[name]       World detail  -- the whole chain for one world, in one place
/sources             Sources       -- list, with "Connect a source"
/sources/new         Guided flow   -- type -> connection -> world selection -> review
/renders             Render history across all worlds
/renders/[id]        Render detail -- live progress and logs
/hosting             Hosting endpoints
/account             The signed-in user, their tenant, their roles
```

Compared to today: `/` stops being an account page (an account page is not a reason to open a
product), `tenant/maps` becomes `/worlds`, and the four resource pages remain reachable for the
detail work they are good at — but they are no longer the only way to understand the system.

### 5.2 The world view model, and the pipeline rail

A "world" is a `BlueMapMap` joined with the source that feeds it, the renders that produced it
and the hosting that serves it. `layers/core/app/utils/worlds.ts` exports a pure function:

```ts
buildWorlds(maps, sources, renders, hostings): World[]
```

Each `World` carries the map, the resolved source (or `null` — a `sourceRef` can dangle), its
renders newest-first, its hosting entry, and a derived five-stage status:

```text
Source ──▶ Bundle ──▶ Map ──▶ Render ──▶ Hosting
```

`PipelineRail` renders exactly these five stages, each in one of: *done*, *active* (with live
progress), *pending*, *failed*, *not applicable*. It appears full-width on the world detail page
and compacted to a single row of five dots in the world list, so the list answers "where is each
of my worlds stuck?" without a click.

This is the piece that turns four disconnected tables into a flow: the chain is the domain's
real shape, it is derivable from data we already fetch, and it gives every state — including the
awkward ones, like a source producing bundles for a map that does not exist yet — a place to be
displayed rather than a silence to be puzzled over.

### 5.3 Flows

**First contact.** No sources: one full-width `EmptyState` with a single call to action,
"Connect a source". A source but no map: an explanatory state naming what happens next (the
platform declares the map; the source's bundles are already being collected) with a link to the
source — honest about the GitOps boundary rather than offering a button that cannot exist.

**Connect a source** (`/sources/new`). The one genuine wizard, because `CreateWorldSourceRequest`
is a four-way type union today rendered as one form with conditional fields. Four steps: pick the
type (s3 / pterodactyl / upload / push, each with a one-sentence description of when to use it),
fill in the type-specific connection fields, select worlds and retention, review and create.
Validation happens per step, so an error appears next to the field that caused it and never as a
list of failures after a long form.

**Trigger a render.** From the world detail page. `force` requires confirmation and states what
it costs (it re-renders from scratch and consumes history budget). On success, the view does not
navigate away: the render appears live in the pipeline rail exactly where the user clicked, and
the progress meter takes over. Continuity of place is the point — today's flow drops the user
into a list.

**Follow a render.** SSE-backed, with `ConnectionState` visible. A dropped stream says so and
retries visibly; it never degrades into a progress bar that has silently stopped moving. Logs
sit in `LogConsole` on the same page, tail-following by default, with the toggle preserved
across navigation.

**Open the map.** The public URL is one click from both the world list and the world detail, with
a copy action next to it. Reaching the rendered map is the reason the product exists; it is never
more than one interaction away.

---

## 6. The management console

### 6.1 Information architecture

```text
/                    Overview -- tenants, cluster-wide renders in flight, storage pressure
/tenants             Tenant list -- dense, sortable, quota vs. observed usage as a bar
/tenants/new         Create a tenant
/tenants/[name]      Tenant detail -- quota, allowed hosting domains, conditions
/renders             Cluster-wide renders, live
```

Today all of this is one page that stacks a list, a create form and a cluster table. Splitting
it gives each task its own focus, gives a tenant a URL worth linking to, and stops an admin from
scrolling past a create form on the way to an operational question.

### 6.2 Personality

Permanent left sidebar (admins move between a few known places repeatedly; a top bar wastes the
horizontal room dense tables want), violet accent, tighter row height and type scale than the
app, and a persistent "Platform" marker in the top bar. `storageUsage.ts` and
`domainValidation.ts` — already written, already tested — drive the quota bar and the domain
editor.

### 6.3 Access

`isPlatformAdmin()` still decides what the console renders, and it still enforces nothing: the
API re-checks every request and answers 403 regardless. What changes is the failure mode. A
non-admin who reaches the console URL now gets a deliberate, explanatory page rather than a
dashboard that 403s on every call — and, more importantly, the app no longer renders a nav link
into an area most of its users cannot enter.

---

## 7. Data flow and error handling

Unchanged in mechanism, sharpened in presentation. `createApusApiClient()` still turns every
failure — HTTP and network alike — into one `ApusApiError`. The design layer gives that one type
three presentations, chosen by `status`:

| Case | Presentation |
| --- | --- |
| `403` | An explanatory panel: what this area is, why it is not visible, who to ask |
| `404` | "Not found, or not in your tenant" — matching the API's deliberate ambiguity |
| `0` (network) | An inline retry affordance, not a dead end; the rest of the page keeps its data |
| other | Inline alert next to the section that failed |

Dashboards keep today's `Promise.allSettled` pattern: one failing endpoint degrades its own tile
and leaves the others intact.

---

## 8. Tests

| What | How |
| --- | --- |
| `layers/core/app/utils/**` | Plain Vitest, as today. The existing specs move unchanged |
| `buildWorlds()` | New unit spec: full chain, dangling `sourceRef`, map without hosting, renders belonging to no map, empty inputs, ordering |
| Design layer components | `@nuxt/test-utils` component tests for the a11y-carrying ones — `AppShell` (skip link, landmarks, focus on navigation), `StatusPill` (label present, not colour-only), `DataTable` (header association, keyboard row activation) |
| Both apps | `tests/server` against the built Nitro output, incl. the console's `baseURL` and the `no-store` / `immutable` header contract |
| Lint | `eslint-plugin-vuejs-accessibility` across the workspace, unchanged |

Purely presentational components stay untested, per this module's existing standard.

---

## 9. Sequence, risks, non-goals

**Sequence.** Delivery boundary first, design second — so that a broken build is discovered
against code we have not yet rewritten:

1. Workspace and layers, existing app moved over unchanged, all tests green.
2. Console extracted into its own app.
3. Dockerfiles, chart, ingress, CI, docs — both images shipping.
4. Design layer: tokens and primitives.
5. Tenant application: world-centric IA and the redesign.
6. Console: redesign.

**Risks.**

- *`@nuxt/ui` inside a layer.* Registering the module from a layer's own `nuxt.config.ts` is
  supported, but the combination with a pnpm workspace and hoisting is the one thing here that
  can fail in a way that is expensive to discover late. Step 1 proves it before anything is
  rewritten.
- *`app.baseURL` for the console.* Interacts with the OIDC redirect URI, the `routeRules`
  patterns, and the ingress rule order. All three are covered by `tests/server` and a chart
  template test.
- *Two Deployments instead of one.* Roughly 128–256 Mi more requested memory in the cluster.
  `console.replicaCount: 1` keeps it modest.
- *Image name confusion.* `apus/ui` continuing to mean "the tenant app" is a decision that must
  be stated in `ui/README.md` and the chart README, or the next person will assume it is both.

**Non-goals.** No API changes. No quota/domain-approval/members features beyond what the current
responses carry. No map or hosting creation from the UI — the API has no such endpoint. No
embedded BlueMap (design spec §11.2 defers it). No change to token storage, the OIDC flow, or
the "backend is the enforcement point" rule.
