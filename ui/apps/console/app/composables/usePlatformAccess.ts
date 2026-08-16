/**
 * Whether to render the console's contents at all.
 *
 * Convenience, not enforcement -- the same rule as everywhere else in this workspace (see
 * layers/core, app/utils/role.ts). The api module re-checks `platform-admin` on every request and
 * answers 403 regardless of what this returns. What it buys is a deliberate explanation instead
 * of a dashboard that fails one call at a time in front of someone who was never going to be
 * allowed in.
 */
export function usePlatformAccess() {
  const { principal } = useAuth()
  return computed(() => isPlatformAdmin(principal.value))
}
