import { fileURLToPath } from 'node:url'

// Layer marker. Nuxt discovers app/{utils,composables,middleware,plugins,pages} by convention
// once a directory has a nuxt.config.ts and is named in an app's `extends`.
//
// It registers no module and no CSS on purpose: this layer must stay installable in a surface
// that has neither @nuxt/ui nor Tailwind (design doc 2026-08-15, §2).
export default defineNuxtConfig({
  alias: {
    // `~` always resolves against the *consuming app's* app/ directory, so an app writing
    // `import type { TenantResponse } from '~/utils/apiTypes'` looks inside itself and fails
    // the build with UNLOADABLE_DEPENDENCY. Auto-imports do cross layer boundaries, but the
    // explicit imports these files use -- and every `import type`, which auto-imports cannot
    // replace -- need an alias that points here regardless of who is extending us.
    '#core': fileURLToPath(new URL('./app', import.meta.url))
  }
})
