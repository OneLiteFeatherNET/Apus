<script setup lang="ts">
// Shared "do you even have a tenant role" guard for every page under app/pages/tenant/. This is
// the same convenience-only pattern as app/utils/role.ts's own module doc describes: it decides
// what to *show* to an account with no tenant-level role (e.g. a platform-admin-only account),
// it enforces nothing -- the api module answers 403/404 regardless of what this renders.
const { principal } = useAuth()
const hasAccess = computed(() => canReadTenant(principal.value))
</script>

<template>
  <UAlert
    v-if="!hasAccess"
    color="warning"
    variant="subtle"
    icon="i-lucide-triangle-alert"
    title="No tenant access"
    description="Your account has no tenant-level role (tenant-viewer, tenant-operator or tenant-owner), so there is nothing to show here."
  />
  <slot v-else />
</template>
