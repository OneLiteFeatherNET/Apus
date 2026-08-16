# Apus UI Split — Part B: Design System and Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give both applications a deliberate Tailwind 4 design system and rebuild their information architecture around what each audience is actually trying to do — the tenant app around a world's journey from server files to a public map, the console around operating many tenants.

**Architecture:** `layers/design` grows a token set and a component library; both apps consume it and differ only in accent ramp and density. The tenant app gains a world-centric entry point built on a pure client-side join over four existing list endpoints. No API changes.

**Tech Stack:** Tailwind 4 (`@theme`), Nuxt UI 4 (primitives only), `@nuxtjs/color-mode` (bundled with Nuxt UI), Vue 3, Vitest.

**Spec:** `docs/superpowers/specs/2026-08-15-ui-split-and-redesign-design.md` (§4–§6)

**Depends on:** `docs/superpowers/plans/2026-08-15-ui-split-part-a-delivery.md`, complete.

## Global Constraints

- **No API changes, no new endpoints.** Everything is composed from what `api` returns today. The write surface is exactly: `POST /api/sources`, `POST /api/maps/{id}/render`, `POST /api/tenants`, `PATCH /api/tenants/{name}`. There is no way to create a map or a hosting from a UI, and the design must say so rather than offer a button that cannot work.
- **No web fonts.** System stack plus `ui-monospace`. The images are distroless and the pages make no external requests; a downloaded typeface would break both properties.
- **`pnpm` runs as `npx --yes pnpm@11.20.0` in this environment** (no pnpm, no corepack), from `ui/`.
- **Commit signing:** if `git commit` fails with `No private key found`, re-run with `--no-gpg-sign` and say so in the report.
- **Accessibility is a gate, not a goal.** `eslint-plugin-vuejs-accessibility` must pass. Beyond it: every status carries a text label, never colour alone; focus is visible; `prefers-reduced-motion` is respected; one `<main>` per page.
- **Design tokens are the only source of colour.** No hex literal and no Tailwind palette colour (`text-blue-500`) in any app or component file. Everything resolves through the semantic tokens in §Task 1.
- **The quantised-cell rule.** No continuous progress bar anywhere, in either app. Progress and utilisation are always drawn as discrete cells. This is the design's one signature and it is applied consistently or not at all.

---

## Design direction (binding — build to this, do not re-litigate it)

**Subject.** Apus turns a Minecraft server's world files into a browsable BlueMap that anyone can open in a browser. The tenant app's user runs a game community and wants their world visible and current. The console's user operates Apus for many such communities.

**Signature: the quantised cell.** World data is a grid — regions of 32×32 chunks, chunks of 16×16 blocks — and BlueMap renders it as a tile pyramid. So progress in this product is not a liquid filling a tube; it is squares completing. Every meter in both applications is a row of discrete cells, `round(percent / 100 × cells)` of them filled. It reads as "this thing is made of blocks" without claiming to show *which* blocks are done, which the API does not tell us and the UI must not imply.

The `PipelineRail` is where the signature lands hardest: five square stage markers joined by hairlines, and the active stage's square subdivides into the cell grid. One idea, one place, executed properly. Everything around it stays quiet.

**Colour.** Minecraft models copper oxidising over time; this product is about worlds ageing and renders refreshing them. The accents come from that: **verdigris** for the tenant app, **lapis** for the console, on a shared cool basalt neutral. Not the default cyan-on-near-black — verdigris is muted and slightly earthy, and it sits on an ink with a blue cast rather than pure black.

**Typography.** With no web fonts, the personality comes from a boundary held strictly: **prose is humanist, every machine value is monospace.** Identifiers, phases, percentages, byte counts, bucket paths, versions, timestamps and log lines are all `--font-mono`; sentences are not. Section labels are mono eyebrows in wide tracking, and they name a resource kind — `SOURCE`, `MAP`, `RENDER`, `HOSTING` — which is the CRD taxonomy, not decoration.

**Density.** The tenant app is airier (few objects, each important). The console is denser (many rows, scanned not read).

**Motion.** Almost none. One exception, earned: the active pipeline stage's cells settle in with a short stagger when a render is live, because that is the one place where "something is happening right now" is the information. Everything is behind `prefers-reduced-motion`.

---

## File Structure

```text
ui/layers/design/
  app/assets/css/main.css          -- imports tailwind, @nuxt/ui, then tokens.css
  app/assets/css/tokens.css        -- NEW: ramps, semantic overrides, apus-* tokens, base rules
  app/app.config.ts                -- NEW: Nuxt UI colour slots (overridden per app)
  app/components/
    AppShell.vue        PageHeader.vue     SectionLabel.vue
    StatusPill.vue      EmptyState.vue     ErrorState.vue
    DataTable.vue       Toolbar.vue        MetaList.vue      CopyField.vue
    StatTile.vue        CellMeter.vue      PipelineRail.vue
    LogConsole.vue      ConnectionState.vue

ui/layers/core/app/utils/
  worlds.ts                        -- NEW: buildWorlds(), the pure join
  pipeline.ts                      -- NEW: deriveStages(), the five-stage status
ui/layers/core/tests/unit/
  worlds.spec.ts  pipeline.spec.ts -- NEW

ui/apps/app/
  app/app.config.ts                -- primary: verdigris
  app/assets/css/app.css           -- density knobs only
  app/layouts/default.vue          -- rebuilt on AppShell
  app/components/layout/{AppHeader,AppNav}.vue
  app/components/world/{WorldRow,WorldSummary,WorldActions}.vue
  app/components/source/{SourceTypeChoice,SourceConnectionFields,SourceReview}.vue
  app/pages/index.vue              -- worlds (was: account)
  app/pages/worlds/[name].vue      -- NEW
  app/pages/sources/index.vue      app/pages/sources/new.vue
  app/pages/renders/index.vue      app/pages/renders/[id].vue
  app/pages/hosting.vue            app/pages/account.vue
  (app/pages/tenant/** removed)

ui/apps/console/
  app/app.config.ts                -- primary: lapis
  app/layouts/default.vue          -- sidebar shell
  app/components/layout/{ConsoleSidebar,ConsoleHeader}.vue
  app/components/tenant/{TenantRow,TenantForm,QuotaMeter,DomainEditor}.vue
  app/pages/index.vue              -- overview (was: everything)
  app/pages/tenants/index.vue      app/pages/tenants/new.vue      app/pages/tenants/[name].vue
  app/pages/renders.vue
```

---

## Task 1: Design tokens

**Files:**

- Create: `ui/layers/design/app/assets/css/tokens.css`, `ui/layers/design/app/app.config.ts`, `ui/apps/app/app/app.config.ts`, `ui/apps/console/app/app.config.ts`
- Modify: `ui/layers/design/app/assets/css/main.css`, `ui/layers/design/nuxt.config.ts` (colour mode default)

**Interfaces:**

