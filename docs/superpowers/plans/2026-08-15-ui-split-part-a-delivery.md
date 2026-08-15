# Apus UI Split — Part A: Workspace and Delivery — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `ui/` into a pnpm workspace holding two Nuxt layers and two applications — the tenant app and the management console — each built, tested and shipped as its own image and Deployment, with behaviour and appearance unchanged.

**Architecture:** `layers/core` carries the domain, auth and transport code with no visual content; `layers/design` registers `@nuxt/ui` and the stylesheet; `apps/app` and `apps/console` each extend both layers and own their pages. The console is served same-origin under the path prefix `/console` (the `api` module has no CORS configuration), which it reaches via `app.baseURL` plus one new ingress rule ordered between `/api` and `/`.

**Tech Stack:** pnpm 11 workspaces, Nuxt 4.5 layers (`extends`), Nuxt UI 4, Tailwind 4, Vitest 4, Helm, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-08-15-ui-split-and-redesign-design.md`

## Global Constraints

- **No API changes.** Every behaviour must be composed from endpoints that exist today. The `api` module is not touched by this plan.
- **No visual redesign in this plan.** Part A moves code and changes delivery. Pages must look and behave as they do now. The design system is Part B.
- **Dependency versions stay exactly pinned**, no `^`/`~`, matching the existing `ui/package.json`. Do not bump anything while moving it.
- **`pnpm` is not installed in this environment and `corepack` is unavailable.** Run every `pnpm` command in this plan as `npx --yes pnpm@11.20.0 <args>` from `ui/`. The plan writes `pnpm` for readability.
- **Commit signing:** the repository's SSH signing key is a hardware token that may not be present. If `git commit` fails with `No private key found`, re-run it with `--no-gpg-sign` and note it in the final report.
- **The tenant app keeps the image name `apus/ui` and the chart key `ui`.** The console is new: image `apus/console`, chart key `console`.
- **Package names:** `@apus/ui-core`, `@apus/ui-design`, `@apus/ui-app`, `@apus/ui-console`. The workspace root is `@apus/ui-workspace`.
- **The `api` prefix is reserved.** Ingress path order must be `/api`, then `/console`, then `/`; controllers evaluate paths in the order they appear for a host.
- **Accessibility lint stays in the gate.** `eslint-plugin-vuejs-accessibility` must pass on every commit.

---

## File Structure

```text
ui/
  package.json                 @apus/ui-workspace  -- fan-out scripts only, no app code
  pnpm-workspace.yaml          packages: apps/*, layers/*  (keeps today's allowBuilds block)
  eslint.config.mjs            one flat config for the whole workspace
  .nvmrc                       unchanged (24)
  .env.example                 unchanged three variables, note about both apps

  layers/core/                 @apus/ui-core -- no Vue components, no CSS, no @nuxt/ui
    package.json
    nuxt.config.ts             empty defineNuxtConfig({}) -- presence makes it a layer
    vitest.config.ts           plain Vitest over tests/unit
    app/composables/{useAuth,useApiClient}.ts
    app/middleware/auth.global.ts
    app/plugins/oidc.client.ts
    app/pages/auth/{callback,silent-renew}.vue
    app/utils/{apiClient,apiTypes,apiErrors,sse,sseController,role,jwt,
               formatTimestamp,renderProgress,storageUsage,domainValidation,oidc}.ts
    tests/unit/**

  layers/design/               @apus/ui-design -- registers @nuxt/ui, owns the stylesheet
    package.json
    nuxt.config.ts
    app/assets/css/main.css

  apps/app/                    @apus/ui-app -- image apus/ui
    package.json, nuxt.config.ts, tsconfig.json
    vitest.nuxt.config.ts, vitest.server.config.ts
    app/app.vue, app/layouts/default.vue
    app/components/layout/{AppHeader,AppNav}.vue
    app/components/tenant/**   (moved verbatim)
    app/pages/{index.vue, tenant/**}
    tests/{nuxt,server}/**

  apps/console/                @apus/ui-console -- image apus/console
    package.json, nuxt.config.ts, tsconfig.json
    vitest.nuxt.config.ts, vitest.server.config.ts
    app/app.vue, app/layouts/default.vue
    app/components/layout/{ConsoleHeader,ConsoleNav}.vue
    app/components/platform/**  (moved verbatim)
    app/pages/index.vue
    tests/{nuxt,server}/**

  Dockerfile.app               replaces ui/Dockerfile
  Dockerfile.console           new

deploy/charts/apus-platform/
  templates/console-deployment.yaml, console-service.yaml   new
  templates/ingress.yaml                                     one rule added
  templates/NOTES.txt, values.yaml, values.schema.json       console block
.github/workflows/{build-pr.yml,release-please.yml}          workspace job, publish-console
```

**Why the split lands this way.** `core` must not depend on `@nuxt/ui` or Tailwind, so a third surface could consume it without a design system; `design` must not know a render from a tenant, so a design change cannot silently depend on a domain type. Component tests for design-layer components live in the consuming apps, which keeps the layers free of a Nuxt test harness of their own.

---

## Task 1: Workspace skeleton and the core layer

**Files:**

- Create: `ui/pnpm-workspace.yaml` (replacing today's), `ui/package.json` (replacing today's), `ui/layers/core/package.json`, `ui/layers/core/nuxt.config.ts`, `ui/layers/core/vitest.config.ts`
- Modify: `/.gitignore` (its `ui/` patterns are anchored one level too high for a workspace)
- Move (via `git mv`, contents unchanged): `ui/app/utils/*.ts` → `ui/layers/core/app/utils/`, `ui/app/composables/*.ts` → `ui/layers/core/app/composables/`, `ui/app/middleware/auth.global.ts` → `ui/layers/core/app/middleware/`, `ui/app/plugins/oidc.client.ts` → `ui/layers/core/app/plugins/`, `ui/app/pages/auth/` → `ui/layers/core/app/pages/auth/`, `ui/tests/unit/` → `ui/layers/core/tests/unit/`
- Delete: `ui/vitest.config.ts` (superseded by the layer's own)

**Interfaces:**

- Consumes: nothing.
- Produces: workspace package `@apus/ui-core` at `ui/layers/core`, extendable by a Nuxt app as `extends: ['@apus/ui-core']`. Its `app/utils/*` exports keep every name they have today (`createApusApiClient`, `ApusApiError`, `parseSseStream`, `parsePrincipal`, `isPlatformAdmin`, `canReadTenant`, `canWriteTenant`, `decodeJwtPayload`, and the rest) — no signature changes in this task.

- [ ] **Step 1: Create the workspace root manifest**

Replace `ui/package.json` entirely:

```json
{
  "name": "@apus/ui-workspace",
  "version": "0.1.0",
  "description": "Apus web UI workspace -- the tenant application, the management console, and the layers they share",
  "private": true,
  "type": "module",
  "engines": {
    "node": "^22.12.0 || ^24.11.0 || >=26.0.0"
  },
  "packageManager": "pnpm@11.20.0",
  "scripts": {
    "dev:app": "pnpm --filter @apus/ui-app dev",
    "dev:console": "pnpm --filter @apus/ui-console dev",
    "build": "pnpm -r build",
    "lint": "eslint .",
    "lint:fix": "eslint . --fix",
    "typecheck": "pnpm -r typecheck",
    "test": "pnpm -r test",
    "test:server": "pnpm -r test:server"
  },
  "devDependencies": {
    "eslint": "10.8.1",
    "eslint-plugin-vuejs-accessibility": "2.5.0"
  }
}
```

Note what left: every runtime and build dependency now belongs to the package that uses it. Only the two ESLint packages stay at the root, because `pnpm lint` runs there over the whole workspace.

- [ ] **Step 2: Declare the workspace members**

Replace `ui/pnpm-workspace.yaml`:

```yaml
packages:
  - 'apps/*'
  - 'layers/*'

# Unchanged from the single-package layout: these three need their install scripts to run.
allowBuilds:
  esbuild: true
  unrs-resolver: true
  vue-demi: true
```

- [ ] **Step 3: Create the core layer's manifest**

`ui/layers/core/package.json`:

```json
{
  "name": "@apus/ui-core",
  "version": "0.1.0",
  "description": "Apus UI core layer -- OIDC session, typed API client, wire types, role helpers. No visual code.",
  "private": true,
  "type": "module",
  "main": "./nuxt.config.ts",
  "scripts": {
    "test": "vitest run -c vitest.config.ts",
    "test:watch": "vitest -c vitest.config.ts",
    "typecheck": "echo 'typechecked through the apps that extend this layer'"
  },
  "dependencies": {
    "oidc-client-ts": "3.5.0"
  },
  "devDependencies": {
    "happy-dom": "20.11.2",
    "vitest": "4.1.10"
  }
}
```

`typecheck` is a deliberate no-op: a layer has no `.nuxt/tsconfig.json` of its own, and its files are typechecked by every app that extends it (`vue-tsc` follows the layer's sources). Making it a no-op keeps `pnpm -r typecheck` uniform instead of needing a filter.

- [ ] **Step 4: Create the core layer's Nuxt config**

`ui/layers/core/nuxt.config.ts`:

```ts
// Layer marker. Nuxt discovers app/{utils,composables,middleware,plugins,pages} by convention
// once a directory has a nuxt.config.ts and is named in an app's `extends`.
//
// Deliberately empty: this layer registers no module and no CSS. It must stay installable in a
// surface that has neither @nuxt/ui nor Tailwind -- see the design doc, §2.
export default defineNuxtConfig({})
```

- [ ] **Step 5: Move the shared code with `git mv`**

Run from `ui/`:

```bash
mkdir -p layers/core/app/{utils,composables,middleware,plugins,pages} layers/core/tests
git mv app/utils layers/core/app/utils
git mv app/composables layers/core/app/composables
git mv app/middleware layers/core/app/middleware
git mv app/plugins layers/core/app/plugins
git mv app/pages/auth layers/core/app/pages/auth
git mv tests/unit layers/core/tests/unit
git mv vitest.config.ts layers/core/vitest.config.ts
```

Do not edit any moved file's contents in this step. `git mv` keeps the history readable, which matters for files carrying as much reasoning in comments as `useAuth.ts` does.

- [ ] **Step 6: Point the layer's Vitest config at its own sources**

`ui/layers/core/vitest.config.ts` — only the alias comment and nothing else changes, because the config was already relative to its own directory:

```ts
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

// Plain Vitest, not @nuxt/test-utils' Nuxt-aware runner: everything under test (app/utils/*)
// is framework-agnostic TypeScript with no Nuxt auto-imports or runtime dependency -- see
// ui/README.md "Why plain Vitest". `happy-dom` supplies the DOM globals (atob, TextDecoder,
// ReadableStream) the API client and JWT helpers touch.
//
// This config lives in the layer, not at the workspace root: the code it covers lives here, and
// `pnpm -r test` reaches it through this package's own `test` script.
export default defineConfig({
  resolve: {
    alias: {
      '~': fileURLToPath(new URL('./app', import.meta.url))
    }
  },
  test: {
    environment: 'happy-dom',
    include: ['tests/unit/**/*.spec.ts']
  }
})
```

- [ ] **Step 7: Make `.gitignore` match the workspace layout**

Today's entries are anchored one level too high: `ui/node_modules/` does not match `ui/apps/app/node_modules/`, and the same holds for `.nuxt`, `.output` and `.env`. Left alone, the first `pnpm install` after this task would offer several thousand files to `git add -A`. Replace lines 10–21 of `/.gitignore` with:

```gitignore
# ui/ (Nuxt workspace) -- not a Gradle module, see ui/README.md. Node dependencies and
# build/test output never belong in the repository. The patterns are unanchored on purpose:
# each workspace package (apps/*, layers/*) has its own node_modules, .nuxt and .output.
ui/**/node_modules/
ui/**/.nuxt/
ui/**/.output/
ui/node_modules/
ui/.nuxt/
ui/.output/
# No trailing slash: nuxt writes dist as a symlink, which `ui/dist/` would not match.
ui/**/dist
ui/coverage/
ui/**/coverage/
ui/**/.env
ui/.env
# Local marker @nuxt/test-utils writes recording which version last ran -- see
# each app's vitest.nuxt.config.ts (added for the layout render regression test).
ui/**/.nuxtrc
ui/.nuxtrc
```

Verify before installing anything else:

```bash
cd /home/themeinerlp/Dokumente/projects/Apus/.claude/worktrees/partitioned-napping-music
git check-ignore -q ui/apps/app/node_modules && echo "nested node_modules ignored -- correct"
```

- [ ] **Step 8: Install the workspace and run the core layer's tests**

```bash
cd ui
pnpm install
pnpm --filter @apus/ui-core test
```

Expected: the same 47 unit tests that pass today, now reported from `layers/core`. If module resolution fails for `oidc-client-ts`, check that Step 3's `dependencies` block landed — the layer, not the root, owns it now.

Note the apps do not exist yet, so a bare `pnpm install` will warn about no `apps/*` matches. That is expected until Task 3.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor(ui): extract the shared core into a Nuxt layer inside a pnpm workspace

Domain, auth and transport code moves to layers/core unchanged; ui/ becomes a
workspace root whose only job is fanning scripts out. No behaviour change --
the same unit tests run, from their new home."
```

---

## Task 2: The OIDC redirect URI must follow the app's base path

**Files:**

- Create: `ui/layers/core/app/utils/oidc.ts`, `ui/layers/core/tests/unit/oidc.spec.ts`
- Modify: `ui/layers/core/app/composables/useAuth.ts` (the `getUserManager()` body)

**Interfaces:**

- Consumes: `@apus/ui-core` from Task 1.
- Produces:

```ts
export interface OidcRedirectUris {
  redirectUri: string
  silentRedirectUri: string
  postLogoutRedirectUri: string
}

export function buildOidcRedirectUris(origin: string, baseUrl: string): OidcRedirectUris
```

Task 4 relies on this returning `/console`-prefixed URIs when handed `baseUrl: '/console/'`.

- [ ] **Step 1: Write the failing test**

`ui/layers/core/tests/unit/oidc.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { buildOidcRedirectUris } from '~/utils/oidc'

// The console is served under a path prefix (design doc §1.2/§3.3). Building the redirect URI
// from the origin alone would return a signed-in admin to the *tenant app's* callback route,
// which is a different application that would then hold a session the admin did not ask for.
describe('buildOidcRedirectUris', () => {
  it('uses bare paths when the app is served at the root', () => {
    const uris = buildOidcRedirectUris('https://apus.example.net', '/')

    expect(uris.redirectUri).toBe('https://apus.example.net/auth/callback')
    expect(uris.silentRedirectUri).toBe('https://apus.example.net/auth/silent-renew')
    expect(uris.postLogoutRedirectUri).toBe('https://apus.example.net/')
  })

  it('prefixes every URI with the base path when the app is served under one', () => {
    const uris = buildOidcRedirectUris('https://apus.example.net', '/console/')

    expect(uris.redirectUri).toBe('https://apus.example.net/console/auth/callback')
    expect(uris.silentRedirectUri).toBe('https://apus.example.net/console/auth/silent-renew')
    expect(uris.postLogoutRedirectUri).toBe('https://apus.example.net/console/')
  })

  it('tolerates a base path without a trailing slash', () => {
    // Nuxt normalises app.baseURL to a trailing slash, but a hand-set value may not have one.
    const uris = buildOidcRedirectUris('https://apus.example.net', '/console')

    expect(uris.redirectUri).toBe('https://apus.example.net/console/auth/callback')
    expect(uris.postLogoutRedirectUri).toBe('https://apus.example.net/console/')
  })

  it('tolerates an empty base path', () => {
    const uris = buildOidcRedirectUris('https://apus.example.net', '')

    expect(uris.redirectUri).toBe('https://apus.example.net/auth/callback')
    expect(uris.postLogoutRedirectUri).toBe('https://apus.example.net/')
  })

  it('does not double a slash when the origin carries a trailing one', () => {
    const uris = buildOidcRedirectUris('https://apus.example.net/', '/console/')

    expect(uris.redirectUri).toBe('https://apus.example.net/console/auth/callback')
  })
})
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
cd ui && pnpm --filter @apus/ui-core test
```

Expected: FAIL — `Failed to resolve import "~/utils/oidc"`.

- [ ] **Step 3: Write the implementation**

`ui/layers/core/app/utils/oidc.ts`:

```ts
/**
 * Redirect URIs for the OIDC public client, derived from where the app is actually served.
 *
 * Both applications in this workspace share one broker client but do not share a base path: the
 * tenant app is served at `/`, the management console under `/console/` (design doc §1.2 --
 * same origin, because the api module configures no CORS). `window.location.origin` alone
 * therefore cannot produce a correct callback address, and getting it wrong is not a visible
 * error: the broker would happily redirect a console sign-in into the tenant app.
 *
 * Kept as a pure function with no Nuxt dependency so it unit-tests without a browser or an app
 * context -- see ui/README.md, "Why plain Vitest".
 */
