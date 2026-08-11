import { describe, expect, it } from 'vitest'
import { parseSseStream } from '../../app/utils/sse'

/** Builds a `ReadableStreamDefaultReader` that yields the given raw text chunks, in order. */
function readerFromChunks(chunks: string[]): ReadableStreamDefaultReader<Uint8Array> {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk))
      }
      controller.close()
    }
  })
  return stream.getReader()
}

async function collect(reader: ReadableStreamDefaultReader<Uint8Array>): Promise<string[]> {
  const events: string[] = []
  for await (const event of parseSseStream(reader)) {
    events.push(event)
  }
  return events
}

describe('parseSseStream', () => {
  it('yields the data payload of a single event', async () => {
    const events = await collect(readerFromChunks(['data: {"percent":50}\n\n']))

    expect(events).toEqual(['{"percent":50}'])
  })

  it('yields multiple events from one chunk', async () => {
    const events = await collect(
      readerFromChunks(['data: one\n\ndata: two\n\ndata: three\n\n'])
    )

    expect(events).toEqual(['one', 'two', 'three'])
  })

  it('reassembles an event split across multiple chunks', async () => {
    const events = await collect(
      readerFromChunks(['data: {"perc', 'ent":50}\n', '\n'])
    )

    expect(events).toEqual(['{"percent":50}'])
  })

  it('joins multiple data: lines within one event with a newline, per the SSE spec', async () => {
    const events = await collect(readerFromChunks(['data: line one\ndata: line two\n\n']))

    expect(events).toEqual(['line one\nline two'])
  })

  it('flushes a trailing event that has no final blank line', async () => {
    const events = await collect(readerFromChunks(['data: only one, no trailing blank line']))

    expect(events).toEqual(['only one, no trailing blank line'])
  })

  it('ignores an event with no data: line at all', async () => {
    const events = await collect(readerFromChunks([': this is a comment, not data\n\ndata: real\n\n']))

    expect(events).toEqual(['real'])
  })

  it('produces nothing for an empty stream', async () => {
    const events = await collect(readerFromChunks([]))

    expect(events).toEqual([])
  })
})
