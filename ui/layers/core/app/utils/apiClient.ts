import { ApusApiError, defaultMessageForStatus } from './apiErrors'
import { parseSseStream } from './sse'
import type {
  PolicyEntryResponse,
  PolicyKeyResponse,
  BlueMapHostingResponse,
  BlueMapMapResponse,
  BlueMapRenderResponse,
  ClusterRenderResponse,
  CreateTeamRequest,
  CreateTenantRequest,
  CreateWorldSourceRequest,
  DirectoryCountsResponse,
  DirectoryTeamResponse,
  DirectoryUserResponse,
  InviteUserRequest,
  PasswordResetResponse,
  RenderProgressEvent,
  TenantDirectoryResponse,
  TenantResponse,
  TriggerRenderRequest,
  UpdateTenantRequest,
  WorldSourceResponse
} from './apiTypes'

/** A `fetch`-compatible function -- swapped out in tests, otherwise the global `fetch`. */
export type FetchLike = typeof fetch

export interface ApusApiClientOptions {
  /** The api module's base URL, e.g. `https://api.apus.example.net` -- no trailing slash needed. */
  baseUrl: string
  /**
   * Resolves the current access token, or `null` if there is none (an unauthenticated request
   * is still sent -- the api module answers with 401, see apiErrors.ts -- rather than the
   * client guessing whether a token is required for a given endpoint).
   */
  getAccessToken: () => Promise<string | null> | string | null
  /** Defaults to the global `fetch`; override in tests. */
  fetchImpl?: FetchLike
  /**
   * The impersonation session, if one is active: `{ tenant, user }` where `user` is `null` to act
   * as the tenant itself. Returning something here cannot grant access — the API decides — so
   * this is a request to be served as somebody, never a claim to be them.
   */
  getImpersonation?: () => { tenant: string, user: string | null } | null
}

export interface SseHandlers<T> {
  onMessage: (event: T) => void
  onError?: (error: unknown) => void
  /** Called when the stream ends normally (the api module closes it once the render is terminal). */
  onClose?: () => void
}

/**
 * Typed client for the `api` module's REST/SSE surface (design spec §11.1). Every method name
 * and shape below is read from the actual controllers under
 * api/src/main/java/net/onelitefeather/apus/api/{rest,events}/ -- see apiTypes.ts's own
 * per-type comments for the exact source file.
 *
 * Deliberately framework-agnostic: no Nuxt composables, no `$fetch`, no global state. Use
 * `useApiClient()` (app/composables/useApiClient.ts) from within Nuxt code; construct this
 * directly (with a mocked `fetchImpl`) in tests -- see tests/unit/apiClient.spec.ts.
 */
