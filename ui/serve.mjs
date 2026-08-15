// Static file server for the built SPA -- the runtime half of ui/Dockerfile, replacing the
// nginx that used to serve `.output/public`.
//
// Why hand-written instead of nginx, `vite preview` or a Nitro server:
//
//   * nginx pulled a whole distribution (OpenSSL, PCRE, zlib, a shell, a package manager)
//     into an image whose only job is to hand out ~2 MB of pre-built files. Every CVE in any
//     of those showed up in the scan report for the UI image even though nothing here ever
//     terminates TLS or rewrites a request.
//   * `vite preview` / `nuxt preview` is a *development* preview server. It needs vite,
//     rollup and esbuild present at runtime, so it would put the entire build toolchain --
//     the largest dependency tree in this module -- into the production image. That is the
//     opposite of hardening, and upstream says it is not for production.
//   * A Nitro `node-server` build would work, but it puts per-request server code back into
//     the deployment. Design spec §11.2 and ui/README.md ("Why no server-side session") rest
//     on the UI having *no* server it can lean on: that is why auth is a public OIDC client
//     with PKCE and not a session cookie. Serving files is the only server behaviour the
//     SPA may have, so that is all this file does -- there is no request handler here that a
//     future route could hook into.
//
// The result has zero third-party runtime dependencies: the image is a distroless Node plus
// this file plus the generated assets, and there is no `node_modules` in it at all.
//
// Behaviour mirrors the retired ui/nginx.conf: SPA fallback to index.html, immutable caching
// for the hashed `/_nuxt/` assets, no-store for index.html. Deviations from it are marked
// "vs nginx" below.

import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import { createServer } from 'node:http'
import { extname, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

// The document root: `serve.mjs` and `public/` sit next to each other in the image (/app),
// so the default needs no configuration there. Locally, pass the directory instead:
// `node serve.mjs .output/public` (that is what `pnpm serve` does).
const root = resolve(process.argv[2] ?? fileURLToPath(new URL('./public', import.meta.url)))

const port = Number(process.env.PORT ?? 8080)
const host = process.env.HOST ?? '0.0.0.0'

// Off by default: in a cluster the ingress controller already logs every request, so a second
// access log per pod is duplicate volume in Loki. Set APUS_UI_ACCESS_LOG=true when debugging a
// pod directly. 5xx responses are logged regardless -- those are this server's own bugs.
const accessLog = process.env.APUS_UI_ACCESS_LOG === 'true'

// Only the types `nuxt generate` actually emits, plus the handful a public/ folder typically
// carries. Anything unlisted is served as application/octet-stream rather than guessed: an
// unknown type is never worth sniffing, and `X-Content-Type-Options: nosniff` below tells the
// browser not to second-guess it either.
const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.svg': 'image/svg+xml; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.avif': 'image/avif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.wasm': 'application/wasm'
}

const IMMUTABLE_PREFIX = '/_nuxt/'

/**
 * Resolves a request path to a file below the document root.
 *
 * Returns `null` when the path escapes the root. `resolve()` collapses `..` segments before
 * the prefix check, so `/../../etc/passwd`, `/_nuxt/../../etc/passwd` and their percent-
 * encoded spellings (the URL is decoded before this is called) all land outside `root` and
 * are rejected rather than followed.
 */
function resolveInRoot(pathname) {
  const target = resolve(root, `.${pathname}`)
  if (target !== root && !target.startsWith(root + sep)) return null
  return target
}

/** A weak validator over size and mtime -- enough for a 304, and cheaper than hashing bodies. */
function etagFor(stats) {
  return `W/"${stats.size.toString(16)}-${Math.floor(stats.mtimeMs).toString(16)}"`
}

