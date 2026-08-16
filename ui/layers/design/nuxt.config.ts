// The visual half of the shared UI: module registration and the single stylesheet every
// application inherits. It has no knowledge of Apus's domain -- no render, no tenant, no map --
// which is what lets a design change be reviewed without reading domain code (design doc
// 2026-08-15, §2).
//
// @nuxt/ui is registered here rather than per app so both applications get the same primitive
// set and the same theming entry point. @vueuse/nuxt sits here for the same reason: it is a
// presentation-side convenience (element size, clipboard, intersection), not domain code.
import { fileURLToPath } from 'node:url'

export default defineNuxtConfig({
  modules: ['@nuxt/ui', '@vueuse/nuxt'],
  alias: {
    // Same reason as layers/core's `#core`: inside a layer `~` resolves against the consuming
    // app's app/ directory, so a test or a component importing a design-layer file by path
    // needs an alias that points here regardless of who is extending us.
    '#design': fileURLToPath(new URL('./app', import.meta.url))
  },
  // Nuxt UI bundles @nuxtjs/color-mode. Dark is the fallback because this is an operations
  // surface read next to a dark BlueMap -- but `fallback` applies only when the visitor has
  // expressed no preference at all; a light-mode system still gets light.
  colorMode: {
    preference: 'system',
    fallback: 'dark',
    classSuffix: ''
  },
  // `import.meta.resolve`, not a `~/assets/...` alias: inside a layer `~` resolves against the
  // *consuming app's* directory, so the alias would look for this file under apps/<app>/app/
  // and silently find nothing.
  css: [import.meta.resolve('./app/assets/css/main.css')]
})
