<script setup lang="ts">
/**
 * A permanent left rail rather than the tenant app's top bar.
 *
 * Administrators move between a small set of known places repeatedly, so the navigation earns its
 * permanent space; and a top bar spends horizontal room that this application's dense tables want
 * more than the navigation does.
 */
const links = [
  { to: '/', label: 'Overview' },
  { to: '/tenants', label: 'Tenants' },
  { to: '/renders', label: 'Renders' }
]

const route = useRoute()
function isCurrent(to: string): boolean {
  return to === '/' ? route.path === '/' : route.path.startsWith(to)
}
</script>

<template>
  <div class="border-default bg-muted flex w-full shrink-0 flex-col gap-6 border-b p-4 sm:w-56 sm:border-r sm:border-b-0">
    <NuxtLink to="/" class="flex items-center gap-2">
      <span class="apus-mark" aria-hidden="true">
        <span class="apus-mark-cell apus-mark-cell--on" />
        <span class="apus-mark-cell apus-mark-cell--on" />
        <span class="apus-mark-cell apus-mark-cell--on" />
        <span class="apus-mark-cell" />
      </span>
      <span class="flex flex-col leading-tight">
        <span class="text-highlighted text-sm font-semibold tracking-tight">Apus</span>
        <!-- Named permanently. An admin has both applications open; this is what stops the two
             from being mistaken for one another when neither URL is visible. -->
        <span class="apus-eyebrow text-primary">Platform</span>
      </span>
    </NuxtLink>

    <nav aria-label="Console" class="flex flex-row gap-1 sm:flex-col">
      <ULink
        v-for="link in links"
        :key="link.to"
        :to="link.to"
        class="border-l-2 px-3 py-1.5 text-sm transition-colors"
        :class="isCurrent(link.to)
          ? 'border-primary text-highlighted bg-elevated'
          : 'text-muted hover:text-highlighted border-transparent'"
        :aria-current="isCurrent(link.to) ? 'page' : undefined"
      >
        {{ link.label }}
      </ULink>
    </nav>

    <a href="/" class="text-dimmed hover:text-muted mt-auto hidden text-xs sm:block">
      ← Tenant app
    </a>
  </div>
</template>

<style scoped>
.apus-mark {
  display: grid;
  grid-template-columns: repeat(2, var(--apus-cell));
  gap: var(--apus-cell-gap);
}

.apus-mark-cell {
  width: var(--apus-cell);
  height: var(--apus-cell);
  border: 1px solid var(--ui-color-primary-500);
}

.apus-mark-cell--on {
  background-color: var(--ui-color-primary-500);
}
</style>
