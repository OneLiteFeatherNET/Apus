<script setup lang="ts">
import type { BlueMapHostingResponse } from '~/utils/apiTypes'

// Design spec §11.2: "Hosting: die URLs, unter denen Karten erreichbar sind."
const props = defineProps<{ hostings: BlueMapHostingResponse[] }>()
</script>

<template>
  <p v-if="props.hostings.length === 0" class="text-sm text-muted">
    Nothing is hosted for this tenant yet.
  </p>
  <div v-else class="overflow-x-auto">
    <table class="w-full text-left text-sm">
      <caption class="sr-only">
        Hosted maps
      </caption>
      <thead>
        <tr class="border-b border-default">
          <th scope="col" class="py-2 pr-4 font-medium">
            Name
          </th>
          <th scope="col" class="py-2 pr-4 font-medium">
            Maps
          </th>
          <th scope="col" class="py-2 pr-4 font-medium">
            URL
          </th>
          <th scope="col" class="py-2 pr-4 font-medium">
            Ready
          </th>
          <th scope="col" class="py-2 pr-4 font-medium">
            Replicas
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="hosting in props.hostings" :key="hosting.name" class="border-b border-default/50">
          <th scope="row" class="py-2 pr-4 font-normal">
            {{ hosting.name }}
          </th>
          <td class="py-2 pr-4">
            {{ hosting.maps.filter(Boolean).join(', ') || '—' }}
          </td>
          <td class="py-2 pr-4">
            <a v-if="hosting.url" :href="hosting.url" target="_blank" rel="noopener noreferrer" class="underline">
              {{ hosting.url }}
            </a>
            <span v-else class="text-muted">not available yet</span>
          </td>
          <td class="py-2 pr-4">
            <UBadge :color="hosting.ready ? 'success' : 'neutral'" variant="subtle">
              {{ hosting.ready ? 'Ready' : 'Not ready' }}
            </UBadge>
          </td>
          <td class="py-2 pr-4">
            {{ hosting.replicas }}
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
