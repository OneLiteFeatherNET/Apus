/**
 * The five stages a world passes through, and the arithmetic behind the quantised meters.
 *
 * Why quantised at all: world data is a grid -- regions of 32x32 chunks, chunks of 16x16 blocks
 * -- and BlueMap renders it as a tile pyramid. Progress in this product is squares completing,
 * not liquid rising, and every meter in both applications draws it that way. The cells
 * deliberately do NOT correspond to real tiles: the API reports a percentage and says nothing
 * about which tiles are done, and a grid that implied otherwise would be a lie told in pixels.
 */

export type StageState = 'done' | 'active' | 'pending' | 'failed' | 'skipped'

export interface PipelineStage {
  key: 'source' | 'bundle' | 'map' | 'render' | 'hosting'
  label: string
  state: StageState
  /** 0-100. Only meaningful when `state` is `'active'`. */
  percent: number
  /** One short sentence naming what this state means for this world. */
  detail: string
}

/**
 * How many of `cells` to fill for `percent`.
 *
 * Both ends are special-cased, and that is the whole point of the function. Plain rounding lets
 * 99.6% fill every cell -- a full meter beside a still-running render, which reads as "your map
 * is ready" and sends someone looking for a URL that does not exist yet. It equally lets 0.4%
 * show an empty meter, which reads as "stuck" when the job is in fact running. So only exactly
 * 100 fills the last cell, and anything above zero lights the first.
 */
export function cellsFilled(percent: number, cells: number): number {
  if (!Number.isFinite(cells) || cells <= 0) return 0
  if (Number.isNaN(percent) || percent <= 0) return 0
  // Ordered before the finite check so an infinite percentage still reads as complete rather
  // than as no progress at all.
  if (percent >= 100) return cells

  const rounded = Math.round((percent / 100) * cells)
  // Never round up into the final cell, and never round down out of the first. On a one-cell
  // meter these collide; the "not finished" guard wins, which is correct -- completion already
  // returned above.
  return Math.min(cells - 1, Math.max(1, rounded))
}

/**
 * The percentage as it should be *printed*, under the same rule `cellsFilled` applies to the
 * cells.
 *
 * Without this the meter contradicts itself: 99.6% leaves a cell empty and then prints "100%"
 * beside it, so the squares say "not yet" while the number says "done". Whichever the reader
 * believes, one of them lied to them. Rounding is floored below 100 and raised above 0 for the
 * same reasons spelled out on `cellsFilled`.
 */
export function displayPercent(percent: number): number {
  if (Number.isNaN(percent) || percent <= 0) return 0
  if (percent >= 100) return 100
  return Math.min(99, Math.max(1, Math.round(percent)))
}

/**
 * The five-stage status for one world, derived from what the four list endpoints return.
 *
 * Every branch answers a question someone actually asks, and `detail` is that answer in one
 * sentence. Two of them are the awkward states this system genuinely produces and that the old
 * resource-per-page UI left as silences: a map whose `sourceRef` resolves to nothing the caller
 * can see, and a map nobody has ever rendered.
 */
export function deriveStages(input: {
  hasSource: boolean
  sourceRefNamed: boolean
  hasBundle: boolean
  latestRenderPhase: string | null
  latestRenderPercent: number
  hasHostingEntry: boolean
  hostingReady: boolean
}): PipelineStage[] {
  const source: PipelineStage = input.hasSource
    ? { key: 'source', label: 'Source', state: 'done', percent: 100, detail: 'Connected and being polled for new snapshots.' }
    : input.sourceRefNamed
      ? { key: 'source', label: 'Source', state: 'failed', percent: 0, detail: 'This map names a source you cannot see. It may have been deleted.' }
      : { key: 'source', label: 'Source', state: 'pending', percent: 0, detail: 'No source is connected to this map yet.' }

  const bundle: PipelineStage = input.hasBundle
    ? { key: 'bundle', label: 'Bundle', state: 'done', percent: 100, detail: 'A snapshot of the world is in storage.' }
    : {
        key: 'bundle',
        label: 'Bundle',
        state: input.hasSource ? 'pending' : 'skipped',
        percent: 0,
        detail: input.hasSource
          ? 'Waiting for the first snapshot from the source.'
          : 'Nothing to snapshot until a source is connected.'
      }

  const map: PipelineStage = {
    key: 'map',
    label: 'Map',
    state: 'done',
    percent: 100,
    detail: 'Declared by the platform and ready to render.'
  }

  const render: PipelineStage = (() => {
    switch (input.latestRenderPhase) {
      case 'Running':
        return { key: 'render' as const, label: 'Render', state: 'active' as const, percent: input.latestRenderPercent, detail: 'Rendering tiles now.' }
      case 'Failed':
        return { key: 'render' as const, label: 'Render', state: 'failed' as const, percent: input.latestRenderPercent, detail: 'The last render failed. Its log says why.' }
      case 'Succeeded':
        return { key: 'render' as const, label: 'Render', state: 'done' as const, percent: 100, detail: 'Tiles are rendered and in storage.' }
      default:
        return { key: 'render' as const, label: 'Render', state: 'pending' as const, percent: 0, detail: 'Never rendered. Start one once a bundle exists.' }
    }
  })()

  const hosting: PipelineStage = input.hostingReady
    ? { key: 'hosting', label: 'Hosting', state: 'done', percent: 100, detail: 'Live, and open to anyone with the link.' }
    : input.hasHostingEntry
      ? { key: 'hosting', label: 'Hosting', state: 'pending', percent: 0, detail: 'A host is assigned but not serving yet.' }
      : { key: 'hosting', label: 'Hosting', state: 'pending', percent: 0, detail: 'No host serves this map yet.' }

  return [source, bundle, map, render, hosting]
}