function cacheControlFor(pathname, filePath, isFallback) {
  // index.html must never be cached, or a deploy leaves clients on the previous bundle: the
  // stale HTML references hashed asset names that no longer exist. Same reasoning for the SPA
  // fallback (which *is* index.html under another URL) and for every other .html in the
  // output -- in a generated SPA they are all entry points, never content.
  if (isFallback || extname(filePath) === '.html') return 'no-store'
  // Hashed build assets: the name changes when the content does, so they can be cached
  // forever.
  if (pathname.startsWith(IMMUTABLE_PREFIX)) return 'public, max-age=31536000, immutable'
  // vs nginx: unhashed extras (favicon, robots.txt, anything dropped into public/) got no
  // Cache-Control at all and were therefore *heuristically* cached by browsers -- a changed
  // favicon could stick around for days. Forcing revalidation costs one conditional request
  // that almost always answers 304.
  return 'public, max-age=0, must-revalidate'
}

/** Resolves to the `stat` of a readable file, or `null` for anything else (missing, dir, …). */
async function statFile(path) {
  try {
    const stats = await stat(path)
    return stats.isFile() ? stats : null
  } catch {
    return null
  }
}

function send(req, res, status, headers, body) {
  res.writeHead(status, {
    'X-Content-Type-Options': 'nosniff',
    ...headers
  })
  if (req.method === 'HEAD' || body === undefined) {
    res.end()
    return
  }
  res.end(body)
}

function sendError(req, res, status, message) {
  send(req, res, status, {
    'Content-Type': 'text/plain; charset=utf-8',
    'Content-Length': Buffer.byteLength(message),
    'Cache-Control': 'no-store'
  }, message)
}

/**
 * Streams a file that has already been stat'ed, answering 304 when the client's copy holds.
 *
 * Always a 200 -- including the SPA fallback, which is the app answering for one of its own
 * client-side routes, not a substitute for a missing resource.
 */
function sendFile(req, res, path, stats, { pathname, isFallback }) {
  const etag = etagFor(stats)
  const headers = {
    'Content-Type': MIME_TYPES[extname(path).toLowerCase()] ?? 'application/octet-stream',
    'Cache-Control': cacheControlFor(pathname, path, isFallback),
    'Last-Modified': new Date(stats.mtimeMs).toUTCString(),
    ETag: etag
  }

  // A conditional request that still matches costs a header round-trip instead of the body.
  if (req.headers['if-none-match'] === etag) {
    send(req, res, 304, headers)
    return
  }

  headers['Content-Length'] = stats.size
  res.writeHead(200, { 'X-Content-Type-Options': 'nosniff', ...headers })

  if (req.method === 'HEAD') {
    res.end()
    return
  }

  // vs nginx: Range is not honoured (no Accept-Ranges advertised, the full body is sent).
  // Ignoring Range is allowed, and an SPA bundle has nothing a client seeks into -- the media
  // BlueMap serves comes from the hosting pod, not from here.
  const stream = createReadStream(path)
  stream.on('error', (error) => {
    // The file vanished between stat and open -- only reachable if the image is being written
    // to at runtime, which readOnlyRootFilesystem forbids. Destroy rather than write a body:
    // the header is already on the wire, so the response cannot be turned into a 500.
    console.error(`[apus-ui] read failed for ${path}:`, error)
    res.destroy(error)
  })
  stream.pipe(res)
}

/**
 * True when a miss should render the SPA instead of 404ing.
 *
 * vs nginx: `try_files $uri $uri/ /index.html` fell back for *every* miss outside `/_nuxt/`,
 * so a typo'd image URL answered 200 with HTML in an <img>. Here a request that names a file
 * type stays a 404 and only navigations fall through -- which is all the SPA router needs,
 * and it keeps broken asset links visible instead of silently "working".
 */
function wantsSpaFallback(req, pathname) {
  if (pathname.startsWith(IMMUTABLE_PREFIX)) return false
  if (extname(pathname) === '') return true
  return (req.headers.accept ?? '').includes('text/html')
}

