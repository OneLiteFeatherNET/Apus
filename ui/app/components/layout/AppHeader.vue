<script setup lang="ts">
const { user, logout } = useAuth()
</script>

<template>
  <header class="flex items-center justify-between border-b border-default px-6 py-4">
    <div class="flex items-center gap-8">
      <span class="text-lg font-semibold">Apus</span>
      <AppNav />
    </div>
    <div class="flex items-center gap-4">
      <span v-if="user" class="text-sm text-muted">
        {{ user.profile.email ?? user.profile.sub }}
      </span>
      <UButton
        variant="ghost"
        size="sm"
        @click="logout"
      >
        Sign out
      </UButton>
    </div>
  </header>
</template>
