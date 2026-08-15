<script setup lang="ts">
// Which links are shown is a UX convenience, not access control -- see layers/core,
// app/utils/role.ts's module Javadoc.
//
// There is no "Platform" entry any more: that area is a separate application (design doc
// 2026-08-15, §2), reachable through the header's own link. A <ULink to="/platform"> here would
// resolve to nothing in this app's router.
const { principal } = useAuth()
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
  </nav>
</template>