- Consumes: nothing.
- Produces: the token vocabulary every later task uses. Tailwind utilities `bg-default`/`bg-muted`/`bg-elevated`, `text-default`/`text-muted`/`text-dimmed`/`text-highlighted`, `border-default`/`border-muted`/`border-accented`, `text-primary`/`bg-primary` (Nuxt UI's own, now pointing at our ramps), plus the custom properties `--apus-cell`, `--apus-cell-gap`, `--apus-eyebrow-tracking`, `--apus-rail-stage`.

- [ ] **Step 1: Write the ramps and semantic overrides**

`ui/layers/design/app/assets/css/tokens.css`:

```css
/*
 * Apus design tokens.
 *
 * Nuxt UI 4's contract (see its dist/runtime/index.css): a colour is a `--ui-color-<slot>-<step>`
 * ramp, and semantic aliases (--ui-bg, --ui-text-muted, --ui-border, ...) are re-pointed per
 * mode by the .light / .dark classes @nuxtjs/color-mode puts on <html>. We supply the ramps and
 * override the handful of semantic values where our neutral wants a different step than the
 * default; everything downstream (`bg-elevated`, `text-muted`, `border-default`) then works
 * unchanged, in both apps, in both modes.
 *
 * Status ramps (success/warning/error/info) are deliberately left as Nuxt UI ships them. They
 * are well-tuned and accessible, they are identical in both applications by construction, and
 * a "failed" badge must look the same everywhere -- that is worth more than colour-matching
 * them to our accent.
 */

@theme static {
  /* -----------------------------------------------------------------------------------------
   * Basalt -- the shared neutral. A cool ink with a blue cast (hue 255), not a pure grey and
   * not black: the product frames BlueMap's own bright aerial imagery, and a dead-neutral
   * surround makes that imagery look dirty.
   * --------------------------------------------------------------------------------------- */
  --ui-color-neutral-50: oklch(0.985 0.002 255);
  --ui-color-neutral-100: oklch(0.965 0.004 255);
  --ui-color-neutral-200: oklch(0.925 0.006 255);
  --ui-color-neutral-300: oklch(0.860 0.008 255);
  --ui-color-neutral-400: oklch(0.700 0.010 255);
  --ui-color-neutral-500: oklch(0.570 0.012 255);
  --ui-color-neutral-600: oklch(0.460 0.014 255);
  --ui-color-neutral-700: oklch(0.370 0.016 255);
  --ui-color-neutral-800: oklch(0.260 0.018 255);
  --ui-color-neutral-900: oklch(0.190 0.020 255);
  --ui-color-neutral-950: oklch(0.145 0.020 255);

  /* -----------------------------------------------------------------------------------------
   * Verdigris -- the tenant application. Oxidised copper: Minecraft models copper ageing, and
   * this product exists because worlds age and renders refresh them. Muted on purpose; a neon
   * cyan here would be the default dashboard accent and would fight the status colours.
   * --------------------------------------------------------------------------------------- */
  --color-verdigris-50: oklch(0.965 0.020 168);
  --color-verdigris-100: oklch(0.930 0.038 168);
  --color-verdigris-200: oklch(0.870 0.060 168);
  --color-verdigris-300: oklch(0.800 0.080 168);
  --color-verdigris-400: oklch(0.730 0.095 168);
  --color-verdigris-500: oklch(0.665 0.105 168);
  --color-verdigris-600: oklch(0.585 0.098 168);
  --color-verdigris-700: oklch(0.485 0.082 168);
  --color-verdigris-800: oklch(0.390 0.065 168);
  --color-verdigris-900: oklch(0.310 0.050 168);
  --color-verdigris-950: oklch(0.220 0.038 168);

  /* -----------------------------------------------------------------------------------------
   * Lapis -- the management console. 100 degrees away from verdigris, so an admin with both
   * applications open never has to read the URL to know which one they are typing into.
   * --------------------------------------------------------------------------------------- */
  --color-lapis-50: oklch(0.965 0.018 268);
  --color-lapis-100: oklch(0.930 0.036 268);
  --color-lapis-200: oklch(0.875 0.065 268);
  --color-lapis-300: oklch(0.800 0.098 268);
  --color-lapis-400: oklch(0.715 0.135 268);
  --color-lapis-500: oklch(0.635 0.155 268);
  --color-lapis-600: oklch(0.555 0.160 268);
  --color-lapis-700: oklch(0.465 0.140 268);
  --color-lapis-800: oklch(0.375 0.110 268);
  --color-lapis-900: oklch(0.300 0.085 268);
  --color-lapis-950: oklch(0.215 0.060 268);

  /* Squares are the grammar (see the plan's design direction). Nuxt UI's own default is
   * already 0.25rem; restated here so a dependency bump cannot quietly round our corners. */
  --ui-radius: 0.25rem;
  --ui-container: 78rem;
}

/* -------------------------------------------------------------------------------------------
 * Apus-owned tokens. Prefixed, so nothing here can collide with a Nuxt UI variable that gains
 * the same name later.
 * ----------------------------------------------------------------------------------------- */
:root {
  --apus-cell: 0.5rem;
  --apus-cell-gap: 2px;
  --apus-rail-stage: 1.25rem;
  --apus-eyebrow-tracking: 0.14em;
  --font-mono: ui-monospace, "SF Mono", "Cascadia Mono", "Roboto Mono", Menlo, Consolas, monospace;
}

/* Dark mode wants one step more separation between the page and a card than Nuxt UI's default
 * (which points bg-muted and bg-elevated at the same neutral-800): our surfaces stack three
 * deep -- page, card, and the cell meter's own track -- and two of the three would otherwise
 * be indistinguishable. */
.dark {
  --ui-bg: var(--ui-color-neutral-950);
  --ui-bg-muted: var(--ui-color-neutral-900);
  --ui-bg-elevated: var(--ui-color-neutral-800);
  --ui-border: var(--ui-color-neutral-800);
  --ui-border-muted: var(--ui-color-neutral-900);
}

@layer base {
  /* One rule, applied everywhere: machine values are monospace, prose is not. Tabular figures
   * so a percentage counting up does not shift the layout under the reader's eye. */
  .apus-value {
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    letter-spacing: -0.01em;
  }

  /* Visible focus, defined once. Components must not restyle it. */
  :focus-visible {
    outline: 2px solid var(--ui-color-primary-500);
    outline-offset: 2px;
  }

  @media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
      animation-duration: 0.01ms !important;
      animation-iteration-count: 1 !important;
      transition-duration: 0.01ms !important;
    }
  }
}
```

- [ ] **Step 2: Import the tokens after Nuxt UI**

`ui/layers/design/app/assets/css/main.css` — order matters, our `@theme` block must come after Nuxt UI's or its defaults win:

```css
@import "tailwindcss";
@import "@nuxt/ui";
@import "./tokens.css";
```

- [ ] **Step 3: Point Nuxt UI's colour slots at the ramps**

`ui/layers/design/app/app.config.ts` — the layer's default, which each app overrides:

```ts
// Nuxt UI resolves `colors.primary: 'verdigris'` to the --color-verdigris-* ramp in tokens.css.
// The layer defaults to the tenant application's accent; apps/console overrides it. An app's
// own app.config.ts wins over a layer's, same as any other file.
export default defineAppConfig({
  ui: {
    colors: {
      primary: 'verdigris',
      neutral: 'neutral'
    }
  }
})
```

`ui/apps/app/app/app.config.ts`:

```ts
export default defineAppConfig({
  ui: { colors: { primary: 'verdigris', neutral: 'neutral' } }
})
```

`ui/apps/console/app/app.config.ts`:

```ts
// Lapis, not verdigris: the console is a different application and must never be mistaken for
// the tenant app at a glance. See the design direction in this task's plan.
export default defineAppConfig({
  ui: { colors: { primary: 'lapis', neutral: 'neutral' } }
})
```

- [ ] **Step 4: Default to dark, respect the system, let the user choose**

In `ui/layers/design/nuxt.config.ts`, add below `modules`:

```ts
  // Nuxt UI bundles @nuxtjs/color-mode. Dark is the default because this is an operations
  // surface looked at next to a dark BlueMap, but `fallback` only applies when the visitor has
  // expressed no preference -- a light-mode system still gets light.
  colorMode: {
    preference: 'system',
    fallback: 'dark',
    classSuffix: ''
  },
```

- [ ] **Step 5: Build and verify the ramps actually reach the stylesheet**

```bash
cd ui
pnpm --filter @apus/ui-app build
grep -o "verdigris-500" apps/app/.output/public/_nuxt/*.css | head -1
grep -c "oklch" apps/app/.output/public/_nuxt/*.css | head -1
```

Expected: `verdigris-500` present, and a non-zero `oklch` count. If the ramp is missing, `@theme static` did not survive — the fallback is to move the ramp definitions out of `@theme` into a plain `:root` block and add a `@theme` block that aliases them (`--color-verdigris-500: var(--ramp-verdigris-500)`). Report which form was needed.

- [ ] **Step 6: Confirm the console renders lapis, not verdigris**

```bash
pnpm --filter @apus/ui-console build
grep -o "lapis-500" apps/console/.output/public/_nuxt/*.css | head -1
```

Expected: present. This is the one check that catches an app-config override that silently did not apply — both apps would otherwise just look "themed" and nobody would notice they look identical.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(ui): design tokens -- basalt neutral, verdigris and lapis accents

One ramp set in layers/design, one accent per application chosen in its own
app.config.ts. Status colours stay Nuxt UI's: a failed render must look the same
in both applications, which matters more than matching them to our accent."
```

---

## Task 2: The signature — CellMeter and PipelineRail

**Files:**

- Create: `ui/layers/core/app/utils/pipeline.ts`, `ui/layers/core/tests/unit/pipeline.spec.ts`, `ui/layers/design/app/components/CellMeter.vue`, `ui/layers/design/app/components/PipelineRail.vue`, `ui/apps/app/tests/nuxt/cellMeter.nuxt.spec.ts`

**Interfaces:**

- Consumes: tokens from Task 1.
- Produces:

```ts
export type StageState = 'done' | 'active' | 'pending' | 'failed' | 'skipped'
export interface PipelineStage {
  key: 'source' | 'bundle' | 'map' | 'render' | 'hosting'
  label: string
  state: StageState
  /** 0-100, only meaningful when state is 'active'. */
  percent: number
  /** One short sentence naming what this state means for this world. */
  detail: string
}
```

`<CellMeter :percent="n" :cells="24" label="…" />` and `<PipelineRail :stages="stages" />`.

- [ ] **Step 1: Write the failing test for the stage derivation**

`ui/layers/core/tests/unit/pipeline.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { cellsFilled } from '~/utils/pipeline'

describe('cellsFilled', () => {
  it('fills none at zero and all at a hundred', () => {
    expect(cellsFilled(0, 24)).toBe(0)
    expect(cellsFilled(100, 24)).toBe(24)
  })

  it('rounds to the nearest cell', () => {
    expect(cellsFilled(50, 24)).toBe(12)
    expect(cellsFilled(52, 24)).toBe(12)
    expect(cellsFilled(54, 24)).toBe(13)
  })

  it('never shows a full meter for work that is not finished', () => {
    // The lie that matters: 99.6% must not round up to every cell filled, because a full
    // meter next to a running render reads as "done" and sends people looking for a map that
    // is not there yet.
    expect(cellsFilled(99.6, 24)).toBe(23)
    expect(cellsFilled(99.99, 8)).toBe(7)
  })

  it('never shows an empty meter for work that has started', () => {
    // The mirror case: 0.4% is not nothing, and an empty meter reads as "stuck".
    expect(cellsFilled(0.4, 24)).toBe(1)
    expect(cellsFilled(0.01, 8)).toBe(1)
  })

  it('clamps values outside the range rather than overflowing the row', () => {
    expect(cellsFilled(-5, 24)).toBe(0)
    expect(cellsFilled(140, 24)).toBe(24)
  })

  it('handles a degenerate cell count', () => {
    expect(cellsFilled(50, 0)).toBe(0)
  })
})
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd ui && pnpm --filter @apus/ui-core test
```

Expected: FAIL — `Failed to resolve import "~/utils/pipeline"`.

- [ ] **Step 3: Implement `cellsFilled`**

`ui/layers/core/app/utils/pipeline.ts`:

```ts
/**
 * The five stages a world passes through, and the arithmetic behind the quantised meters.
 *
 * `cellsFilled` is the whole reason the meter is honest. Plain rounding would let 99.6% fill
 * every cell -- a full meter beside a still-running render, which reads as "your map is ready"
 * and sends someone looking for a URL that does not exist yet. It would equally let 0.4% show
 * an empty meter, which reads as "stuck". So the two ends are special-cased: only exactly-100
 * fills the last cell, and anything above zero lights the first.
 */
export type StageState = 'done' | 'active' | 'pending' | 'failed' | 'skipped'

export interface PipelineStage {
  key: 'source' | 'bundle' | 'map' | 'render' | 'hosting'
  label: string
  state: StageState
  /** 0-100. Only meaningful when `state` is `'active'`. */
  percent: number
  /** One short sentence naming what this state means for this world. */
  detail: string
}

export function cellsFilled(percent: number, cells: number): number {
  if (cells <= 0) return 0
  if (!Number.isFinite(percent) || percent <= 0) return 0
  if (percent >= 100) return cells

  const exact = (percent / 100) * cells
  const rounded = Math.round(exact)
  // Never round up into the final cell, and never round down out of the first.
  return Math.min(cells - 1, Math.max(1, rounded))
}
```

- [ ] **Step 4: Run it and watch it pass**

```bash
cd ui && pnpm --filter @apus/ui-core test
```

Expected: PASS, six cases, plus the 112 already there.

- [ ] **Step 5: Build `CellMeter`**

`ui/layers/design/app/components/CellMeter.vue`:

```vue
<script setup lang="ts">
/**
 * The design's signature, and the only way this product draws a proportion.
 *
 * World data is a grid -- regions of 32x32 chunks, chunks of 16x16 blocks -- and BlueMap
 * renders it as a tile pyramid. So progress here is squares completing, not liquid rising. The
 * cells deliberately do NOT map to real tiles: the API reports a percentage and nothing about
 * which tiles are done, and a grid that implied otherwise would be a lie told in pixels.
 *
 * Accessibility: the row is one progressbar with a real value, and the numeric readout beside
 * it is not decoration -- it is the accessible value made visible, so the meter never
 * communicates by colour or count alone.
 */
import { computed } from 'vue'
import { cellsFilled } from '#core/utils/pipeline'

const props = withDefaults(defineProps<{
  percent: number
  cells?: number
  label: string
  /** Renders the settle animation. Only pass true while work is genuinely in flight. */
  live?: boolean
  tone?: 'primary' | 'success' | 'warning' | 'error'
}>(), {
  cells: 24,
  live: false,
  tone: 'primary'
})

const filled = computed(() => cellsFilled(props.percent, props.cells))
const rounded = computed(() => Math.round(props.percent))
const toneClass = computed(() => ({
  primary: 'bg-primary',
  success: 'bg-success',
  warning: 'bg-warning',
  error: 'bg-error'
}[props.tone]))
</script>

<template>
  <div class="flex items-center gap-3">
    <div
      class="flex"
      role="progressbar"
      :aria-label="label"
      :aria-valuenow="rounded"
      aria-valuemin="0"
      aria-valuemax="100"
      :style="{ gap: 'var(--apus-cell-gap)' }"
    >
      <span
        v-for="cell in props.cells"
        :key="cell"
        class="apus-cell"
        :class="cell <= filled ? [toneClass, props.live && cell === filled ? 'apus-cell--live' : ''] : 'bg-accented'"
        :style="{
          width: 'var(--apus-cell)',
          height: 'var(--apus-cell)',
          animationDelay: props.live ? `${cell * 12}ms` : undefined
        }"
      />
    </div>
    <span class="apus-value text-sm text-highlighted">{{ rounded }}%</span>
  </div>
