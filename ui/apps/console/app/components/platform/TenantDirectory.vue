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
  /**
   * Who is in which team, keyed by team id. Absent for a team nobody has expanded yet — which is
   * why this is a map rather than a field on the team: assignments are one request per team, and
   * fetching all of them to render a list most people only scan would be a burst the identity
   * provider throttles.
   */
  members?: Record<string, DirectoryUserResponse[]>
}>()

const emit = defineEmits<{
  /** `create-team` carries the name; `load-members` carries the team id. */
  (event: 'create-team' | 'load-members', value: string): void
  (event: 'invite', payload: { email: string, displayName: string }): void
  (event: 'reset-password', user: DirectoryUserResponse): void
}>()

/** Which teams the reader has opened. Nothing is fetched until one is. */
const expanded = ref<Set<string>>(new Set())

function toggle(teamId: string): void {
  const next = new Set(expanded.value)
  if (next.has(teamId)) {
    next.delete(teamId)
  } else {
    next.add(teamId)
    if (!props.members?.[teamId]) emit('load-members', teamId)
  }
  expanded.value = next
}

const newTeamName = ref('')
const inviteEmail = ref('')
const inviteName = ref('')

const unavailable = computed(() => props.directory.unavailableReason)

/**
 * A team's size, from whichever source actually knows it.
 *
 * The list endpoint usually does not: Graph reports `members@odata.count` for the group being
 * listed, not for each nested group in the result, so the count is normally absent. Rather than
 * printing "Unknown size" against every row — technically honest, useless to read — the label
 * simply says nothing about size until the team is opened, at which point the loaded membership
 * is an exact count.
 *
 * What it never does is print a zero it did not measure.
 */
function memberCountLabel(teamId: string, count: number | null): string {
  const loaded = props.members?.[teamId]
  const known = loaded ? loaded.length : count
  if (known === null || known === undefined) return ''
  return known === 1 ? '1 member' : `${known} members`
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
            class="border-default flex flex-col gap-2 border p-3"
          >
            <button
              type="button"
              class="hover:text-primary flex items-center justify-between gap-3 text-left"
              @click="toggle(team.id)"
            >
              <span class="text-highlighted text-sm">{{ team.displayName }}</span>
              <span class="text-muted text-xs">
                <template v-if="memberCountLabel(team.id, team.memberCount)">
                  {{ memberCountLabel(team.id, team.memberCount) }} ·
                </template>
                {{ expanded.has(team.id) ? 'Hide' : 'Who is in it' }}
              </span>
            </button>

            <template v-if="expanded.has(team.id)">
              <ul v-if="members?.[team.id]?.length" class="flex flex-col gap-1 pl-3">
                <li
                  v-for="member in members[team.id]"
                  :key="member.id"
                  class="text-muted text-xs"
                >
                  {{ member.displayName || member.email }}
                  <span class="apus-value">({{ member.email }})</span>
                </li>
              </ul>
              <!-- "Nobody" only once the answer is actually in: an empty list shown while the
                   request is still out would read as a fact about the team. -->
              <p v-else-if="members?.[team.id]" class="text-muted pl-3 text-xs">
                Nobody is in this team yet.
              </p>
              <p v-else class="text-muted pl-3 text-xs">
                Loading…
              </p>
            </template>
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
