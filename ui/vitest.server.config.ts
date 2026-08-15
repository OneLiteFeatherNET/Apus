import { defineConfig } from 'vitest/config'

// A third config next to vitest.config.ts (plain unit tests) and vitest.nuxt.config.ts (Nuxt
// app context), for the one kind of test neither can run: tests against the *built* Nitro
// server in `.output/`.
//
// It is separate rather than another `tests/unit/**` file because it has a build as a
// precondition -- `pnpm test:server` runs `nuxt build` first. Folding it into `pnpm test`
// would make the fast feedback loop pay for a full production build on every run.
export default defineConfig({
  test: {
    // Real sockets and a real child process, so no DOM environment.
    environment: 'node',
    include: ['tests/server/**/*.spec.ts'],
    // A cold `node .output/server/index.mjs` plus the SIGTERM case needs more than the 5s
    // default on a loaded CI runner.
    testTimeout: 30_000,
    hookTimeout: 60_000
  }
})
