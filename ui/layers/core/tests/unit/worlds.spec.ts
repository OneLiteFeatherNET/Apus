import { describe, expect, it } from 'vitest'
import { buildWorlds } from '~/utils/worlds'
import type {
  BlueMapHostingResponse,
  BlueMapMapResponse,
  BlueMapRenderResponse,
  WorldSourceResponse
} from '~/utils/apiTypes'

function aMap(
  name: string,
  sourceRef: string | null = 'src',
  phase: string | null = null
): BlueMapMapResponse {
  return {
    name,
    source: { sourceRef, world: 'world', dimension: 'overworld' },
    trigger: { onNewBundle: true, schedule: null, concurrencyPolicy: 'Forbid' },
    bluemap: { version: null, minecraftVersion: null },
    shards: 1,
    historyLimit: 3,
    purgeOnDelete: false,
    bucket: { name: `${name}-bucket`, endpoint: 'https://s3.example.net' },
    latestRender: { name: phase ? `${name}-r1` : null, phase },
    conditions: []
  }
}

function aSource(name: string, bundleVersion: string | null = 'v3'): WorldSourceResponse {
  return {
    name,
    type: 's3',
    poll: '5m',
    worlds: [{ name: 'world', layout: 'vanilla', minecraftVersion: '1.21' }],
    keepVersions: 3,
    lastSeenVersion: bundleVersion,
    latestBundle: bundleVersion ? { path: `bundles/${bundleVersion}`, version: bundleVersion } : null,
    lastPollTime: '2026-08-15T10:00:00Z',
    conditions: []
  }
}

function aRender(
  name: string,
  mapRef: string,
  phase: string,
  percent = 0,
  start: string | null = '2026-08-15T10:00:00Z'
): BlueMapRenderResponse {
  return {
    name,
    mapRef,
    force: false,
    phase,
    progress: { percent, currentMap: mapRef, etaSeconds: 0, degraded: false },
    startTime: start,
    completionTime: null,
    conditions: []
  }
}

function aHosting(name: string, maps: string[], url: string | null): BlueMapHostingResponse {
  return {
    name,
    maps,
    hostname: 'maps.example.net',
    url,
    ready: url !== null,
    replicas: 1,
    conditions: []
  }
}

describe('buildWorlds', () => {
  it('joins a map to its source, its renders and its hosting', () => {
    const worlds = buildWorlds(
      [aMap('atlas', 'survival', 'Succeeded')],
      [aSource('survival')],
      [aRender('atlas-r1', 'atlas', 'Succeeded', 100)],
      [aHosting('public', ['atlas'], 'https://maps.example.net/atlas')]
    )

    expect(worlds).toHaveLength(1)
    expect(worlds[0]!.name).toBe('atlas')
    expect(worlds[0]!.source?.name).toBe('survival')
    expect(worlds[0]!.renders.map(render => render.name)).toEqual(['atlas-r1'])
    expect(worlds[0]!.url).toBe('https://maps.example.net/atlas')
  })

  it('survives a sourceRef that names nothing the caller can see', () => {
    // A 404 from this API is deliberately indistinguishable from "not in your tenant", so a
    // dangling reference is an ordinary state to render, not a bug to throw on.
    const worlds = buildWorlds([aMap('atlas', 'gone')], [], [], [])

    expect(worlds[0]!.source).toBeNull()
    expect(worlds[0]!.stages.find(stage => stage.key === 'source')!.state).toBe('failed')
  })

  it('distinguishes a map with no source at all from one whose source vanished', () => {
    const worlds = buildWorlds([aMap('atlas', null)], [], [], [])

    expect(worlds[0]!.stages.find(stage => stage.key === 'source')!.state).toBe('pending')
    expect(worlds[0]!.stages.find(stage => stage.key === 'bundle')!.state).toBe('skipped')
  })

  it('orders renders newest first regardless of the order the API returned them', () => {
    const worlds = buildWorlds(
      [aMap('atlas')],
      [aSource('src')],
      [
        aRender('old', 'atlas', 'Succeeded', 100, '2026-08-01T00:00:00Z'),
        aRender('new', 'atlas', 'Succeeded', 100, '2026-08-14T00:00:00Z')
      ],
      []
    )

    expect(worlds[0]!.renders.map(render => render.name)).toEqual(['new', 'old'])
  })

  it('does not drop a render whose startTime is missing or unparseable', () => {
    // A render that has not started yet has no startTime. Sorting must not lose it.
    const worlds = buildWorlds(
      [aMap('atlas')],
      [],
      [aRender('queued', 'atlas', 'Pending', 0, null), aRender('done', 'atlas', 'Succeeded', 100)],
      []
    )

    expect(worlds[0]!.renders).toHaveLength(2)
    expect(worlds[0]!.renders[0]!.name).toBe('done')
  })

  it('ignores renders belonging to no map in this tenant', () => {
    const worlds = buildWorlds([aMap('atlas')], [], [aRender('x', 'other', 'Succeeded', 100)], [])

    expect(worlds[0]!.renders).toHaveLength(0)
  })

  it('reports no URL when a hosting lists the map but is not serving yet', () => {
    const worlds = buildWorlds([aMap('atlas')], [], [], [aHosting('public', ['atlas'], null)])

    expect(worlds[0]!.url).toBeNull()
    expect(worlds[0]!.hosting?.name).toBe('public')
    expect(worlds[0]!.stages.find(stage => stage.key === 'hosting')!.state).toBe('pending')
  })

  it('marks the render stage active with the live percentage', () => {
    const worlds = buildWorlds(
      [aMap('atlas', 'src', 'Running')],
      [aSource('src')],
      [aRender('atlas-r1', 'atlas', 'Running', 37)],
      []
    )

    const render = worlds[0]!.stages.find(stage => stage.key === 'render')!
    expect(render.state).toBe('active')
    expect(render.percent).toBe(37)
  })

  it('marks the render stage failed rather than falling back to waiting', () => {
    const worlds = buildWorlds(
      [aMap('atlas', 'src', 'Failed')],
      [aSource('src')],
      [aRender('atlas-r1', 'atlas', 'Failed', 12)],
      []
    )

    expect(worlds[0]!.stages.find(stage => stage.key === 'render')!.state).toBe('failed')
  })

  it('shows a source that has produced no bundle yet as connected but waiting', () => {
    const worlds = buildWorlds([aMap('atlas', 'src')], [aSource('src', null)], [], [])

    expect(worlds[0]!.stages.find(stage => stage.key === 'source')!.state).toBe('done')
    expect(worlds[0]!.stages.find(stage => stage.key === 'bundle')!.state).toBe('pending')
  })

  it('returns an empty list for an empty tenant', () => {
    expect(buildWorlds([], [], [], [])).toEqual([])
  })

  it('orders worlds by name so the list does not reshuffle between polls', () => {
    const worlds = buildWorlds([aMap('zulu'), aMap('alpha')], [], [], [])

    expect(worlds.map(world => world.name)).toEqual(['alpha', 'zulu'])
  })
})
