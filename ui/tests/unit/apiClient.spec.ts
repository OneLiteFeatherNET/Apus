import { describe, expect, it, vi } from 'vitest'
import { createApusApiClient, type FetchLike } from '../../app/utils/apiClient'
import { ApusApiError } from '../../app/utils/apiErrors'
import type { TenantResponse } from '../../app/utils/apiTypes'

const BASE_URL = 'https://api.apus.example.net'

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body)
  } as Response
}

function emptyResponse(status: number): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => ''
  } as Response
}

function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    }
  })
  return { ok: true, status: 200, body } as unknown as Response
}

describe('createApusApiClient / request handling', () => {
  it('sends a bearer token and Accept header on a GET request', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(jsonResponse(200, []))
    const client = createApusApiClient({
      baseUrl: BASE_URL,
      getAccessToken: () => 'the-token',
      fetchImpl
    })

    await client.listTenants()

    expect(fetchImpl).toHaveBeenCalledOnce()
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe(`${BASE_URL}/api/tenants`)
    const headers = new Headers(init?.headers)
    expect(headers.get('Authorization')).toBe('Bearer the-token')
    expect(headers.get('Accept')).toBe('application/json')
  })

  it('sends no Authorization header when there is no token', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(jsonResponse(200, []))
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    await client.listTenants()

    const headers = new Headers(fetchImpl.mock.calls[0][1]?.headers)
    expect(headers.has('Authorization')).toBe(false)
  })

  it('supports an async getAccessToken', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(jsonResponse(200, []))
    const client = createApusApiClient({
      baseUrl: BASE_URL,
      getAccessToken: async () => 'async-token',
      fetchImpl
    })

    await client.listTenants()

    const headers = new Headers(fetchImpl.mock.calls[0][1]?.headers)
    expect(headers.get('Authorization')).toBe('Bearer async-token')
  })

  it('parses a successful JSON response', async () => {
    const tenant: TenantResponse = {
      name: 'friends-server',
      displayName: 'Friends Server',
      storage: { quota: '500Gi', maxObjects: 5_000_000 },
      allowedHostingDomains: ['*.friends.example.net'],
      namespace: 'bluemap-friends-server',
      objectStoreUser: 'apus-friends-server',
      storageUsedBytes: 228_730_548_224,
      conditions: []
    }
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(jsonResponse(200, [tenant]))
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    await expect(client.listTenants()).resolves.toEqual([tenant])
  })

  it('POSTs a JSON body with a Content-Type header', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(jsonResponse(201, { name: 'new-tenant' }))
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    await client.createTenant({ name: 'new-tenant' })

    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe(`${BASE_URL}/api/tenants`)
    expect(init?.method).toBe('POST')
    expect(init?.body).toBe(JSON.stringify({ name: 'new-tenant' }))
    expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json')
  })

  it('triggers a render with no body and no Content-Type header when force is omitted', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(
      jsonResponse(201, { name: 'r1', mapRef: 'survival-overworld', force: false })
    )
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    await client.triggerRender('survival-overworld')

    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe(`${BASE_URL}/api/maps/survival-overworld/render`)
    expect(init?.body).toBeUndefined()
    expect(new Headers(init?.headers).has('Content-Type')).toBe(false)
  })

  it('PATCHes a JSON body against the tenant path for updateTenant', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(jsonResponse(200, { name: 'acme' }))
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    await client.updateTenant('acme', { storageQuota: '500Gi' })

    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe(`${BASE_URL}/api/tenants/acme`)
    expect(init?.method).toBe('PATCH')
    expect(init?.body).toBe(JSON.stringify({ storageQuota: '500Gi' }))
    expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json')
  })

  it('URL-encodes the tenant name for updateTenant', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(jsonResponse(200, {}))
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    await client.updateTenant('needs encoding/slash', {})

    expect(fetchImpl.mock.calls[0][0]).toBe(`${BASE_URL}/api/tenants/needs%20encoding%2Fslash`)
  })

  it('fetches the cluster-wide render view from /api/renders/cluster', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(jsonResponse(200, []))
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    await client.listClusterRenders()

    expect(fetchImpl.mock.calls[0][0]).toBe(`${BASE_URL}/api/renders/cluster`)
  })

  it('URL-encodes path parameters', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(jsonResponse(200, {}))
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    await client.getMap('needs encoding/slash')

    expect(fetchImpl.mock.calls[0][0]).toBe(`${BASE_URL}/api/maps/needs%20encoding%2Fslash`)
  })

  it('treats 204 No Content as a successful empty result', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(emptyResponse(204))
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    await expect(client.listTenants()).resolves.toBeUndefined()
  })

  it('strips a trailing slash from baseUrl', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(jsonResponse(200, []))
    const client = createApusApiClient({ baseUrl: `${BASE_URL}/`, getAccessToken: () => null, fetchImpl })

    await client.listTenants()

    expect(fetchImpl.mock.calls[0][0]).toBe(`${BASE_URL}/api/tenants`)
  })
})

