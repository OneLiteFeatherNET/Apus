<script setup lang="ts">
// Which links are shown is a UX convenience, not access control -- see
// app/utils/role.ts's module Javadoc. The platform-level dashboard (Tenants, Quotas, cluster-
// wide jobs -- design spec §11.2) does not exist yet; this is the foundation the next task
// builds it into, so the link is a placeholder route for now.
const { principal } = useAuth()
const showPlatformLink = computed(() => isPlatformAdmin(principal.value))
</script>

<template>
  <nav aria-label="Main" class="flex items-center gap-4">
    <ULink to="/" class="text-sm font-medium">
      Account
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
