/**
 * Uniform error type for every failure `ApusApiClient` can raise -- HTTP error responses,
 * network failures, and failed SSE stream opens alike, so a caller can catch one type instead
 * of juggling `fetch` rejections and HTTP status branches separately.
 *
 * `status` mirrors what the api module actually sends (see
 * api/src/main/java/net/onelitefeather/apus/api/rest/support/*ExceptionHandler.java):
 * - `400` -- {@link BadRequestExceptionHandler}, body is `{"message": "..."}}`, surfaced as-is.
 * - `401` -- Micronaut Security's own default response for a missing/invalid/expired token;
 *   the api module adds no custom body for this case.
 * - `403` -- {@link ForbiddenExceptionHandler}, no body at all.
 * - `404` -- {@link NotFoundExceptionHandler}, no body at all -- also what a resource in a
 *   *different* tenant's namespace returns (see the relevant controllers' Javadoc: this is
 *   deliberate, not a client bug).
 * - `0` -- no HTTP response was received at all (network failure, CORS, aborted request); see
 *   {@link networkError}.
 */
export class ApusApiError extends Error {
  readonly status: number
  readonly body: unknown
  /** The underlying `fetch` rejection, when `status` is `0`. Not named `cause`: that name is
   * `Error`'s own standard property (ES2022) and this is deliberately a distinct field. */
  readonly networkError: unknown

  constructor(options: { status: number; message: string; body?: unknown; cause?: unknown }) {
    super(options.message)
    this.name = 'ApusApiError'
    this.status = options.status
    this.body = options.body
    this.networkError = options.cause
  }
}

/** Default, human-readable message per status when the response carried no usable body. */
export function defaultMessageForStatus(status: number): string {
  switch (status) {
    case 400:
      return 'The request was rejected as invalid.'
    case 401:
      return 'Not authenticated.'
    case 403:
      return 'Not permitted.'
    case 404:
      return 'Not found.'
    default:
      return `Request failed with status ${status}.`
  }
}
