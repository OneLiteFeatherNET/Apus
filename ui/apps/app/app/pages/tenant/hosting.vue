<script setup lang="ts">
import type { BlueMapHostingResponse } from '#core/utils/apiTypes'
import { ApusApiError } from '#core/utils/apiErrors'

const { principal } = useAuth()
const hasAccess = computed(() => canReadTenant(principal.value))

const api = useApiClient()
const hostings = ref<BlueMapHostingResponse[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  if (!hasAccess.value) {
    loading.value = false
    return
  }
  try {
    hostings.value = await api.listHostings()
  } catch (caught) {
    error.value = caught instanceof ApusApiError ? caught.message : 'Could not load hosting info.'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <TenantNav />
    <h1 class="mb-4 text-2xl font-semibold">
      Hosting
    </h1>
    <TenantAccessGate>
      <UAlert
        v-if="error"
        color="error"
        variant="subtle"
        title="Could not load hosting info"
        :description="error"
      />
      <p v-else-if="loading" class="text-sm text-muted">
        Loading…
      </p>
      <TenantHostingTable v-else :hostings="hostings" />
    </TenantAccessGate>
  </div>
</template>
