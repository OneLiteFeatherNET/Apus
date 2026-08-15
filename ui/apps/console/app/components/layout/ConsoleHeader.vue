<script setup lang="ts">
const { user, logout } = useAuth()
</script>

<template>
  <header class="flex items-center justify-between border-b border-default px-6 py-4">
    <div class="flex items-center gap-3">
      <span class="text-lg font-semibold">Apus</span>
      <!-- Named, not just implied by the URL: an admin has both applications open at once and
           the two shells are otherwise identical until the Part B redesign lands. -->
      <span class="text-muted text-sm">Console</span>
    </div>
    <div class="flex items-center gap-4">
      <span v-if="user" class="text-sm text-muted">
        {{ user.profile.email ?? user.profile.sub }}
      </span>
      <!-- A separate application, so a plain anchor and not <ULink to>: this router knows
           nothing about the tenant app's routes and would refuse to resolve them. -->
      <a href="/" class="text-sm font-medium text-muted hover:text-default">
        Tenant app
      </a>
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
