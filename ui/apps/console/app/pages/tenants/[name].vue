<script setup lang="ts">
/**
 * One tenant: what it is using, what it is allowed, and the two things `PATCH /api/tenants/{name}`
 * can change -- quota and hosting domains.
 *
 * Partial-update semantics: an omitted field leaves the current value alone (see
 * UpdateTenantRequest's Javadoc). This form therefore always sends both fields it edits, so
 * "cleared the domain list" and "did not touch the domain list" cannot be confused.
 */
import { ApusApiError } from '#core/utils/apiErrors'
import { parseQuotaBytes } from '#core/utils/storageUsage'
import { validateAllowedDomains } from '#core/utils/domainValidation'
import type { MetaItem } from '#design/components/MetaList.vue'
import type {
  PolicyEntryResponse,
  PolicyKeyResponse,
  TenantDirectoryResponse,
  DirectoryUserResponse,
  PasswordResetResponse
} from '#core/utils/apiTypes'

const route = useRoute()
const name = computed(() => String(route.params.name ?? ''))

const { tenants, loading, error, refresh } = useTenants()
const tenant = computed(() => tenants.value.find(candidate => candidate.name === name.value) ?? null)

const storageQuota = ref('')
const maxObjects = ref<number | null>(null)
const domainsText = ref('')
const policyEntries = ref<PolicyEntryResponse[]>([])
const knownKeys = ref<PolicyKeyResponse[]>([])
const dirty = ref(false)

// The catalogue is the same for every tenant and never changes within a session, so it is read
// once. Its failure is not the page's failure: without it the editor loses its descriptions and
// type hints, which is a smaller loss than refusing to render the options at all.
onMounted(async () => {
  try {
    knownKeys.value = await api.listPolicyKeys()
  } catch {
    knownKeys.value = []
  }
})

// Seed the form once the tenant arrives, but never overwrite edits in progress -- a refresh
// landing mid-typing that silently reverted someone's input would be maddening.
watch(tenant, current => {
  if (!current || dirty.value) return
  storageQuota.value = current.storage.quota ?? ''
  maxObjects.value = current.storage.maxObjects
  domainsText.value = current.allowedHostingDomains.join('\n')
  policyEntries.value = current.policy.map(entry => ({ ...entry }))
}, { immediate: true })

const domains = computed(() =>
  domainsText.value.split('\n').map(line => line.trim()).filter(line => line.length > 0)
)

const problem = computed<string | null>(() => {
  if (storageQuota.value.trim() && parseQuotaBytes(storageQuota.value) === null) {
    return 'Storage quota must be a size such as 50Gi, 200G or 1Ti.'
  }
  if (maxObjects.value !== null && maxObjects.value < 0) return 'Object limit cannot be negative.'
  return validateAllowedDomains(domains.value).error
})

const saving = ref(false)
const saveError = ref<string | null>(null)
const saved = ref(false)

const api = useApiClient()

async function save(): Promise<void> {
  if (problem.value) return
  saving.value = true
  saveError.value = null
  saved.value = false
  try {
    await api.updateTenant(name.value, {
      storageQuota: storageQuota.value.trim() || null,
      maxObjects: maxObjects.value,
      allowedHostingDomains: domains.value,
      // A present list replaces every entry, which is exactly what this form holds.
      policy: policyEntries.value.map(entry => ({
        key: entry.key,
        type: entry.type,
        value: entry.value,
        locked: entry.locked
      }))
    })
    dirty.value = false
    saved.value = true
    await refresh()
  } catch (caught) {
    saveError.value = caught instanceof ApusApiError ? caught.message : 'Could not save this tenant.'
  } finally {
    saving.value = false
  }
}

