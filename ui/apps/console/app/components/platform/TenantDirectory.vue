<script setup lang="ts">
/**
 * A tenant's teams and people, and the four things an administrator can change here.
 *
 * Two rules shape the whole component, and both are about not lying:
 *
 * - **Unavailable is not empty.** When the identity provider cannot be reached, the API answers
 *   with `unavailableReason` set rather than an error, and this shows that reason. An empty list
 *   would read as "this tenant has nobody in it", which is a fact somebody would act on.
 * - **An unknown count is not zero.** A team whose size the directory would not report shows
 *   "Unknown", never "0 members".
 *
 * `canWrite` hides the changing actions. It is a courtesy, not the enforcement -- the API
 * refuses regardless, and so does the guard behind it.
 */
import type { TenantDirectoryResponse, DirectoryUserResponse } from '#core/utils/apiTypes'

const props = defineProps<{
  tenant: string
  directory: TenantDirectoryResponse
  canWrite: boolean
}>()

const emit = defineEmits<{
  (event: 'create-team', displayName: string): void
  (event: 'invite', payload: { email: string, displayName: string }): void
  (event: 'reset-password', user: DirectoryUserResponse): void
}>()

const newTeamName = ref('')
const inviteEmail = ref('')
const inviteName = ref('')

const unavailable = computed(() => props.directory.unavailableReason)

function memberCountLabel(count: number | null): string {
  // Never "0 members" for a count we never got -- see the component comment.
  if (count === null) return 'Unknown size'
  return count === 1 ? '1 member' : `${count} members`
}

function submitTeam(): void {
  const name = newTeamName.value.trim()
  if (!name) return
  emit('create-team', name)
  newTeamName.value = ''
}

function submitInvite(): void {
  const email = inviteEmail.value.trim()
  if (!email) return
  emit('invite', { email, displayName: inviteName.value.trim() })
  inviteEmail.value = ''
  inviteName.value = ''
}
</script>

<template>
  <section class="flex flex-col gap-5">
    <SectionLabel as="h2">
      Teams and people
    </SectionLabel>

    <!-- The directory is somebody else's service. Say so plainly, with the reason, rather than
         rendering an empty state that reads like a fact about this tenant. -->
    <p
      v-if="unavailable"
      class="border-warning/40 bg-warning/5 text-muted border p-4 text-sm"
    >
      The directory could not be reached, so teams and people are not shown: {{ unavailable }}
    </p>

    <template v-else>
      <div class="flex flex-col gap-3">
        <SectionLabel as="h3">
          Teams
        </SectionLabel>

        <ul v-if="directory.teams.length" class="flex flex-col gap-2">
          <li
            v-for="team in directory.teams"
            :key="team.id"
            class="border-default flex items-center justify-between gap-3 border p-3"
          >
            <span class="text-highlighted text-sm">{{ team.displayName }}</span>
            <span class="text-muted text-xs">{{ memberCountLabel(team.memberCount) }}</span>
          </li>
        </ul>
        <p v-else class="text-muted text-sm">
          No teams yet.
        </p>

        <div v-if="canWrite" class="flex flex-wrap items-end gap-2">
          <UFormField label="New team" name="newTeam" class="grow">
            <UInput v-model="newTeamName" placeholder="Builders" class="apus-value" />
          </UFormField>
          <UButton size="sm" :disabled="!newTeamName.trim()" @click="submitTeam">
            Create team
          </UButton>
        </div>
      </div>

      <div class="flex flex-col gap-3">
        <SectionLabel as="h3">
          People
        </SectionLabel>

        <ul v-if="directory.users.length" class="flex flex-col gap-2">
          <li
            v-for="user in directory.users"
            :key="user.id"
            class="border-default flex flex-wrap items-center justify-between gap-3 border p-3"
          >
            <span class="flex flex-col">
              <span class="text-highlighted text-sm">{{ user.displayName || user.email }}</span>
              <span class="apus-value text-muted text-xs break-all">{{ user.email }}</span>
            </span>
            <!-- Greyed out rather than absent, with the reason: an administrator wondering why
                 they cannot help this person gets the answer here instead of from a refusal. -->
            <UBadge v-if="user.privileged" color="warning" variant="subtle" size="sm">
              Directory admin — password reset refused
            </UBadge>
            <UButton
              v-else-if="canWrite"
              size="sm"
              variant="subtle"
              @click="emit('reset-password', user)"
            >
              Reset password
            </UButton>
          </li>
        </ul>
        <p v-else class="text-muted text-sm">
          Nobody in this tenant yet.
        </p>

        <div v-if="canWrite" class="flex flex-wrap items-end gap-2">
          <UFormField label="Invite by e-mail" name="inviteEmail" class="grow">
            <UInput v-model="inviteEmail" type="email" placeholder="carol@example.net" class="apus-value" />
          </UFormField>
          <UFormField label="Name (optional)" name="inviteName">
            <UInput v-model="inviteName" placeholder="Carol" />
          </UFormField>
          <UButton size="sm" :disabled="!inviteEmail.trim()" @click="submitInvite">
            Invite
          </UButton>
        </div>
      </div>
    </template>
  </section>
</template>
