<script setup lang="ts">
/**
 * Creating a tenant. Its own page rather than a form stacked under the list, so an operator
 * answering an operational question never scrolls past it to get there.
 */
import { ApusApiError } from '#core/utils/apiErrors'
import { parseQuotaBytes } from '#core/utils/storageUsage'
import { validateAllowedDomains } from '#core/utils/domainValidation'

const name = ref('')
const displayName = ref('')
const storageQuota = ref('')
const maxObjects = ref<number | null>(null)
const domainsText = ref('')

const submitting = ref(false)
const formError = ref<string | null>(null)

/** Kubernetes object names: this becomes a namespace, so the API will refuse anything else. */
const NAME_PATTERN = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/

const domains = computed(() =>
  domainsText.value.split('\n').map(line => line.trim()).filter(line => line.length > 0)
)

const problem = computed<string | null>(() => {
  if (!name.value) return 'Give the tenant a name.'
  if (!NAME_PATTERN.test(name.value)) {
    return 'Use lowercase letters, digits and hyphens only, starting and ending with a letter or digit.'
  }
  if (storageQuota.value.trim() && parseQuotaBytes(storageQuota.value) === null) {
    return 'Storage quota must be a size such as 50Gi, 200G or 1Ti.'
  }
  if (maxObjects.value !== null && maxObjects.value < 0) return 'Object limit cannot be negative.'
  return validateAllowedDomains(domains.value).error
})

const api = useApiClient()

async function submit(): Promise<void> {
  if (problem.value) return
  submitting.value = true
  formError.value = null
  try {
    await api.createTenant({
      name: name.value,
      displayName: displayName.value || null,
      storageQuota: storageQuota.value.trim() || null,
      maxObjects: maxObjects.value,
      allowedHostingDomains: domains.value.length ? domains.value : null
    })
    await navigateTo('/tenants')
  } catch (caught) {
    formError.value = caught instanceof ApusApiError ? caught.message : 'Could not create the tenant.'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <PlatformGate>
    <div class="mx-auto flex max-w-2xl flex-col gap-8 p-6 sm:p-10">
      <PageHeader
        eyebrow="Tenant"
        title="Create a tenant"
        description="A tenant owns a namespace, a storage quota and the hostnames its maps may be published on."
      />

      <div class="flex flex-col gap-5">
        <UFormField label="Name" name="name" help="Becomes the namespace and cannot be changed later. Lowercase letters, digits and hyphens.">
          <UInput v-model="name" placeholder="acme" class="apus-value" />
        </UFormField>

        <UFormField label="Display name" name="displayName" help="Optional. What people call this tenant.">
          <UInput v-model="displayName" placeholder="ACME Community" />
        </UFormField>

        <UFormField label="Storage quota" name="storageQuota" help="Optional. A size such as 50Gi. Renders fail without retry once a tenant reaches it, so leaving it unset means no ceiling at all.">
          <UInput v-model="storageQuota" placeholder="50Gi" class="apus-value" />
        </UFormField>

        <UFormField label="Object limit" name="maxObjects" help="Optional. Maximum number of stored objects.">
          <UInput v-model.number="maxObjects" type="number" min="0" class="apus-value" />
        </UFormField>

        <UFormField label="Allowed hosting domains" name="domains">
          <PlatformDomainEditor v-model="domainsText" />
        </UFormField>
      </div>

      <p v-if="problem" class="border-warning/40 bg-warning/5 text-muted border p-3 text-sm">
        {{ problem }}
      </p>

      <p v-if="formError" class="border-error/40 bg-error/5 text-error border p-3 text-sm">
        {{ formError }}
      </p>

      <div class="border-default flex items-center justify-between border-t pt-6">
        <NuxtLink to="/tenants" class="text-muted hover:text-highlighted text-sm">
          Cancel
        </NuxtLink>
        <UButton size="sm" :disabled="problem !== null" :loading="submitting" @click="submit">
          Create tenant
        </UButton>
      </div>
    </div>
  </PlatformGate>
</template>
