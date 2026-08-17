<script setup lang="ts">
/**
 * View the platform as a tenant sees it.
 *
 * The mechanism is two request headers, not a second login: the API's `ImpersonationFilter`
 * reads them, applies `ImpersonationPolicy`, and serves the request as the narrowed principal.
 * Nothing here grants anything — the policy strips the platform role and refuses a tenant the
 * caller may not act in, so the worst this component can do is ask for something and be told no.
 *
 * Two things are said out loud rather than assumed:
 *
 * - **The session is recorded.** Every impersonated request is logged with the real subject. A
 *   person about to use this should know that before they do, not afterwards.
 * - **Which tenant they are entering.** Acting in the wrong tenant while wearing somebody else's
 *   name is the mistake worth designing against.
 *
 * Acting as the tenant itself — the "org admin" case — stays available even when the directory
 * listed nobody, because a tenant whose members cannot be loaded is exactly when somebody is
 * trying to work out what is wrong.
 */
import type { DirectoryUserResponse } from '#core/utils/apiTypes'
import { useImpersonation } from '#core/composables/useImpersonation'

const props = defineProps<{
  tenant: string
  users: DirectoryUserResponse[]
}>()

const { startImpersonating, stopImpersonating, actingAs } = useImpersonation()

/**
 * Stands for "the tenant itself" rather than a person. A sentinel and not an empty string
 * because the select treats empty as "nothing chosen" and would show its placeholder instead —
 * and acting as the tenant is a real choice, not the absence of one.
 */
const AS_TENANT = '__as-tenant__'

const selectedUser = ref(AS_TENANT)

const options = computed(() => [
  { label: 'the tenant itself (org admin)', value: AS_TENANT },
  ...props.users.map(user => ({ label: user.displayName || user.email, value: user.id }))
])

function enter(): void {
  startImpersonating(props.tenant, selectedUser.value === AS_TENANT ? null : selectedUser.value)
}
</script>

<template>
  <section class="flex flex-col gap-3">
    <SectionLabel as="h2">
      View as this tenant
    </SectionLabel>

    <div class="border-default flex flex-col gap-4 border p-6">
      <template v-if="actingAs">
        <p class="text-highlighted text-sm">
          You are viewing Apus as
          <span class="apus-value">{{ actingAs.user ?? 'the tenant itself' }}</span>
          in <span class="apus-value">{{ actingAs.tenant }}</span>.
        </p>
        <p class="text-muted text-sm">
          Your own platform-wide access is switched off while this lasts, and every request is
          recorded under your name.
        </p>
        <div>
          <UButton size="sm" @click="stopImpersonating">
            Stop and go back to yourself
          </UButton>
        </div>
      </template>

      <template v-else>
        <p class="text-muted text-sm">
          See exactly what <span class="apus-value">{{ tenant }}</span> sees — useful when a
          report only makes sense from inside the tenant. Every request made this way is
          <strong>recorded</strong> under your own name, and platform-wide access is dropped for
          the duration, so you can never do more this way than you could as yourself.
        </p>

        <div class="flex flex-wrap items-end gap-2">
          <UFormField label="Act as" name="actAs" class="grow">
            <USelect v-model="selectedUser" :items="options" value-key="value" />
          </UFormField>
          <UButton size="sm" @click="enter">
            Start viewing as {{ tenant }}
          </UButton>
        </div>
      </template>
    </div>
  </section>
</template>
