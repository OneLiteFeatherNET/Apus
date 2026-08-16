import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import PolicyEditor from '~/components/platform/PolicyEditor.vue'
import type { PolicyEntryResponse } from '#core/utils/apiTypes'

function entry(key: string, enforced: boolean): PolicyEntryResponse {
  return { key, type: 'integer', value: '2', locked: true, enforced }
}

describe('PolicyEditor', () => {
  it('says in words when an entry will not be enforced', async () => {
    // The promise the whole design rests on: an option the API cannot enforce is stored and
    // shown and does nothing, and a lock switch that locks nothing has to admit it -- otherwise
    // an administrator sets one and relies on it.
    const wrapper = await mountSuspended(PolicyEditor, {
      props: { modelValue: [entry('render.concurrency.maximum', false)], knownKeys: [] }
    })

    expect(wrapper.text()).toContain('not enforced')
  })

  it('warns above the table too, so the state is visible without reading every row', async () => {
    const wrapper = await mountSuspended(PolicyEditor, {
      props: { modelValue: [entry('render.concurrency.maximum', false)], knownKeys: [] }
    })

    expect(wrapper.text()).toContain('needs a change in the api module')
  })

  it('does not cry wolf when every entry is enforced', async () => {
    const wrapper = await mountSuspended(PolicyEditor, {
      props: { modelValue: [entry('source.keepVersions.maximum', true)], knownKeys: [] }
    })

    expect(wrapper.text()).toContain('enforced')
    expect(wrapper.text()).not.toContain('not enforced')
  })

  it('says an empty policy means the tenant is unregulated, rather than showing a bare table', async () => {
    const wrapper = await mountSuspended(PolicyEditor, {
      props: { modelValue: [], knownKeys: [] }
    })

    expect(wrapper.text()).toContain('No options set')
  })

  it('shows a known option its own explanation from the API catalogue', async () => {
    // The description comes from the same registry the enforcement does, so the sentence beside
    // the input cannot drift from what the option actually does.
    const wrapper = await mountSuspended(PolicyEditor, {
      props: {
        modelValue: [entry('source.keepVersions.maximum', true)],
        knownKeys: [{
          key: 'source.keepVersions.maximum',
          type: 'integer',
          description: 'The most snapshots a tenant may keep per source.'
        }]
      }
    })

    expect(wrapper.text()).toContain('The most snapshots a tenant may keep per source.')
  })
})
