// @vitest-environment node
//
// Integration test for ui/serve.mjs -- the static file server that replaced nginx in the UI
// image (see the header comment there for why).
//
// It spawns `node serve.mjs <root>` as a child process rather than importing the module,
// because that is literally the container's CMD: argv handling, the listen call and the
// SIGTERM handler are part of what has to work, and none of them run on a bare import. The
// happy-dom environment the other unit tests use is switched off above -- this one talks to a
// real socket.

import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { connect } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

const serveScript = fileURLToPath(new URL('../../serve.mjs', import.meta.url))

let workdir: string
let root: string
let server: ChildProcessWithoutNullStreams
let base: string

/**
 * Sends a request line verbatim, bypassing `fetch`'s URL normalisation.
 *
 * The traversal cases below only mean something if `..` and its encodings reach the server as
 * written; `fetch` (and `new URL`) would resolve some of them away on the client side and the
 * test would then prove nothing about the server.
 */
function rawRequest(requestLine: string, port: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const socket = connect(port, '127.0.0.1', () => {
      socket.write(`${requestLine} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n`)
    })
    let response = ''
    socket.setEncoding('utf8')
    socket.on('data', chunk => response += chunk)
    socket.on('error', reject)
    socket.on('close', () => resolve(response))
  })
}

beforeAll(async () => {
  workdir = await mkdtemp(join(tmpdir(), 'apus-ui-serve-'))
  root = join(workdir, 'public')

  await mkdir(join(root, '_nuxt'), { recursive: true })
  await mkdir(join(root, 'nested'), { recursive: true })
  await writeFile(join(root, 'index.html'), '<!doctype html><title>Apus</title>')
  await writeFile(join(root, '_nuxt', 'app.abc123.css'), 'body{}')
  await writeFile(join(root, 'robots.txt'), 'User-agent: *\n')
  await writeFile(join(root, 'nested', 'index.html'), '<!doctype html>nested')
  // One level above the document root: what a traversal would reach if the guard were wrong.
  await writeFile(join(workdir, 'secret.txt'), 'SECRET')

  // PORT=0 lets the kernel pick a free port -- a hard-coded one turns a busy CI runner into a
  // flaky test. The server prints the port it actually bound; that log line is the handshake.
  server = spawn(process.execPath, [serveScript, root], {
    env: { ...process.env, PORT: '0' },
    stdio: 'pipe'
  })
  server.stderr.setEncoding('utf8')
  server.stdout.setEncoding('utf8')

  base = await new Promise<string>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('server did not report a port in 10s')), 10_000)
    server.stdout.on('data', (chunk: string) => {
      const match = chunk.match(/on (http:\/\/[^\s]+)/)
      if (match) {
        clearTimeout(timer)
        resolve(match[1]!.replace('0.0.0.0', '127.0.0.1'))
      }
    })
    server.on('error', reject)
  })
}, 20_000)

afterAll(async () => {
  if (server && server.exitCode === null) {
    server.kill('SIGTERM')
    await new Promise(resolve => server.once('exit', resolve))
  }
  if (workdir) await rm(workdir, { recursive: true, force: true })
})

