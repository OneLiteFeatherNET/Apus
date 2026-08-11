<script setup lang="ts">
// Which links are shown is a UX convenience, not access control -- see
// app/utils/role.ts's module Javadoc.
const { principal } = useAuth()
const showPlatformLink = computed(() => isPlatformAdmin(principal.value))
const showTenantLink = computed(() => canReadTenant(principal.value))
</script>

<template>
  <nav aria-label="Main" class="flex items-center gap-4">
    <ULink to="/" class="text-sm font-medium">
      Account
    </ULink>
    <ULink
      v-if="showTenantLink"
      to="/tenant"
      class="text-sm font-medium"
    >
      Tenant
    </ULink>
    <ULink
      v-if="showPlatformLink"
      to="/platform"
      class="text-sm font-medium"
    >
      Platform
    </ULink>
  </nav>
</template>
