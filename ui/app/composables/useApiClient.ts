import { createApusApiClient, type ApusApiClient } from '~/utils/apiClient'

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

  return createApusApiClient({
    baseUrl: config.public.apiBaseUrl,
    getAccessToken
  })
}
