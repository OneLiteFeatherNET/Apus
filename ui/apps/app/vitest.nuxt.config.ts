import { defineVitestConfig } from '@nuxt/test-utils/config'

// Separate from vitest.config.ts on purpose (see that file's own comment on "why plain
// Vitest" for app/utils/*): this config boots an actual Nuxt app context (auto-imports,
// component auto-registration with the real directory-prefixed names, plugins) so that a
// component referencing e.g. `<AppHeader />` where Nuxt only ever registered
// `LayoutAppHeader` fails the test the same way it fails at runtime -- a plain `vue-tsc`
// typecheck or `nuxt build` does not catch this (see tests/nuxt/defaultLayout.nuxt.spec.ts).
export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    include: ['tests/nuxt/**/*.spec.ts'],
    // Each spec file boots its own Nuxt app, and several booting in parallel on a loaded machine
    // run past Vitest's 10s default -- a timeout here means "the machine was busy", not "the
    // component is broken", and it is not worth a flaky suite to find that out each time.
    hookTimeout: 60_000
  }
})
