<script setup lang="ts">
// Edits an existing tenant's storage quota and allowed hosting domains -- `PATCH
// /api/tenants/{name}` (api/src/main/java/net/onelitefeather/apus/api/rest/tenant/
// TenantController.java#update), platform-admin only, closing the gap design spec §10.3's role
// table already promised ("Tenants anlegen/ändern/löschen, Quotas") but the api module did not
// yet implement when the platform dashboard first shipped. `displayName` is deliberately not
// editable here -- `UpdateTenantRequest` does not carry it, see that record's own Javadoc.
import type { FormError, FormSubmitEvent } from '@nuxt/ui'
import type { TenantResponse, UpdateTenantRequest } from '~/utils/apiTypes'
import { ApusApiError } from '~/utils/apiErrors'
import { validateAllowedDomains } from '~/utils/domainValidation'
import { parseQuotaBytes } from '~/utils/storageUsage'

const props = defineProps<{
  tenant: TenantResponse
}>()

const emit = defineEmits<{
  updated: []
  cancel: []
}>()

interface FormState {
  storageQuota: string
  maxObjects: number | null
  allowedHostingDomains: string[]
}

function initialState(): FormState {
  return {
    storageQuota: props.tenant.storage.quota ?? '',
    maxObjects: props.tenant.storage.maxObjects,
    allowedHostingDomains: [...props.tenant.allowedHostingDomains]
  }
}

const state = reactive<FormState>(initialState())
const submitting = ref(false)
const submitError = ref<string | null>(null)

function validate(input: FormState): FormError[] {
  const errors: FormError[] = []

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

  const body: UpdateTenantRequest = {
    storageQuota: event.data.storageQuota.trim().length > 0 ? event.data.storageQuota.trim() : null,
    maxObjects: event.data.maxObjects,
    allowedHostingDomains: event.data.allowedHostingDomains
  }

  try {
    await api.updateTenant(props.tenant.name, body)
    emit('updated')
  } catch (error) {
    submitError.value = error instanceof ApusApiError ? error.message : 'Could not update the tenant.'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <UForm :state="state" :validate="validate" class="space-y-4" @submit="onSubmit">
    <UFormField
      label="Storage quota"
      name="storageQuota"
      help='Kubernetes-style quantity, e.g. "100Gi". Enforced by the storage backend (Ceph), not by Apus.'
    >
      <UInput v-model="state.storageQuota" placeholder="100Gi" />
    </UFormField>

    <UFormField label="Max objects" name="maxObjects" help="Optional cap on the number of stored objects, in addition to the byte quota above.">
      <UInputNumber v-model="state.maxObjects" :min="0" placeholder="No limit" />
    </UFormField>

    <UFormField
      label="Allowed hosting domains"
      name="allowedHostingDomains"
      help='Hostnames this tenant may claim for hosted maps. Never use a bare "*", which would let this tenant claim any hostname on the platform.'
    >
      <UInputTags v-model="state.allowedHostingDomains" placeholder="maps.example.net" />
    </UFormField>

    <UAlert v-if="submitError" color="error" variant="subtle" :title="submitError" />

    <div class="flex gap-2">
      <UButton type="submit" :loading="submitting">
        Save changes
      </UButton>
      <UButton variant="ghost" color="neutral" :disabled="submitting" @click="emit('cancel')">
        Cancel
      </UButton>
    </div>
  </UForm>
</template>
