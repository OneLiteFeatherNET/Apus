<script setup lang="ts">
/**
 * Connecting a source, as four short steps instead of one long form.
 *
 * `CreateWorldSourceRequest` is a four-way union: which fields exist at all depends on the type.
 * Rendered as a single form that was a page of conditionally-visible inputs, where the reader had
 * to work out which half applied to them. Splitting it means the type decision -- the only one
 * that genuinely branches -- is made first, alone, and every later step shows only what that
 * choice implies.
 *
 * Nothing is created until the last step: one POST /api/sources, after a review. A wizard that
 * writes as it goes would leave half-configured sources behind whenever someone changed their
 * mind on step three.
 */
import { ApusApiError } from '#core/utils/apiErrors'
import { parseDurationSeconds } from '#core/utils/policy'
import type { CreateWorldSourceRequest } from '#core/utils/apiTypes'

type SourceType = CreateWorldSourceRequest['type']

const TYPES: { value: SourceType, label: string, when: string }[] = [
  { value: 's3', label: 'S3 bucket', when: 'Your world files already sit in object storage, or a backup job puts them there.' },
  { value: 'pterodactyl', label: 'Pterodactyl panel', when: 'Your server is managed by a Pterodactyl panel and Apus can pull straight from it.' },
  { value: 'push', label: 'Push from the server', when: 'The Apus plugin runs on your Paper server and pushes worlds as they are saved.' },
  { value: 'upload', label: 'Manual upload', when: 'You will upload an archive yourself whenever the world should be updated.' }
]

// The platform may restrict what this tenant is allowed to choose. Offering a type it cannot
// create, only to refuse it three steps later, is exactly the flow the policy exists to avoid.
const { sourceTypes, pollMinimumSeconds, keepVersionsMaximum } = useTenantPolicy()

const offeredTypes = computed(() =>
  sourceTypes.value === null ? TYPES : TYPES.filter(entry => sourceTypes.value!.includes(entry.value))
)

/** Seconds back into the spelling the field expects, so the message names a value you can type. */
function formatSeconds(total: number): string {
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  return [hours ? `${hours}h` : '', minutes ? `${minutes}m` : '', seconds ? `${seconds}s` : '']
    .filter(part => part !== '')
    .join('') || '0s'
}

const step = ref(1)
const submitting = ref(false)
const formError = ref<string | null>(null)
const stepError = ref<string | null>(null)

const type = ref<SourceType>('s3')
const name = ref('')
const poll = ref('5m')
const keepVersions = ref(3)
// Empty strings rather than nulls while editing: an input's model cannot be null, and converting
// once at submit time (buildRequest) keeps that detail out of every field binding.
interface WorldDraft { name: string, layout: string, minecraftVersion: string }
const worlds = ref<WorldDraft[]>([{ name: 'world', layout: '', minecraftVersion: '' }])

const s3 = reactive({ endpoint: '', bucket: '', prefix: '', credentialsSecretName: '' })
const pterodactyl = reactive({ panelUrl: '', serverId: '', select: '', credentialsSecretName: '' })

const selectedType = computed(() => TYPES.find(entry => entry.value === type.value)!)

/** Kubernetes object names: this becomes a resource name, so the API will refuse anything else. */
const NAME_PATTERN = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/

function validateStep(): string | null {
  if (step.value === 1 && sourceTypes.value !== null && !sourceTypes.value.includes(type.value)) {
    return 'This source type is not available for your tenant.'
  }
  if (step.value === 2) {
    if (!name.value) return 'Give the source a name.'
    if (!NAME_PATTERN.test(name.value)) {
      return 'Use lowercase letters, digits and hyphens only, starting and ending with a letter or digit.'
    }
    if (type.value === 's3') {
      if (!s3.endpoint) return 'Enter the endpoint of your object storage.'
      if (!s3.bucket) return 'Enter the bucket the world files are in.'
    }
    if (type.value === 'pterodactyl') {
      if (!pterodactyl.panelUrl) return 'Enter the address of your Pterodactyl panel.'
      if (!pterodactyl.serverId) return 'Enter the server ID to pull from.'
    }
  }
  if (step.value === 3) {
    if (worlds.value.some(world => !world.name)) return 'Every world needs a name.'
    if (keepVersions.value < 1) return 'Keep at least one snapshot.'
    // Checked here rather than only at submit so the reader learns the rule while still on the
    // field it applies to.
    const minimum = pollMinimumSeconds.value
    if (minimum !== null && poll.value) {
      const requested = parseDurationSeconds(poll.value)
      if (requested !== null && requested < minimum) {
        return `Your tenant's shortest permitted interval is ${formatSeconds(minimum)}.`
      }
    }
    const maximum = keepVersionsMaximum.value
    if (maximum !== null && keepVersions.value > maximum) {
      return `Your tenant may keep at most ${maximum} snapshots.`
    }
  }
  return null
}

