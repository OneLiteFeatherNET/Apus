<script setup lang="ts">
import { ApusApiError } from '#core/utils/apiErrors'

const { principal } = useAuth()
const hasAccess = computed(() => canReadTenant(principal.value))

interface SectionSummary {
  label: string
  to: string
  count: number | null
  error: string | null
}

const sections = ref<SectionSummary[]>([
  { label: 'Sources', to: '/tenant/sources', count: null, error: null },
  { label: 'Maps', to: '/tenant/maps', count: null, error: null },
  { label: 'Renders', to: '/tenant/renders', count: null, error: null },
  { label: 'Hosting', to: '/tenant/hosting', count: null, error: null }
])
const loading = ref(true)

function applyResult(index: number, result: PromiseSettledResult<unknown[]>): void {
  const section = sections.value[index]
  if (!section) return
  if (result.status === 'fulfilled') {
    section.count = result.value.length
  } else {
    section.error = result.reason instanceof ApusApiError ? result.reason.message : 'Could not load.'
  }
}

const api = useApiClient()

onMounted(async () => {
  if (!hasAccess.value) {
    loading.value = false
    return
  }
  // Independent sections, independent failures -- one endpoint erroring must not blank the
  // other three (Promise.allSettled, not Promise.all).
  const results = await Promise.allSettled([
    api.listSources(),
    api.listMaps(),
    api.listRenders(),
    api.listHostings()
  ])
  results.forEach((result, index) => applyResult(index, result))
  loading.value = false
})
</script>

<template>
  <div>
    <TenantNav />
    <h1 class="mb-4 text-2xl font-semibold">
      Tenant overview
    </h1>
    <TenantAccessGate>
      <p v-if="loading" class="text-sm text-muted">
        Loading…
      </p>
      <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <NuxtLink v-for="section in sections" :key="section.to" :to="section.to">
          <UCard>
            <template #header>
              <h2 class="font-medium">
                {{ section.label }}
              </h2>
            </template>
            <p v-if="section.error" class="text-sm text-error">
              {{ section.error }}
            </p>
            <p v-else class="text-2xl font-semibold">
              {{ section.count ?? '—' }}
            </p>
          </UCard>
        </NuxtLink>
      </div>
    </TenantAccessGate>
  </div>
</template>
