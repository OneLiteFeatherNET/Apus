<script setup lang="ts">
// Local section navigation for the tenant dashboard (design spec §11.2). Deliberately not part
// of the shared AppNav (app/components/layout/AppNav.vue, outside this task's file scope) --
// AppNav has no "Tenant" entry point yet; see this task's report for that gap.
const route = useRoute()

const links = [
  { to: '/tenant', label: 'Overview' },
  { to: '/tenant/sources', label: 'Sources' },
  { to: '/tenant/maps', label: 'Maps' },
  { to: '/tenant/renders', label: 'Renders' },
  { to: '/tenant/hosting', label: 'Hosting' }
]

function isActive(to: string): boolean {
  return to === '/tenant' ? route.path === '/tenant' : route.path.startsWith(to)
}
</script>

<template>
  <nav aria-label="Tenant dashboard sections" class="mb-6 flex flex-wrap gap-4 border-b border-default pb-2">
    <NuxtLink
      v-for="link in links"
      :key="link.to"
      :to="link.to"
      :aria-current="isActive(link.to) ? 'page' : undefined"
      class="text-sm font-medium"
      :class="isActive(link.to) ? 'text-primary' : 'text-muted hover:text-default'"
    >
      {{ link.label }}
    </NuxtLink>
  </nav>
</template>