</template>

<style scoped>
/* Square, flush, no radius: the grammar of the whole design is the cell. */
.apus-cell {
  flex: none;
  border-radius: 0;
}

/* The one animation in the product, and only on the leading cell of a live render -- the single
   place where "right now" is itself the information. Held to a slow, low-contrast pulse so a
   dashboard left open does not flicker in someone's peripheral vision all afternoon. */
.apus-cell--live {
  animation: apus-cell-pulse 1.6s ease-in-out infinite;
}

@keyframes apus-cell-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}
</style>
```

- [ ] **Step 6: Test the honesty rule end to end**

`ui/apps/app/tests/nuxt/cellMeter.nuxt.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import CellMeter from '#design/components/CellMeter.vue'

describe('CellMeter', () => {
  it('exposes the percentage to assistive technology and to the eye', async () => {
    const wrapper = await mountSuspended(CellMeter, {
      props: { percent: 42, label: 'Render progress' }
    })

    const bar = wrapper.find('[role="progressbar"]')
    expect(bar.attributes('aria-valuenow')).toBe('42')
    expect(bar.attributes('aria-label')).toBe('Render progress')
    // Never colour or cell-count alone.
    expect(wrapper.text()).toContain('42%')
  })

  it('leaves the last cell unfilled until the work is actually complete', async () => {
    const wrapper = await mountSuspended(CellMeter, {
      props: { percent: 99.6, cells: 10, label: 'Render progress' }
    })

    expect(wrapper.findAll('.apus-cell').filter(c => c.classes('bg-accented'))).toHaveLength(1)
  })
})
```

Add the `#design` alias to `ui/layers/design/nuxt.config.ts`, mirroring `#core`:

