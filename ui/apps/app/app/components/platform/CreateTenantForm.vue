<script setup lang="ts">
// Create-tenant form for the platform dashboard (design spec §11.2, and the explicit
// requirement behind this whole task: "We want, as operators, a management
// dashboard where we can set a maximum storage limit per customer"). Quota and allowed
// domains set here can be changed later too, via each tenant's "Edit quota / domains" control in
// PlatformTenantList.vue (backed by `PATCH /api/tenants/{name}`).
import type { FormError, FormSubmitEvent } from '@nuxt/ui'
import type { CreateTenantRequest } from '#core/utils/apiTypes'
import { ApusApiError } from '#core/utils/apiErrors'
import { validateAllowedDomains } from '#core/utils/domainValidation'
import { parseQuotaBytes } from '#core/utils/storageUsage'

const emit = defineEmits<{
  created: []
}>()

interface FormState {
  name: string
  displayName: string
  storageQuota: string
  maxObjects: number | null
  allowedHostingDomains: string[]
}

function emptyState(): FormState {
  return {
    name: '',
    displayName: '',
    storageQuota: '',
    maxObjects: null,
    allowedHostingDomains: []
  }
}

const state = reactive<FormState>(emptyState())
const submitting = ref(false)
const submitError = ref<string | null>(null)

/** Kubernetes object name rules (RFC 1123 label): lowercase alphanumeric and hyphens only. */
const TENANT_NAME_PATTERN = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/

function validate(input: FormState): FormError[] {
  const errors: FormError[] = []
  const name = input.name.trim()

  if (name.length === 0) {
    errors.push({ name: 'name', message: 'Tenant name is required.' })
  } else if (!TENANT_NAME_PATTERN.test(name)) {
    errors.push({
      name: 'name',
      message: 'Use lowercase letters, digits, and hyphens only, matching a Kubernetes resource name.'
    })
  }

  if (input.storageQuota.trim().length > 0 && parseQuotaBytes(input.storageQuota) === null) {
    errors.push({
      name: 'storageQuota',
      message: 'Use a Kubernetes-style quantity, e.g. "100Gi" or "500Mi".'
    })
  }

  const domainsResult = validateAllowedDomains(input.allowedHostingDomains)
  if (!domainsResult.valid) {
    errors.push({ name: 'allowedHostingDomains', message: domainsResult.error ?? 'Invalid domain.' })
  }

  return errors
}

const api = useApiClient()

async function onSubmit(event: FormSubmitEvent<FormState>) {
  submitting.value = true
  submitError.value = null

  const body: CreateTenantRequest = {
    name: event.data.name.trim(),
    displayName: event.data.displayName.trim().length > 0 ? event.data.displayName.trim() : null,
    storageQuota: event.data.storageQuota.trim().length > 0 ? event.data.storageQuota.trim() : null,
    maxObjects: event.data.maxObjects,
    allowedHostingDomains: event.data.allowedHostingDomains
  }

  try {
    await api.createTenant(body)
    Object.assign(state, emptyState())
    emit('created')
  } catch (error) {
    submitError.value = error instanceof ApusApiError ? error.message : 'Could not create the tenant.'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section aria-labelledby="create-tenant-heading" class="space-y-4">
    <h2 id="create-tenant-heading" class="text-lg font-medium">
      Create a tenant
    </h2>

    <UForm :state="state" :validate="validate" class="space-y-4" @submit="onSubmit">
      <UFormField label="Name" name="name" required help="Lowercase letters, digits, and hyphens -- this becomes the tenant's Kubernetes namespace.">
        <UInput v-model="state.name" placeholder="friends-server" />
      </UFormField>

      <UFormField label="Display name" name="displayName" help="Shown in the dashboard; falls back to the name above when left blank.">
        <UInput v-model="state.displayName" placeholder="Friends Server" />
      </UFormField>

      <UFormField
        label="Storage quota"
        name="storageQuota"
        help='Kubernetes-style quantity, e.g. "100Gi". Enforced by the storage backend (Ceph), not by Apus -- leave blank to use the platform default.'
      >
        <UInput v-model="state.storageQuota" placeholder="100Gi" />
      </UFormField>

      <UFormField label="Max objects" name="maxObjects" help="Optional cap on the number of stored objects, in addition to the byte quota above.">
        <UInputNumber v-model="state.maxObjects" :min="0" placeholder="No limit" />
      </UFormField>

      <UFormField
        label="Allowed hosting domains"
        name="allowedHostingDomains"
        help='Hostnames this tenant may claim for hosted maps, e.g. "maps.friends.example.net" or "*.friends.example.net". Leave empty to allow no hosting yet -- never use a bare "*", which would let this tenant claim any hostname on the platform.'
      >
        <UInputTags v-model="state.allowedHostingDomains" placeholder="maps.example.net" />
      </UFormField>

      <UAlert v-if="submitError" color="error" variant="subtle" :title="submitError" />

      <UButton type="submit" :loading="submitting">
        Create tenant
      </UButton>
    </UForm>
  </section>
</template>
