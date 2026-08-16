// @ts-check
import tsParser from '@typescript-eslint/parser'
import vuejsAccessibility from 'eslint-plugin-vuejs-accessibility'
import vueParser from 'vue-eslint-parser'
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
  // The generated config's own `files` globs are written relative to apps/app/.nuxt/ (they read
  // `../../layers/core/app`), so from this file's location they resolve outside the workspace
  // and match nothing. Everything outside apps/app/app/ -- both layers, and every tests/
  // directory -- would otherwise fall back to ESLint's default JavaScript parser and fail with
  // "Parsing error: Unexpected token" on the first type annotation. Restating the parsers here
  // covers the whole workspace from one place; the rules above still come from Nuxt's config.
  name: 'apus/workspace-parsers',
  files: ['**/*.ts', '**/*.mts', '**/*.vue'],
  languageOptions: {
    parser: vueParser,
    parserOptions: {
      parser: tsParser,
      ecmaVersion: 'latest',
      sourceType: 'module',
      extraFileExtensions: ['.vue']
    }
  }
}, {
  rules: {
    'linebreak-style': ['error', 'unix'],
    'no-trailing-spaces': 'error'
  }
}, {
  // Same cause as the parser block above: Nuxt's own exemption for these directories is scoped
  // by a glob that does not resolve from here. A file-based router names the component after
  // the route segment, so `pages/tenant/maps.vue` cannot be multi-word without changing the URL.
  name: 'apus/router-owned-filenames',
  files: [
    '**/app/pages/**/*.vue',
    '**/app/layouts/**/*.vue',
    '**/app/app.vue',
    '**/app/error.vue'
  ],
  rules: {
    'vue/multi-word-component-names': 'off'
  }
}, {
  files: ['**/tests/**/*.ts'],
  rules: {
    // Test doubles/fixtures legitimately reach for `any` more often than app code.
    '@typescript-eslint/no-explicit-any': 'off'
  }
})
