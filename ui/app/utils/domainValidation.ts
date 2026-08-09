/**
 * Validation for `Tenant.spec.hosting.allowedDomains` entries (design spec §11.2: "Domain-
 * Freigaben"; operator/src/main/java/net/onelitefeather/apus/operator/api/TenantSpec.java's
 * `Hosting.allowedDomains`). Pure TypeScript, no Nuxt/Vue dependency -- unit-tested in
 * tests/unit/platform/domainValidation.spec.ts.
 *
 * IMPORTANT -- read before relaxing any rule here: this list is the only thing that stops one
 * tenant's `BlueMapHosting` from claiming another tenant's hostname -- exactly the hole Phase 3
 * closed (`BlueMapHostingReconciler`, see `Hosting`'s own Javadoc: "An empty allowedDomains is
 * deliberately treated as 'no hosting permitted yet', not 'anything goes'"). Rejecting a bare
 * `*` is the single most important rule in this file, not a nice-to-have -- it would grant a
 * tenant every hostname on the platform.
 */

export interface DomainValidationResult {
  readonly valid: boolean
  readonly error: string | null
}

const VALID: DomainValidationResult = { valid: true, error: null }

function invalid(error: string): DomainValidationResult {
  return { valid: false, error }
}

/** One DNS label: letters/digits, hyphens allowed except leading/trailing, max 63 characters. */
const LABEL_PATTERN = /^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$/

/**
 * Validates a single `allowedDomains` entry. Accepts a plain hostname (`maps.example.net`) or a
 * single leading wildcard label (`*.friends.example.net`, matching what `TenantSpec.Hosting`'s
 * Javadoc documents as supported) -- never a bare `*`, and never a wildcard anywhere but the
 * leading label.
 */
export function validateAllowedDomain(input: string): DomainValidationResult {
  const value = input.trim()

  if (value.length === 0) {
    return invalid('Domain must not be empty.')
  }
  if (value === '*') {
    return invalid(
      'A bare "*" would let this tenant claim every hostname on the platform -- use a specific '
      + 'domain, or a single leading wildcard label like "*.example.net", instead.'
    )
  }
  if (/\s/.test(value)) {
    return invalid('Domain must not contain whitespace.')
  }
  if (value.includes('://') || value.includes('/') || value.includes(':')) {
    return invalid('Enter a hostname only, without a scheme, path, or port.')
  }

  const labels = value.split('.')
  if (labels.some((label) => label.length === 0)) {
    return invalid('Domain must not contain empty labels (e.g. two dots in a row, or a leading/trailing dot).')
  }

  const [firstLabel, ...restLabels] = labels
  const isWildcard = firstLabel === '*'
  if (isWildcard && restLabels.length === 0) {
    return invalid('A wildcard needs a concrete domain to scope it, e.g. "*.example.net".')
  }

  const labelsToValidate = isWildcard ? restLabels : labels
  for (const label of labelsToValidate) {
    if (label === '*') {
      return invalid('Only a single leading "*" label is allowed, e.g. "*.example.net".')
    }
    if (label.length > 63) {
      return invalid(`"${label}" is too long -- domain labels are limited to 63 characters.`)
    }
    if (!LABEL_PATTERN.test(label)) {
      return invalid(`"${label}" is not a valid domain label.`)
    }
  }

  return VALID
}

/**
 * Validates a whole `allowedDomains` list: every entry via {@link validateAllowedDomain}, plus
 * no case-insensitive duplicates. An empty list is valid -- see this module's own Javadoc on
 * why that means "hosting not yet allowed" rather than an error state.
 */
export function validateAllowedDomains(inputs: readonly string[]): DomainValidationResult {
  const seen = new Set<string>()
  for (const raw of inputs) {
    const result = validateAllowedDomain(raw)
    if (!result.valid) return result

    const normalized = raw.trim().toLowerCase()
    if (seen.has(normalized)) {
      return invalid(`"${raw.trim()}" is listed more than once.`)
    }
    seen.add(normalized)
  }
  return VALID
}
