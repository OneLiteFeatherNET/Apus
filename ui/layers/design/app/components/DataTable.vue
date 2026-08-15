<script setup lang="ts">
/**
 * The dense table both applications use for lists of resources.
 *
 * A real <table>, not a grid of divs: the header-to-cell association a screen reader needs comes
 * free from the element, and reimplementing it with ARIA on divs is a well-known way to get it
 * subtly wrong.
 *
 * Loading renders skeleton rows rather than a spinner, so the page does not change height when
 * the data lands and nobody loses their place mid-scan.
 */
export interface DataTableColumn {
  key: string
  label: string
  /** Right-aligns the column. For numeric values, which read better on a shared decimal edge. */
  numeric?: boolean
  /** Hidden below the sm breakpoint. For columns a phone has no room for. */
  secondary?: boolean
}

withDefaults(defineProps<{
  columns: DataTableColumn[]
  rows: Record<string, unknown>[]
  rowKey: string
  loading?: boolean
  caption?: string
}>(), { loading: false, caption: undefined })
</script>

<template>
  <div class="border-default overflow-x-auto border">
    <table class="w-full border-collapse text-left text-sm">
      <caption v-if="caption" class="sr-only">{{ caption }}</caption>
      <thead>
        <tr class="border-default bg-muted border-b">
          <th
            v-for="column in columns"
            :key="column.key"
            scope="col"
            class="apus-eyebrow text-dimmed px-4 py-2.5 font-normal"
            :class="[column.numeric ? 'text-right' : '', column.secondary ? 'hidden sm:table-cell' : '']"
          >
            {{ column.label }}
          </th>
        </tr>
      </thead>

      <tbody v-if="loading">
        <tr v-for="row in 3" :key="row" class="border-default border-b last:border-0">
          <td v-for="column in columns" :key="column.key" class="px-4 py-3">
            <span class="bg-accented block h-3 w-24 max-w-full" />
            <span class="sr-only">Loading</span>
          </td>
        </tr>
      </tbody>

      <tbody v-else-if="rows.length === 0">
        <tr>
          <td :colspan="columns.length" class="px-4 py-8">
            <slot name="empty" />
          </td>
        </tr>
      </tbody>

      <tbody v-else>
        <tr
          v-for="row in rows"
          :key="String(row[rowKey])"
          class="border-default hover:bg-muted border-b last:border-0"
        >
          <td
            v-for="column in columns"
            :key="column.key"
            class="px-4 py-3 align-middle"
            :class="[column.numeric ? 'text-right' : '', column.secondary ? 'hidden sm:table-cell' : '']"
          >
            <slot :name="`cell-${column.key}`" :row="row">
              {{ row[column.key] }}
            </slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
