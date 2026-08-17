<script setup lang="ts">
/**
 * The impersonation banner lives here rather than on the tenant page that starts it, because
 * that is the point: once a session is running, every page is answering as somebody else, and a
 * reader who navigated away must still be able to tell. A banner only on the page you started
 * from would be a banner you stop seeing exactly when you have forgotten.
 */
import { useImpersonation } from '#core/composables/useImpersonation'

const { actingAs, stopImpersonating } = useImpersonation()
</script>

<template>
  <AppShell variant="side">
    <template #nav>
      <LayoutConsoleSidebar />
    </template>
    <template #header>
      <LayoutConsoleHeader />
    </template>

    <div
      v-if="actingAs"
      class="border-warning/50 bg-warning/10 flex flex-wrap items-center justify-between gap-3 border-b px-6 py-3"
    >
      <p class="text-highlighted text-sm">
        Viewing as
        <span class="apus-value">{{ actingAs.user ?? 'the tenant itself' }}</span>
        in <span class="apus-value">{{ actingAs.tenant }}</span> — every request is recorded under
        your own name.
      </p>
      <UButton size="sm" @click="stopImpersonating">
        Stop
      </UButton>
    </div>

    <slot />
  </AppShell>
</template>
