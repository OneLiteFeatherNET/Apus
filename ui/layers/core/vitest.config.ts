import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

// Deliberately not `@nuxt/test-utils`' Nuxt-aware runner: everything under test (app/utils/*)
// is plain, framework-agnostic TypeScript with no Nuxt auto-imports or runtime dependency --
// see ui/README.md "Why plain Vitest". A `happy-dom` environment is enough for the DOM globals
// (atob, TextDecoder, ReadableStream) the API client and JWT helpers touch.
//
// This config lives in the layer, not at the workspace root: the code it covers lives here, and
// `pnpm -r test` reaches it through this package's own `test` script.
export default defineConfig({
  resolve: {
    alias: {
      // Both spellings resolve here. `#core` is the alias this layer publishes to the apps that
      // extend it (see nuxt.config.ts) and is what the layer's own non-test code uses; `~` is
      // kept because the specs were written against it and it costs nothing to honour.
      '#core': fileURLToPath(new URL('./app', import.meta.url)),
      '~': fileURLToPath(new URL('./app', import.meta.url))
    }
  },
  test: {
    environment: 'happy-dom',
    include: ['tests/unit/**/*.spec.ts']
  }
})
