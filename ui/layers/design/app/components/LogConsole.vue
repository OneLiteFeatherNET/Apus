<script setup lang="ts">
/**
 * Render logs.
 *
 * Dark in both colour modes on purpose. A log is terminal output; a light-mode log surface is a
 * lie about what the reader is looking at, and it makes the one place where a monospaced wall of
 * text is correct look like a document.
 *
 * Follow-tail is on by default and switches itself off the moment the reader scrolls up -- the
 * standard, and the only behaviour that does not fight someone trying to read the line that just
 * scrolled past. `aria-live` is announced only while following: a log that is being read
 * deliberately must not interrupt the reader every time a line arrives.
 */
import { nextTick, ref, watch } from 'vue'

const props = withDefaults(defineProps<{ lines: string[], label?: string }>(), {
  label: 'Render log'
})

const following = ref(true)
const box = ref<HTMLElement | null>(null)

function onScroll(): void {
  const element = box.value
  if (!element) return
  const atBottom = element.scrollHeight - element.scrollTop - element.clientHeight < 24
  following.value = atBottom
}

watch(() => props.lines.length, async () => {
  if (!following.value) return
  await nextTick()
  const element = box.value
  if (element) element.scrollTop = element.scrollHeight
})
</script>

<template>
  <div class="border-default flex flex-col border">
    <div class="border-default bg-muted flex items-center justify-between border-b px-3 py-2">
      <SectionLabel>{{ label }}</SectionLabel>
      <label class="text-muted flex items-center gap-2 text-xs">
        <input v-model="following" type="checkbox" class="accent-primary">
        Follow
      </label>
    </div>

    <div
      ref="box"
      class="apus-log max-h-96 overflow-auto p-3"
      :aria-live="following ? 'polite' : 'off'"
      @scroll="onScroll"
    >
      <p v-if="lines.length === 0" class="apus-log-line opacity-60">
        Waiting for output…
      </p>
      <p v-for="(line, index) in lines" :key="index" class="apus-log-line">{{ line }}</p>
    </div>
  </div>
</template>

<style scoped>
/* Not `bg-default`: this surface stays dark in light mode, see the component's own comment. */
.apus-log {
  background-color: var(--color-basalt-950);
  color: var(--color-basalt-200);
}

.apus-log-line {
  font-family: var(--apus-font-mono);
  font-size: 0.75rem;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
