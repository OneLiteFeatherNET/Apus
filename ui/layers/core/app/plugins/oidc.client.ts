/**
 * Restores whatever OIDC session `oidc-client-ts` already knows about (in-memory only, see
 * app/composables/useAuth.ts) before the app renders its first route. `.client.ts` suffix: this
 * touches `window`, so it must never run during SSR -- moot in this app (`ssr: false`), but the
 * suffix documents the constraint even if that ever changes.
 */
export default defineNuxtPlugin(async () => {
  const { init } = useAuth()
  await init()
})