export interface OidcRedirectUris {
  redirectUri: string
  silentRedirectUri: string
  postLogoutRedirectUri: string
}

export function buildOidcRedirectUris(origin: string, baseUrl: string): OidcRedirectUris {
  const root = origin.replace(/\/+$/, '')
  const path = baseUrl.replace(/^\/+/, '').replace(/\/+$/, '')
  const prefix = path === '' ? root : `${root}/${path}`

  return {
    redirectUri: `${prefix}/auth/callback`,
    silentRedirectUri: `${prefix}/auth/silent-renew`,
    postLogoutRedirectUri: `${prefix}/`
  }
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
cd ui && pnpm --filter @apus/ui-core test
```

Expected: PASS, all five cases, and the pre-existing unit tests still green.

- [ ] **Step 5: Use it from `useAuth`**

In `ui/layers/core/app/composables/useAuth.ts`, add the import next to the existing ones:

```ts
import { buildOidcRedirectUris } from '~/utils/oidc'
```

and replace the body of `getUserManager()` between `const config = useRuntimeConfig()` and the `return manager` line:

```ts
  const config = useRuntimeConfig()
  // Not window.location.origin alone: the console runs under a base path (see utils/oidc.ts).
  const uris = buildOidcRedirectUris(window.location.origin, config.app.baseURL)
  manager = new UserManager({
    authority: config.public.oidcIssuer,
    client_id: config.public.oidcClientId,
    redirect_uri: uris.redirectUri,
    silent_redirect_uri: uris.silentRedirectUri,
    post_logout_redirect_uri: uris.postLogoutRedirectUri,
    response_type: 'code',
    scope: 'openid profile email',
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() })
  })
