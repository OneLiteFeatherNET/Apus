<script setup lang="ts">
// The one page this foundation ships (design spec task scope, see ui/README.md): who is signed
// in, and which tenant they act as. The two dashboard levels built on top of this module get
// their own routes/pages elsewhere -- this is deliberately not one of them.
const { user, principal } = useAuth()

const roleLabels: Record<string, string> = {
  'platform-admin': 'Platform admin',
  'tenant-owner': 'Tenant owner',
  'tenant-operator': 'Tenant operator',
  'tenant-viewer': 'Tenant viewer'
}
</script>

<template>
  <div class="max-w-xl space-y-6">
    <h1 class="text-2xl font-semibold">
      Account
    </h1>

    <UCard>
      <template #header>
        <h2 class="font-medium">
          Signed in as
        </h2>
      </template>

      <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
        <dt class="text-muted">
          Subject
        </dt>
        <dd>{{ principal?.subject ?? user?.profile.sub ?? '—' }}</dd>

        <dt class="text-muted">
          Email
        </dt>
        <dd>{{ user?.profile.email ?? '—' }}</dd>

        <dt class="text-muted">
          Tenant
        </dt>
        <dd>{{ principal?.tenant ?? 'none (platform-level account)' }}</dd>

        <dt class="text-muted">
          Roles
        </dt>
        <dd>
          <span v-if="!principal?.roles.length">none</span>
          <ul v-else class="flex flex-wrap gap-2">
            <li v-for="role in principal.roles" :key="role">
              <UBadge variant="subtle">
                {{ roleLabels[role] ?? role }}
              </UBadge>
            </li>
          </ul>
        </dd>
      </dl>
    </UCard>
  </div>
</template>
