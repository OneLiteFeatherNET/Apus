import { ApusApiError } from '#core/utils/apiErrors'
import { buildWorlds, type World } from '#core/utils/worlds'

/**
 * The four list calls behind the world-centric entry point, joined into one shape.
 *
 * `Promise.allSettled`, not `Promise.all`: the four endpoints fail independently, and one of them
 * erroring must degrade its own part of the picture rather than blank the page. A tenant whose
 * hosting list is unavailable should still see which worlds exist and what state their renders
 * are in -- the pipeline rail simply shows one stage it could not determine.
 *
 * `partial` names which lists are missing, so a page can say so rather than quietly presenting an
 * incomplete world as a complete one.
 */
export interface WorldsState {
  worlds: Ref<World[]>
  loading: Ref<boolean>
  /** Set only when *every* call failed -- i.e. there is nothing to show at all. */
  error: Ref<ApusApiError | null>
  /** Names of the lists that could not be loaded. Empty when everything arrived. */
  partial: Ref<string[]>
  refresh: () => Promise<void>
}

export function useWorlds(): WorldsState {
  const api = useApiClient()

  const worlds = ref<World[]>([])
  const loading = ref(true)
  const error = ref<ApusApiError | null>(null)
  const partial = ref<string[]>([])

  async function refresh(): Promise<void> {
    loading.value = true
    error.value = null

    const [maps, sources, renders, hostings] = await Promise.allSettled([
      api.listMaps(),
      api.listSources(),
      api.listRenders(),
      api.listHostings()
    ])

    const missing: string[] = []
    const failures: ApusApiError[] = []

    function valueOf<T>(result: PromiseSettledResult<T[]>, label: string): T[] {
      if (result.status === 'fulfilled') return result.value
      missing.push(label)
      if (result.reason instanceof ApusApiError) failures.push(result.reason)
      return []
    }

    const mapList = valueOf(maps, 'worlds')
    const sourceList = valueOf(sources, 'sources')
    const renderList = valueOf(renders, 'renders')
    const hostingList = valueOf(hostings, 'hosting')

    worlds.value = buildWorlds(mapList, sourceList, renderList, hostingList)
    partial.value = missing
    // Only a total failure is the page's error. Anything less is a gap the pipeline rail can
    // show honestly, and blanking the page over it would hide the parts that did load.
    error.value = missing.length === 4
      ? failures[0] ?? new ApusApiError({ status: 0, message: 'Could not load anything.' })
      : null
    loading.value = false
  }

  onMounted(refresh)

  return { worlds, loading, error, partial, refresh }
}