export function createApusApiClient(options: ApusApiClientOptions) {
  const baseUrl = options.baseUrl.replace(/\/+$/, '')
  const fetchImpl = options.fetchImpl ?? fetch

  async function resolveToken(): Promise<string | null> {
    return await options.getAccessToken()
  }

  /**
   * Impersonation is two headers and nothing else. Sending them can never grant anything: the
   * API's `ImpersonationFilter` applies `ImpersonationPolicy`, which strips the platform role and
   * refuses a tenant the caller may not act in.
   *
   * Applied to every request while a session is active — including the SSE stream — so a page
   * that happens to make three calls does not answer as three different people.
   */
  function applyImpersonation(headers: Headers): void {
    const actingAs = options.getImpersonation?.()
    if (!actingAs?.tenant) return
    headers.set('X-Apus-Act-As-Tenant', actingAs.tenant)
    if (actingAs.user) {
      headers.set('X-Apus-Act-As-User', actingAs.user)
    }
  }

  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const token = await resolveToken()
    const headers = new Headers(init.headers)
    headers.set('Accept', 'application/json')
    if (init.body !== undefined && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }
    if (token) {
      headers.set('Authorization', `Bearer ${token}`)
    }
    applyImpersonation(headers)

    let response: Response
    try {
      response = await fetchImpl(`${baseUrl}${path}`, { ...init, headers })
    } catch (cause) {
      throw new ApusApiError({ status: 0, message: 'Could not reach the Apus API.', cause })
    }

    if (!response.ok) {
      throw await toApiError(response)
    }
    if (response.status === 204) {
      return undefined as T
    }

    const text = await response.text()
    if (text.length === 0) {
      return undefined as T
    }
    return JSON.parse(text) as T
  }

  async function toApiError(response: Response): Promise<ApusApiError> {
    let body: unknown
    let message: string | undefined
    try {
      const text = await response.text()
      if (text.length > 0) {
        body = JSON.parse(text)
        if (typeof body === 'object' && body !== null && 'message' in body) {
          const candidate = (body as { message?: unknown }).message
          if (typeof candidate === 'string') {
            message = candidate
          }
        }
      }
    } catch {
      // Non-JSON or empty error body (403/404 send none at all, see apiErrors.ts) -- fall
      // through to the default message below.
    }
    return new ApusApiError({
      status: response.status,
      message: message ?? defaultMessageForStatus(response.status),
      body
    })
  }

  async function streamSse<T>(path: string, parse: (raw: string) => T, handlers: SseHandlers<T>, signal?: AbortSignal) {
    const token = await resolveToken()
    const headers = new Headers({ Accept: 'text/event-stream' })
    if (token) {
      headers.set('Authorization', `Bearer ${token}`)
    }
    // The stream too, or a page viewed as a tenant would show that tenant's maps alongside the
    // platform admin's own render progress.
    applyImpersonation(headers)

    let response: Response
    try {
      response = await fetchImpl(`${baseUrl}${path}`, { headers, signal })
    } catch (cause) {
      throw new ApusApiError({ status: 0, message: 'Could not open the event stream.', cause })
    }
    if (!response.ok || !response.body) {
      throw await toApiError(response)
    }

    const reader = response.body.getReader()
    try {
      for await (const raw of parseSseStream(reader)) {
        handlers.onMessage(parse(raw))
      }
      handlers.onClose?.()
    } catch (error) {
      handlers.onError?.(error)
      throw error
    }
  }

  return {
    // -- Tenants: platform-admin only (design spec §10.3, §11.1) -------------------------------
    listTenants: () => request<TenantResponse[]>('/api/tenants'),
    /** The catalogue of options the API can actually enforce -- see PolicyKeyController. */
    listPolicyKeys: () => request<PolicyKeyResponse[]>('/api/policy-keys'),
    /** The caller's own tenant's options. Any tenant role; the tenant comes from the token. */
    getTenantPolicy: () => request<PolicyEntryResponse[]>('/api/tenant/policy'),
    createTenant: (body: CreateTenantRequest) =>
      request<TenantResponse>('/api/tenants', { method: 'POST', body: JSON.stringify(body) }),
    /** Changes an existing tenant's quota and/or allowed hosting domains -- `displayName` is
     * not settable here, see `UpdateTenantRequest`'s own comment. */
    updateTenant: (name: string, body: UpdateTenantRequest) =>
      request<TenantResponse>(`/api/tenants/${encodeURIComponent(name)}`, {
        method: 'PATCH',
        body: JSON.stringify(body)
      }),

    // -- A tenant's teams and people ------------------------------------------------------------
    // Reads never fail the page: the API answers 200 with `unavailableReason` set when the
    // identity provider cannot be reached, so a tenant whose storage and renders are fine stays
    // readable while Microsoft is throttling.
    getDirectoryCounts: (tenant: string) =>
      request<DirectoryCountsResponse>(`/api/tenants/${encodeURIComponent(tenant)}/directory/counts`),
    getTenantDirectory: (tenant: string) =>
      request<TenantDirectoryResponse>(`/api/tenants/${encodeURIComponent(tenant)}/directory`),
    /** Who is in one team — the assignment, rather than the two lists side by side. */
    getTeamMembers: (tenant: string, teamId: string) =>
      request<DirectoryUserResponse[]>(
        `/api/tenants/${encodeURIComponent(tenant)}/directory/teams/${encodeURIComponent(teamId)}/members`
      ),
    createTeam: (tenant: string, body: CreateTeamRequest) =>
      request<DirectoryTeamResponse>(`/api/tenants/${encodeURIComponent(tenant)}/directory/teams`, {
        method: 'POST',
        body: JSON.stringify(body)
      }),
    inviteUser: (tenant: string, body: InviteUserRequest) =>
      request<DirectoryUserResponse>(`/api/tenants/${encodeURIComponent(tenant)}/directory/invitations`, {
        method: 'POST',
        body: JSON.stringify(body)
      }),
    /** The temporary password comes back once and is never retrievable again -- show it, never store it. */
    resetPassword: (tenant: string, userId: string) =>
      request<PasswordResetResponse>(
        `/api/tenants/${encodeURIComponent(tenant)}/directory/users/${encodeURIComponent(userId)}/password-reset`,
        { method: 'POST' }
      ),

    // -- World sources: caller's own tenant -----------------------------------------------------
    listSources: () => request<WorldSourceResponse[]>('/api/sources'),
    createSource: (body: CreateWorldSourceRequest) =>
      request<WorldSourceResponse>('/api/sources', { method: 'POST', body: JSON.stringify(body) }),

    // -- Maps: caller's own tenant ---------------------------------------------------------------
    listMaps: () => request<BlueMapMapResponse[]>('/api/maps'),
    getMap: (id: string) => request<BlueMapMapResponse>(`/api/maps/${encodeURIComponent(id)}`),
    triggerRender: (id: string, body?: TriggerRenderRequest) =>
      request<BlueMapRenderResponse>(`/api/maps/${encodeURIComponent(id)}/render`, {
        method: 'POST',
        body: body === undefined ? undefined : JSON.stringify(body)
      }),

    // -- Renders: caller's own tenant, read-only --------------------------------------------------
    listRenders: () => request<BlueMapRenderResponse[]>('/api/renders'),
    getRender: (id: string) => request<BlueMapRenderResponse>(`/api/renders/${encodeURIComponent(id)}`),

    /** Cluster-wide render view -- `GET /api/renders/cluster`, `platform-admin` only (design
     * spec §10.3, §11.2: "laufende Jobs clusterweit"). */
    listClusterRenders: () => request<ClusterRenderResponse[]>('/api/renders/cluster'),

    /**
     * Live progress for one render -- `GET /api/renders/{id}/events`. Ends when the render
     * reaches a terminal phase (`onClose`) or the caller aborts via `signal`.
     */
    streamRenderEvents: (id: string, handlers: SseHandlers<RenderProgressEvent>, signal?: AbortSignal) =>
      streamSse(
        `/api/renders/${encodeURIComponent(id)}/events`,
        (raw) => JSON.parse(raw) as RenderProgressEvent,
        handlers,
        signal
      ),

    /**
     * Live log lines for one render's job -- `GET /api/renders/{id}/logs`. Each event is one
     * raw log line (not JSON) -- unlike `streamRenderEvents`, the api module's `Event<String>`
     * payload here is the line's text itself.
     */
    streamRenderLogs: (id: string, handlers: SseHandlers<string>, signal?: AbortSignal) =>
      streamSse(`/api/renders/${encodeURIComponent(id)}/logs`, (raw) => raw, handlers, signal),

    // -- Hostings: caller's own tenant, read-only -------------------------------------------------
    listHostings: () => request<BlueMapHostingResponse[]>('/api/hostings')
  }
}

export type ApusApiClient = ReturnType<typeof createApusApiClient>
