// Nuxt UI resolves `colors.primary: 'verdigris'` to the --color-verdigris-* ramp defined in
// app/assets/css/tokens.css.
//
// The layer defaults to the tenant application's accent; apps/console overrides it with lapis.
// An app's own app.config.ts wins over a layer's, the same way any other file does.
export default defineAppConfig({
  ui: {
    colors: {
      primary: 'verdigris',
      neutral: 'basalt'
    }
  }
})
