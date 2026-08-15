import { defineConfig } from 'vitest/config'

// Third config next to vitest.nuxt.config.ts, for tests against the *built* Nitro server.
// Separate because it needs a build first -- `pnpm test:server` runs one; folding it into
// `pnpm test` would put a production build in the fast feedback loop.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['tests/server/**/*.spec.ts'],
    // A cold server start plus the SIGTERM case needs more than the 5s default on CI.
    testTimeout: 30_000,
    hookTimeout: 60_000
  }
})
