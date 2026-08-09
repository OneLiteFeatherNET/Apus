<script setup lang="ts">
// Cluster-wide job visibility for the platform dashboard (design spec §11.2: "laufende Jobs
// clusterweit"). Not implemented as a live list: `GET /api/renders`
// (api/src/main/java/net/onelitefeather/apus/api/rest/render/BlueMapRenderController.java)
// always resolves through `TenantResolver.namespaceFor`, which reads a single tenant from the
// caller's own token claim and throws `ForbiddenException` when there is none -- exactly the
// case for a platform-admin account with no tenant of its own. There is no endpoint today that
// lists renders across every tenant's namespace. Rather than build a control that silently
// fails (or worse, quietly falls back to just this admin's own tenant and calls that "cluster-
// wide"), this notice says so plainly -- see this task's report for the upstream request.
</script>

<template>
  <section aria-labelledby="cluster-jobs-heading" class="space-y-4">
    <h2 id="cluster-jobs-heading" class="text-lg font-medium">
      Running jobs across tenants
    </h2>

    <UAlert
      color="neutral"
      variant="subtle"
      icon="i-lucide-construction"
      title="Cluster-wide job visibility is not available yet"
      description="The api module's render endpoint is scoped to one tenant's namespace at a time and has no cluster-wide listing today. This dashboard will not guess at a workaround (such as silently showing only this account's own tenant) -- it needs a platform-scoped endpoint from the api module first."
    />
  </section>
</template>
