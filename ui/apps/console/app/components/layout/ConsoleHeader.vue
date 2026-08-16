<script setup lang="ts">
const { user, logout } = useAuth()
const account = computed(() => user.value?.profile.email ?? user.value?.profile.sub ?? 'Account')
</script>

<template>
  <div class="flex flex-wrap items-center justify-between gap-3 px-6 py-3">
    <span class="apus-eyebrow text-dimmed">Management console</span>
    <div class="flex items-center gap-3">
      <span class="apus-value text-muted max-w-56 truncate text-sm">{{ account }}</span>
      <!-- A separate application, so a plain anchor: this router knows nothing about the tenant
           app's routes and would refuse to resolve them. -->
      <a href="/" class="text-muted hover:text-highlighted text-sm sm:hidden">Tenant app</a>
      <UButton variant="ghost" size="sm" @click="logout">
        Sign out
      </UButton>
    </div>
  </div>
</template>
