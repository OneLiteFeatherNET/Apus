// https://nuxt.com/docs/api/configuration/nuxt-config
//
// Apus UI is a pure SPA (design spec §11.2): `ssr: false`, no server-rendered routes, no
// backend-for-frontend session. Auth (see app/composables/useAuth.ts) is therefore a
// client-only, public OIDC client rather than a confidential one behind a session cookie --
// see ui/README.md "Why no server-side session".
//
// It ships as Nitro's own `node-server` build: `nuxt build` writes `.output/server` (the
// server) and `.output/public` (the client bundle), and the container runs the former. The
// SPA shell is rendered per request from the same output; there is no index.html on disk.
// `ssr: false` still holds -- nothing about a *page* is server-rendered, and this app
// deliberately has no `server/` directory, so the only thing that server does is hand out the
// shell and the assets (see ui/README.md "Serving the built SPA").
export default defineNuxtConfig({
  compatibilityDate: '2026-08-09',
  devtools: { enabled: true },
  ssr: false,
  nitro: {
    // Not the default `node-server`-by-omission: spelling it out keeps `nuxt build` from
    // silently picking a different preset when it detects a deploy provider in CI, which
    // would produce an output the Dockerfile's CMD cannot start.
    preset: 'node-server'
  },
  // Nitro serves the shell with no Cache-Control at all, which leaves browsers free to cache
  // it heuristically -- the exact failure the retired nginx.conf guarded against, where a
  // deploy strands clients on HTML referencing hashed assets that no longer exist. The
  // hashed assets themselves already come back `immutable` from Nitro; that rule is repeated
  // here only because `/**` would otherwise override it.
  routeRules: {
    '/**': {
      headers: {
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff'
      }
    },
    '/_nuxt/**': {
      headers: {
        'cache-control': 'public, max-age=31536000, immutable',
        'x-content-type-options': 'nosniff'
      }
    }
  },
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