```ts
import { fileURLToPath } from 'node:url'
// …
  alias: {
    '#design': fileURLToPath(new URL('./app', import.meta.url))
  },
```

- [ ] **Step 7: Build `PipelineRail`**

`ui/layers/design/app/components/PipelineRail.vue`:

```vue
<script setup lang="ts">
/**
 * A world's journey, in the order it actually happens: the server's files become a bundle, the
 * bundle feeds a map, the map is rendered, the render is served. Five stages, because that is
 * how many the system has -- the numbering is the sequence, not decoration.
 *
 * The active stage's square subdivides into the cell grid. That is the design's signature
 * landing in the one place where it carries the most: the rail answers "where is this stuck?"
 * at a glance, and then answers "how far along?" without a second component.
 */
import type { PipelineStage } from '#core/utils/pipeline'

defineProps<{ stages: PipelineStage[], compact?: boolean }>()

const stateClass: Record<string, string> = {
  done: 'bg-primary border-primary',
  active: 'border-primary bg-transparent',
  pending: 'bg-transparent border-accented',
  failed: 'bg-error border-error',
  skipped: 'bg-transparent border-muted'
}

const stateLabel: Record<string, string> = {
  done: 'Done',
  active: 'In progress',
  pending: 'Waiting',
  failed: 'Failed',
  skipped: 'Not applicable'
}
</script>

<template>
  <ol class="flex items-start" :class="compact ? 'gap-2' : 'gap-0'">
    <li
      v-for="(stage, index) in stages"
      :key="stage.key"
      class="flex items-start"
      :class="compact ? '' : 'flex-1'"
    >
      <div class="flex flex-col gap-2" :class="compact ? '' : 'min-w-0 flex-1'">
        <div class="flex items-center gap-0">
          <span
            class="border shrink-0"
            :class="stateClass[stage.state]"
            :style="{ width: 'var(--apus-rail-stage)', height: 'var(--apus-rail-stage)' }"
          />
          <span
            v-if="index < stages.length - 1 && !compact"
            class="h-px flex-1 bg-accented"
          />
        </div>

        <template v-if="!compact">
          <span class="apus-value text-dimmed text-[0.6875rem] uppercase"
                :style="{ letterSpacing: 'var(--apus-eyebrow-tracking)' }">
            {{ stage.label }}
          </span>
          <span class="text-sm text-muted">{{ stateLabel[stage.state] }}</span>
          <p class="text-dimmed max-w-[22ch] text-xs">{{ stage.detail }}</p>
          <CellMeter
            v-if="stage.state === 'active'"
            :percent="stage.percent"
            :cells="12"
            :live="true"
            :label="`${stage.label} progress`"
            class="mt-1"
          />
        </template>
      </div>

      <!-- Compact rows carry the same five states as bare squares; the label lives in the
           row's own heading, so repeating it here would be noise in a list of twenty. -->
      <span class="sr-only">{{ stage.label }}: {{ stateLabel[stage.state] }}</span>
    </li>
  </ol>
</template>
```

- [ ] **Step 8: Verify, then commit**

```bash
cd ui
pnpm lint
pnpm typecheck
pnpm test
git add -A
git commit -m "feat(ui): the quantised cell meter and the pipeline rail

Progress in this product is squares completing, not liquid rising -- world data
is a grid and BlueMap renders it as a tile pyramid. The cells do not map to real
tiles and must not: the API reports a percentage and nothing about which tiles
are done.

cellsFilled special-cases both ends. Plain rounding lets 99.6% fill every cell,
and a full meter beside a running render sends people looking for a map that is
not there yet."
```

