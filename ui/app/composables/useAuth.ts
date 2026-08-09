import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { decodeJwtPayload } from '~/utils/jwt'
import { parsePrincipal, type ApusUiPrincipal } from '~/utils/role'

/**
 * Client-only OIDC session (Authorization Code + PKCE, public client -- design spec §10.3,
 * §11.2). One `UserManager` per page load, shared by every `useAuth()` caller; the reactive
 * state around it lives at module scope for the same reason (a composable that re-created its
 * state per call would let two components disagree about who is logged in).
 *
 * ## Token storage (binding requirement, see the design spec and ui/README.md)
 *
 * `userStore` below is `InMemoryWebStorage`, not the library's own default
 * (`window.sessionStorage`) and not `localStorage`. A plain JS object is not reachable by
 * `localStorage.getItem(...)`-style scraping and does not survive a reload -- so an XSS bug
 * elsewhere on the page can only exfiltrate a token while it is actively being used, not read
 * it out of storage at leisure or find it still sitting there after the tab was closed and
 * reopened. The cost: a hard reload loses the in-memory token. `automaticSilentRenew` plus the
 * `signinSilent()` call in `init()` below paper over that by re-authenticating against the
 * broker's own (httpOnly, broker-controlled, not readable by this page's JS) session cookie via
 * a hidden iframe -- standard OIDC "silent renew". If the broker session has also expired, that
 * falls through to an interactive `login()` redirect, same as a first visit.
 */
let manager: UserManager | undefined

function getUserManager(): UserManager {
  if (manager) return manager

  const config = useRuntimeConfig()
  const origin = window.location.origin
  manager = new UserManager({
    authority: config.public.oidcIssuer,
    client_id: config.public.oidcClientId,
    redirect_uri: `${origin}/auth/callback`,
    silent_redirect_uri: `${origin}/auth/silent-renew`,
    post_logout_redirect_uri: origin,
    response_type: 'code',
    scope: 'openid profile email',
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() })
  })
  return manager
}

const currentUser = ref<User | null>(null)
const initialized = ref(false)

const principal = computed<ApusUiPrincipal | null>(() => {
  const token = currentUser.value?.access_token
  if (!token) return null
  try {
    return parsePrincipal(decodeJwtPayload(token))
  } catch {
    // A token the broker issued that this helper cannot parse is a display problem, not a
    // reason to crash the app -- the api module validates the real token independently.
    return null
  }
})

export function useAuth() {
  const oidc = getUserManager()

  /** Restores a session already known to `oidc-client-ts` (in-memory only, see above) and
   * wires up event listeners. Call once, e.g. from app/plugins/oidc.client.ts. */
  async function init(): Promise<void> {
    if (initialized.value) return
    initialized.value = true

    oidc.events.addUserLoaded((user) => {
      currentUser.value = user
    })
    oidc.events.addUserUnloaded(() => {
      currentUser.value = null
    })
    oidc.events.addSilentRenewError(() => {
      currentUser.value = null
    })

    currentUser.value = await oidc.getUser()
  }

  /** Redirects to the broker to sign in. `returnTo` is restored from `state` after the callback. */
  async function login(returnTo: string = '/'): Promise<void> {
    await oidc.signinRedirect({ state: { returnTo } })
  }

  async function logout(): Promise<void> {
    await oidc.removeUser()
    currentUser.value = null
  }

  /** Attempts to restore the session silently (hidden iframe against the broker's own session).
   * Returns `false` rather than throwing when the broker has no active session either. */
  async function trySilentSignin(): Promise<boolean> {
    try {
      const user = await oidc.signinSilent()
      currentUser.value = user
      return user !== null
    } catch {
      return false
    }
  }

  async function getAccessToken(): Promise<string | null> {
    return currentUser.value?.access_token ?? null
  }

  return {
    oidc,
    user: currentUser,
    principal,
    isAuthenticated: computed(() => currentUser.value !== null),
    init,
    login,
    logout,
    trySilentSignin,
    getAccessToken
  }
}
