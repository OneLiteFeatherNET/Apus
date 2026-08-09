<script setup lang="ts">
// Platform dashboard (design spec §11.2): tenants, their storage quota vs. observed usage,
// their allowed hosting domains, and cluster-wide job visibility. `platform-admin` only.
//
// The role check below is a UX convenience, same as the "Platform" nav link in AppNav.vue --
// see app/utils/role.ts's module Javadoc. It only decides what this page *shows*; the api
// module re-checks `platform-admin` on every request underneath it and answers with 403
// otherwise (TenantController.requirePlatformAdmin). Nothing here should be mistaken for the
// actual access-control boundary.
import type { TenantResponse } from '~/utils/apiTypes'
import { ApusApiError } from '~/utils/apiErrors'

const { principal } = useAuth()
const allowed = computed(() => isPlatformAdmin(principal.value))

const api = useApiClient()
const tenants = ref<TenantResponse[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)

async function refresh(): Promise<void> {
  if (!allowed.value) return
  loading.value = true
  loadError.value = null
  try {
    tenants.value = await api.listTenants()
  } catch (error) {
    loadError.value = error instanceof ApusApiError ? error.message : 'Could not load tenants.'
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <div v-if="!allowed" class="max-w-xl">
    <UAlert
      icon="i-lucide-lock"
      color="neutral"
      variant="subtle"
      title="Platform administrators only"
      description="This area manages tenants across the whole platform. It is shown only to accounts with the platform-admin role -- the api module enforces that independently on every request."
    />
  </div>

  <div v-else class="max-w-4xl space-y-10">
    <div>
      <h1 class="text-2xl font-semibold">
        Platform
      </h1>
      <p class="text-muted mt-1 text-sm">
        Tenants, their storage quota, and which hostnames each may use for hosted maps.
      </p>
    </div>

    <UAlert v-if="loadError" color="error" variant="subtle" :title="loadError" />

    <PlatformTenantList :tenants="tenants" :loading="loading" />

    <PlatformCreateTenantForm @created="refresh" />

    <PlatformClusterJobsNotice />
  </div>
</template>
