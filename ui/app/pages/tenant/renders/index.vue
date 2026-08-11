<script setup lang="ts">
import type { BlueMapRenderResponse } from '~/utils/apiTypes'
import { ApusApiError } from '~/utils/apiErrors'

const { principal } = useAuth()
const hasAccess = computed(() => canReadTenant(principal.value))

const api = useApiClient()
const renders = ref<BlueMapRenderResponse[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  if (!hasAccess.value) {
    loading.value = false
    return
  }
  try {
    renders.value = await api.listRenders()
  } catch (caught) {
    error.value = caught instanceof ApusApiError ? caught.message : 'Could not load renders.'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <TenantNav />
    <h1 class="mb-4 text-2xl font-semibold">
      Renders
    </h1>
    <TenantAccessGate>
      <UAlert
        v-if="error"
        color="error"
        variant="subtle"
        title="Could not load renders"
        :description="error"
      />
      <p v-else-if="loading" class="text-sm text-muted">
        Loading…
      </p>
      <TenantRenderTable v-else :renders="renders" />
    </TenantAccessGate>
  </div>
</template>