describe('createApusApiClient / error handling', () => {
  it('surfaces the message from a 400 error body (BadRequestExceptionHandler)', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(jsonResponse(400, { message: 'name must not be blank' }))
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    const error = await client.createTenant({ name: '' }).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApusApiError)
    expect((error as ApusApiError).status).toBe(400)
    expect((error as ApusApiError).message).toBe('name must not be blank')
  })

  it('falls back to a default message for a 403 with no body (ForbiddenExceptionHandler)', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(emptyResponse(403))
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    const error = await client.listSources().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApusApiError)
    expect((error as ApusApiError).status).toBe(403)
    expect((error as ApusApiError).message).toBe('Not permitted.')
  })

  it('falls back to a default message for a 404 with no body (NotFoundExceptionHandler)', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(emptyResponse(404))
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    const error = await client.getRender('missing').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApusApiError)
    expect((error as ApusApiError).status).toBe(404)
    expect((error as ApusApiError).message).toBe('Not found.')
  })

  it('maps a fetch/network failure to a status-0 ApusApiError instead of rejecting raw', async () => {
    const cause = new TypeError('Failed to fetch')
    const fetchImpl = vi.fn<FetchLike>().mockRejectedValue(cause)
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    const error = await client.listTenants().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApusApiError)
    expect((error as ApusApiError).status).toBe(0)
    expect((error as ApusApiError).networkError).toBe(cause)
  })

  it('does not throw when an error body is present but not JSON', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => 'Internal Server Error'
    } as Response)
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    const error = await client.listTenants().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApusApiError)
    expect((error as ApusApiError).status).toBe(500)
    expect((error as ApusApiError).message).toBe('Request failed with status 500.')
  })
})

describe('createApusApiClient / SSE streams', () => {
  it('parses each render progress event as JSON and calls onClose at stream end', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(
      sseResponse(['data: {"phase":"Rendering","percent":10}\n\n', 'data: {"phase":"Succeeded","percent":100}\n\n'])
    )
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => 'tok', fetchImpl })

    const received: unknown[] = []
    let closed = false
    await client.streamRenderEvents('r1', {
      onMessage: (event) => received.push(event),
      onClose: () => {
        closed = true
      }
    })

    expect(received).toEqual([
      { phase: 'Rendering', percent: 10 },
      { phase: 'Succeeded', percent: 100 }
    ])
    expect(closed).toBe(true)
    expect(fetchImpl.mock.calls[0][0]).toBe(`${BASE_URL}/api/renders/r1/events`)
    const headers = new Headers(fetchImpl.mock.calls[0][1]?.headers)
    expect(headers.get('Authorization')).toBe('Bearer tok')
    expect(headers.get('Accept')).toBe('text/event-stream')
  })

  it('delivers log lines as raw strings, not JSON-parsed', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(
      sseResponse(['data: [INFO] starting render\n\n', 'data: [INFO] updating map \'overworld\': 35%\n\n'])
    )
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    const lines: string[] = []
    await client.streamRenderLogs('r1', { onMessage: (line) => lines.push(line) })

    expect(lines).toEqual(['[INFO] starting render', '[INFO] updating map \'overworld\': 35%'])
  })

  it('rejects with an ApusApiError when the stream fails to open', async () => {
    const fetchImpl = vi.fn<FetchLike>().mockResolvedValue(emptyResponse(404))
    const client = createApusApiClient({ baseUrl: BASE_URL, getAccessToken: () => null, fetchImpl })

    const error = await client
      .streamRenderEvents('missing', { onMessage: () => {} })
      .catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApusApiError)
    expect((error as ApusApiError).status).toBe(404)
  })
})
