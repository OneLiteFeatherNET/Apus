<script setup lang="ts">
// Loaded inside the hidden iframe oidc-client-ts opens for silent token renewal
// (silent_redirect_uri in useAuth.ts). Never navigated to directly, renders nothing.
definePageMeta({ layout: false })

const { oidc } = useAuth()

onMounted(() => {
  void oidc.signinSilentCallback()
})
</script>

<template>
  <div />
</template>
