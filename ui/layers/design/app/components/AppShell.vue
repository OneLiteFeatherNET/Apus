<script setup lang="ts">
/**
 * The page frame both applications sit in, and the home of the accessibility scaffolding that a
 * linter cannot check for us.
 *
 * The focus move on navigation is the part worth understanding: Vue Router changes the view but
 * leaves focus exactly where it was, so a keyboard or screen-reader user who activates a link is
 * left standing in the *old* page's navigation with no announcement that anything happened. Every
 * route change therefore moves focus to the main region, which is also what makes the skip link
 * worth having on the second page rather than only the first.
 */
import { onMounted, ref } from 'vue'

defineProps<{ variant?: 'top' | 'side' }>()

const main = ref<HTMLElement | null>(null)
const router = useRouter()

onMounted(() => {
  router.afterEach(() => {
    // afterEach fires before the new view is painted; wait a tick so focus lands on content
    // that exists.
    nextTick(() => main.value?.focus())
  })
})
</script>

<template>
  <div class="bg-default text-default min-h-screen">
    <a
      href="#main"
      class="bg-elevated text-highlighted border-default sr-only z-50 border px-4 py-2 focus:not-sr-only focus:absolute focus:top-2 focus:left-2"
    >
      Skip to content
    </a>

    <!-- Column below sm, row above: a side rail beside the content needs width the phone does not
         have, so on a narrow screen it becomes a bar across the top instead of squeezing both. -->
    <div :class="variant === 'side' ? 'flex min-h-screen flex-col sm:flex-row' : ''">
      <slot name="nav" />

      <div class="flex min-w-0 flex-1 flex-col">
        <header class="border-default border-b">
          <slot name="header" />
        </header>

        <!-- tabindex -1 so the focus move above can land here without making it a tab stop. -->
        <main id="main" ref="main" tabindex="-1" class="flex-1 focus:outline-none">
          <slot />
        </main>
      </div>
    </div>
  </div>
</template>