---

## Task 3: The world view model

**Files:**

- Create: `ui/layers/core/app/utils/worlds.ts`, `ui/layers/core/tests/unit/worlds.spec.ts`
- Modify: `ui/layers/core/app/utils/pipeline.ts` (add `deriveStages`)

**Interfaces:**

- Consumes: `apiTypes.ts`, `PipelineStage` from Task 2.
- Produces:

```ts
export interface World {
  /** The BlueMapMap's name -- this is the world's identity throughout the app. */
  name: string
  map: BlueMapMapResponse
  /** null when sourceRef names a source the caller cannot see, or none at all. */
  source: WorldSourceResponse | null
  /** Newest first. */
  renders: BlueMapRenderResponse[]
  hosting: BlueMapHostingResponse | null
  /** The public URL, or null when nothing serves this world yet. */
  url: string | null
  stages: PipelineStage[]
}

export function buildWorlds(
  maps: BlueMapMapResponse[],
  sources: WorldSourceResponse[],
  renders: BlueMapRenderResponse[],
  hostings: BlueMapHostingResponse[]
): World[]
```

- [ ] **Step 1: Write the failing test**

`ui/layers/core/tests/unit/worlds.spec.ts`. Build fixtures with small factory helpers at the top of the file (`aMap`, `aSource`, `aRender`, `aHosting`) so each case states only what it is about. Cases, all of them real situations this API produces:

```ts
import { describe, expect, it } from 'vitest'
import { buildWorlds } from '~/utils/worlds'
import type {
  BlueMapHostingResponse, BlueMapMapResponse, BlueMapRenderResponse, WorldSourceResponse
} from '~/utils/apiTypes'

function aMap(name: string, sourceRef: string | null = 'src', phase: string | null = null): BlueMapMapResponse {
  return {
    name,
    source: { sourceRef, world: 'world', dimension: 'overworld' },
    trigger: { onNewBundle: true, schedule: null, concurrencyPolicy: 'Forbid' },
    bluemap: { version: null, minecraftVersion: null },
    shards: 1,
    historyLimit: 3,
    purgeOnDelete: false,
    bucket: { name: `${name}-bucket`, endpoint: 'https://s3.example.net' },
    latestRender: { name: phase ? `${name}-r1` : null, phase },
    conditions: []
  }
}

function aSource(name: string, bundleVersion: string | null = 'v3'): WorldSourceResponse {
  return {
    name,
    type: 's3',
    poll: '5m',
    worlds: [{ name: 'world', layout: 'vanilla', minecraftVersion: '1.21' }],
    keepVersions: 3,
    lastSeenVersion: bundleVersion,
    latestBundle: bundleVersion ? { path: `bundles/${bundleVersion}`, version: bundleVersion } : null,
    lastPollTime: '2026-08-15T10:00:00Z',
    conditions: []
  }
}

function aRender(name: string, mapRef: string, phase: string, percent = 0, start = '2026-08-15T10:00:00Z'): BlueMapRenderResponse {
  return {
    name,
    mapRef,
    force: false,
    phase,
    progress: { percent, currentMap: mapRef, etaSeconds: 0, degraded: false },
    startTime: start,
    completionTime: null,
    conditions: []
  }
}

function aHosting(name: string, maps: string[], url: string | null): BlueMapHostingResponse {
  return { name, maps, hostname: 'maps.example.net', url, ready: url !== null, replicas: 1, conditions: [] }
}

describe('buildWorlds', () => {
  it('joins a map to its source, its renders and its hosting', () => {
    const worlds = buildWorlds(
      [aMap('atlas', 'survival', 'Succeeded')],
      [aSource('survival')],
      [aRender('atlas-r1', 'atlas', 'Succeeded', 100)],
      [aHosting('public', ['atlas'], 'https://maps.example.net/atlas')]
    )

    expect(worlds).toHaveLength(1)
    expect(worlds[0]!.name).toBe('atlas')
    expect(worlds[0]!.source?.name).toBe('survival')
    expect(worlds[0]!.renders.map(r => r.name)).toEqual(['atlas-r1'])
    expect(worlds[0]!.url).toBe('https://maps.example.net/atlas')
  })

  it('survives a sourceRef that names nothing the caller can see', () => {
    // A 404 in this API is deliberately indistinguishable from "not in your tenant", so a
    // dangling ref is a normal state, not a bug to throw on.
    const worlds = buildWorlds([aMap('atlas', 'gone')], [], [], [])

    expect(worlds[0]!.source).toBeNull()
    expect(worlds[0]!.stages.find(s => s.key === 'source')!.state).toBe('failed')
  })

  it('orders renders newest first regardless of the order the API returned them', () => {
    const worlds = buildWorlds(
      [aMap('atlas')],
      [aSource('src')],
      [
        aRender('old', 'atlas', 'Succeeded', 100, '2026-08-01T00:00:00Z'),
        aRender('new', 'atlas', 'Succeeded', 100, '2026-08-14T00:00:00Z')
      ],
      []
    )

    expect(worlds[0]!.renders.map(r => r.name)).toEqual(['new', 'old'])
  })

  it('ignores renders belonging to no map in this tenant', () => {
    const worlds = buildWorlds([aMap('atlas')], [], [aRender('x', 'other', 'Succeeded', 100)], [])

    expect(worlds[0]!.renders).toHaveLength(0)
  })

  it('reports no URL when a hosting lists the map but is not ready', () => {
    const worlds = buildWorlds([aMap('atlas')], [], [], [aHosting('public', ['atlas'], null)])

    expect(worlds[0]!.url).toBeNull()
    expect(worlds[0]!.stages.find(s => s.key === 'hosting')!.state).toBe('pending')
  })

  it('marks the render stage active with the live percentage', () => {
    const worlds = buildWorlds(
      [aMap('atlas', 'src', 'Running')],
      [aSource('src')],
      [aRender('atlas-r1', 'atlas', 'Running', 37)],
      []
    )

    const render = worlds[0]!.stages.find(s => s.key === 'render')!
    expect(render.state).toBe('active')
    expect(render.percent).toBe(37)
  })

  it('marks the render stage failed and says so, rather than falling back to pending', () => {
    const worlds = buildWorlds(
      [aMap('atlas', 'src', 'Failed')],
      [aSource('src')],
      [aRender('atlas-r1', 'atlas', 'Failed', 12)],
      []
    )

    expect(worlds[0]!.stages.find(s => s.key === 'render')!.state).toBe('failed')
  })

  it('shows a source that has produced no bundle yet as waiting, not broken', () => {
    const worlds = buildWorlds([aMap('atlas', 'src')], [aSource('src', null)], [], [])

    expect(worlds[0]!.stages.find(s => s.key === 'source')!.state).toBe('done')
    expect(worlds[0]!.stages.find(s => s.key === 'bundle')!.state).toBe('pending')
  })

  it('returns an empty list for an empty tenant', () => {
    expect(buildWorlds([], [], [], [])).toEqual([])
  })

  it('orders worlds by name so the list does not reshuffle between polls', () => {
    const worlds = buildWorlds([aMap('zulu'), aMap('alpha')], [], [], [])

    expect(worlds.map(w => w.name)).toEqual(['alpha', 'zulu'])
  })
})
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd ui && pnpm --filter @apus/ui-core test
```

