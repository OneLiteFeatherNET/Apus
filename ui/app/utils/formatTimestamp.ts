/**
 * Formats an ISO-8601 timestamp (or `null`) for display -- shared across the tenant dashboard's
 * tables so "when did this last happen" reads consistently. Pure presentation (locale-dependent
 * `Intl` formatting), not unit-tested per this task's brief ("Tests: für das, was Logik trägt...
 * nicht für reine Darstellung").
 */
export function formatTimestamp(value: string | null): string {
  if (!value) return 'never'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}
