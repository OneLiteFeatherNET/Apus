import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import Impersonation from '~/components/platform/ImpersonationPanel.vue'

const USERS = [
  { id: 'u1', displayName: 'Alice', email: 'alice@acme.example', privileged: false },
  { id: 'u2', displayName: 'Carol', email: 'carol@acme.example', privileged: false }
]

describe('Impersonation', () => {
  it('says plainly that the session is recorded', async () => {
    // Impersonation is a feature whose whole safety story is the audit trail. Someone about to
    // use it should be told that before they do, not discover it afterwards.
    const wrapper = await mountSuspended(Impersonation, { props: { tenant: 'acme', users: USERS } })

    expect(wrapper.text().toLowerCase()).toContain('recorded')
  })

  it('names the tenant being entered, so nobody acts in the wrong one', async () => {
    const wrapper = await mountSuspended(Impersonation, { props: { tenant: 'acme', users: USERS } })

    expect(wrapper.text()).toContain('acme')
  })

  it('offers acting as the tenant itself, not only as a named person', async () => {
    // The "as org admin" case: seeing what the tenant sees without borrowing anybody's name.
    const wrapper = await mountSuspended(Impersonation, { props: { tenant: 'acme', users: USERS } })

    expect(wrapper.text()).toContain('the tenant itself')
  })

  it('still offers acting as the tenant when the directory listed nobody', async () => {
    // A tenant with no members, or one whose directory is unreachable, must not lose the org
    // admin view -- that is exactly when someone is trying to work out what is wrong.
    const wrapper = await mountSuspended(Impersonation, { props: { tenant: 'acme', users: [] } })

    expect(wrapper.text()).toContain('the tenant itself')
  })
})
