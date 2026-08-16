<script setup lang="ts">
const { user, principal, logout } = useAuth()

// Convenience only, exactly like the nav link it replaced: the console re-checks the role and the
// api module answers 403 regardless of what this renders (layers/core, app/utils/role.ts). Shown
// so an admin is not left guessing at a URL, hidden so the other 99% of users are not offered a
// door they cannot open.
const showConsoleLink = computed(() => isPlatformAdmin(principal.value))
const account = computed(() => user.value?.profile.email ?? user.value?.profile.sub ?? 'Account')
</script>

<template>
  <div class="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-6 py-3 sm:px-10">
    <!-- Wraps, and a smaller gap below sm: four nav items plus the wordmark overflow a 360px
         viewport otherwise, and a page that scrolls sideways on a phone is a bug, not a trade-off. -->
    <div class="flex flex-wrap items-center gap-x-4 gap-y-2 sm:gap-x-8">
      <NuxtLink to="/" class="flex items-center gap-2">
        <!-- The mark is the same cell the meters are built from: three filled, one not. The
             product is a thing that fills in over time, and the wordmark says so. -->
        <span class="apus-mark" aria-hidden="true">
          <span class="apus-mark-cell apus-mark-cell--on" />
          <span class="apus-mark-cell apus-mark-cell--on" />
          <span class="apus-mark-cell apus-mark-cell--on" />
          <span class="apus-mark-cell" />
        </span>
        <span class="text-highlighted text-base font-semibold tracking-tight">Apus</span>
      </NuxtLink>
      <LayoutAppNav />
    </div>

    <div class="flex items-center gap-3">
      <a
        v-if="showConsoleLink"
        href="/console/"
        class="text-muted hover:text-highlighted text-sm"
      >
        Platform console
      </a>
      <UDropdownMenu
        :items="[[
          { label: 'Your access', to: '/account' },
          { label: 'Sign out', onSelect: () => logout() }
        ]]"
      >
        <UButton variant="ghost" size="sm" class="apus-value max-w-48 truncate">
          {{ account }}
        </UButton>
      </UDropdownMenu>
    </div>
  </div>
</template>

<style scoped>
.apus-mark {
  display: grid;
  grid-template-columns: repeat(2, var(--apus-cell));
  gap: var(--apus-cell-gap);
}

.apus-mark-cell {
  width: var(--apus-cell);
  height: var(--apus-cell);
  border: 1px solid var(--ui-color-primary-500);
}

.apus-mark-cell--on {
  background-color: var(--ui-color-primary-500);
}
</style>
