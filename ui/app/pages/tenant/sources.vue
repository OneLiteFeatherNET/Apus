<script setup lang="ts">
import type { WorldSourceResponse } from '~/utils/apiTypes'
import { ApusApiError } from '~/utils/apiErrors'

const { principal } = useAuth()
const hasAccess = computed(() => canReadTenant(principal.value))

const api = useApiClient()
const sources = ref<WorldSourceResponse[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  if (!hasAccess.value) {
    loading.value = false
    return
  }
  try {
    sources.value = await api.listSources()
  } catch (caught) {
    error.value = caught instanceof ApusApiError ? caught.message : 'Could not load world sources.'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <TenantNav />
    <h1 class="mb-4 text-2xl font-semibold">
      Sources
    </h1>
    <TenantAccessGate>
      <UAlert
        v-if="error"
        color="error"
        variant="subtle"
        title="Could not load world sources"
        :description="error"
      />
      <p v-else-if="loading" class="text-sm text-muted">
        Loading…
      </p>
      <TenantSourceTable v-else :sources="sources" />
    </TenantAccessGate>
  </div>
</template>
