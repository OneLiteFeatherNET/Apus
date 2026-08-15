<script setup lang="ts">
/**
 * A mono eyebrow naming a resource kind: SOURCE, MAP, RENDER, HOSTING, TENANT.
 *
 * That vocabulary is the CRD taxonomy, not a decorative flourish -- the label tells the reader
 * which kind of thing they are looking at, in the same words the platform, the docs and
 * `kubectl get` use. If a label here ever stops naming a real kind, it has become decoration and
 * should go.
 */
defineProps<{ as?: 'span' | 'h2' | 'h3' }>()
</script>

<template>
  <component :is="as ?? 'span'" class="apus-eyebrow text-dimmed block">
    <slot />
  </component>
</template>
