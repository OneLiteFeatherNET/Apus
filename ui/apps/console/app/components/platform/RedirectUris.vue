<script setup lang="ts">
/**
 * The two redirect URIs a tenant's own application instance needs registered with the identity
 * provider.
 *
 * This is a to-do item, not a status readout, and it is shown here because there is nowhere else
 * it could be shown in time. The operator cannot register these itself -- that needs application
 * permissions on the app registration that nobody has granted -- and a missing registration does
 * not fail at deploy time. It fails much later, at someone's first sign-in, with an error from
 * the broker (Entra says AADSTS50011) and nothing whatsoever in the cluster's logs. The person
 * who can still act on it is the one who just created the tenant, standing on this page.
 *
 * Nothing renders when the list is empty: a tenant with no application instance has no such step
 * pending, and an empty "Redirect URIs" section would read like something failed to load.
 */
const props = defineProps<{ uris: string[] }>()

const copied = ref(false)

async function copyAll(): Promise<void> {
  // Both at once, in order: they are pasted into a list, and copying them one at a time is how
  // one of the two gets forgotten.
  await navigator.clipboard.writeText(props.uris.join('\n'))
  copied.value = true
  setTimeout(() => { copied.value = false }, 2000)
}
</script>

<template>
  <section v-if="uris.length" class="flex flex-col gap-3">
    <SectionLabel as="h2">
      Redirect URIs
    </SectionLabel>

    <div class="border-warning/40 bg-warning/5 flex flex-col gap-4 border p-6">
      <p class="text-muted text-sm">
        This tenant has its own instance of the application. Before anyone can sign in to it, these
        two URIs have to be registered with the identity provider — Apus cannot add them itself.
      </p>

      <ul class="flex flex-col gap-2">
        <li
          v-for="uri in uris"
          :key="uri"
          class="apus-value text-highlighted border-default border p-3 text-sm break-all"
        >
          {{ uri }}
        </li>
      </ul>

      <!-- Said plainly, because the failure gives no hint of its own cause: it happens at the
           provider, at sign-in, long after this page was closed. -->
      <p class="text-muted text-sm">
        Until they are registered, signing in fails at the identity provider and nothing appears in
        this cluster's logs.
      </p>

      <div>
        <UButton size="sm" variant="subtle" @click="copyAll">
          {{ copied ? 'Copied' : 'Copy both' }}
        </UButton>
      </div>
    </div>
  </section>
</template>
