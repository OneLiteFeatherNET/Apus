<script setup lang="ts">
import type { WorldSourceResponse } from '#core/utils/apiTypes'
import { formatTimestamp } from '#core/utils/formatTimestamp'

// Design spec §11.2: "Sources: list of the WorldSource, its status, when it was last checked,
// which bundle was produced last."
defineProps<{ sources: WorldSourceResponse[] }>()
</script>

<template>
  <p v-if="sources.length === 0" class="text-sm text-muted">
    No world sources configured for this tenant yet.
  </p>
  <div v-else class="overflow-x-auto">
    <table class="w-full text-left text-sm">
      <caption class="sr-only">
        World sources
      </caption>
      <thead>
        <tr class="border-b border-default">
          <th scope="col" class="py-2 pr-4 font-medium">
            Name
          </th>
          <th scope="col" class="py-2 pr-4 font-medium">
            Type
          </th>
          <th scope="col" class="py-2 pr-4 font-medium">
            State
          </th>
          <th scope="col" class="py-2 pr-4 font-medium">
            Last checked
          </th>
          <th scope="col" class="py-2 pr-4 font-medium">
            Latest bundle
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="source in sources" :key="source.name" class="border-b border-default/50">
          <th scope="row" class="py-2 pr-4 font-normal">
            {{ source.name }}
          </th>
          <td class="py-2 pr-4">
            {{ source.type }}
          </td>
          <td class="py-2 pr-4">
            <TenantConditionsBadgeList :conditions="source.conditions" />
          </td>
          <td class="py-2 pr-4">
            {{ formatTimestamp(source.lastPollTime) }}
          </td>
          <td class="py-2 pr-4">
            <span v-if="source.latestBundle">{{ source.latestBundle.version }}</span>
            <span v-else class="text-muted">none yet</span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
