<script setup lang="ts">
// Landing point for the broker's Authorization Code redirect (redirect_uri in useAuth.ts).
// No layout/nav here -- this page renders for a moment at most.
definePageMeta({ layout: false })

const { oidc, user } = useAuth()
const router = useRouter()
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    const signedInUser = await oidc.signinRedirectCallback()
    user.value = signedInUser
    const state = signedInUser.state as { returnTo?: string } | null
    await router.replace(state?.returnTo || '/')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Sign-in failed.'
  }
})
</script>

<template>
  <!-- `bg-default`/`text-error` rather than a palette colour: this page belongs to layers/core,
       but it is rendered by applications that carry the design system, and a hardcoded red here
       would be the one thing on screen that ignores the colour mode. -->
  <div class="bg-default text-default flex min-h-screen items-center justify-center p-6">
    <p v-if="error" role="alert" class="text-error text-sm">
      {{ error }}
    </p>
    <p v-else class="text-muted text-sm">Signing you in…</p>
  </div>
</template>
