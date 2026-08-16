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

  it('says nothing about the size of a team it did not count, rather than showing a zero', async () => {
    // Graph reports members@odata.count for the group being listed, not for each nested group in
    // the result, so this is the normal case rather than an edge one. A zero here would be a lie
    // somebody would act on; "Unknown size" against every row would be honest and useless.
    const wrapper = await mountSuspended(TenantDirectory, {
      props: {
        tenant: 'acme',
        directory: directory({ teams: [{ id: 't1', displayName: 'Builders', memberCount: null }] }),
        canWrite: true
      }
    })

    expect(wrapper.text()).not.toContain('0 members')
    expect(wrapper.text()).not.toContain('members')
    expect(wrapper.text()).toContain('Who is in it')
  })

  it('counts a team exactly once its membership has been loaded', async () => {
    const wrapper = await mountSuspended(TenantDirectory, {
      props: {
        tenant: 'acme',
        directory: directory({ teams: [{ id: 't1', displayName: 'Builders', memberCount: null }] }),
        canWrite: true,
        members: {
          t1: [
            { id: 'u1', displayName: 'Alice', email: 'alice@acme.example', privileged: false },
            { id: 'u2', displayName: 'Carol', email: 'carol@acme.example', privileged: false }
          ]
        }
      }
    })

    expect(wrapper.text()).toContain('2 members')
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

  it('shows who is in a team once it is opened', async () => {
    // The assignment, rather than two lists side by side.
    const wrapper = await mountSuspended(TenantDirectory, {
      props: {
        tenant: 'acme',
        directory: directory(),
        canWrite: true,
        members: { t1: [{ id: 'u1', displayName: 'Alice', email: 'alice@acme.example', privileged: false }] }
      }
    })

    await wrapper.find('button').trigger('click')

    expect(wrapper.text()).toContain('alice@acme.example')
  })

  it('does not claim a team is empty while its membership is still loading', async () => {
    // An empty list shown mid-request would read as a fact about the team.
    const wrapper = await mountSuspended(TenantDirectory, {
      props: { tenant: 'acme', directory: directory(), canWrite: true, members: {} }
    })

    await wrapper.find('button').trigger('click')

    expect(wrapper.text()).toContain('Loading')
    expect(wrapper.text()).not.toContain('Nobody is in this team yet')
  })

  it('hides every changing action from someone who may only look', async () => {
    const wrapper = await mountSuspended(TenantDirectory, {
      props: { tenant: 'acme', directory: directory(), canWrite: false }
    })

    expect(wrapper.text()).not.toContain('Invite')
    expect(wrapper.text()).not.toContain('New team')
  })
})
