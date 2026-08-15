// Tests the *built* Nitro server -- `node .output/server/index.mjs`, which is literally the
// container's CMD. Run via `pnpm test:server`, which builds first; `pnpm test` does not, so
// this file lives outside tests/unit (see vitest.server.config.ts).
//
// What it is really guarding is the header contract in nuxt.config.ts' routeRules. Nitro
// serves the SPA shell with no Cache-Control at all, and a browser is then free to cache it
// heuristically -- after which a deploy leaves clients on HTML that references hashed assets
// the new build no longer ships. That failure is invisible in development and expensive in
// production, so it gets a test rather than a comment.

import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { existsSync, readdirSync } from 'node:fs'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

const outputDir = fileURLToPath(new URL('../../.output', import.meta.url))
const serverEntry = `${outputDir}/server/index.mjs`
const publicAssets = `${outputDir}/public/_nuxt`

let server: ChildProcessWithoutNullStreams
let base: string
let hashedAsset: string

/**
 * Reserves a free port by binding one and letting go again.
 *
 * `PORT=0` cannot be used the way it can with a plain `net` server: Nitro reads 0 as unset
 * and falls back to its default 3000, which would collide with anything else on the runner.
 */
function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const probe = createServer()
    probe.on('error', reject)
    probe.listen(0, '127.0.0.1', () => {
      const address = probe.address()
      const port = typeof address === 'object' && address !== null ? address.port : 0
      probe.close(() => resolve(port))
    })
  })
}

beforeAll(async () => {
  if (!existsSync(serverEntry)) {
    throw new Error(`${serverEntry} is missing -- run \`pnpm build\` first (pnpm test:server does).`)
  }

  // Whatever the current build happens to have hashed the entry chunk to; hard-coding a name
  // would make this fail on the next dependency bump for no reason.
  const asset = readdirSync(publicAssets).find(name => name.endsWith('.js'))
  if (!asset) throw new Error(`no hashed .js asset below ${publicAssets}`)
  hashedAsset = `/_nuxt/${asset}`

  const port = await freePort()
  base = `http://127.0.0.1:${port}`

  server = spawn(process.execPath, [serverEntry], {
    // The same three the image sets, so the test exercises the shipped configuration.
    env: { ...process.env, PORT: String(port), HOST: '127.0.0.1', NODE_OPTIONS: '--max-old-space-size=64' },
    stdio: 'pipe'
  })
  server.stdout.setEncoding('utf8')
  server.stderr.setEncoding('utf8')

  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Nitro did not report a listener in 30s')), 30_000)
    server.stdout.on('data', (chunk: string) => {
      if (chunk.includes('Listening')) {
        clearTimeout(timer)
        resolve()
      }
    })
    server.on('error', reject)
    server.on('exit', code => reject(new Error(`Nitro exited with ${code} before listening`)))
  })
})

afterAll(async () => {
  if (server && server.exitCode === null) {
    server.kill('SIGKILL')
    await new Promise(resolve => server.once('exit', resolve))
  }
})

describe('the built Nitro server', () => {
  it('never lets a browser cache the SPA shell', async () => {
    // Without the routeRule this header is absent entirely, which means heuristic caching.
    const response = await fetch(base)

    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toContain('text/html')
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.text()).toContain('id="__nuxt"')
  })

  it('marks hashed build assets immutable', async () => {
    const response = await fetch(base + hashedAsset)

    expect(response.status).toBe(200)
    // The `/**` rule must not win over `/_nuxt/**` here, or every asset would be uncacheable.
    expect(response.headers.get('cache-control')).toBe('public, max-age=31536000, immutable')
    expect(response.headers.get('etag')).toBeTruthy()
  })

  it('answers a matching conditional asset request with 304', async () => {
    const first = await fetch(base + hashedAsset)
    const etag = first.headers.get('etag')!

    const second = await fetch(base + hashedAsset, { headers: { 'If-None-Match': etag } })

    expect(second.status).toBe(304)
  })

  it('sends nosniff on both the shell and the assets', async () => {
    const [shell, asset] = await Promise.all([fetch(base), fetch(base + hashedAsset)])

    expect(shell.headers.get('x-content-type-options')).toBe('nosniff')
    expect(asset.headers.get('x-content-type-options')).toBe('nosniff')
  })

  it('serves the shell for a client-side route so a deep link survives a reload', async () => {
    // /tenant/renders is a Vue Router route, not a file. This is the reload-on-a-deep-link
    // case the retired nginx `try_files` fallback existed for.
    const response = await fetch(`${base}/tenant/renders`, { headers: { Accept: 'text/html' } })

    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.text()).toContain('id="__nuxt"')
  })

  it('404s a missing hashed asset instead of answering it with the shell', async () => {
    // A miss below /_nuxt/ means the deploy is broken; the shell cannot substitute for a
    // chunk, and answering 200 with HTML would only hide it.
    const response = await fetch(`${base}/_nuxt/does-not-exist.js`)

    expect(response.status).toBe(404)
  })

  it('shuts down on SIGTERM instead of sitting out the termination grace period', async () => {
    const exited = new Promise<void>(resolve => server.once('exit', () => resolve()))

    server.kill('SIGTERM')

    await expect(exited).resolves.toBeUndefined()
  })
})
