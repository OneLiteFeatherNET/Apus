<script setup lang="ts">
/**
 * One world, whole.
 *
 * The rail is the page's thesis and sits at the top; everything below is in the order people ask
 * for it: is it online (the URL), what is happening now (the live render), what happened before
 * (the history), and finally the configuration behind it all.
 *
 * Starting a render deliberately does not navigate away. The reader's attention is on the button
 * they just pressed, and moving them to a list would make them find their way back to the thing
 * they were already looking at.
 */
import { ApusApiError } from '#core/utils/apiErrors'
import { formatTimestamp } from '#core/utils/formatTimestamp'
import type { BlueMapRenderResponse } from '#core/utils/apiTypes'
import type { World } from '#core/utils/worlds'
import type { DataTableColumn } from '#design/components/DataTable.vue'
import type { MetaItem } from '#design/components/MetaList.vue'

const route = useRoute()
const name = computed(() => String(route.params.name ?? ''))

const { worlds, loading, error, refresh } = useWorlds()
const world = computed<World | null>(() => worlds.value.find(candidate => candidate.name === name.value) ?? null)

const api = useApiClient()
// The platform may forbid forced renders for this tenant. Disabled rather than hidden: a control
// that vanishes leaves the reader wondering whether they misremembered it, while a disabled one
// with a sentence beside it answers the question.
const { forceRenderAllowed } = useTenantPolicy()
const starting = ref(false)
const actionError = ref<string | null>(null)
const confirmForce = ref(false)

async function startRender(force: boolean): Promise<void> {
  starting.value = true
  actionError.value = null
  try {
    await api.triggerRender(name.value, { force })
    confirmForce.value = false
    await refresh()
  } catch (caught) {
    actionError.value = caught instanceof ApusApiError ? caught.message : 'Could not start the render.'
  } finally {
    starting.value = false
  }
}

const liveRender = computed<BlueMapRenderResponse | null>(
  () => world.value?.renders.find(render => render.phase === 'Running') ?? null
)

const metadata = computed<MetaItem[]>(() => {
  const current = world.value
  if (!current) return []
  return [
    { label: 'Source', value: current.source?.name ?? current.map.source.sourceRef ?? null },
    { label: 'World', value: current.map.source.world },
    { label: 'Dimension', value: current.map.source.dimension },
    { label: 'Latest bundle', value: current.source?.latestBundle?.version ?? null },
    { label: 'BlueMap version', value: current.map.bluemap.version },
    { label: 'Minecraft version', value: current.map.bluemap.minecraftVersion },
    { label: 'Shards', value: String(current.map.shards) },
    { label: 'History limit', value: String(current.map.historyLimit) },
    { label: 'Bucket', value: current.map.bucket.name }
  ]
})

const historyColumns: DataTableColumn[] = [
  { key: 'name', label: 'Render' },
  { key: 'phase', label: 'Phase' },
  { key: 'startTime', label: 'Started', secondary: true },
  { key: 'progress', label: 'Progress', numeric: true }
]
</script>

<template>
  <div class="mx-auto flex max-w-5xl flex-col gap-10 p-6 sm:p-10">
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
      v-else-if="!world"
      title="No such world here"
      description="This map either does not exist or belongs to another tenant. Apus cannot tell you which — that is deliberate."
    >
      <template #action>
        <UButton to="/" size="sm" variant="subtle">
          Back to your worlds
        </UButton>
      </template>
    </EmptyState>

    <template v-else>
      <PageHeader eyebrow="World" :title="world.name">
        <template #actions>
          <UButton size="sm" :loading="starting" @click="startRender(false)">
            Start a render
          </UButton>
          <UButton
            size="sm"
            variant="subtle"
            :disabled="forceRenderAllowed === false"
            @click="confirmForce = true"
          >
            Force a full re-render
          </UButton>
        </template>
      </PageHeader>

      <p v-if="forceRenderAllowed === false" class="text-muted text-sm">
        Forced re-renders are not available for your tenant. Your platform administrator sets this.
      </p>

      <p v-if="actionError" class="text-error text-sm">
        {{ actionError }}
      </p>

      <section class="border-default border p-6">
        <PipelineRail :stages="world.stages" />
      </section>

      <section class="flex flex-col gap-3">
        <SectionLabel as="h2">
          Hosting
        </SectionLabel>
        <CopyField
          v-if="world.url"
          :value="world.url"
          :href="world.url"
          label="the public map URL"
        />
        <p v-else class="text-muted text-sm">
          Nothing serves this world yet. Once a hosting is assigned and ready, its public link
          appears here.
        </p>
      </section>

      <section v-if="liveRender" class="flex flex-col gap-3">
        <SectionLabel as="h2">
          Rendering now
        </SectionLabel>
        <div class="border-default flex flex-col gap-3 border p-6">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <NuxtLink
              :to="`/renders/${encodeURIComponent(liveRender.name)}`"
              class="apus-value text-primary text-sm hover:underline"
            >
              {{ liveRender.name }}
            </NuxtLink>
            <StatusPill :phase="liveRender.phase" />
          </div>
          <CellMeter
            :percent="liveRender.progress.percent"
            :live="true"
            label="Render progress"
          />
          <p class="text-muted text-sm">
            <template v-if="liveRender.progress.degraded">
              Progress reporting is degraded, so the percentage may lag. The render itself is
              unaffected.
            </template>
            <template v-else-if="liveRender.progress.etaSeconds > 0">
              About {{ Math.round(liveRender.progress.etaSeconds / 60) }} minutes left.
            </template>
          </p>
        </div>
      </section>

      <section class="flex flex-col gap-3">
        <SectionLabel as="h2">
          Render history
        </SectionLabel>
        <DataTable
          :columns="historyColumns"
          :rows="world.renders.map(render => ({ ...render, id: render.name }))"
          row-key="id"
          caption="Every render of this world, newest first"
        >
          <template #empty>
            <p class="text-muted text-sm">
              This world has never been rendered. Start one above once its bundle is ready.
            </p>
          </template>

          <template #cell-name="{ row }">
            <NuxtLink
              :to="`/renders/${encodeURIComponent(String(row.name))}`"
              class="apus-value text-highlighted hover:text-primary"
            >{{ row.name }}</NuxtLink>
          </template>

          <template #cell-phase="{ row }">
            <StatusPill :phase="row.phase" />
          </template>

          <template #cell-startTime="{ row }">
            <span class="apus-value text-muted text-xs">
              {{ formatTimestamp(row.startTime) }}
            </span>
          </template>

          <template #cell-progress="{ row }">
            <span class="apus-value text-muted text-xs">
              {{ row.progress.percent }}%
            </span>
          </template>
        </DataTable>
      </section>

      <section class="flex flex-col gap-3">
        <SectionLabel as="h2">
          Configuration
        </SectionLabel>
        <MetaList :items="metadata" />
      </section>

      <UModal v-model:open="confirmForce" title="Force a full re-render?">
        <template #body>
          <p class="text-muted text-sm">
            A forced render ignores the existing tiles and rebuilds this world from scratch. It
            takes as long as the first render did and counts against this world's history limit of
            {{ world.map.historyLimit }}, so an older render may be dropped to make room.
          </p>
        </template>
        <template #footer>
          <UButton variant="subtle" size="sm" @click="confirmForce = false">
            Cancel
          </UButton>
          <UButton size="sm" :loading="starting" @click="startRender(true)">
            Force re-render
          </UButton>
        </template>
      </UModal>
    </template>
  </div>
</template>
