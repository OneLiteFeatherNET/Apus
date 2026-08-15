import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import AppShell from '#design/components/AppShell.vue'
import DataTable from '#design/components/DataTable.vue'
import StatusPill from '#design/components/StatusPill.vue'

/**
 * The three accessibility promises a linter cannot check for us, and that a redesign is most
 * likely to break silently.
 */
describe('AppShell', () => {
  it('offers a skip link that actually targets the main region', async () => {
    const wrapper = await mountSuspended(AppShell, { slots: { default: () => 'content' } })

    const skip = wrapper.find('a[href="#main"]')
    expect(skip.exists()).toBe(true)
    expect(skip.text()).toBe('Skip to content')
    // A skip link pointing at an id nothing carries is worse than none: it looks like a feature
    // and lands the reader nowhere.
    expect(wrapper.find('#main').exists()).toBe(true)
  })

  it('renders exactly one main landmark', async () => {
    const wrapper = await mountSuspended(AppShell, { slots: { default: () => 'content' } })

    expect(wrapper.findAll('main')).toHaveLength(1)
  })

  it('makes the main region focusable without making it a tab stop', async () => {
    // Route changes move focus here (see the component). That needs tabindex="-1"; tabindex="0"
    // would add a stop to every page's tab order for no benefit.
    const wrapper = await mountSuspended(AppShell, { slots: { default: () => 'content' } })

    expect(wrapper.find('main').attributes('tabindex')).toBe('-1')
  })
})

describe('StatusPill', () => {
  it('always prints the phase, so colour is never the only signal', async () => {
    const wrapper = await mountSuspended(StatusPill, { props: { phase: 'Failed' } })

    expect(wrapper.text()).toContain('Failed')
  })

  it('says Unknown rather than inventing a phase the API did not send', async () => {
    const wrapper = await mountSuspended(StatusPill, { props: { phase: null } })

    expect(wrapper.text()).toContain('Unknown')
  })
})

describe('DataTable', () => {
  it('associates headers with cells through a real table element', async () => {
    const wrapper = await mountSuspended(DataTable, {
      props: {
        columns: [{ key: 'name', label: 'Name' }, { key: 'phase', label: 'Phase' }],
        rows: [{ name: 'atlas', phase: 'Succeeded' }],
        rowKey: 'name',
        caption: 'Worlds'
      }
    })

    expect(wrapper.findAll('th[scope="col"]')).toHaveLength(2)
    expect(wrapper.find('caption').text()).toBe('Worlds')
    expect(wrapper.text()).toContain('atlas')
  })

  it('shows the empty slot instead of a bare table when there are no rows', async () => {
    const wrapper = await mountSuspended(DataTable, {
      props: { columns: [{ key: 'name', label: 'Name' }], rows: [], rowKey: 'name' },
      slots: { empty: () => 'No worlds yet' }
    })

    expect(wrapper.text()).toContain('No worlds yet')
  })
})