Expected: FAIL on the missing module.

- [ ] **Step 3: Implement `deriveStages` and `buildWorlds`**

Append to `ui/layers/core/app/utils/pipeline.ts`:

```ts
/**
 * The five-stage status for one world, derived from what the four list endpoints return.
 *
 * Every branch here answers a question a user actually asks, and the `detail` strings are the
 * answer in one sentence. Two of them are the awkward states this system genuinely produces and
 * that the old resource-per-page UI left as a silence: a source whose `sourceRef` resolves to
 * nothing the caller can see, and a map nobody has rendered yet.
 */
export function deriveStages(input: {
  hasSource: boolean
  sourceRefNamed: boolean
  hasBundle: boolean
  latestRenderPhase: string | null
  latestRenderPercent: number
  hasHostingEntry: boolean
  hostingReady: boolean
}): PipelineStage[] {
  const source: PipelineStage = input.hasSource
    ? { key: 'source', label: 'Source', state: 'done', percent: 100, detail: 'Connected and being polled.' }
    : input.sourceRefNamed
      ? { key: 'source', label: 'Source', state: 'failed', percent: 0, detail: 'This map names a source you cannot see. It may have been deleted.' }
      : { key: 'source', label: 'Source', state: 'pending', percent: 0, detail: 'No source is connected to this map yet.' }

  const bundle: PipelineStage = input.hasBundle
    ? { key: 'bundle', label: 'Bundle', state: 'done', percent: 100, detail: 'A snapshot of the world is in storage.' }
    : { key: 'bundle', label: 'Bundle', state: input.hasSource ? 'pending' : 'skipped', percent: 0, detail: input.hasSource ? 'Waiting for the first snapshot from the source.' : 'Nothing to snapshot until a source is connected.' }

  const map: PipelineStage = { key: 'map', label: 'Map', state: 'done', percent: 100, detail: 'Declared by the platform and ready to render.' }

  const phase = input.latestRenderPhase
  const render: PipelineStage =
    phase === 'Running'
      ? { key: 'render', label: 'Render', state: 'active', percent: input.latestRenderPercent, detail: 'Rendering tiles now.' }
      : phase === 'Failed'
        ? { key: 'render', label: 'Render', state: 'failed', percent: input.latestRenderPercent, detail: 'The last render failed. Its log says why.' }
        : phase === 'Succeeded'
          ? { key: 'render', label: 'Render', state: 'done', percent: 100, detail: 'Tiles are rendered and in storage.' }
          : { key: 'render', label: 'Render', state: 'pending', percent: 0, detail: 'Never rendered. Start one when the bundle is ready.' }

  const hosting: PipelineStage = input.hostingReady
    ? { key: 'hosting', label: 'Hosting', state: 'done', percent: 100, detail: 'Live and open to anyone with the link.' }
    : input.hasHostingEntry
      ? { key: 'hosting', label: 'Hosting', state: 'pending', percent: 0, detail: 'A host is assigned but not serving yet.' }
      : { key: 'hosting', label: 'Hosting', state: 'pending', percent: 0, detail: 'No host serves this map yet.' }

  return [source, bundle, map, render, hosting]
}
```

`ui/layers/core/app/utils/worlds.ts`:

```ts
/**
 * A "world" is what a person means when they talk about this product; the API has no such
 * resource. It is a BlueMapMap joined with the source feeding it, the renders that produced it
 * and the hosting that serves it -- and every one of those joins is already in the response
 * bodies (`map.source.sourceRef`, `render.mapRef`, `hosting.maps[]`), so this needs no endpoint
 * that does not exist.
 *
 * Deliberately pure and Nuxt-free: this is the single piece of real logic behind the
 * application's entry point, and it unit-tests without a browser (see ui/README.md, "Why plain
 * Vitest").
 */
import type {
  BlueMapHostingResponse, BlueMapMapResponse, BlueMapRenderResponse, WorldSourceResponse
} from '#core/utils/apiTypes'
import { deriveStages, type PipelineStage } from '#core/utils/pipeline'

export interface World {
  name: string
  map: BlueMapMapResponse
  source: WorldSourceResponse | null
  renders: BlueMapRenderResponse[]
  hosting: BlueMapHostingResponse | null
  url: string | null
  stages: PipelineStage[]
}

function startedAt(render: BlueMapRenderResponse): number {
  const value = render.startTime ? Date.parse(render.startTime) : Number.NaN
  return Number.isNaN(value) ? 0 : value
}

export function buildWorlds(
  maps: BlueMapMapResponse[],
  sources: WorldSourceResponse[],
  renders: BlueMapRenderResponse[],
  hostings: BlueMapHostingResponse[]
): World[] {
  const sourcesByName = new Map(sources.map(source => [source.name, source]))

  return maps
    .map((map): World => {
      const sourceRef = map.source.sourceRef
      const source = sourceRef ? sourcesByName.get(sourceRef) ?? null : null
      const hosting = hostings.find(entry => entry.maps.includes(map.name)) ?? null
      const own = renders
        .filter(render => render.mapRef === map.name)
        // Newest first. The API does not promise an order, and a list that reshuffles between
        // polls is worse than one that is merely unsorted.
        .sort((a, b) => startedAt(b) - startedAt(a))

      return {
        name: map.name,
        map,
        source,
        renders: own,
        hosting,
        url: hosting?.ready ? hosting.url : null,
        stages: deriveStages({
          hasSource: source !== null,
          sourceRefNamed: sourceRef !== null && sourceRef !== '',
          hasBundle: source?.latestBundle != null,
          latestRenderPhase: map.latestRender.phase ?? own[0]?.phase ?? null,
          latestRenderPercent: own[0]?.progress.percent ?? 0,
          hasHostingEntry: hosting !== null,
          hostingReady: hosting?.ready === true
        })
      }
    })
    .sort((a, b) => a.name.localeCompare(b.name))
}
```

- [ ] **Step 4: Run it and watch it pass**

```bash
cd ui && pnpm --filter @apus/ui-core test
```

Expected: PASS, all ten cases.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(ui): buildWorlds, the join the world-centric UI rests on

A world is a BlueMapMap plus its source, its renders and its hosting. Every one
of those joins is already in the response bodies, so the entry point that finally
answers 'where is my world stuck' needs no endpoint that does not exist.

