/**
 * A "world" is what a person means when they talk about this product. The API has no such
 * resource: it is a `BlueMapMap` joined with the source feeding it, the renders that produced it
 * and the hosting that serves it. Every one of those joins is already carried in the response
 * bodies -- `map.source.sourceRef` names a source, `render.mapRef` names a map, `hosting.maps[]`
 * lists map names -- so the entry point that finally answers "where is my world stuck?" needs no
 * endpoint that does not exist.
 *
 * Deliberately pure and Nuxt-free: this is the one piece of real logic behind the application's
 * front page, and it unit-tests without a browser (see ui/README.md, "Why plain Vitest").
 */
import type {
  BlueMapHostingResponse,
  BlueMapMapResponse,
  BlueMapRenderResponse,
  WorldSourceResponse
} from '#core/utils/apiTypes'
import { deriveStages, type PipelineStage } from '#core/utils/pipeline'

export interface World {
  /** The map's name. This is the world's identity everywhere in the application. */
  name: string
  map: BlueMapMapResponse
  /** `null` when the map names no source, or names one the caller cannot see. */
  source: WorldSourceResponse | null
  /** Newest first. */
  renders: BlueMapRenderResponse[]
  hosting: BlueMapHostingResponse | null
  /** The public URL, or `null` when nothing is serving this world yet. */
  url: string | null
  stages: PipelineStage[]
}

/** Sortable start time. A render that has not started yet has none, and must not be dropped. */
function startedAt(render: BlueMapRenderResponse): number {
  const parsed = render.startTime ? Date.parse(render.startTime) : Number.NaN
  return Number.isNaN(parsed) ? 0 : parsed
}

export function buildWorlds(
  maps: BlueMapMapResponse[],
  sources: WorldSourceResponse[],
  renders: BlueMapRenderResponse[],
  hostings: BlueMapHostingResponse[]
): World[] {
  const sourcesByName = new Map(sources.map(source => [source.name, source]))

  return maps
    .map((map): World => {
      const sourceRef = map.source.sourceRef
      const source = sourceRef ? sourcesByName.get(sourceRef) ?? null : null
      const hosting = hostings.find(entry => entry.maps.includes(map.name)) ?? null
      // The API promises no order. A list that reshuffles between polls is worse than one that
      // is merely unsorted, so both this and the world order below are pinned here.
      const own = renders
        .filter(render => render.mapRef === map.name)
        .slice()
        .sort((a, b) => startedAt(b) - startedAt(a))

      return {
        name: map.name,
        map,
        source,
        renders: own,
        hosting,
        url: hosting?.ready ? hosting.url : null,
        stages: deriveStages({
          hasSource: source !== null,
          sourceRefNamed: sourceRef !== null && sourceRef !== '',
          hasBundle: source?.latestBundle != null,
          // The map's own `latestRender.phase` is the authority; the render list is the fallback
          // for a map whose status has not caught up yet.
          latestRenderPhase: map.latestRender.phase ?? own[0]?.phase ?? null,
          latestRenderPercent: own[0]?.progress.percent ?? 0,
          hasHostingEntry: hosting !== null,
          hostingReady: hosting?.ready === true
        })
      }
    })
    .sort((a, b) => a.name.localeCompare(b.name))
}
