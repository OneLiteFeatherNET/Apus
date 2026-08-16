/**
 * Decodes a JWT's payload (second segment) into its claims, without verifying the signature.
 *
 * This is deliberately *not* validation. The api module is the only party that verifies a
 * token's signature and issuer (see api/src/main/resources/application.yml -- Micronaut
 * Security validates against `APUS_JWT_JWKS_URI`/`APUS_JWT_ISSUER` on every request). This
 * helper only reads claims already sitting in a token the broker issued to us, purely so the UI
 * can decide what to show (see app/utils/role.ts). Never use its output for anything that needs
 * to be trustworthy -- the API re-checks everything regardless.
 *
 * Works both in the browser (atob) and under Node/Vitest (Buffer fallback), and decodes the
 * base64url payload as UTF-8 so non-ASCII claim values (e.g. a tenant display name) survive.
 */
export function decodeJwtPayload(token: string): Record<string, unknown> {
  const segments = token.split('.')
  const payloadSegment = segments[1]
  if (segments.length < 2 || !payloadSegment) {
    throw new Error('not a JWT: expected at least a header and payload segment')
  }

  const base64 = base64UrlToBase64(payloadSegment)
  const binary = typeof atob === 'function' ? atob(base64) : bufferAtob(base64)
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0))
  const json = new TextDecoder('utf-8').decode(bytes)

  const parsed: unknown = JSON.parse(json)
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('not a JWT: payload is not a JSON object')
  }
  return parsed as Record<string, unknown>
}

function base64UrlToBase64(value: string): string {
  const normalized = value.replaceAll('-', '+').replaceAll('_', '/')
  const paddingNeeded = (4 - (normalized.length % 4)) % 4
  return normalized + '='.repeat(paddingNeeded)
}

// Node-only fallback (no `atob` global): used under Vitest, never in a browser build.
function bufferAtob(base64: string): string {
  return Buffer.from(base64, 'base64').toString('binary')
}