Pure and Nuxt-free, so the one piece of real logic behind the app's front page is
covered by ten plain unit tests -- including the states the old resource-per-page
UI left as silences: a dangling sourceRef, and a map nobody has rendered."
```

---

## Task 4: Shared presentational components

**Files:**

- Create in `ui/layers/design/app/components/`: `AppShell.vue`, `PageHeader.vue`, `SectionLabel.vue`, `StatusPill.vue`, `EmptyState.vue`, `ErrorState.vue`, `DataTable.vue`, `Toolbar.vue`, `MetaList.vue`, `CopyField.vue`, `StatTile.vue`, `LogConsole.vue`, `ConnectionState.vue`
- Create: `ui/apps/app/tests/nuxt/appShell.nuxt.spec.ts`

**Interfaces:**

- Consumes: tokens (Task 1), `CellMeter` (Task 2).
- Produces the component vocabulary both applications build on. Key props:
  - `<AppShell>` slots `nav`, `header`, default. Renders skip link, `<header>`, `<main id="main">`.
  - `<PageHeader title eyebrow? description?>` slot `actions`.
  - `<SectionLabel>` — mono eyebrow, wide tracking, uppercase.
  - `<StatusPill :phase="string" />` — the single status vocabulary.
  - `<DataTable :columns :rows :loading>` slots `cell-<key>`, `empty`.
  - `<StatTile label :value :meter?>`, `<MetaList :items>`, `<CopyField :value>`,
    `<LogConsole :lines :following>`, `<ConnectionState :state>`.

- [ ] **Step 1: Write `StatusPill` — the status vocabulary, in one place**

```vue
<script setup lang="ts">
/**
 * One vocabulary for every phase and condition in both applications. A "Failed" render must
 * look identical in a tenant's world list and in an admin's cluster view -- an operator reads
 * both, often in the same minute, and two dialects would cost them a beat every time.
 *
 * Colour never carries the meaning on its own: the label is always rendered.
 */
const props = defineProps<{ phase: string | null, size?: 'sm' | 'md' }>()

const tone = computed(() => {
  switch (props.phase) {
    case 'Succeeded': case 'Ready': case 'Bound': return 'success'
    case 'Running': case 'Pending': return 'info'
    case 'Failed': case 'Error': return 'error'
    case 'Degraded': return 'warning'
    default: return 'neutral'
  }
})
const label = computed(() => props.phase ?? 'Unknown')
</script>

<template>
  <span
    class="apus-value inline-flex items-center gap-1.5 border px-2 py-0.5 text-xs"
    :class="{
      'border-success/40 text-success bg-success/10': tone === 'success',
      'border-info/40 text-info bg-info/10': tone === 'info',
      'border-error/40 text-error bg-error/10': tone === 'error',
      'border-warning/40 text-warning bg-warning/10': tone === 'warning',
      'border-default text-muted': tone === 'neutral'
    }"
  >
    <span class="size-1.5 shrink-0" :class="{
      'bg-success': tone === 'success', 'bg-info': tone === 'info',
      'bg-error': tone === 'error', 'bg-warning': tone === 'warning',
      'bg-muted': tone === 'neutral'
    }" />
    {{ label }}
  </span>
</template>
```

Note the square marker, not a dot: the cell grammar again, at its smallest size.

- [ ] **Step 2: Write `AppShell` with the accessibility scaffolding**

It renders, in order: a skip link that is visually hidden until focused, a `<header>` with the `header` slot, a `<nav>` with the `nav` slot when one is supplied, and `<main id="main" tabindex="-1">`. On route change it moves focus to `main` — Vue Router leaves focus where it was, which strands a keyboard or screen-reader user in the old page's navigation. Use `useRouter().afterEach()` inside `onMounted`.

- [ ] **Step 3: Write the remaining components**

Follow the same rules throughout: semantic tokens only, `apus-value` on every machine value, `SectionLabel` for eyebrows, no radius beyond `--ui-radius`, no continuous bars. `StatTile` takes an optional `meter` prop and renders a `CellMeter` when given one. `LogConsole` renders `<pre>` with `--ui-color-neutral-950` in both modes (a log is a terminal, and a light-mode log surface is a lie about what it is), a follow-tail toggle, and `aria-live="polite"` only while following.

`EmptyState` and `ErrorState` carry the copy rules from the design direction: an empty screen is an invitation to act (one sentence, at most one action), an error says what happened and what to do, in the interface's voice, without apologising.

- [ ] **Step 4: Test the shell's accessibility contract**

`ui/apps/app/tests/nuxt/appShell.nuxt.spec.ts` — assert the skip link targets `#main`, exactly one `<main>` exists, and the nav landmark carries an accessible name. These are the three things a linter cannot check and a redesign silently breaks.

- [ ] **Step 5: Verify and commit**

```bash
cd ui && pnpm lint && pnpm typecheck && pnpm test
git add -A
git commit -m "feat(ui): the shared component vocabulary

One status vocabulary, one shell, one table, one empty state -- both applications
build on these so a phase badge reads the same wherever an operator meets it."
```

---

## Task 5: The tenant application — worlds

**Files:**

- Create: `ui/apps/app/app/pages/worlds/[name].vue`, `ui/apps/app/app/components/world/{WorldRow,WorldSummary,WorldActions}.vue`, `ui/apps/app/app/composables/useWorlds.ts`
- Modify: `ui/apps/app/app/pages/index.vue` (becomes the worlds list), `ui/apps/app/app/layouts/default.vue`, `ui/apps/app/app/components/layout/{AppHeader,AppNav}.vue`
- Create: `ui/apps/app/app/pages/account.vue` (the old `index.vue` content)

**Interfaces:**

- Consumes: `buildWorlds`, `PipelineRail`, `CellMeter`, `AppShell`, `DataTable`, `StatusPill`, `EmptyState`.
- Produces: `useWorlds()` returning `{ worlds, loading, error, refresh }`, fetching the four lists with `Promise.allSettled` and joining them.

- [ ] **Step 1: `useWorlds`**

Four independent calls with `Promise.allSettled`, exactly as today's tenant dashboard does — one failing endpoint must degrade its own stage, not blank the page. A failed `listSources()` yields worlds whose source stage reads "could not be loaded" rather than "failed".

- [ ] **Step 2: The worlds list (`pages/index.vue`)**

The application's front page. Per world, one row: name (mono), the compact five-square rail, the current state in words, and the public URL as a link when there is one. Rows are ordered by name. The page's own heading says what this is; there is no dashboard of counts, because a count of sources is not something anyone came here to learn.

Three empty states, and they are the difference between a usable product and a wall:

- No sources at all → "Connect a source" as the single action, one sentence explaining that Apus polls a source for world snapshots.
- Sources but no maps → explain that the platform declares maps, name the source that is already collecting snapshots, and link to it. **Do not offer a "create map" button**: there is no endpoint behind it (see Global Constraints).
- Maps but nothing rendered → the rail already shows this; the row's action is "Start a render".

- [ ] **Step 3: The world detail page (`pages/worlds/[name].vue`)**

The rail full-width at the top — the page's thesis. Below it, in this order because it is the order of the questions people ask: the public URL with a copy action, the live render (or the last one) with its `CellMeter` and ETA, the render history, and the source and map metadata in a `MetaList`.

