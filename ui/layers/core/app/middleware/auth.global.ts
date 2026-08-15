/**
 * Requires a signed-in user for every route except the two OIDC redirect targets. This is a UX
 * guard, not an access-control boundary -- it decides whether to *show* a login redirect, never
 * whether a request is allowed; the api module enforces that independently on every call (see
 * app/utils/role.ts's module Javadoc for the same point applied to roles).
 *
 * On an already-restored session (see useAuth().init(), called from the oidc plugin before any
 * route renders) this is a no-op. On a fresh load with no in-memory session -- e.g. after a hard
 * reload, since tokens are deliberately not persisted to Web Storage, see useAuth.ts -- it first
 * tries a silent renew against the broker's own session before falling back to an interactive
 * redirect, so a reload does not force a full login round-trip whenever the broker session is
 * still valid.
 */
export default defineNuxtRouteMiddleware(async (to) => {
  if (to.path.startsWith('/auth/')) {
    return
  }

  const { isAuthenticated, trySilentSignin, login } = useAuth()
  if (isAuthenticated.value) {
    return
  }

  const restored = await trySilentSignin()
  if (restored) {
    return
  }

  await login(to.fullPath)
  return abortNavigation()
})
