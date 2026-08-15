// The visual half of the shared UI: module registration and the single stylesheet every
// application inherits. It has no knowledge of Apus's domain -- no render, no tenant, no map --
// which is what lets a design change be reviewed without reading domain code (design doc
// 2026-08-15, §2).
//
// @nuxt/ui is registered here rather than per app so both applications get the same primitive
// set and the same theming entry point. @vueuse/nuxt sits here for the same reason: it is a
// presentation-side convenience (element size, clipboard, intersection), not domain code.
export default defineNuxtConfig({
  modules: ['@nuxt/ui', '@vueuse/nuxt'],
  // `import.meta.resolve`, not a `~/assets/...` alias: inside a layer `~` resolves against the
  // *consuming app's* directory, so the alias would look for this file under apps/<app>/app/
  // and silently find nothing.
  css: [import.meta.resolve('./app/assets/css/main.css')]
})
