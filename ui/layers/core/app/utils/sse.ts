/**
 * Minimal server-sent-events framer over a raw `ReadableStreamDefaultReader<Uint8Array>`.
 *
 * Why not `EventSource`: `EventSource` cannot set an `Authorization` header, and the api
 * module's SSE endpoints (`GET /api/renders/{id}/events`, `.../logs`, see
 * api/src/main/java/net/onelitefeather/apus/api/events/RenderStreamController.java) are behind
 * the same bearer-JWT auth as everything else -- there is no query-parameter token reader
 * configured in api/src/main/resources/application.yml. So the client opens the stream with
 * `fetch` (which can set the header) and frames it itself; see `streamSse` in apiClient.ts for
 * the fetch + auth-header side of this.
 *
 * Only handles the subset of the SSE wire format Micronaut's `Event.of(value)` actually emits:
 * one or more `data:` lines per event, separated by a blank line. `event:`/`id:`/`retry:` lines
 * and comments (`:`-prefixed) are intentionally not parsed -- the api module does not send them
 * for these two endpoints, and speculatively supporting them would be untested code.
 */
export async function* parseSseStream(
  reader: ReadableStreamDefaultReader<Uint8Array>
): AsyncGenerator<string> {
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let separatorIndex = buffer.indexOf('\n\n')
    while (separatorIndex !== -1) {
      const rawEvent = buffer.slice(0, separatorIndex)
      buffer = buffer.slice(separatorIndex + 2)
      const data = extractData(rawEvent)
      if (data !== null) {
        yield data
      }
      separatorIndex = buffer.indexOf('\n\n')
    }
  }

  // A final event without a trailing blank line (stream closed right after it) is still a
  // complete event -- flush it rather than silently dropping the last message.
  const trailing = extractData(buffer)
  if (trailing !== null) {
    yield trailing
  }
}

function extractData(rawEvent: string): string | null {
  const dataLines = rawEvent
    .split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice('data:'.length).replace(/^ /, ''))

  if (dataLines.length === 0) {
    return null
  }
  // Per the SSE spec, multiple `data:` lines in one event join with `\n`.
  return dataLines.join('\n')
}
