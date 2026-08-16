import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TenantDirectory from '~/components/platform/TenantDirectory.vue'
import type { TenantDirectoryResponse } from '#core/utils/apiTypes'

function directory(overrides: Partial<TenantDirectoryResponse> = {}): TenantDirectoryResponse {
  return {
    teams: [{ id: 't1', displayName: 'Builders', memberCount: 3 }],
    users: [{ id: 'u1', displayName: 'Alice', email: 'alice@acme.example', privileged: false }],
    unavailableReason: null,
    ...overrides
  }
}

describe('TenantDirectory', () => {
  it('shows the teams and the people', async () => {
    const wrapper = await mountSuspended(TenantDirectory, {
      props: { tenant: 'acme', directory: directory(), canWrite: true }
    })

    expect(wrapper.text()).toContain('Builders')
    expect(wrapper.text()).toContain('Alice')
  })

  it('says a team of unknown size is unknown rather than showing a zero', async () => {
    // A zero that means "we could not count" is a lie somebody would act on.
    const wrapper = await mountSuspended(TenantDirectory, {
      props: {
        tenant: 'acme',
        directory: directory({ teams: [{ id: 't1', displayName: 'Builders', memberCount: null }] }),
        canWrite: true
      }
    })

    expect(wrapper.text()).not.toContain('0 members')
    expect(wrapper.text()).toContain('Unknown')
  })

  it('says why the directory is unavailable instead of showing an empty list', async () => {
    // An empty list reads as "this tenant has nobody in it". The reason is what stops somebody
    // going to read server logs for something the server already told us.
    const wrapper = await mountSuspended(TenantDirectory, {
      props: {
        tenant: 'acme',
        directory: directory({ teams: [], users: [], unavailableReason: 'the directory is unavailable' }),
        canWrite: true
      }
    })

    expect(wrapper.text()).toContain('the directory is unavailable')
    expect(wrapper.text()).not.toContain('No teams yet')
  })

  it('offers no password reset for a privileged account', async () => {
    // The API refuses regardless. This only spares somebody pressing a button to be told no.
    const wrapper = await mountSuspended(TenantDirectory, {
      props: {
        tenant: 'acme',
        directory: directory({
          users: [{ id: 'u-admin', displayName: 'Root', email: 'root@acme.example', privileged: true }]
        }),
        canWrite: true
      }
    })

    expect(wrapper.text()).toContain('Directory admin')
  })

  it('hides every changing action from someone who may only look', async () => {
    const wrapper = await mountSuspended(TenantDirectory, {
      props: { tenant: 'acme', directory: directory(), canWrite: false }
    })

    expect(wrapper.text()).not.toContain('Invite')
    expect(wrapper.text()).not.toContain('New team')
  })
})
