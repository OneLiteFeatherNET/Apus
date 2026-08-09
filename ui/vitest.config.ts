import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

// Deliberately not `@nuxt/test-utils`' Nuxt-aware runner: everything under test (app/utils/*)
// is plain, framework-agnostic TypeScript with no Nuxt auto-imports or runtime dependency --
// see ui/README.md "Why plain Vitest". A `happy-dom` environment is enough for the DOM globals
// (atob, TextDecoder, ReadableStream) the API client and JWT helpers touch.
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
