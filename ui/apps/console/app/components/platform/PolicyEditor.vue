<script setup lang="ts">
/**
 * The options set on one tenant, and which of them it may not deviate from.
 *
 * The column that matters most is the last one. An entry whose key the API does not enforce is
 * stored, returned and shown — and changes nothing. A lock switch that locks nothing has to admit
 * it in words, or an administrator will set one and rely on it. That is the single promise this
 * component exists to keep, and `policyEditor.nuxt.spec.ts` holds it.
 *
 * Known keys come from the API's own catalogue rather than a list kept here, so the form cannot
 * drift from what actually bites.
 */
import type { PolicyEntryRequest, PolicyEntryResponse, PolicyKeyResponse } from '#core/utils/apiTypes'
import type { DataTableColumn } from '#design/components/DataTable.vue'

const model = defineModel<PolicyEntryResponse[]>({ required: true })

const props = withDefaults(defineProps<{ knownKeys?: PolicyKeyResponse[] }>(), {
  knownKeys: () => []
})

const columns: DataTableColumn[] = [
  { key: 'key', label: 'Option' },
  { key: 'value', label: 'Value' },
  { key: 'locked', label: 'Locked' },
  { key: 'enforced', label: 'Effect' },
  { key: 'actions', label: '', secondary: true }
]

const rows = computed(() => model.value.map((entry, index) => ({ ...entry, id: `${index}-${entry.key}` })))

const newKey = ref('')

function describe(key: string): string | null {
  return props.knownKeys.find(known => known.key === key)?.description ?? null
}

function addEntry(): void {
  const key = newKey.value.trim()
  if (!key) return
  const known = props.knownKeys.find(candidate => candidate.key === key)
  model.value = [
    ...model.value,
    {
      key,
      // A known key must carry the type its enforcement expects; the API refuses anything else,
      // so offering a choice here would only produce a rejection later.
      type: known?.type ?? 'string',
      value: '',
      locked: false,
      // Optimistic only until the next read: the API recomputes it and is the authority.
      enforced: known !== undefined
    }
  ]
  newKey.value = ''
}

function removeEntry(index: number): void {
  model.value = model.value.filter((_, position) => position !== index)
}

function update(index: number, patch: Partial<PolicyEntryRequest>): void {
  model.value = model.value.map((entry, position) =>
    position === index ? { ...entry, ...patch } : entry
  )
}

const unenforcedCount = computed(() => model.value.filter(entry => !entry.enforced).length)
</script>

<template>
  <section class="flex flex-col gap-3">
    <SectionLabel as="h2">
      Options
    </SectionLabel>

    <p class="text-muted max-w-prose text-sm">
      Values this platform sets for the tenant. A locked option is refused by the API when the
      tenant tries to deviate from it; an unlocked one is a recommendation its forms are
      pre-filled with.
    </p>

    <p
      v-if="unenforcedCount > 0"
      class="border-warning/40 bg-warning/5 text-muted border p-3 text-sm"
    >
      {{ unenforcedCount }} of these {{ unenforcedCount === 1 ? 'options is' : 'options are' }} not
      enforced: Apus stores and shows {{ unenforcedCount === 1 ? 'it' : 'them' }} but nothing acts
      on {{ unenforcedCount === 1 ? 'it' : 'them' }}, whether locked or not. Enforcement for a new
      option needs a change in the api module.
    </p>

    <DataTable
      :columns="columns"
      :rows="rows"
      row-key="id"
      caption="Options set for this tenant"
    >
      <template #empty>
        <p class="text-muted text-sm">
          No options set. The tenant behaves exactly as it would with no policy at all.
        </p>
      </template>

      <template #cell-key="{ row }">
        <div class="flex flex-col gap-0.5">
          <span class="apus-value text-highlighted">{{ row.key }}</span>
          <span class="apus-value text-dimmed text-xs">{{ row.type }}</span>
          <span v-if="describe(row.key)" class="text-muted max-w-[36ch] text-xs">
            {{ describe(row.key) }}
          </span>
        </div>
      </template>

      <template #cell-value="{ row }">
        <UInput
          :model-value="row.value"
          class="apus-value"
          :placeholder="row.type === 'stringList' ? 's3, push' : ''"
          @update:model-value="value => update(rows.indexOf(row), { value: String(value) })"
        />
      </template>

      <template #cell-locked="{ row }">
        <UCheckbox
          :model-value="row.locked"
          :aria-label="`Lock ${row.key}`"
          @update:model-value="locked => update(rows.indexOf(row), { locked: Boolean(locked) })"
        />
      </template>

      <template #cell-enforced="{ row }">
        <span
          class="apus-value inline-flex items-center gap-1.5 border px-2 py-0.5 text-xs"
          :class="row.enforced ? 'border-success/40 text-success bg-success/10' : 'border-warning/40 text-warning bg-warning/10'"
        >
          <span
            class="size-1.5 shrink-0"
            aria-hidden="true"
            :class="row.enforced ? 'bg-success' : 'bg-warning'"
          />
          {{ row.enforced ? 'enforced' : 'not enforced' }}
        </span>
      </template>

      <template #cell-actions="{ row }">
        <UButton size="xs" variant="ghost" @click="removeEntry(rows.indexOf(row))">
          Remove
        </UButton>
      </template>
    </DataTable>

    <div class="flex flex-wrap items-end gap-3">
      <UFormField label="Add an option" name="newPolicyKey" class="min-w-64 flex-1">
        <UInput
          v-model="newKey"
          list="apus-policy-keys"
          placeholder="source.types.allowed"
          class="apus-value"
        />
        <!-- The catalogue as suggestions rather than a closed select: an unknown key is a
             legitimate thing to record, it is simply never enforced. -->
        <datalist id="apus-policy-keys">
          <option v-for="known in knownKeys" :key="known.key" :value="known.key" />
        </datalist>
      </UFormField>
      <UButton size="sm" variant="subtle" :disabled="!newKey.trim()" @click="addEntry">
        Add
      </UButton>
    </div>
  </section>
</template>
