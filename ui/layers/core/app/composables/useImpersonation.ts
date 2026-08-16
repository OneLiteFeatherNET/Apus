/**
 * The impersonation session, if one is running.
 *
 * Held in `sessionStorage` rather than memory so a page reload does not silently drop it — a
 * half-exited impersonation, where the banner is gone but the reader still believes they are
 * looking at a tenant, is worse than either state on its own. Session storage also means it ends
 * with the tab, which is the right lifetime for something you enter deliberately.
 *
 * Nothing here grants anything. The value becomes two request headers; the API's
 * `ImpersonationPolicy` decides, strips the platform role, and refuses a tenant the caller may
 * not act in. Writing a tenant name here that you have no business in produces a refusal, not
 * access.
 */

export interface ImpersonationSession {
  tenant: string
  /** `null` means acting as the tenant itself — the "org admin" view — rather than as a person. */
  user: string | null
}

const STORAGE_KEY = 'apus.impersonation'

/** Read straight from storage rather than a module-level ref, so every caller agrees. */
function read(): ImpersonationSession | null {
  if (typeof sessionStorage === 'undefined') return null
  const raw = sessionStorage.getItem(STORAGE_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as ImpersonationSession
    // A stored value with no tenant would send a header the API rejects on every request, and
    // the reader would have no idea why. Treat it as no session at all.
    return parsed && typeof parsed.tenant === 'string' && parsed.tenant.length > 0 ? parsed : null
  } catch {
    return null
  }
}

export function useImpersonation() {
  const actingAs = useState<ImpersonationSession | null>('apus-impersonation', () => read())

  function startImpersonating(tenant: string, user: string | null): void {
    const session: ImpersonationSession = { tenant, user }
    actingAs.value = session
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session))
    }
    // A full reload rather than a route change: every page holding already-fetched data would
    // otherwise keep showing the platform admin's own view under a banner claiming otherwise.
    if (typeof window !== 'undefined') {
      window.location.reload()
    }
  }

  function stopImpersonating(): void {
    actingAs.value = null
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.removeItem(STORAGE_KEY)
    }
    if (typeof window !== 'undefined') {
      window.location.reload()
    }
  }

  return { actingAs, startImpersonating, stopImpersonating, readSession: read }
}
