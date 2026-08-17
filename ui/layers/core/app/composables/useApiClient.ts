import { createApusApiClient, type ApusApiClient } from '#core/utils/apiClient'
// Imported explicitly rather than relying on auto-import: this file is in a layer, and the app
// that extends it only learns about a newly added composable once its generated types are
// rebuilt -- which turns "added a composable" into a confusing type error in a different package.
import { useImpersonation } from '#core/composables/useImpersonation'

/**
 * Nuxt-facing entry point to the api module client. Thin on purpose -- all the actual logic
 * (request handling, error mapping, SSE framing) lives in app/utils/apiClient.ts, which stays
 * plain TypeScript so it is unit-testable without a Nuxt runtime (tests/unit/apiClient.spec.ts).
 *
 * Both dashboard levels (platform, tenant -- see design spec §11.2) should go through this
 * rather than constructing their own client.
 */
export function useApiClient(): ApusApiClient {
  const config = useRuntimeConfig()
  const { getAccessToken } = useAuth()
  const { readSession } = useImpersonation()

  return createApusApiClient({
    baseUrl: config.public.apiBaseUrl,
    getAccessToken,
    // Read at request time, not captured once: a session started or ended after this client was
    // created must take effect on the very next call, not on the next page that happens to build
    // a fresh client.
    getImpersonation: readSession
  })
}
