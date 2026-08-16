<script setup lang="ts">
/**
 * The hostnames a tenant may publish maps on, one per line.
 *
 * Validation is `validateAllowedDomains` from layers/core -- already written, already tested, and
 * applying the same rules the API does. It reports the first problem it finds rather than a list,
 * so this shows exactly that: one specific complaint beats "invalid domains".
 */
import { validateAllowedDomains } from '#core/utils/domainValidation'

const model = defineModel<string>({ required: true })

/** Blank lines are how people separate groups while typing; they are not entries. */
const domains = computed(() =>
  model.value.split('\n').map(line => line.trim()).filter(line => line.length > 0)
)

const problem = computed(() => validateAllowedDomains(domains.value).error)

defineExpose({ domains, problem })
</script>

<template>
  <div class="flex flex-col gap-2">
    <UTextarea
      v-model="model"
      :rows="4"
      class="apus-value"
      placeholder="maps.example.net&#10;atlas.example.org"
    />
    <p class="text-muted text-xs">
      One hostname per line. Maps for this tenant can only be published on these.
    </p>
    <p v-if="problem" class="text-error text-xs">
      {{ problem }}
    </p>
  </div>
</template>
