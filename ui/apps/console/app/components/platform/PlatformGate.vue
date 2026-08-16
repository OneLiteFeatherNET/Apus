<script setup lang="ts">
/**
 * Wraps every console page. A non-admin who reaches this URL gets a page that explains itself
 * rather than a dashboard whose every request 403s in front of them one at a time.
 *
 * Convenience only -- see usePlatformAccess. Nothing here decides what an account may do.
 */
const allowed = usePlatformAccess()
</script>

<template>
  <div v-if="allowed">
    <slot />
  </div>

  <div v-else class="mx-auto max-w-2xl p-6 sm:p-10">
    <EmptyState
      title="This is the platform console"
      description="It manages tenants, quotas and cluster-wide renders, and it is shown only to accounts with the platform-admin role. Apus checks that on the server for every request, so nothing here would answer even if the page rendered."
    >
      <template #action>
        <UButton to="/" size="sm" variant="subtle" external>
          Go to the tenant app
        </UButton>
      </template>
    </EmptyState>
  </div>
</template>
