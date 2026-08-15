// Layer marker. Nuxt discovers app/{utils,composables,middleware,plugins,pages} by convention
// once a directory has a nuxt.config.ts and is named in an app's `extends`.
//
// Deliberately empty: this layer registers no module and no CSS. It must stay installable in a
// surface that has neither @nuxt/ui nor Tailwind -- see the design doc, §2.
export default defineNuxtConfig({})
