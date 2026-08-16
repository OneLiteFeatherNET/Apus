<script setup lang="ts">
// Which links are shown is a UX convenience, not access control -- see layers/core,
// app/utils/role.ts's module Javadoc.
//
// Four destinations, in the order of the pipeline they belong to. Account is not among them: it
// is a reference page, reached from the account menu, and nobody opens this product to read their
// own subject claim. There is no Platform entry either -- that is a separate application, linked
// from the header.
const { principal } = useAuth()
const visible = computed(() => canReadTenant(principal.value))

const links = [
  { to: '/', label: 'Worlds' },
  { to: '/sources', label: 'Sources' },
  { to: '/renders', label: 'Renders' },
  { to: '/hosting', label: 'Hosting' }
]

const route = useRoute()
function isCurrent(to: string): boolean {
  return to === '/' ? route.path === '/' : route.path.startsWith(to)
}
</script>

<template>
  <nav v-if="visible" aria-label="Main" class="flex flex-wrap items-center gap-x-1 gap-y-0.5">
    <ULink
      v-for="link in links"
      :key="link.to"
      :to="link.to"
      class="border-b-2 px-2 py-1 text-sm transition-colors"
      :class="isCurrent(link.to)
        ? 'border-primary text-highlighted'
        : 'text-muted hover:text-highlighted border-transparent'"
      :aria-current="isCurrent(link.to) ? 'page' : undefined"
    >
      {{ link.label }}
    </ULink>
  </nav>
</template>