describe('serve.mjs', () => {
  it('serves index.html at the root and forbids caching it', async () => {
    const response = await fetch(base)

    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toBe('text/html; charset=utf-8')
    // A cached index.html points at hashed asset names the next deploy no longer ships.
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.text()).toContain('Apus')
  })

  it('marks hashed build assets immutable', async () => {
    const response = await fetch(`${base}/_nuxt/app.abc123.css`)

    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toBe('text/css; charset=utf-8')
    expect(response.headers.get('cache-control')).toBe('public, max-age=31536000, immutable')
  })

  it('makes unhashed extras revalidate instead of being heuristically cached', async () => {
    const response = await fetch(`${base}/robots.txt`)

    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('public, max-age=0, must-revalidate')
  })

  it('sends nosniff on every response', async () => {
    const responses = await Promise.all([
      fetch(base),
      fetch(`${base}/_nuxt/app.abc123.css`),
      fetch(`${base}/missing.png`, { headers: { Accept: 'image/png' } })
    ])

    for (const response of responses) {
      expect(response.headers.get('x-content-type-options')).toBe('nosniff')
    }
  })

  it('answers a matching conditional request with 304 and no body', async () => {
    const first = await fetch(`${base}/_nuxt/app.abc123.css`)
    const etag = first.headers.get('etag')
    expect(etag).toBeTruthy()

    const second = await fetch(`${base}/_nuxt/app.abc123.css`, {
      headers: { 'If-None-Match': etag! }
    })

    expect(second.status).toBe(304)
    expect(await second.text()).toBe('')
  })

  it('falls back to the SPA for client-side routes', async () => {
    // The reload-on-a-deep-link case: /tenants/foo is a Vue Router route, not a file.
    const response = await fetch(`${base}/tenants/foo`, { headers: { Accept: 'text/html' } })

    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.text()).toContain('Apus')
  })

  it('keeps missing assets a 404 instead of answering them with HTML', async () => {
    // Unlike nginx's `try_files ... /index.html`, which handed an <img> a page of HTML and a
    // 200, hiding the broken link.
    const image = await fetch(`${base}/logo.png`, { headers: { Accept: 'image/png' } })
    expect(image.status).toBe(404)

    // Not even for a browser navigation: a miss below /_nuxt/ means the deploy is broken, and
    // the SPA shell cannot fix a missing chunk.
    const chunk = await fetch(`${base}/_nuxt/missing.js`, { headers: { Accept: 'text/html' } })
    expect(chunk.status).toBe(404)
  })

  it('serves a directory index with or without the trailing slash', async () => {
    // `nuxt generate` writes one directory per route (tenant/renders/index.html), and both
    // /nested and /nested/ have to reach it -- the second spelling only because a user who
    // types the first gets no redirect from this server.
    for (const path of ['/nested/', '/nested']) {
      const response = await fetch(`${base}${path}`)

      expect(response.status, path).toBe(200)
      expect(await response.text(), path).toBe('<!doctype html>nested')
    }
  })

  it('answers HEAD with the headers and no body', async () => {
    const response = await fetch(base, { method: 'HEAD' })

    expect(response.status).toBe(200)
    expect(response.headers.get('content-length')).toBeTruthy()
    expect(await response.text()).toBe('')
  })

  it('rejects anything but GET and HEAD', async () => {
    const response = await fetch(base, { method: 'POST' })

    expect(response.status).toBe(405)
    expect(response.headers.get('allow')).toBe('GET, HEAD')
  })

  it('refuses to serve files above the document root', async () => {
    const port = Number(new URL(base).port)

    for (const target of [
      '/../secret.txt',
      '/%2e%2e/secret.txt',
      '/%2e%2e%2fsecret.txt',
      '/_nuxt/../../secret.txt',
      '/nested/../../secret.txt'
    ]) {
      const response = await rawRequest(`GET ${target}`, port)

      expect(response, target).not.toContain('SECRET')
      expect(response, target).toMatch(/^HTTP\/1\.1 (403|404)/)
    }
  })

  it('rejects a path it cannot decode or that carries a NUL byte', async () => {
    const port = Number(new URL(base).port)

    // %ZZ is not valid percent-encoding; %00 would truncate the path in the open() syscall
    // and open a file other than the one that was checked.
    expect(await rawRequest('GET /%ZZ', port)).toMatch(/^HTTP\/1\.1 400/)
    expect(await rawRequest('GET /index.html%00.png', port)).toMatch(/^HTTP\/1\.1 400/)
  })

  it('shuts down on SIGTERM instead of sitting out the termination grace period', async () => {
    // Node is PID 1 in the distroless image, where SIGTERM has no default action: if the
    // handler were missing, every rollout would wait the full terminationGracePeriodSeconds.
    const exited = new Promise<number | null>(resolve => server.once('exit', code => resolve(code)))
    server.kill('SIGTERM')

    await expect(exited).resolves.toBe(0)
  }, 10_000)
})
