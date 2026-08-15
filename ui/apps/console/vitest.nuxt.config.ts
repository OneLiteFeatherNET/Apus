import { defineVitestConfig } from '@nuxt/test-utils/config'

// Boots an actual Nuxt app context (auto-imports, component auto-registration under the real
// directory-prefixed names, plugins), so a component referencing a name Nuxt never registered
// fails here the way it fails at runtime -- an unresolved tag compiles fine, renders as an
// empty custom element, and is caught by neither vue-tsc nor nuxt build.
export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    include: ['tests/nuxt/**/*.spec.ts']
  }
})
