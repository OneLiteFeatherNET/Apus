// @ts-check
import vuejsAccessibility from 'eslint-plugin-vuejs-accessibility'
import withNuxt from './.nuxt/eslint.config.mjs'

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
  rules: {
    'linebreak-style': ['error', 'unix'],
    'no-trailing-spaces': 'error'
  }
}, {
  files: ['tests/**/*.ts'],
  rules: {
    // Test doubles/fixtures legitimately reach for `any` more often than app code.
    '@typescript-eslint/no-explicit-any': 'off'
  }
})