// -- Teams and people ------------------------------------------------------------------------
// Loaded separately from the tenant itself, and its failure is its own: the API answers 200 with
// `unavailableReason` when the identity provider is unreachable, so this only ever throws when
// something is genuinely wrong with the request -- which must not take the rest of the page down.
const tenantDirectory = ref<TenantDirectoryResponse | null>(null)
const directoryError = ref<string | null>(null)
const temporaryPassword = ref<PasswordResetResponse | null>(null)

async function loadDirectory(): Promise<void> {
  if (!name.value) return
  try {
    tenantDirectory.value = await api.getTenantDirectory(name.value)
    directoryError.value = null
  } catch (caught) {
    tenantDirectory.value = null
    directoryError.value = caught instanceof ApusApiError
      ? caught.message
      : 'Could not load this tenant\'s teams and people.'
  }
}

watch(name, loadDirectory, { immediate: true })

/**
 * Team membership, fetched one team at a time when somebody opens it. Not up front: that would
 * be one request per team on every page load, for a list most people only scan.
 */
const teamMembers = ref<Record<string, DirectoryUserResponse[]>>({})

async function loadTeamMembers(teamId: string): Promise<void> {
  try {
    teamMembers.value = { ...teamMembers.value, [teamId]: await api.getTeamMembers(name.value, teamId) }
  } catch (caught) {
    directoryError.value = caught instanceof ApusApiError ? caught.message : 'Could not load that team.'
  }
}

async function createTeam(displayName: string): Promise<void> {
  try {
    await api.createTeam(name.value, { displayName })
    await loadDirectory()
  } catch (caught) {
    directoryError.value = caught instanceof ApusApiError ? caught.message : 'Could not create that team.'
  }
}

async function invite(payload: { email: string, displayName: string }): Promise<void> {
  try {
    await api.inviteUser(name.value, { email: payload.email, displayName: payload.displayName || null })
    await loadDirectory()
  } catch (caught) {
    directoryError.value = caught instanceof ApusApiError ? caught.message : 'Could not send that invitation.'
  }
}

async function resetPassword(user: DirectoryUserResponse): Promise<void> {
  try {
    temporaryPassword.value = await api.resetPassword(name.value, user.id)
    directoryError.value = null
  } catch (caught) {
    directoryError.value = caught instanceof ApusApiError ? caught.message : 'Could not reset that password.'
  }
}

const metadata = computed<MetaItem[]>(() => {
  const current = tenant.value
  if (!current) return []
  return [
    { label: 'Namespace', value: current.namespace },
    { label: 'Object store user', value: current.objectStoreUser },
    { label: 'Storage used', value: current.usage.usedLabel },
    { label: 'Storage quota', value: current.usage.quotaLabel }
  ]
})
</script>

