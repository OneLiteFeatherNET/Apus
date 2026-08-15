<script setup lang="ts">
const { user, principal, logout } = useAuth()
// Convenience only, exactly like the nav link it replaces: the console re-checks the role and
// the api module answers 403 regardless of what this renders (layers/core, app/utils/role.ts).
// Shown so an admin is not left guessing at a URL, hidden so the other 99% of users are not
// offered a door they cannot open.
const showConsoleLink = computed(() => isPlatformAdmin(principal.value))
</script>

<template>
  <header class="flex items-center justify-between border-b border-default px-6 py-4">
    <div class="flex items-center gap-8">
      <span class="text-lg font-semibold">Apus</span>
      <LayoutAppNav />
    </div>
    <div class="flex items-center gap-4">
      <span v-if="user" class="text-sm text-muted">
        {{ user.profile.email ?? user.profile.sub }}
      </span>
      <!-- A separate application, so a plain anchor and not <ULink to>: this router knows
           nothing about /console and would refuse to resolve it. -->
      <a
        v-if="showConsoleLink"
        href="/console/"
        class="text-sm font-medium text-muted hover:text-default"
      >
        Platform console
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
