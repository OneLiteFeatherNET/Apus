<script setup lang="ts">
import type { BlueMapMapResponse } from '#core/utils/apiTypes'
import { ApusApiError } from '#core/utils/apiErrors'

const { principal } = useAuth()
const hasAccess = computed(() => canReadTenant(principal.value))

const api = useApiClient()
const maps = ref<BlueMapMapResponse[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  if (!hasAccess.value) {
    loading.value = false
    return
  }
  try {
    maps.value = await api.listMaps()
  } catch (caught) {
    error.value = caught instanceof ApusApiError ? caught.message : 'Could not load maps.'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <TenantNav />
    <h1 class="mb-4 text-2xl font-semibold">
      Maps
    </h1>
    <TenantAccessGate>
      <UAlert
        v-if="error"
        color="error"
        variant="subtle"
        title="Could not load maps"
        :description="error"
      />
      <p v-else-if="loading" class="text-sm text-muted">
        Loading…
      </p>
      <TenantMapTable v-else :maps="maps" />
    </TenantAccessGate>
  </div>
</template>