function next(): void {
  const problem = validateStep()
  stepError.value = problem
  if (!problem) step.value += 1
}

function back(): void {
  stepError.value = null
  step.value -= 1
}

function addWorld(): void {
  worlds.value.push({ name: '', layout: '', minecraftVersion: '' })
}

function removeWorld(index: number): void {
  worlds.value.splice(index, 1)
}

function buildRequest(): CreateWorldSourceRequest {
  const base: CreateWorldSourceRequest = {
    name: name.value,
    type: type.value,
    poll: poll.value || null,
    worlds: worlds.value.map(world => ({
      name: world.name,
      layout: world.layout || null,
      minecraftVersion: world.minecraftVersion || null
    })),
    keepVersions: keepVersions.value
  }
  if (type.value === 's3') {
    base.s3 = {
      endpoint: s3.endpoint,
      bucket: s3.bucket,
      prefix: s3.prefix || null,
      credentialsSecretName: s3.credentialsSecretName || null
    }
  }
  if (type.value === 'pterodactyl') {
    base.pterodactyl = {
      panelUrl: pterodactyl.panelUrl,
      serverId: pterodactyl.serverId,
      select: pterodactyl.select || null,
      credentialsSecretName: pterodactyl.credentialsSecretName || null
    }
  }
  return base
}

const api = useApiClient()

async function submit(): Promise<void> {
  submitting.value = true
  formError.value = null
  try {
    await api.createSource(buildRequest())
    await navigateTo('/sources')
  } catch (caught) {
    formError.value = caught instanceof ApusApiError ? caught.message : 'Could not connect the source.'
  } finally {
    submitting.value = false
  }
}

const steps = ['Type', 'Connection', 'Worlds', 'Review']
</script>

