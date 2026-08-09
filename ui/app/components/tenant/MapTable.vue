<script setup lang="ts">
import type { BlueMapMapResponse } from '~/utils/apiTypes'
import { ApusApiError } from '~/utils/apiErrors'

// Design spec §11.2: "Karten: Liste der BlueMapMap mit ihrem Zustand, und ein Weg, einen Render
// auszulösen." Triggering is gated on canWriteTenant() -- convenience only, see
// app/utils/role.ts's module doc: the api module re-checks this on every POST regardless, a
// tenant-viewer who somehow still called it would just get a 403 back.
const props = defineProps<{ maps: BlueMapMapResponse[] }>()

const { principal } = useAuth()
const canTrigger = computed(() => canWriteTenant(principal.value))

const api = useApiClient()
const toast = useToast()
const router = useRouter()
const pending = reactive<Record<string, boolean>>({})

async function trigger(map: BlueMapMapResponse): Promise<void> {
  pending[map.name] = true
  try {
    const render = await api.triggerRender(map.name)
    toast.add({ title: `Render started for "${map.name}"`, color: 'success' })
    // Straight to the render's own live-progress view -- this is the whole point of shipping
    // this UI (design spec §11.2's motivation: rendering used to mean "start and hope").
    await router.push(`/tenant/renders/${encodeURIComponent(render.name)}`)
  } catch (error) {
    const message = error instanceof ApusApiError ? error.message : 'Could not start the render.'
    toast.add({ title: 'Could not start render', description: message, color: 'error' })
  } finally {
    pending[map.name] = false
  }
}
</script>

<template>
  <p v-if="props.maps.length === 0" class="text-sm text-muted">
    No maps configured for this tenant yet.
  </p>
  <div v-else class="overflow-x-auto">
    <table class="w-full text-left text-sm">
      <caption class="sr-only">
        BlueMap maps
      </caption>
      <thead>
        <tr class="border-b border-default">
          <th scope="col" class="py-2 pr-4 font-medium">
            Name
          </th>
          <th scope="col" class="py-2 pr-4 font-medium">
            Source
          </th>
          <th scope="col" class="py-2 pr-4 font-medium">
            State
          </th>
          <th scope="col" class="py-2 pr-4 font-medium">
            Latest render
          </th>
          <th v-if="canTrigger" scope="col" class="py-2 pr-4 font-medium">
            <span class="sr-only">Actions</span>
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="map in props.maps" :key="map.name" class="border-b border-default/50">
          <th scope="row" class="py-2 pr-4 font-normal">
            {{ map.name }}
          </th>
          <td class="py-2 pr-4">
            {{ map.source.world ?? '—' }}<span v-if="map.source.dimension">/{{ map.source.dimension }}</span>
          </td>
          <td class="py-2 pr-4">
            <TenantConditionsBadgeList :conditions="map.conditions" />
          </td>
          <td class="py-2 pr-4">
            <NuxtLink
              v-if="map.latestRender.name"
              :to="`/tenant/renders/${encodeURIComponent(map.latestRender.name)}`"
              class="underline"
            >
              {{ map.latestRender.phase ?? map.latestRender.name }}
            </NuxtLink>
            <span v-else class="text-muted">none yet</span>
          </td>
          <td v-if="canTrigger" class="py-2 pr-4">
            <UButton
              size="sm"
              :loading="pending[map.name] ?? false"
              @click="trigger(map)"
            >
              Trigger render
            </UButton>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