```

- [ ] **Step 6: Commit**

```bash
git add layers/core/app/utils/oidc.ts layers/core/tests/unit/oidc.spec.ts layers/core/app/composables/useAuth.ts
git commit -m "fix(ui): derive OIDC redirect URIs from the app base path, not the origin

The console will be served under /console; building the callback from the origin
alone would return an admin signing in to the console into the tenant app instead."
```

---

## Task 3: The design layer and the tenant application

**Files:**

- Create: `ui/layers/design/package.json`, `ui/layers/design/nuxt.config.ts`, `ui/apps/app/package.json`, `ui/apps/app/nuxt.config.ts`, `ui/apps/app/tsconfig.json`, `ui/apps/app/vitest.nuxt.config.ts`, `ui/apps/app/vitest.server.config.ts`, `ui/eslint.config.mjs` (replacing today's)
- Move: `ui/app/assets/css/main.css` → `ui/layers/design/app/assets/css/main.css`; `ui/app/app.vue`, `ui/app/layouts/`, `ui/app/components/layout/`, `ui/app/components/tenant/`, `ui/app/pages/index.vue`, `ui/app/pages/tenant/` → under `ui/apps/app/app/`; `ui/tests/nuxt/` → `ui/apps/app/tests/nuxt/`; `ui/tests/server/` → `ui/apps/app/tests/server/`
- Delete: `ui/nuxt.config.ts`, `ui/tsconfig.json`, `ui/vitest.nuxt.config.ts`, `ui/vitest.server.config.ts` (each replaced by an app-local copy)

**Interfaces:**

- Consumes: `@apus/ui-core` (Task 1), `buildOidcRedirectUris` (Task 2, indirectly through `useAuth`).
- Produces: `@apus/ui-design`, extendable as `extends: ['@apus/ui-design']`, contributing `@nuxt/ui` + `@vueuse/nuxt` registration and `app/assets/css/main.css`. And `@apus/ui-app`, a buildable app whose `pnpm build` emits `apps/app/.output/{server,public}`.

- [ ] **Step 1: Create the design layer**

`ui/layers/design/package.json`:

```json
{
  "name": "@apus/ui-design",
  "version": "0.1.0",
  "description": "Apus UI design layer -- design tokens, the stylesheet, and the shared presentational components. Knows nothing about Apus's domain.",
  "private": true,
  "type": "module",
  "main": "./nuxt.config.ts",
  "scripts": {
    "typecheck": "echo 'typechecked through the apps that extend this layer'"
  },
  "dependencies": {
    "@nuxt/ui": "4.10.0",
    "@vueuse/nuxt": "14.4.0",
    "tailwindcss": "4.3.3"
  }
}
```

`ui/layers/design/nuxt.config.ts`:

```ts
// The visual half of the shared UI: module registration and the single stylesheet every
// application inherits. It has no knowledge of Apus's domain -- no render, no tenant, no map --
// which is what lets a design change be reviewed without reading domain code (design doc §2).
//
// @nuxt/ui is registered here rather than per app so both applications get the same primitive
// set and the same theming entry point. @vueuse/nuxt sits here for the same reason: it is a
// presentation-side convenience (element size, clipboard, intersection), not domain code.
export default defineNuxtConfig({
  modules: ['@nuxt/ui', '@vueuse/nuxt'],
  css: [import.meta.resolve('./app/assets/css/main.css')]
})
```

`css` uses `import.meta.resolve` rather than `~/assets/...` on purpose: inside a layer, `~` resolves against the *consuming app's* directory, so the plain alias would look for the stylesheet in `apps/app/app/assets/` and silently find nothing.

- [ ] **Step 2: Move the stylesheet into the design layer**

```bash
cd ui
mkdir -p layers/design/app/assets/css
git mv app/assets/css/main.css layers/design/app/assets/css/main.css
```

Contents stay as they are (`@import "tailwindcss"; @import "@nuxt/ui";`). Tokens arrive in Part B.

- [ ] **Step 3: Create the tenant app's manifest**

`ui/apps/app/package.json`:

```json
{
  "name": "@apus/ui-app",
  "version": "0.1.0",
  "description": "Apus tenant application -- worlds, sources, renders, hosting. Shipped as apus/ui.",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "nuxt dev",
    "build": "nuxt build",
    "preview": "nuxt preview",
    "postinstall": "nuxt prepare",
    "typecheck": "vue-tsc --noEmit -p tsconfig.json",
    "test": "vitest run -c vitest.nuxt.config.ts",
    "test:server": "nuxt build && vitest run -c vitest.server.config.ts"
  },
  "dependencies": {
    "@apus/ui-core": "workspace:*",
    "@apus/ui-design": "workspace:*"
  },
  "devDependencies": {
    "@nuxt/eslint": "1.17.0",
    "@nuxt/test-utils": "4.1.0",
    "@types/node": "26.2.0",
    "@vue/test-utils": "2.4.11",
    "happy-dom": "20.11.2",
    "nuxt": "4.5.2",
    "typescript": "6.0.3",
    "vitest": "4.1.10",
    "vue": "3.5.41",
    "vue-tsc": "3.3.9"
  }
}
```

The `workspace:*` dependencies are not decoration: the Dockerfile in Task 6 installs with `--filter @apus/ui-app...`, and that dependency selector is how pnpm knows the two layers belong in the image.

- [ ] **Step 4: Create the tenant app's Nuxt config**

`ui/apps/app/nuxt.config.ts` — today's `ui/nuxt.config.ts` with the module list and CSS moved out to the design layer, and `extends` added:

```ts
// https://nuxt.com/docs/api/configuration/nuxt-config
//
// The Apus tenant application. A pure SPA (design spec §11.2): `ssr: false`, no server-rendered
// routes, no backend-for-frontend session. Auth (layers/core, useAuth.ts) is therefore a
// client-only, public OIDC client -- see ui/README.md, "Why no server-side session".
//
// The container runs Nitro's own node-server build; ui/README.md, "Serving the built SPA",
// covers what that means for the output layout, the headers below and runtime config.
export default defineNuxtConfig({
  // Domain/auth/transport, then the design system. Order matters only for overrides: a file
  // in this app at the same path wins over both.
  extends: ['@apus/ui-core', '@apus/ui-design'],
  compatibilityDate: '2026-08-09',
  devtools: { enabled: true },
  ssr: false,
  nitro: {
    // Explicit, so CI cannot auto-detect a deploy provider and build an output the
    // Dockerfile's CMD refuses to start.
    preset: 'node-server'
  },
  // Nitro sends no Cache-Control on the shell at all; `/_nuxt/**` repeats Nitro's own
  // immutable because `/**` would otherwise override it. Held by tests/server/nitro.spec.ts.
  routeRules: {
    '/**': {
      headers: {
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff'
      }
    },
    '/_nuxt/**': {
      headers: {
        'cache-control': 'public, max-age=31536000, immutable',
        'x-content-type-options': 'nosniff'
      }
    }
  },
  modules: ['@nuxt/eslint'],
  runtimeConfig: {
    public: {
      // Base URL of the `api` module (design spec §11.1). No default -- an empty value would
      // silently point every request at the SPA's own origin.
      apiBaseUrl: '',
      // Must match `APUS_JWT_ISSUER` on the `api` module -- see that module's
      // application.yml. Which broker sits here is intentionally undecided (design spec §15).
      oidcIssuer: '',
      // The public (no client secret) OIDC client registered for this SPA at the broker.
      // The console shares it; only the redirect URI differs (see layers/core, utils/oidc.ts).
      oidcClientId: ''
    }
  },
  app: {
    head: {
      title: 'Apus'
    }
  },
  typescript: {
    typeCheck: false
  }
})
```

- [ ] **Step 5: Move the tenant app's own files**

```bash
cd ui
mkdir -p apps/app/app apps/app/tests
git mv app/app.vue apps/app/app/app.vue
git mv app/layouts apps/app/app/layouts
git mv app/components apps/app/app/components
git mv app/pages apps/app/app/pages
git mv tests/nuxt apps/app/tests/nuxt
git mv tests/server apps/app/tests/server
git mv vitest.nuxt.config.ts apps/app/vitest.nuxt.config.ts
git mv vitest.server.config.ts apps/app/vitest.server.config.ts
git mv tsconfig.json apps/app/tsconfig.json
git rm nuxt.config.ts
rmdir app/assets/css app/assets app 2>/dev/null || true
```

`app/components/platform/` moves along with the rest here and is removed from this app in Task 5, after the console has a copy. Keeping it for one task means every intermediate commit builds.

- [ ] **Step 6: Point the app's server test at its own output**

In `ui/apps/app/tests/server/nitro.spec.ts`, the `outputDir` line resolves `../../.output` relative to the spec file, which is still correct after the move (`apps/app/tests/server/` → `apps/app/.output`). Change nothing there. The one edit is the deep-link case, whose comment names a route:

```ts
  it('serves the shell for a client-side route so a deep link survives a reload', async () => {
    // /tenant/renders is a Vue Router route in this app, not a file on disk.
    const response = await fetch(`${base}/tenant/renders`, { headers: { Accept: 'text/html' } })
```

(Only the comment gains "in this app"; the assertion stays.)

- [ ] **Step 7: Make one ESLint config cover the workspace**

Replace `ui/eslint.config.mjs`:

```js
// @ts-check
import vuejsAccessibility from 'eslint-plugin-vuejs-accessibility'
// One generated config for the whole workspace. Both apps run the same Nuxt version and the
// same module set, so the config @nuxt/eslint generates for the tenant app describes the
// console and both layers just as accurately -- and a single `eslint .` at the root is what
// keeps layer code, which belongs to no app's directory tree, from going unlinted.
import withNuxt from './apps/app/.nuxt/eslint.config.mjs'

// Accessibility is checked via eslint-plugin-vuejs-accessibility, same as launchpad
// (design spec §11.2, house standard).
const a11yConfigs = vuejsAccessibility.configs['flat/recommended'].map(config => ({
  ...config,
  files: ['**/*.vue'],
  rules: {
    ...config.rules,
    // Labels associated via `for`/`id` are valid; do not also require nesting.
    'vuejs-accessibility/label-has-for': [
      'error',
      { required: { some: ['nesting', 'id'] }, allowChildren: false }
    ]
  }
}))

export default withNuxt(...a11yConfigs, {
  ignores: ['**/.nuxt/**', '**/.output/**', '**/node_modules/**', '**/dist/**']
}, {
  rules: {
    'linebreak-style': ['error', 'unix'],
    'no-trailing-spaces': 'error'
  }
}, {
  files: ['**/tests/**/*.ts'],
  rules: {
    // Test doubles/fixtures legitimately reach for `any` more often than app code.
    '@typescript-eslint/no-explicit-any': 'off'
  }
})
```

- [ ] **Step 8: Install, then verify the risky assumption end to end**

```bash
cd ui
pnpm install
pnpm --filter @apus/ui-app build
```

Expected: a successful build emitting `apps/app/.output/server/index.mjs`. **This step is the whole point of Task 3** — it is the first proof that `@nuxt/ui` registered from inside a layer resolves under pnpm's non-flat `node_modules`. If it fails with an unresolved `@nuxt/ui`, the fallback is to change `extends` in the app to relative paths (`extends: ['../../layers/core', '../../layers/design']`) and keep the `workspace:*` dependencies for the Dockerfile's filter; report which form was needed.

- [ ] **Step 9: Run every check**

```bash
cd ui
pnpm lint
pnpm typecheck
pnpm test
pnpm --filter @apus/ui-app test:server
```

Expected: lint clean, typecheck clean, the core layer's unit tests plus the app's Nuxt component test green, and the seven Nitro server tests green.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor(ui): move the tenant app into apps/app behind a design layer

@nuxt/ui and the stylesheet move to layers/design; the app keeps only what is
its own. Same pages, same behaviour, same header contract -- proven by the
existing Nitro server tests running against the app's own build output."
```

---

## Task 4: The management console as its own application

**Files:**

- Create: `ui/apps/console/package.json`, `ui/apps/console/nuxt.config.ts`, `ui/apps/console/tsconfig.json`, `ui/apps/console/vitest.nuxt.config.ts`, `ui/apps/console/vitest.server.config.ts`, `ui/apps/console/app/app.vue`, `ui/apps/console/app/layouts/default.vue`, `ui/apps/console/app/components/layout/ConsoleHeader.vue`, `ui/apps/console/tests/server/nitro.spec.ts`
- Copy (contents unchanged, `cp` not `git mv` — Task 5 deletes the originals): `ui/apps/app/app/components/platform/*` → `ui/apps/console/app/components/platform/`, `ui/apps/app/app/pages/platform/index.vue` → `ui/apps/console/app/pages/index.vue`

**Interfaces:**

- Consumes: `@apus/ui-core`, `@apus/ui-design`, `buildOidcRedirectUris`.
- Produces: `@apus/ui-console`, whose `pnpm build` emits `apps/console/.output/{server,public}` and whose shell is served under `/console/`.

- [ ] **Step 1: Create the console's manifest**

`ui/apps/console/package.json` — identical to the app's except for name, description and the absence of nothing else:

```json
{
  "name": "@apus/ui-console",
  "version": "0.1.0",
  "description": "Apus management console -- tenants, quotas, cluster-wide renders. platform-admin only. Shipped as apus/console.",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "nuxt dev",
    "build": "nuxt build",
    "preview": "nuxt preview",
    "postinstall": "nuxt prepare",
    "typecheck": "vue-tsc --noEmit -p tsconfig.json",
    "test": "vitest run -c vitest.nuxt.config.ts",
    "test:server": "nuxt build && vitest run -c vitest.server.config.ts"
  },
  "dependencies": {
    "@apus/ui-core": "workspace:*",
    "@apus/ui-design": "workspace:*"
  },
  "devDependencies": {
    "@nuxt/eslint": "1.17.0",
    "@nuxt/test-utils": "4.1.0",
    "@types/node": "26.2.0",
    "@vue/test-utils": "2.4.11",
    "happy-dom": "20.11.2",
    "nuxt": "4.5.2",
    "typescript": "6.0.3",
    "vitest": "4.1.10",
    "vue": "3.5.41",
    "vue-tsc": "3.3.9"
  }
}
```

- [ ] **Step 2: Create the console's Nuxt config**

`ui/apps/console/nuxt.config.ts`:

```ts
// https://nuxt.com/docs/api/configuration/nuxt-config
//
// The Apus management console. Same shape as the tenant app (SPA, node-server Nitro, the same
// header contract) with one structural difference: it is served under the path prefix
// /console on the *same* origin as the tenant app and the API.
//
// Why the same origin: the api module configures no CORS (api/src/main/resources/
// application.yml has no micronaut.server.cors block), so a console on its own hostname would
// fail every request at the preflight. Same origin also means one certificate, one DNS record
// and one OIDC client -- see the design doc, §1.2.
export default defineNuxtConfig({
  extends: ['@apus/ui-core', '@apus/ui-design'],
  compatibilityDate: '2026-08-09',
  devtools: { enabled: true },
  ssr: false,
  nitro: {
    preset: 'node-server'
  },
  app: {
    // The ingress routes /console here without a rewrite, so the app must own the prefix
    // itself: asset URLs, the router and -- via layers/core's utils/oidc.ts -- the OIDC
    // redirect URIs all derive from this value.
    baseURL: '/console/',
    head: {
      title: 'Apus Console'
    }
  },
  // Same contract as the tenant app, restated under this app's own prefix: Nitro sends no
  // Cache-Control on the shell, and /_nuxt/** must keep its immutable header against the
  // catch-all. Held by tests/server/nitro.spec.ts.
  routeRules: {
    '/**': {
      headers: {
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff'
      }
    },
    '/_nuxt/**': {
      headers: {
        'cache-control': 'public, max-age=31536000, immutable',
        'x-content-type-options': 'nosniff'
      }
    }
  },
  modules: ['@nuxt/eslint'],
  runtimeConfig: {
    public: {
      apiBaseUrl: '',
      oidcIssuer: '',
      // The same public client as the tenant app. Only the redirect URI differs, and the
      // broker must have /console/auth/callback and /console/auth/silent-renew registered
      // alongside the app's -- see ui/README.md, "Two applications, one OIDC client".
      oidcClientId: ''
    }
  },
  typescript: {
    typeCheck: false
  }
})
```

- [ ] **Step 3: Create the console's tsconfig and test configs**

`ui/apps/console/tsconfig.json`:

```json
{
  // https://nuxt.com/docs/guide/concepts/typescript
  "extends": "./.nuxt/tsconfig.json"
}
```

`ui/apps/console/vitest.nuxt.config.ts`:

```ts
import { defineVitestConfig } from '@nuxt/test-utils/config'

// Boots an actual Nuxt app context (auto-imports, component auto-registration under the real
// directory-prefixed names, plugins), so a component referencing a name Nuxt never registered
// fails here the way it fails at runtime -- something neither vue-tsc nor nuxt build catches.
export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    include: ['tests/nuxt/**/*.spec.ts']
  }
})
```

`ui/apps/console/vitest.server.config.ts`:

```ts
import { defineConfig } from 'vitest/config'

// Tests against the *built* Nitro server; separate from the component config because it needs
// a build first (`pnpm test:server` runs one).
export default defineConfig({
  test: {
    environment: 'node',
    include: ['tests/server/**/*.spec.ts'],
    // A cold server start plus the SIGTERM case needs more than the 5s default on CI.
    testTimeout: 30_000,
    hookTimeout: 60_000
  }
})
```

- [ ] **Step 4: Create the console's shell**

`ui/apps/console/app/app.vue`:

```vue
<template>
  <UApp>
    <NuxtLayout>
      <NuxtPage />
    </NuxtLayout>
  </UApp>
</template>
```

`ui/apps/console/app/layouts/default.vue`:

```vue
<template>
  <div class="min-h-screen">
    <LayoutConsoleHeader />
    <main class="p-6">
      <slot />
    </main>
  </div>
</template>
```

`ui/apps/console/app/components/layout/ConsoleHeader.vue` — the app's header without the nav, since the console has one area in Part A:

```vue
<script setup lang="ts">
const { user, logout } = useAuth()
</script>

<template>
  <header class="flex items-center justify-between border-b border-default px-6 py-4">
    <div class="flex items-center gap-3">
      <span class="text-lg font-semibold">Apus</span>
      <span class="text-muted text-sm">Console</span>
    </div>
    <div class="flex items-center gap-4">
      <span v-if="user" class="text-sm text-muted">
        {{ user.profile.email ?? user.profile.sub }}
      </span>
      <UButton
        variant="ghost"
        size="sm"
        @click="logout"
      >
        Sign out
      </UButton>
    </div>
  </header>
</template>
```

- [ ] **Step 5: Copy the platform pages and components across**

```bash
cd ui
mkdir -p apps/console/app/pages apps/console/app/components
cp -r apps/app/app/components/platform apps/console/app/components/platform
cp apps/app/app/pages/platform/index.vue apps/console/app/pages/index.vue
```

Then edit `apps/console/app/pages/index.vue`: it is now the console's root page, so the `<h1>` text `Platform` stays accurate but the surrounding note about being one area among several does not apply. Change nothing else — Part B redesigns this page and splits it into overview/list/detail.

- [ ] **Step 6: Write the failing server test for the base path**

`ui/apps/console/tests/server/nitro.spec.ts` — the app's spec with the prefix asserted. Copy `apps/app/tests/server/nitro.spec.ts` and change these parts:

```ts
const outputDir = fileURLToPath(new URL('../../.output', import.meta.url))
const serverEntry = `${outputDir}/server/index.mjs`
// Under app.baseURL the hashed assets move with the prefix; a build that emits them at the
// root would 404 behind the ingress rule, and this is where that shows up.
const publicAssets = `${outputDir}/public/console/_nuxt`
```

and, in `beforeAll`:

```ts
  hashedAsset = `/console/_nuxt/${asset}`
```

and replace the two prefix-sensitive test cases:

```ts
  it('serves the shell under its base path', async () => {
    const response = await fetch(`${base}/console/`)

    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toContain('text/html')
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.text()).toContain('id="__nuxt"')
  })

  it('serves the shell for a client-side route under the prefix so a deep link survives a reload', async () => {
    const response = await fetch(`${base}/console/tenants`, { headers: { Accept: 'text/html' } })

    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.text()).toContain('id="__nuxt"')
  })
```

In the remaining cases (`marks hashed build assets immutable`, `answers a matching conditional asset request with 304`, `404s a missing hashed asset`, `SIGTERM`), replace every bare `fetch(base)` with `fetch(`${base}/console/`)` and every `/_nuxt/does-not-exist.js` with `/console/_nuxt/does-not-exist.js`. Keep the `nosniff` case, pointing both fetches at the prefixed URLs.

- [ ] **Step 7: Run it and watch it fail**

```bash
cd ui && pnpm install && pnpm --filter @apus/ui-console test:server
```

Expected: FAIL — the build has not run against a config the console owns yet, or the asset directory assertion fails. Read which: a failure in `beforeAll` on `no hashed .js asset below …/public/console/_nuxt` means `app.baseURL` did not reach the build output and Step 2's config needs checking before going on.

- [ ] **Step 8: Make it pass**

Re-run after confirming Step 2's `app.baseURL` is exactly `'/console/'` (leading and trailing slash — Nuxt normalises, but the asset path in the test asserts the normalised form):

```bash
cd ui && pnpm --filter @apus/ui-console test:server
```

Expected: PASS, seven cases.

- [ ] **Step 9: Run the whole workspace's checks**

```bash
cd ui
pnpm lint
pnpm typecheck
pnpm test
pnpm test:server
```

Expected: everything green, both apps building.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(ui): split the management console into its own application

apps/console is a second Nuxt app over the same two layers, served under
/console on the same origin -- the api module configures no CORS, so a separate
hostname is not available without API work. Its Nitro tests assert the prefix
reaches both the shell and the hashed assets."
```

---

## Task 5: Remove the platform area from the tenant application

**Files:**

- Delete: `ui/apps/app/app/components/platform/` (all four components), `ui/apps/app/app/pages/platform/`
- Modify: `ui/apps/app/app/components/layout/AppNav.vue`, `ui/apps/app/app/components/layout/AppHeader.vue`
- Modify: `ui/apps/app/tests/nuxt/defaultLayout.nuxt.spec.ts`

**Interfaces:**

- Consumes: everything from Tasks 3 and 4.
- Produces: a tenant app whose bundle contains no platform code, and whose account menu carries one role-gated link out to `/console`.

- [ ] **Step 1: Write the failing test for the new navigation contract**

Create `ui/apps/app/tests/nuxt/appHeaderConsoleLink.nuxt.spec.ts`. **The role has to be mocked, or the test is vacuous:** with no signed-in principal, `AppNav` renders neither the Tenant nor the Platform link today, so a bare "does not contain /platform" assertion would pass before the change as well as after it.

```ts
import { describe, expect, it } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref, computed } from 'vue'
import DefaultLayout from '~/layouts/default.vue'

/**
 * The console is a separate application now (design doc §2), which changes two things in this
 * app's header at once: the in-app `/platform` route is gone, and admins instead get a plain
 * link out to `/console/`.
 *
 * `useAuth` is mocked with a platform-admin principal on purpose. Both the old nav link and the
 * new console link are gated on the role, so without a principal neither renders and any
 * assertion about them passes for the wrong reason.
 */
mockNuxtImport('useAuth', () => {
  return () => ({
    user: ref({ profile: { email: 'admin@example.net', sub: 'admin' } }),
    principal: ref({ subject: 'admin', tenant: null, roles: ['platform-admin'] }),
    isAuthenticated: computed(() => true),
    init: async () => {},
    login: async () => {},
    logout: async () => {},
    trySilentSignin: async () => true,
    getAccessToken: async () => 'token'
  })
})

describe('the tenant app header, for a platform admin', () => {
  it('no longer offers an in-app /platform route', async () => {
    const wrapper = await mountSuspended(DefaultLayout, {
      slots: { default: () => 'content' }
    })

    // A router link here would resolve to nothing in this app and 404 in the browser.
    expect(wrapper.html()).not.toContain('/platform')
  })

  it('links out to the console instead', async () => {
    const wrapper = await mountSuspended(DefaultLayout, {
      slots: { default: () => 'content' }
    })

    const link = wrapper.find('a[href="/console/"]')
    expect(link.exists()).toBe(true)
    expect(link.text()).toContain('Platform console')
  })
})
```

Check the shape the mocked `principal` must have against `layers/core/app/utils/role.ts`'s `ApusUiPrincipal` and its `parsePrincipal` return value, and match it exactly — `isPlatformAdmin()` reads it directly.

- [ ] **Step 2: Run it and watch both cases fail**

```bash
cd ui && pnpm --filter @apus/ui-app test
```

Expected: FAIL twice — the rendered nav still contains `/platform`, and there is no `a[href="/console/"]` yet.

- [ ] **Step 3: Remove the platform link from the nav**

`ui/apps/app/app/components/layout/AppNav.vue` becomes:

```vue
<script setup lang="ts">
// Which links are shown is a UX convenience, not access control -- see
// layers/core, app/utils/role.ts's module Javadoc.
const { principal } = useAuth()
const showTenantLink = computed(() => canReadTenant(principal.value))
</script>

<template>
  <nav aria-label="Main" class="flex items-center gap-4">
    <ULink to="/" class="text-sm font-medium">
      Account
    </ULink>
    <ULink
      v-if="showTenantLink"
      to="/tenant"
      class="text-sm font-medium"
    >
      Tenant
    </ULink>
  </nav>
</template>
```

- [ ] **Step 4: Give admins a way into the console**

`ui/apps/app/app/components/layout/AppHeader.vue` — add the role-gated link next to the sign-out button:

```vue
<script setup lang="ts">
const { user, principal, logout } = useAuth()
// Convenience only, exactly like the nav link it replaces: the console re-checks the role and
// the api module answers 403 regardless of what this renders (layers/core, app/utils/role.ts).
const showConsoleLink = computed(() => isPlatformAdmin(principal.value))
</script>

<template>
  <header class="flex items-center justify-between border-b border-default px-6 py-4">
    <div class="flex items-center gap-8">
      <span class="text-lg font-semibold">Apus</span>
      <LayoutAppNav />
    </div>
    <div class="flex items-center gap-4">
      <span v-if="user" class="text-sm text-muted">
        {{ user.profile.email ?? user.profile.sub }}
      </span>
      <!-- A separate application, so a plain anchor and not <ULink to>: the router here knows
           nothing about /console and would refuse to resolve it. -->
      <a
        v-if="showConsoleLink"
        href="/console/"
        class="text-sm font-medium text-muted hover:text-default"
      >
        Platform console
      </a>
      <UButton
        variant="ghost"
        size="sm"
        @click="logout"
      >
        Sign out
      </UButton>
    </div>
  </header>
</template>
```

- [ ] **Step 5: Delete the platform code from this app**

```bash
cd ui
git rm -r apps/app/app/components/platform apps/app/app/pages/platform
```

- [ ] **Step 6: Run the tests and watch them pass**

```bash
cd ui
pnpm --filter @apus/ui-app test
pnpm lint
pnpm typecheck
```

Expected: PASS. A typecheck error naming `PlatformTenantList` or a sibling means a reference survived the delete — find it with `grep -rn "Platform" apps/app/app`.

- [ ] **Step 7: Prove the platform code is gone from the shipped bundle**

```bash
cd ui
pnpm --filter @apus/ui-app build
grep -rl "CreateTenantForm\|allowedHostingDomains" apps/app/.output/public/_nuxt/ || echo "not in the bundle -- correct"
```

Expected: `not in the bundle -- correct`. This is the point of the whole split; verify it rather than assume it.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor(ui): drop the platform area from the tenant application

The console owns it now. A tenant user is no longer served the tenant-management
code, and admins reach the console through one role-gated link in the header."
```

---

## Task 6: Two images

**Files:**

- Create: `ui/Dockerfile.app`, `ui/Dockerfile.console`
- Delete: `ui/Dockerfile`
- Modify: `.dockerignore` (only if it names `ui/Dockerfile` — check first)

**Interfaces:**

- Consumes: both apps from Tasks 3 and 4.
- Produces: two images, each starting its own app's `.output/server/index.mjs` on port 8080 as uid 65532.

- [ ] **Step 1: Write the tenant app's Dockerfile**

`ui/Dockerfile.app`:

```dockerfile
# syntax=docker/dockerfile:1
#
# The Apus tenant application (image apus/ui). See ui/README.md, "Serving the built SPA", for
# why this image looks the way it does, and Dockerfile.console for its sibling.
#
# Build context is the repository root, matching what release-please.yml passes.

########################################
# Stage 1: build the SPA
########################################
# Version pinned to ui/.nvmrc (24); keep both in sync with the runtime stage below.
FROM node:24-bookworm-slim AS build

RUN corepack enable

WORKDIR /src
# Manifests first, so a source-only change reuses the install layer. `--filter @apus/ui-app...`
# resolves the app *and its workspace dependencies*, which is how the two layers get installed
# without naming them here.
COPY ui/package.json ui/pnpm-lock.yaml ui/pnpm-workspace.yaml ./
COPY ui/apps/app/package.json ./apps/app/
COPY ui/layers/core/package.json ./layers/core/
COPY ui/layers/design/package.json ./layers/design/
RUN pnpm install --frozen-lockfile --filter @apus/ui-app...

COPY ui/ ./
# Not `nuxt generate`: that emits static files and no server for the CMD below to start.
RUN pnpm --filter @apus/ui-app build

########################################
# Stage 2: serve it
########################################
FROM gcr.io/distroless/nodejs24-debian12:nonroot

WORKDIR /app
COPY --from=build /src/apps/app/.output ./.output

# Already the default for the :nonroot tag; repeated because the chart's runAsUser must match.
USER 65532:65532

# Nitro would otherwise listen on 3000 and bind [::]; the chart says 8080 on an IPv4 cluster.
ENV PORT=8080
ENV HOST=0.0.0.0
# Without a cap V8 sizes its heap from the host's RAM, not the cgroup limit. Kept in step with
# ui.resources in the apus-platform chart.
ENV NODE_OPTIONS=--max-old-space-size=64

EXPOSE 8080

# The distroless entrypoint is already ["/nodejs/bin/node"].
CMD ["/app/.output/server/index.mjs"]
```

The `postinstall: nuxt prepare` in the app's manifest runs during `pnpm install`, before the sources are copied. That is harmless — it writes `.nuxt` scaffolding that the subsequent `build` regenerates.

- [ ] **Step 2: Write the console's Dockerfile**

`ui/Dockerfile.console` — identical but for the filter, the copied manifest and the output path:

```dockerfile
# syntax=docker/dockerfile:1
#
# The Apus management console (image apus/console). Sibling of Dockerfile.app; the two differ
# only in which workspace package they build. See ui/README.md, "Serving the built SPA".
#
# Build context is the repository root, matching what release-please.yml passes.

########################################
# Stage 1: build the SPA
########################################
FROM node:24-bookworm-slim AS build

RUN corepack enable

WORKDIR /src
COPY ui/package.json ui/pnpm-lock.yaml ui/pnpm-workspace.yaml ./
COPY ui/apps/console/package.json ./apps/console/
COPY ui/layers/core/package.json ./layers/core/
COPY ui/layers/design/package.json ./layers/design/
RUN pnpm install --frozen-lockfile --filter @apus/ui-console...

COPY ui/ ./
RUN pnpm --filter @apus/ui-console build

########################################
# Stage 2: serve it
########################################
FROM gcr.io/distroless/nodejs24-debian12:nonroot

WORKDIR /app
COPY --from=build /src/apps/console/.output ./.output

USER 65532:65532

ENV PORT=8080
ENV HOST=0.0.0.0
# Same cap as the tenant app; kept in step with console.resources in the apus-platform chart.
ENV NODE_OPTIONS=--max-old-space-size=64

EXPOSE 8080

CMD ["/app/.output/server/index.mjs"]
```

- [ ] **Step 3: Remove the old Dockerfile and check `.dockerignore`**

```bash
cd /home/themeinerlp/Dokumente/projects/Apus/.claude/worktrees/partitioned-napping-music
git rm ui/Dockerfile
grep -n "ui/" .dockerignore
```

If `.dockerignore` excludes `ui/node_modules` or similar, leave it. If it names `ui/Dockerfile`, replace that line with `ui/Dockerfile.app` and `ui/Dockerfile.console`.

- [ ] **Step 4: Build both images**

```bash
cd /home/themeinerlp/Dokumente/projects/Apus/.claude/worktrees/partitioned-napping-music
docker build -f ui/Dockerfile.app -t apus/ui:plan-check .
docker build -f ui/Dockerfile.console -t apus/console:plan-check .
```

Expected: both succeed. If Docker is unavailable in the environment, say so explicitly in the task report rather than marking the step done — this is the only check that covers the `--filter …` install path.

- [ ] **Step 5: Smoke-test the console image's base path**

```bash
docker run --rm -d -p 18080:8080 --name apus-console-check apus/console:plan-check
sleep 3
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/console/
docker rm -f apus-console-check
```

Expected: `200`. A `404` means `app.baseURL` did not survive the build.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "build(ui): one Dockerfile per application

Both install the workspace with --filter <app>..., which pulls in the two layers
without naming them, and copy only their own app's .output into distroless."
```

---

## Task 7: Chart support for the console

**Files:**

- Create: `deploy/charts/apus-platform/templates/console-deployment.yaml`, `deploy/charts/apus-platform/templates/console-service.yaml`
- Modify: `deploy/charts/apus-platform/values.yaml`, `deploy/charts/apus-platform/values.schema.json`, `deploy/charts/apus-platform/templates/ingress.yaml`, `deploy/charts/apus-platform/templates/_helpers.tpl`, `deploy/charts/apus-platform/templates/NOTES.txt`

**Interfaces:**

- Consumes: the `apus/console` image from Task 6.
- Produces: `console.enabled` (default `true`), the `console.*` value block, the helper `apus-platform.console.fullname`, and an ingress rule at `/console`.

- [ ] **Step 1: Add the fullname helper**

Read `deploy/charts/apus-platform/templates/_helpers.tpl` and find `apus-platform.ui.fullname`. Add its sibling immediately below, matching the existing one's shape exactly:

```yaml
{{/*
Fully qualified name of the console component. Same construction as the ui and api helpers.
*/}}
{{- define "apus-platform.console.fullname" -}}
{{- printf "%s-console" (include "apus-platform.fullname" .) -}}
{{- end -}}
```

If `apus-platform.ui.fullname` is built differently (e.g. via a shared `component` helper), mirror *that* form instead — consistency with the file beats this snippet.

- [ ] **Step 2: Add the values block**

In `deploy/charts/apus-platform/values.yaml`, immediately after the `ui:` block:

```yaml
# The management console (design doc 2026-08-15, §3.3): a second SPA image, served under
# /console on the same host as the ui. Same origin on purpose -- the api module configures no
# CORS, so a console on its own hostname could not call it.
console:
  # A handful of platform admins, not tenants. Set to false to ship the platform without a
  # management UI at all; the Deployment, the Service and the ingress rule all disappear.
  enabled: true
  image:
    repository: harbor.onelitefeather.dev/apus/console
    tag: ""
    pullPolicy: IfNotPresent
  # One replica: the audience is administrators, and a restart costs them a page reload.
  replicaCount: 1
  podSecurityContext:
    runAsNonRoot: true
    # The distroless :nonroot base runs as uid 65532, not 10001 like the Java images.
    runAsUser: 65532
    seccompProfile:
      type: RuntimeDefault
  securityContext:
    allowPrivilegeEscalation: false
    # The Nitro server only reads from the image; it needs no writable path.
    readOnlyRootFilesystem: true
    capabilities:
      drop: ["ALL"]
  resources:
    requests:
      cpu: 50m
      # Same Nitro shell-per-request profile as the ui, at a fraction of the request rate.
      memory: 128Mi
    limits:
      memory: 256Mi
  # Passed to the container verbatim. NUXT_PUBLIC_* reach the SPA at runtime because the image
  # runs Nitro -- see ui/README.md, "Runtime configuration". The console needs the same three
  # values as the ui.
  env: {}
  envFrom: []
  podAnnotations: {}
  podLabels: {}
  nodeSelector: {}
  tolerations: []
  affinity: {}
```

- [ ] **Step 3: Add the Deployment and Service templates**

`deploy/charts/apus-platform/templates/console-deployment.yaml`:

```yaml
{{- if .Values.console.enabled }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "apus-platform.console.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "apus-platform.componentLabels" (dict "ctx" . "component" "console") | nindent 4 }}
spec:
  replicas: {{ .Values.console.replicaCount }}
  strategy:
    # Stateless: no local state to lose by running old and new pods side by side.
    type: RollingUpdate
  selector:
    matchLabels:
      {{- include "apus-platform.componentSelectorLabels" (dict "ctx" . "component" "console") | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "apus-platform.componentLabels" (dict "ctx" . "component" "console") | nindent 8 }}
        {{- with .Values.console.podLabels }}{{- toYaml . | nindent 8 }}{{- end }}
      {{- with .Values.console.podAnnotations }}
      annotations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
    spec:
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      securityContext:
        {{- toYaml .Values.console.podSecurityContext | nindent 8 }}
      containers:
        - name: console
          image: {{ include "apus-platform.image" (dict "image" .Values.console.image "ctx" .) }}
          imagePullPolicy: {{ .Values.console.image.pullPolicy }}
          securityContext:
            {{- toYaml .Values.console.securityContext | nindent 12 }}
          ports:
            # Nitro's own default is 3000; the image pins PORT=8080 (see ui/Dockerfile.console).
            - name: http
              containerPort: 8080
              protocol: TCP
          {{- with .Values.console.env }}
          env:
            {{- range $name, $value := . }}
            - name: {{ $name }}
              value: {{ $value | quote }}
            {{- end }}
          {{- end }}
          {{- with .Values.console.envFrom }}
          envFrom:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          readinessProbe:
            # /console/, not /: this SPA is served under app.baseURL, so Nitro answers the bare
            # root with a 404 and the pod would never become ready.
            httpGet:
              path: /console/
              port: http
            initialDelaySeconds: 5
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /console/
              port: http
            initialDelaySeconds: 10
            periodSeconds: 20
          resources:
            {{- toYaml .Values.console.resources | nindent 12 }}
      {{- with .Values.console.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.console.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.console.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
{{- end }}
```

`deploy/charts/apus-platform/templates/console-service.yaml`:

```yaml
{{- if .Values.console.enabled }}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "apus-platform.console.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "apus-platform.componentLabels" (dict "ctx" . "component" "console") | nindent 4 }}
spec:
  type: ClusterIP
  selector:
    {{- include "apus-platform.componentSelectorLabels" (dict "ctx" . "component" "console") | nindent 4 }}
  ports:
    - name: http
      port: 80
      targetPort: http
      protocol: TCP
{{- end }}
```

Both mirror their `ui-*` siblings exactly; if `_helpers.tpl` turns out to define `componentLabels`/`componentSelectorLabels`/`image` with different argument names than the `dict` calls above, follow the existing `ui-deployment.yaml` rather than this snippet.

- [ ] **Step 4: Add the ingress rule in the right position**

In `deploy/charts/apus-platform/templates/ingress.yaml`, insert between the `/api` path and the `/` path:

```yaml
          {{- if .Values.console.enabled }}
          # Between /api and /: the controller evaluates a host's paths in order, so the
          # catch-all below would otherwise swallow the console. No rewrite annotation --
          # the console owns the prefix itself via app.baseURL (see ui/apps/console/nuxt.config.ts).
          - path: /console
            pathType: Prefix
            backend:
              service:
                name: {{ include "apus-platform.console.fullname" . }}
                port:
                  name: http
          {{- end }}
```

- [ ] **Step 5: Extend the values schema**

Read `deploy/charts/apus-platform/values.schema.json`. It currently lists only `auth` under `properties` while `required` names `auth`, `api`, `ui`. Add `console` to `required` and, matching however `auth` is described, add a `console` property object with at least:

```json
"console": {
  "type": "object",
  "required": ["enabled", "image"],
  "properties": {
    "enabled": { "type": "boolean" },
    "image": {
      "type": "object",
      "required": ["repository"],
      "properties": {
        "repository": { "type": "string", "minLength": 1 },
        "tag": { "type": "string" },
        "pullPolicy": { "enum": ["Always", "IfNotPresent", "Never"] }
      }
    },
    "replicaCount": { "type": "integer", "minimum": 0 }
  }
}
```

- [ ] **Step 6: Mention the broker prerequisite in NOTES.txt**

Append to `deploy/charts/apus-platform/templates/NOTES.txt`:

```text
{{- if .Values.console.enabled }}

The management console is served at https://{{ .Values.ingress.host }}/console/ .
It shares the tenant UI's OIDC client, so the broker must have these two redirect URIs
registered in addition to the UI's own, or signing in to the console will fail:

  https://{{ .Values.ingress.host }}/console/auth/callback
  https://{{ .Values.ingress.host }}/console/auth/silent-renew
{{- end }}
```

- [ ] **Step 7: Render and check the chart**

```bash
cd /home/themeinerlp/Dokumente/projects/Apus/.claude/worktrees/partitioned-napping-music
helm lint deploy/charts/apus-platform
helm template t deploy/charts/apus-platform --set ingress.enabled=true --set ingress.host=apus.example.net | grep -A3 "path: /console"
helm template t deploy/charts/apus-platform --set console.enabled=false | grep -c "console" || echo "console fully absent -- correct"
```

Expected: lint clean; the `/console` rule present and ordered after `/api` and before `/`; nothing console-related rendered when disabled.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(deploy): ship the management console from the apus-platform chart

Its own Deployment and Service behind console.enabled, plus an ingress rule
ordered between /api and the catch-all. NOTES.txt states the two redirect URIs
the broker needs, since both apps share one OIDC client."
```

---

## Task 8: CI and release

**Files:**

- Modify: `.github/workflows/build-pr.yml` (the `ui` job), `.github/workflows/release-please.yml` (`publish-ui`, new `publish-console`, `publish-charts`'s `needs`)

**Interfaces:**

- Consumes: the workspace scripts from Task 1 and the two Dockerfiles from Task 6.
- Produces: a PR gate covering both apps, and a released `apus/console` image alongside `apus/ui`.

- [ ] **Step 1: Update the PR job**

In `.github/workflows/build-pr.yml`, the `ui` job's steps become:

```yaml
      - run: pnpm install --frozen-lockfile
      - run: pnpm lint
      - run: pnpm typecheck
      - run: pnpm test
      # Builds each app, then tests its .output/server/index.mjs -- the containers' CMD. Not
      # part of `pnpm test` because it needs those builds. Covers both apps via `pnpm -r`.
      - run: pnpm test:server
```

The surrounding job (checkout, `pnpm/action-setup` with `package_json_file: ui/package.json`, `setup-node` with `node-version-file: ui/.nvmrc` and `cache-dependency-path: ui/pnpm-lock.yaml`, `working-directory: ui`) is already correct for a workspace root and needs no change — `packageManager` still lives in `ui/package.json` and the lockfile is still one file at `ui/`.

- [ ] **Step 2: Point `publish-ui` at the renamed Dockerfile**

In `.github/workflows/release-please.yml`, in the `publish-ui` job's `with:` block:

```yaml
      dockerfile: "ui/Dockerfile.app"
```

- [ ] **Step 3: Add `publish-console`**

Immediately after the `publish-ui` job:

```yaml
  publish-console:
    # Same standalone gate as publish-ui: no Gradle artifact needed.
    needs: [release-please]
    if: ${{ !cancelled() && needs.release-please.outputs.root-released == 'true' }}
    permissions:
      contents: read
      id-token: write
    uses: OneLiteFeatherNET/workflows/.github/workflows/docker-publish.yml@v2.8.1
    with:
      image-name: "apus/console"
      version: ${{ needs.release-please.outputs.root-version }}
      context: "."
      dockerfile: "ui/Dockerfile.console"
    secrets: inherit
```

- [ ] **Step 4: Gate the chart on it**

In the `publish-charts` job, extend `needs` and its comment:

```yaml
    needs: [release-please, publish-runner, publish-ingest, publish-operator, publish-api, publish-hosting, publish-ui, publish-console]
    # Runs after all seven images. Not a concurrency constraint any more -- just ordering:
```

- [ ] **Step 5: Validate the workflow files parse**

```bash
cd /home/themeinerlp/Dokumente/projects/Apus/.claude/worktrees/partitioned-napping-music
python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in ['.github/workflows/build-pr.yml','.github/workflows/release-please.yml']]; print('both parse')"
```

Expected: `both parse`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "ci(ui): gate and publish both UI applications

pnpm -r runs lint, typecheck and both apps' tests from the workspace root, and
the release publishes apus/console alongside apus/ui."
```

---

## Task 9: Documentation

**Files:**

- Modify: `ui/README.md` (substantially), `README.md` (the module table), `deploy/charts/apus-platform/README.md`, `ui/.env.example`

**Interfaces:**

- Consumes: everything above.
- Produces: no code.

- [ ] **Step 1: Rewrite `ui/README.md`'s structural sections**

Keep every section whose reasoning is unchanged — "Why no server-side session", "Token storage", "Role logic", "Why plain Vitest", the version table, the header contract. Replace "Project layout" with the workspace tree from this plan's File Structure section, and add these three sections:

- **"Two applications"** — what each is for, which image it ships as (`apus/ui` for `apps/app`, `apus/console` for `apps/console`), and the explicit note that `apus/ui` continuing to mean the tenant app is a deliberate compatibility decision.
- **"Two applications, one OIDC client"** — the console runs under `/console`, its redirect URIs are `/console/auth/callback` and `/console/auth/silent-renew`, both must be registered at the broker, and the construction lives in `layers/core/app/utils/oidc.ts`.
- **"Why the console is same-origin"** — the `api` module configures no CORS; a separate hostname would need API work. Name `api/src/main/resources/application.yml` so the next person can check whether that is still true.

Update the "Building and testing" commands to the workspace scripts (`pnpm dev:app`, `pnpm dev:console`, `pnpm build`, `pnpm test`, `pnpm test:server`, `pnpm lint`, `pnpm typecheck`).

- [ ] **Step 2: Update the root module table**

In `README.md`, the `ui` row becomes two rows:

```markdown
| `ui` | Nuxt 4 workspace: the tenant application (`apus/ui`) and the management console (`apus/console`) | Container images |
```

- [ ] **Step 3: Update the chart README**

In `deploy/charts/apus-platform/README.md`, document the `console.*` values alongside `ui.*` in whatever table or list the file already uses, and state the ingress path ordering (`/api`, `/console`, `/`) and the broker redirect-URI prerequisite.

- [ ] **Step 4: Note both apps in `.env.example`**

Add a comment at the top of `ui/.env.example`:

```bash
# Both applications read the same three variables. Copy this file to apps/app/.env and
# apps/console/.env for local development -- `pnpm dev:app` and `pnpm dev:console` each read
# the .env next to their own nuxt.config.ts.
```

- [ ] **Step 5: Lint the markdown**

```bash
cd /home/themeinerlp/Dokumente/projects/Apus/.claude/worktrees/partitioned-napping-music
npx --yes markdownlint-cli2 "**/*.md"
```

Expected: 0 issues.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "docs(ui): describe the workspace, the two applications and their delivery"
```

---

## Done when

- `pnpm lint`, `pnpm typecheck`, `pnpm test` and `pnpm test:server` all pass from `ui/`.
- `docker build` succeeds for both Dockerfiles, and `curl /console/` against the console image returns 200.
- `helm template` renders the `/console` rule between `/api` and `/`, and renders nothing console-related with `console.enabled=false`.
- The tenant app's built bundle contains no platform-management code (Task 5, Step 7).
- Every page reachable before this plan is reachable after it, at the same URL — except `/platform`, which moved to `/console/`.
