/**
 * UI-side role helpers, mirroring the server-side model in
 * `api/src/main/java/net/onelitefeather/apus/api/security/{Role,ApusPrincipal}.java` and
 * `api/src/main/java/net/onelitefeather/apus/api/rest/support/TenantAccess.java`.
 *
 * IMPORTANT -- read before using any of this: these helpers exist purely so the UI can decide
 * what to *show* (design spec §11.2: "Two levels, separated via the role in the token"). They
 * enforce nothing. Every one of these checks is re-done, authoritatively, by the api module on
 * every request (design spec §10.3: "the backend is the enforcement point"). Hiding a button
 * here only hides something the API would have refused anyway -- it must never be the *only*
 * thing standing between a user and an action. Do not add logic here that a reviewer could
 * mistake for an access-control boundary.
 */

/** The four roles from design spec §10.3. Order matches the table there. */
export const ROLES = ['platform-admin', 'tenant-owner', 'tenant-operator', 'tenant-viewer'] as const

export type Role = (typeof ROLES)[number]

const ROLE_SET: ReadonlySet<string> = new Set(ROLES)

/** Mirrors `Role.fromClaim`'s exact-match, case-insensitive, no-separator-tolerance behaviour. */
export function isRole(value: string): value is Role {
  return ROLE_SET.has(value)
}

/**
 * Claim name Micronaut Security's JWT roles resolution reads by default -- unconfigured in
 * api/src/main/resources/application.yml (no `micronaut.security.token.roles-claim-name`
 * override), so the framework default `"roles"` claim applies. This is what
 * `authentication.getRoles()` resolves in `PrincipalResolver`; if that ever changes there, it
 * must change here too.
 */
const ROLES_CLAIM = 'roles'

/**
 * Matches `PrincipalResolver.TENANT_CLAIM` exactly (api module, `support/PrincipalResolver.java`)
 * -- the organisation claim design spec §10.3 says determines the tenant.
 */
const TENANT_CLAIM = 'organization'

/** Who the UI is rendering for, decoded from the access token's claims. See the module Javadoc. */
export interface ApusUiPrincipal {
  /** The token's `sub` claim, or `null` if absent -- for display only. */
  readonly subject: string | null
  /** The `organization` claim, or `null` if absent/blank -- mirrors `ApusPrincipal.tenant()`. */
  readonly tenant: string | null
  /** Recognised roles only; unrecognised claim entries are dropped, never rejected. */
  readonly roles: readonly Role[]
}

/**
 * Builds an {@link ApusUiPrincipal} from a decoded token payload (see app/utils/jwt.ts).
 * Unrecognised role claim entries are silently dropped -- mirroring `Role.fromClaim` returning
 * `Optional.empty()` for a role the broker knows about but Apus does not (yet), rather than
 * failing the whole token.
 */
export function parsePrincipal(claims: Record<string, unknown>): ApusUiPrincipal {
  const subject = typeof claims.sub === 'string' ? claims.sub : null

  const tenantClaim = claims[TENANT_CLAIM]
  const tenant = typeof tenantClaim === 'string' && tenantClaim.trim().length > 0 ? tenantClaim : null

  const rawRoles = claims[ROLES_CLAIM]
  const roles: Role[] = Array.isArray(rawRoles)
    ? rawRoles
      .filter((entry): entry is string => typeof entry === 'string')
      .map((entry) => entry.trim().toLowerCase())
      .filter(isRole)
    : []

  return { subject, tenant, roles }
}

/** Whether the platform-level dashboard should be offered at all (design spec §11.2). */
export function isPlatformAdmin(principal: ApusUiPrincipal | null | undefined): boolean {
  return principal?.roles.includes('platform-admin') ?? false
}

/**
 * Whether the tenant dashboard should offer write actions (create/trigger) --
 * mirrors `ApusPrincipal.canWrite()`. Deliberately excludes `platform-admin`, same as the
 * server: that role's write access is to platform resources, not a tenant's own.
 */
export function canWriteTenant(principal: ApusUiPrincipal | null | undefined): boolean {
  if (!principal) return false
  return principal.roles.includes('tenant-owner') || principal.roles.includes('tenant-operator')
}

/**
 * Whether the tenant dashboard should be shown at all -- mirrors `TenantAccess.canRead()`:
 * any of the three tenant-level roles.
 */
export function canReadTenant(principal: ApusUiPrincipal | null | undefined): boolean {
  if (!principal) return false
  return (
    principal.roles.includes('tenant-owner')
    || principal.roles.includes('tenant-operator')
    || principal.roles.includes('tenant-viewer')
  )
}
