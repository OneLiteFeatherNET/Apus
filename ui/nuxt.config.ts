// https://nuxt.com/docs/api/configuration/nuxt-config
//
// Apus UI is a pure SPA (design spec §11.2): `ssr: false`, no server-rendered routes, no
// backend-for-frontend session. It is built as static assets (`nuxt generate` under the hood
// via `nuxt build` + `ssr: false`) and served from a plain webserver container -- there is no
// Nitro server available at runtime to lean on for anything (see ui/README.md "Why no
// server-side session"). That absence is why auth (see app/composables/useAuth.ts) is a
// client-only, public OIDC client rather than a confidential one behind a session cookie.
export default defineNuxtConfig({
  compatibilityDate: '2026-08-09',
  devtools: { enabled: true },
  ssr: false,
  modules: [
    '@nuxt/ui',
    '@vueuse/nuxt',
    '@nuxt/eslint'
  ],
  css: ['~/assets/css/main.css'],
  runtimeConfig: {
    public: {
      // Base URL of the `api` module (design spec §11.1). No default -- an empty value would
      // silently point every request at the SPA's own origin.
      apiBaseUrl: '',
      // Must match `APUS_JWT_ISSUER` on the `api` module -- see that module's
      // application.yml. Which broker sits here is intentionally undecided (design spec §15).
      oidcIssuer: '',
      // The public (no client secret) OIDC client registered for this SPA at the broker.
      oidcClientId: ''
    }
  },
  app: {
    head: {
      title: 'Apus'
    }
  },
  typescript: {
    typeCheck: false
  }
})