`WorldActions` holds "Start a render" and "Force a full re-render". The force variant confirms first and says what it costs — it re-renders from scratch and consumes history budget. On success the view does not navigate: the new render appears in place and the meter takes over, because the user's attention is already where they clicked.

- [ ] **Step 4: Rebuild the shell**

`AppHeader` gets the wordmark, the account menu and the role-gated console link (unchanged in behaviour from Part A). `AppNav`: Worlds, Sources, Renders, Hosting. The account page moves to `/account` and out of the main nav into the account menu — it is a reference page, not a destination.

- [ ] **Step 5: Verify and commit**

Run the full gate. Add a Nuxt component test asserting the worlds list renders one row per world and that a world with no URL shows no link (the mock returns two worlds, one hosted, one not).

---

## Task 6: The tenant application — sources, renders, hosting

**Files:**

- Create: `ui/apps/app/app/pages/sources/index.vue`, `ui/apps/app/app/pages/sources/new.vue`, `ui/apps/app/app/pages/renders/index.vue`, `ui/apps/app/app/pages/renders/[id].vue`, `ui/apps/app/app/pages/hosting.vue`, `ui/apps/app/app/components/source/*`
- Delete: `ui/apps/app/app/pages/tenant/**`, the superseded `ui/apps/app/app/components/tenant/*` tables

- [ ] **Step 1: The guided "Connect a source" flow**

`CreateWorldSourceRequest` is a four-way union rendered today as one form with conditional fields. Four steps instead:

1. **Type** — s3, Pterodactyl, upload, push, each with one sentence saying when to use it. This is the only genuinely branching decision and it belongs first.
2. **Connection** — only the fields that type needs (`s3` → endpoint, bucket, prefix, credentials secret; `pterodactyl` → panel URL, server ID, selector; upload/push → nothing but a name).
3. **Worlds and retention** — the world selectors and `keepVersions`.
4. **Review** — what will be created, then "Connect source".

Validation runs per step, so an error appears beside the field that caused it and never as a list of failures after a long form. The steps map to one `POST /api/sources` at the end; nothing is created until step 4.

- [ ] **Step 2: Renders**

`/renders` is the history across all worlds, newest first, with the world name linking back. `/renders/[id]` is the live view: `CellMeter`, ETA, `ConnectionState`, and the `LogConsole` on the same page. The SSE wiring is the existing `sseController.ts` — unchanged logic, new presentation. A dropped stream shows its state and retries visibly; it never degrades into a meter that has silently stopped moving.

- [ ] **Step 3: Hosting**

A short page: one card per hosting, the URL as the primary element with a copy action, the maps it serves as links into their worlds, and readiness as a `StatusPill`.

- [ ] **Step 4: Remove the old pages and verify**

Delete `app/pages/tenant/**` and any `components/tenant/*` no longer imported. Then:

```bash
cd ui
grep -rn "tenant/" apps/app/app || echo "no stale tenant routes"
pnpm lint && pnpm typecheck && pnpm test && pnpm --filter @apus/ui-app test:server
```

The server spec's deep-link case names `/tenant/renders`; change it to `/renders`.

- [ ] **Step 5: Commit**

---

## Task 7: The management console

**Files:**

- Create: `ui/apps/console/app/components/layout/ConsoleSidebar.vue`, `ui/apps/console/app/pages/tenants/{index,new,[name]}.vue`, `ui/apps/console/app/pages/renders.vue`, `ui/apps/console/app/components/tenant/{TenantRow,TenantForm,QuotaMeter,DomainEditor}.vue`
- Modify: `ui/apps/console/app/pages/index.vue` (becomes the overview), `ui/apps/console/app/layouts/default.vue`

- [ ] **Step 1: The sidebar shell**

A permanent left rail: admins move between a few known places repeatedly, and a top bar spends horizontal room that dense tables want. Lapis accent, tighter row height and type scale than the tenant app, and a persistent "Platform" marker so the surface is never mistaken for the tenant application.

- [ ] **Step 2: Overview (`pages/index.vue`)**

Three `StatTile`s — tenants, renders in flight cluster-wide, tenants near their storage quota — and below them the renders currently running, because that is the thing an operator opens this page to see. The tiles link to the pages that explain them.

- [ ] **Step 3: Tenants**

`/tenants` is the dense list: name, namespace, quota vs. observed usage as a `CellMeter`, allowed domains count, conditions. `storageUsage.ts` and `domainValidation.ts` already exist and are already tested — drive the meter and the domain editor from them, do not reimplement.

`/tenants/new` is the create form. `/tenants/[name]` is the detail: edit quota and domains via `PATCH`, and show conditions. Splitting these out of today's single stacked page is the point — an admin answering an operational question should not scroll past a create form to reach it.

- [ ] **Step 4: Cluster renders (`pages/renders.vue`)**

`GET /api/renders/cluster`, grouped by tenant, each row with its `StatusPill` and `CellMeter`.

- [ ] **Step 5: The non-admin case**

A `platform-admin`-only application still renders for anyone who reaches the URL. Give it a deliberate page — what this area is, that it needs the `platform-admin` role, and a link back to the tenant app — rather than a dashboard that 403s on every call. Restate in a comment that this is convenience, not enforcement.

- [ ] **Step 6: Verify and commit**

---

## Task 8: Documentation and final verification

**Files:**

- Modify: `ui/README.md`, `docs/superpowers/specs/2026-08-15-ui-split-and-redesign-design.md` (mark Part B built)

- [ ] **Step 1: Document the design system in `ui/README.md`**

A "Design system" section: where tokens live, the two accents and why they differ, the quantised-cell rule and the reason it exists, the mono/prose boundary, and the one rule a future contributor is most likely to break — **no hex literals, no Tailwind palette colours, no continuous progress bars.** State that `layers/design` must stay free of domain types.

- [ ] **Step 2: Run everything**

```bash
cd ui
pnpm lint
pnpm typecheck
pnpm test
pnpm test:server
```

- [ ] **Step 3: Check both apps in a browser**

```bash
pnpm --filter @apus/ui-app build && PORT=3001 node apps/app/.output/server/index.mjs
```

Confirm by eye, in both colour modes: the accent differs between the applications, no continuous bar appears anywhere, focus rings are visible on tab, and the page does not scroll horizontally at 360px wide. Screenshot both if the environment allows it.

- [ ] **Step 4: Commit**

---

## Done when

- `pnpm lint`, `pnpm typecheck`, `pnpm test`, `pnpm test:server` all pass.
- The tenant app opens on a list of worlds, each showing where it stands end to end, and a world's detail page answers "is it live, and if not, why" without a second click.
- Connecting a source is four short steps, and no screen offers an action the API cannot perform.
- The console has separate overview, list and detail pages, and looks unmistakably unlike the tenant app.
- No hex literal, no Tailwind palette colour, and no continuous progress bar exists in either app or in `layers/design`.
- `layers/design` imports no type from `layers/core` except `PipelineStage` and `cellsFilled`.