<template>
  <PlatformGate>
    <div class="mx-auto flex max-w-3xl flex-col gap-8 p-6 sm:p-10">
      <ErrorState
        v-if="error"
        :status="error.status"
        :message="error.message"
        retryable
        @retry="refresh"
      />

      <p v-else-if="loading" class="text-muted text-sm">
        Loading…
      </p>

      <EmptyState
        v-else-if="!tenant"
        title="No such tenant"
        description="Nothing on this platform goes by that name."
      >
        <template #action>
          <UButton to="/tenants" size="sm" variant="subtle">
            Back to tenants
          </UButton>
        </template>
      </EmptyState>

      <template v-else>
        <PageHeader eyebrow="Tenant" :title="tenant.name" :description="tenant.displayName || undefined" />

        <section class="flex flex-col gap-3">
          <SectionLabel as="h2">
            Storage
          </SectionLabel>
          <div class="border-default flex flex-col gap-3 border p-6">
            <CellMeter
              v-if="tenant.usage.ratio !== null"
              :percent="tenant.usage.ratio * 100"
              :cells="24"
              :tone="tenant.usage.level === 'critical' ? 'error' : tenant.usage.level === 'warning' ? 'warning' : 'primary'"
              :label="`${tenant.name} storage use`"
            />
            <p class="apus-value text-muted text-sm">
              {{ tenant.usage.usedLabel }} of {{ tenant.usage.quotaLabel }}
            </p>
            <!-- Worth stating plainly: this number is observed, not enforced here, and the
                 consequence of reaching it is harsher than a warning. -->
            <p class="text-muted text-sm">
              Usage is observed from object storage; the quota itself is enforced by Ceph. A tenant
              at its limit fails renders with no retry.
            </p>
          </div>
        </section>

        <section class="flex flex-col gap-5">
          <SectionLabel as="h2">
            Limits and domains
          </SectionLabel>

          <UFormField label="Storage quota" name="storageQuota" help="A size such as 50Gi. Leave empty for no ceiling.">
            <UInput v-model="storageQuota" class="apus-value" @update:model-value="dirty = true" />
          </UFormField>

          <UFormField label="Object limit" name="maxObjects" help="Maximum number of stored objects. Leave empty for no limit.">
            <UInput v-model.number="maxObjects" type="number" min="0" class="apus-value" @update:model-value="dirty = true" />
          </UFormField>

          <UFormField label="Allowed hosting domains" name="domains">
            <PlatformDomainEditor v-model="domainsText" @update:model-value="dirty = true" />
          </UFormField>

          <p v-if="problem" class="border-warning/40 bg-warning/5 text-muted border p-3 text-sm">
            {{ problem }}
          </p>
          <p v-if="saveError" class="border-error/40 bg-error/5 text-error border p-3 text-sm">
            {{ saveError }}
          </p>
          <p v-else-if="saved" class="text-success text-sm">
            Saved.
          </p>

          <div>
            <UButton size="sm" :disabled="problem !== null" :loading="saving" @click="save">
              Save changes
            </UButton>
          </div>
        </section>

        <PlatformPolicyEditor v-model="policyEntries" :known-keys="knownKeys" @update:model-value="dirty = true" />

        <PlatformTenantDirectory
          v-if="tenantDirectory"
          :tenant="tenant.name"
          :directory="tenantDirectory"
          :can-write="true"
          :members="teamMembers"
          @create-team="createTeam"
          @invite="invite"
          @reset-password="resetPassword"
          @load-members="loadTeamMembers"
        />

        <p v-if="directoryError" class="border-error/40 bg-error/5 text-error border p-3 text-sm">
          {{ directoryError }}
        </p>

        <!-- Shown once and never again: it exists here and nowhere else. -->
        <div
          v-if="temporaryPassword"
          class="border-warning/40 bg-warning/5 flex flex-col gap-2 border p-4"
        >
          <p class="text-highlighted text-sm">
            Temporary password for {{ temporaryPassword.userId }} — copy it now, it is not shown again.
          </p>
          <p class="apus-value text-highlighted text-sm break-all">
            {{ temporaryPassword.temporaryPassword }}
          </p>
          <div>
            <UButton size="sm" variant="subtle" @click="temporaryPassword = null">
              Done
            </UButton>
          </div>
        </div>

        <PlatformImpersonationPanel :tenant="tenant.name" :users="tenantDirectory?.users ?? []" />

        <PlatformRedirectUris :uris="tenant.redirectUris" />

        <section class="flex flex-col gap-3">
          <SectionLabel as="h2">
            Details
          </SectionLabel>
          <MetaList :items="metadata" />
        </section>

        <section v-if="tenant.conditions.length" class="flex flex-col gap-3">
          <SectionLabel as="h2">
            Conditions
          </SectionLabel>
          <ul class="flex flex-col gap-2">
            <li
              v-for="condition in tenant.conditions"
              :key="condition.type"
              class="border-default flex flex-col gap-1 border p-3"
            >
              <span class="apus-value text-highlighted text-sm">
                {{ condition.type }} = {{ condition.status }}
              </span>
              <span v-if="condition.message" class="text-muted text-sm">{{ condition.message }}</span>
            </li>
          </ul>
        </section>
      </template>
    </div>
  </PlatformGate>
</template>