async function handle(req, res) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    // Nothing here mutates anything; anything but a read is a client bug or a probe.
    send(req, res, 405, { Allow: 'GET, HEAD', 'Content-Length': 0, 'Cache-Control': 'no-store' })
    return
  }

  // The base is irrelevant -- only the path is used -- but `new URL` needs one to parse an
  // origin-form request target, and it drops the query string and normalises the path for us.
  const url = new URL(req.url, 'http://localhost')

  let pathname
  try {
    pathname = decodeURIComponent(url.pathname)
  } catch {
    // Malformed percent-encoding: not decodable, so not resolvable to a file.
    sendError(req, res, 400, 'Bad Request')
    return
  }

  // A NUL byte truncates the path in the syscall layer, so `/index.html\0.png` would open a
  // different file than the one that was checked. Node rejects it too; failing here keeps the
  // rejection a 400 rather than a 500.
  if (pathname.includes('\0')) {
    sendError(req, res, 400, 'Bad Request')
    return
  }

  const target = resolveInRoot(pathname)
  if (target === null) {
    sendError(req, res, 403, 'Forbidden')
    return
  }

  // A real file wins; otherwise a directory index (nginx's `$uri/`). `nuxt generate` emits one
  // per route -- /tenant/renders is the directory tenant/renders/ holding index.html -- and
  // both spellings of that URL must serve it. Unlike nginx this does not 301 the missing
  // trailing slash first: the generated HTML references its assets absolutely (/_nuxt/...),
  // so nothing resolves relative to the directory and the redirect would buy only a round
  // trip. Anything still unresolved falls through to the SPA below.
  let filePath = target
  let stats = await statFile(filePath)
  if (stats === null) {
    filePath = resolve(target, 'index.html')
    stats = await statFile(filePath)
  }

  if (stats !== null) {
    sendFile(req, res, filePath, stats, { pathname, isFallback: false })
    return
  }

  if (!wantsSpaFallback(req, pathname)) {
    sendError(req, res, 404, 'Not Found')
    return
  }

  const indexPath = resolve(root, 'index.html')
  const index = await statFile(indexPath)
  if (index === null) {
    // The document root has no index.html: the image was built wrong. Say so plainly instead
    // of 404ing, which would look like a routing problem in the SPA.
    console.error(`[apus-ui] no index.html below ${root} -- is the build output missing?`)
    sendError(req, res, 500, 'Internal Server Error')
    return
  }

  // The router decides from here whether the route exists, and renders its own 404 page if
  // it does not -- which is why this is a 200 and not a 404 (see sendFile).
  sendFile(req, res, indexPath, index, { pathname, isFallback: true })
}

export function createStaticServer() {
  return createServer((req, res) => {
    res.on('finish', () => {
      if (accessLog || res.statusCode >= 500) {
        console.log(`[apus-ui] ${req.method} ${req.url} ${res.statusCode}`)
      }
    })

    handle(req, res).catch((error) => {
      console.error(`[apus-ui] ${req.method} ${req.url} failed:`, error)
      if (!res.headersSent) sendError(req, res, 500, 'Internal Server Error')
      else res.destroy(error)
    })
  })
}

// Only when run directly (`node serve.mjs`), not when the tests import createStaticServer.
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const server = createStaticServer()

  // Above the 60s an AWS/GCP-style LB and ingress-nginx keep idle upstream connections open,
  // so the pod is never the side that closes a connection the proxy is about to reuse --
  // that race shows up as sporadic 502s.
  server.keepAliveTimeout = 65_000
  server.headersTimeout = 66_000

  server.listen(port, host, () => {
    // The bound port, not the requested one: with PORT=0 (what the tests use to avoid
    // fighting over a fixed port) they differ, and this line is how the caller learns it.
    console.log(`[apus-ui] serving ${root} on http://${host}:${server.address().port}`)
  })

  // Node is PID 1 in the distroless image: without these handlers SIGTERM is not the default
  // "terminate" (PID 1 has no default action), so the pod would sit out its full termination
  // grace period on every rollout.
  for (const signal of ['SIGTERM', 'SIGINT']) {
    process.on(signal, () => {
      console.log(`[apus-ui] ${signal} received, shutting down`)
      server.close(() => process.exit(0))
      server.closeIdleConnections()
    })
  }
}
