import { ApusApiError, defaultMessageForStatus } from './apiErrors'
import { parseSseStream } from './sse'
import type {
  BlueMapHostingResponse,
  BlueMapMapResponse,
  BlueMapRenderResponse,
  CreateTenantRequest,
  CreateWorldSourceRequest,
  RenderProgressEvent,
  TenantResponse,
  TriggerRenderRequest,
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
    createTenant: (body: CreateTenantRequest) =>
      request<TenantResponse>('/api/tenants', { method: 'POST', body: JSON.stringify(body) }),

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
