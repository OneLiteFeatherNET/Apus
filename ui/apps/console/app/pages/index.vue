<script setup lang="ts">
/**
 * The console's overview: how many tenants, what is rendering across the cluster right now, and
 * who is close to their storage limit.
 *
 * The last one is the reason this page exists rather than a link straight to the tenant list. A
 * tenant hitting its quota fails renders with no retry (design spec §12), so "who is about to"
 * is the single most useful thing an operator can be told on arrival.
 */
import type { ClusterRenderResponse } from '#core/utils/apiTypes'

const { tenants, loading, error, refresh } = useTenants()

const api = useApiClient()
const clusterRenders = ref<ClusterRenderResponse[]>([])
const rendersLoading = ref(true)

async function loadRenders(): Promise<void> {
  rendersLoading.value = true
  try {
    clusterRenders.value = await api.listClusterRenders()
  } catch {
    clusterRenders.value = []
  } finally {
    rendersLoading.value = false
  }
}

onMounted(loadRenders)

const running = computed(() => clusterRenders.value.filter(entry => entry.render.phase === 'Running'))
const nearQuota = computed(() => tenants.value.filter(tenant => tenant.usage.level !== 'ok'))
const worstUsage = computed(() => {
  const ratios = tenants.value.map(tenant => tenant.usage.ratio).filter((ratio): ratio is number => ratio !== null)
  return ratios.length ? Math.round(Math.max(...ratios) * 100) : null
})
</script>

<template>
  <PlatformGate>
    <div class="flex flex-col gap-8 p-6 sm:p-10">
      <PageHeader
        eyebrow="Platform"
        title="Overview"
        description="The state of the platform right now."
      >
        <template #actions>
          <UButton size="sm" variant="subtle" :loading="loading || rendersLoading" @click="refresh(); loadRenders()">
            Refresh
          </UButton>
        </template>
      </PageHeader>

      <ErrorState
        v-if="error"
        :status="error.status"
        :message="error.message"
        retryable
        @retry="refresh"
      />

      <template v-else>
        <div class="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <StatTile label="Tenants" :value="tenants.length" hint="Every tenant on this platform." />
          <StatTile
            label="Renders in flight"
            :value="running.length"
            hint="Running across all tenants."
          />
          <StatTile
            label="Highest storage use"
            :value="worstUsage === null ? '—' : `${worstUsage}%`"
            :percent="worstUsage ?? undefined"
            meter-label="Highest tenant storage use"
            :tone="worstUsage !== null && worstUsage >= 95 ? 'error' : worstUsage !== null && worstUsage >= 80 ? 'warning' : 'primary'"
            hint="A tenant at its limit fails renders with no retry."
          />
        </div>

        <section v-if="nearQuota.length" class="flex flex-col gap-3">
          <SectionLabel as="h2">
            Close to their storage limit
          </SectionLabel>
          <ul class="flex flex-col gap-2">
            <li
              v-for="tenant in nearQuota"
              :key="tenant.name"
              class="border-default flex flex-wrap items-center justify-between gap-3 border p-3"
            >
              <NuxtLink
                :to="`/tenants/${encodeURIComponent(tenant.name)}`"
                class="apus-value text-highlighted hover:text-primary text-sm"
              >{{ tenant.name }}</NuxtLink>
              <span class="apus-value text-muted text-xs">
                {{ tenant.usage.usedLabel }} of {{ tenant.usage.quotaLabel }}
              </span>
              <CellMeter
                v-if="tenant.usage.ratio !== null"
                :percent="tenant.usage.ratio * 100"
                :cells="16"
                :tone="tenant.usage.level === 'critical' ? 'error' : 'warning'"
                :label="`${tenant.name} storage use`"
              />
            </li>
          </ul>
        </section>

        <section class="flex flex-col gap-3">
          <SectionLabel as="h2">
            Rendering now
          </SectionLabel>
          <p v-if="rendersLoading" class="text-muted text-sm">
            Loading…
          </p>
          <p v-else-if="running.length === 0" class="text-muted text-sm">
            Nothing is rendering anywhere on the platform.
          </p>
          <ul v-else class="flex flex-col gap-2">
            <li
              v-for="entry in running"
              :key="`${entry.tenant}/${entry.render.name}`"
              class="border-default flex flex-wrap items-center justify-between gap-3 border p-3"
            >
              <span class="apus-value text-muted text-xs">{{ entry.tenant }}</span>
              <span class="apus-value text-highlighted text-sm">{{ entry.render.name }}</span>
              <CellMeter
                :percent="entry.render.progress.percent"
                :cells="16"
                :live="true"
                :label="`${entry.render.name} progress`"
              />
            </li>
          </ul>
        </section>
      </template>
    </div>
  </PlatformGate>
</template>
