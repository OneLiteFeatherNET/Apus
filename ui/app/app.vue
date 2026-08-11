<script setup lang="ts">
useHead({
  titleTemplate: (titleChunk) => (titleChunk ? `${titleChunk} | Apus` : 'Apus')
})
</script>

<template>
  <UApp>
    <NuxtLayout>
      <NuxtPage />
    </NuxtLayout>
  </UApp>
</template>