<template>
  <div class="mx-auto flex max-w-3xl flex-col gap-8 p-6 sm:p-10">
    <PageHeader
      eyebrow="Source"
      title="Connect a source"
      description="Four steps. Nothing is created until the last one."
    />

    <ol class="flex flex-wrap items-center gap-3">
      <li v-for="(label, index) in steps" :key="label" class="flex items-center gap-2">
        <span
          class="apus-step border"
          :class="index + 1 <= step ? 'bg-primary border-primary' : 'border-accented'"
          aria-hidden="true"
        />
        <span
          class="apus-eyebrow"
          :class="index + 1 === step ? 'text-highlighted' : 'text-dimmed'"
          :aria-current="index + 1 === step ? 'step' : undefined"
        >{{ label }}</span>
      </li>
    </ol>

    <p v-if="stepError" class="border-error/40 bg-error/5 text-error border p-3 text-sm">
      {{ stepError }}
    </p>

    <!-- Step 1: the only genuinely branching decision, made alone and first. -->
    <fieldset v-if="step === 1" class="flex flex-col gap-3">
      <legend class="text-highlighted mb-2 text-base font-medium">
        Where do your world files come from?
      </legend>
      <p v-if="sourceTypes !== null" class="text-muted text-sm">
        Your platform limits which source types this tenant may use.
      </p>

      <label
        v-for="entry in offeredTypes"
        :key="entry.value"
        class="border-default hover:bg-muted flex cursor-pointer items-start gap-3 border p-4"
        :class="type === entry.value ? 'border-primary' : ''"
      >
        <input v-model="type" type="radio" :value="entry.value" class="accent-primary mt-1">
        <span class="flex flex-col gap-1">
          <span class="text-highlighted text-sm font-medium">{{ entry.label }}</span>
          <span class="text-muted text-sm">{{ entry.when }}</span>
        </span>
      </label>
    </fieldset>

    <!-- Step 2: only the fields this type actually has. -->
    <div v-else-if="step === 2" class="flex flex-col gap-5">
      <h2 class="text-highlighted text-base font-medium">
        {{ selectedType.label }} connection
      </h2>

      <UFormField label="Name" name="name" help="Lowercase letters, digits and hyphens. This becomes the resource name and cannot be changed later.">
        <UInput v-model="name" placeholder="survival" class="apus-value" />
      </UFormField>

      <template v-if="type === 's3'">
        <UFormField label="Endpoint" name="endpoint">
          <UInput v-model="s3.endpoint" placeholder="https://s3.example.net" class="apus-value" />
        </UFormField>
        <UFormField label="Bucket" name="bucket">
          <UInput v-model="s3.bucket" placeholder="worlds" class="apus-value" />
        </UFormField>
        <UFormField label="Prefix" name="prefix" help="Optional. Restricts Apus to one folder inside the bucket.">
          <UInput v-model="s3.prefix" placeholder="survival/" class="apus-value" />
        </UFormField>
        <UFormField label="Credentials secret" name="s3secret" help="Optional. The name of a Kubernetes Secret holding the access key. Apus never sees the key itself.">
          <UInput v-model="s3.credentialsSecretName" class="apus-value" />
        </UFormField>
      </template>

      <template v-else-if="type === 'pterodactyl'">
        <UFormField label="Panel address" name="panelUrl">
          <UInput v-model="pterodactyl.panelUrl" placeholder="https://panel.example.net" class="apus-value" />
        </UFormField>
        <UFormField label="Server ID" name="serverId">
          <UInput v-model="pterodactyl.serverId" class="apus-value" />
        </UFormField>
        <UFormField label="File selector" name="select" help="Optional. Which files to pull, if not the whole server directory.">
          <UInput v-model="pterodactyl.select" class="apus-value" />
        </UFormField>
        <UFormField label="Credentials secret" name="ptSecret" help="Optional. The name of a Kubernetes Secret holding the panel API key.">
          <UInput v-model="pterodactyl.credentialsSecretName" class="apus-value" />
        </UFormField>
      </template>

      <p v-else class="text-muted text-sm">
        Nothing else to configure. A {{ selectedType.label.toLowerCase() }} source waits for world
        data to arrive rather than going out to fetch it.
      </p>
    </div>

    <!-- Step 3: what to take, and how much to keep. -->
    <div v-else-if="step === 3" class="flex flex-col gap-5">
      <h2 class="text-highlighted text-base font-medium">
        Worlds and retention
      </h2>

      <div class="flex flex-col gap-3">
        <SectionLabel as="h3">
          Worlds
        </SectionLabel>
        <div
          v-for="(world, index) in worlds"
          :key="index"
          class="border-default flex flex-wrap items-end gap-3 border p-4"
        >
          <UFormField :label="`World ${index + 1}`" :name="`world-${index}`" class="flex-1">
            <UInput v-model="world.name" placeholder="world" class="apus-value" />
          </UFormField>
          <UFormField label="Layout" :name="`layout-${index}`" help="Optional. Detected automatically when left blank.">
            <UInput v-model="world.layout" placeholder="vanilla" class="apus-value" />
          </UFormField>
          <UButton
            v-if="worlds.length > 1"
            size="sm"
            variant="subtle"
            @click="removeWorld(index)"
          >
            Remove
          </UButton>
        </div>
        <UButton size="sm" variant="subtle" class="self-start" @click="addWorld">
          Add another world
        </UButton>
      </div>

      <UFormField label="Check every" name="poll" help="How often Apus looks for changes. Leave blank to only take snapshots when you ask for one.">
        <UInput v-model="poll" placeholder="5m" class="apus-value" />
      </UFormField>

      <UFormField label="Snapshots to keep" name="keepVersions" help="Older snapshots are deleted once this many newer ones exist.">
        <UInput v-model.number="keepVersions" type="number" min="1" class="apus-value" />
      </UFormField>
    </div>

    <!-- Step 4: exactly what is about to be created. -->
    <div v-else class="flex flex-col gap-5">
      <h2 class="text-highlighted text-base font-medium">
        Review
      </h2>
      <MetaList
        :items="[
          { label: 'Name', value: name },
          { label: 'Type', value: selectedType.label, prose: true },
          { label: 'Worlds', value: worlds.map(world => world.name).join(', ') },
          { label: 'Check every', value: poll || 'Only on request', prose: !poll },
          { label: 'Snapshots kept', value: String(keepVersions) },
          ...(type === 's3'
            ? [{ label: 'Bucket', value: `${s3.endpoint}/${s3.bucket}${s3.prefix ? `/${s3.prefix}` : ''}` }]
            : []),
          ...(type === 'pterodactyl'
            ? [{ label: 'Panel', value: `${pterodactyl.panelUrl} · ${pterodactyl.serverId}` }]
            : [])
        ]"
      />

      <p v-if="formError" class="border-error/40 bg-error/5 text-error border p-3 text-sm">
        {{ formError }}
      </p>
    </div>

    <div class="border-default flex items-center justify-between border-t pt-6">
      <UButton v-if="step > 1" variant="subtle" size="sm" @click="back">
        Back
      </UButton>
      <NuxtLink v-else to="/sources" class="text-muted hover:text-highlighted text-sm">
        Cancel
      </NuxtLink>

      <UButton v-if="step < 4" size="sm" @click="next">
        Continue
      </UButton>
      <UButton v-else size="sm" :loading="submitting" @click="submit">
        Connect source
      </UButton>
    </div>
  </div>
</template>

<style scoped>
.apus-step {
  width: var(--apus-cell);
  height: var(--apus-cell);
  border-radius: 0;
}
</style>
