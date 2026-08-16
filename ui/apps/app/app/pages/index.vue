<script setup lang="ts">
/**
 * The application's front page: every world, and where each one stands end to end.
 *
 * It replaces a dashboard of counts. Nobody opens this product to learn how many sources they
 * have; they open it to find out whether their map is online, and if not, what is holding it up.
 * The compact pipeline rail answers that in one row per world, without a click.
 *
 * The empty states below are load-bearing rather than polish. Maps and hostings cannot be created
 * from any UI -- the api module has no endpoint for it, they are declared through GitOps -- so a
 * tenant who has connected a source and is waiting for a map would otherwise sit in front of a
 * blank page with a button that cannot exist. Saying what happens next is the only honest option.
 */
// Types are not auto-imported the way components and composables are -- Nuxt registers values,
// and an interface exported from a .vue file is not one.
import type { World } from '#core/utils/worlds'
import type { DataTableColumn } from '#design/components/DataTable.vue'

const { worlds, loading, error, partial, refresh } = useWorlds()

// A signed-in account with no tenant role -- a platform admin, most often -- can reach this page
// but nothing on it. Saying so beats an empty header above an empty table, and beats four nav
// links that would 403 one after another. Convenience only, as ever: the api module enforces it.
const { principal } = useAuth()
const hasTenantAccess = computed(() => canReadTenant(principal.value))

const api = useApiClient()
const sourceCount = ref<number | null>(null)

// Only needed to tell "you have nothing at all" apart from "your source has not produced a map
// yet" -- two empty screens that deserve completely different sentences.
onMounted(async () => {
  try {
    sourceCount.value = (await api.listSources()).length
  } catch {
    sourceCount.value = null
  }
})

const columns: DataTableColumn[] = [
  { key: 'name', label: 'World' },
  { key: 'pipeline', label: 'Pipeline' },
  { key: 'state', label: 'State' },
  { key: 'url', label: 'Public map', secondary: true }
]

const rows = computed(() => worlds.value.map(world => ({ ...world, id: world.name })))

/** The one sentence a row is worth reducing to: the first stage that is not finished. */
function currentState(world: World): string {
  const failed = world.stages.find(stage => stage.state === 'failed')
  if (failed) return `${failed.label} failed`
  const active = world.stages.find(stage => stage.state === 'active')
  if (active) return `${active.label} in progress`
  const waiting = world.stages.find(stage => stage.state === 'pending')
  if (waiting) return `Waiting on ${waiting.label.toLowerCase()}`
  return 'Live'
}
</script>

<template>
  <div class="mx-auto flex max-w-6xl flex-col gap-8 p-6 sm:p-10">
    <PageHeader
      eyebrow="Worlds"
      title="Your worlds"
      description="Each world runs from a source snapshot through a render to a public map. This is where every one of them stands."
    >
      <template #actions>
        <UButton size="sm" variant="subtle" :loading="loading" @click="refresh">
          Refresh
        </UButton>
      </template>
    </PageHeader>

    <EmptyState
      v-if="!hasTenantAccess"
      title="No tenant to show"
      description="Your account can sign in but is not a member of any tenant, so there are no worlds here. If you administer the platform, the console is the surface you want."
    >
      <template #action>
        <UButton to="/account" size="sm" variant="subtle">
          See your access
        </UButton>
      </template>
    </EmptyState>

    <ErrorState
      v-else-if="error"
      :status="error.status"
      :message="error.message"
      retryable
      @retry="refresh"
    />

    <template v-else>
      <p v-if="partial.length" class="border-warning/40 bg-warning/5 text-muted border p-3 text-sm">
        Some of this could not be loaded ({{ partial.join(', ') }}), so parts of the pipeline below
        may be incomplete. Everything else is current.
      </p>

      <EmptyState
        v-if="!loading && worlds.length === 0 && sourceCount === 0"
        title="Connect a source to get started"
        description="Apus watches a source — an S3 bucket, a Pterodactyl server, an upload or a push from your game server — and takes a snapshot of your world whenever it changes. Nothing else can happen until there is one."
      >
        <template #action>
          <UButton to="/sources/new" size="sm">
            Connect a source
          </UButton>
        </template>
      </EmptyState>

      <EmptyState
        v-else-if="!loading && worlds.length === 0"
        title="No maps yet"
        description="Your source is connected and Apus is collecting snapshots. Maps are declared by the platform rather than from here, so the next step is with your administrator — once a map exists for this tenant, it appears on this page."
      >
        <template #action>
          <UButton to="/sources" size="sm" variant="subtle">
            Check your sources
          </UButton>
        </template>
      </EmptyState>

      <DataTable
        v-else
        :columns="columns"
        :rows="rows"
        row-key="id"
        :loading="loading"
        caption="Your worlds and the state of each stage of their pipeline"
      >
        <template #cell-name="{ row }">
          <NuxtLink
            :to="`/worlds/${encodeURIComponent(row.name)}`"
            class="apus-value text-highlighted hover:text-primary"
          >
            {{ row.name }}
          </NuxtLink>
        </template>

        <template #cell-pipeline="{ row }">
          <PipelineRail :stages="row.stages" compact />
        </template>

        <template #cell-state="{ row }">
          <span class="text-muted text-sm">{{ currentState(row) }}</span>
        </template>

        <template #cell-url="{ row }">
          <a
            v-if="row.url"
            :href="row.url!"
            target="_blank"
            rel="noopener"
            class="apus-value text-primary text-sm hover:underline"
          >Open</a>
          <span v-else class="text-dimmed text-sm">—</span>
        </template>
      </DataTable>
    </template>
  </div>
</template>
