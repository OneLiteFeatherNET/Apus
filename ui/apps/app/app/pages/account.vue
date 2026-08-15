<script setup lang="ts">
// Who is signed in, and which tenant they act as. A reference page, not a destination -- it
// lives in the account menu rather than the main navigation, because nobody opens this product
// to read their own subject claim.
import type { MetaItem } from '#design/components/MetaList.vue'

const { user, principal } = useAuth()

const roleLabels: Record<string, string> = {
  'platform-admin': 'Platform admin',
  'tenant-owner': 'Tenant owner',
  'tenant-operator': 'Tenant operator',
  'tenant-viewer': 'Tenant viewer'
}

const items = computed<MetaItem[]>(() => [
  { label: 'Subject', value: principal.value?.subject ?? user.value?.profile.sub ?? null },
  { label: 'Email', value: user.value?.profile.email ?? null },
  {
    label: 'Tenant',
    value: principal.value?.tenant ?? 'No tenant (platform-level account)',
    prose: principal.value?.tenant == null
  }
])
</script>

<template>
  <div class="mx-auto flex max-w-3xl flex-col gap-8 p-6 sm:p-10">
    <PageHeader
      eyebrow="Account"
      title="Your access"
      description="What Apus knows about you, and what your roles let you do. Roles are granted by your identity provider, not here."
    />

    <MetaList :items="items" />

    <div class="flex flex-col gap-3">
      <SectionLabel as="h2">
        Roles
      </SectionLabel>
      <p v-if="!principal?.roles.length" class="text-muted text-sm">
        No Apus roles. You can sign in, but every request will be refused until an administrator
        grants you one.
      </p>
      <ul v-else class="flex flex-wrap gap-2">
        <li v-for="role in principal.roles" :key="role">
          <span class="border-default apus-value text-highlighted border px-2 py-0.5 text-xs">
            {{ roleLabels[role] ?? role }}
          </span>
        </li>
      </ul>
    </div>
  </div>
</template>
