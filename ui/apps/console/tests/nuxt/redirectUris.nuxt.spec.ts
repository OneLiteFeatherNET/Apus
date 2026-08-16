import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import RedirectUris from '~/components/platform/RedirectUris.vue'

const URIS = [
  'https://apus.example.dev/t/acme/auth/callback',
  'https://apus.example.dev/t/acme/auth/silent-renew'
]

describe('RedirectUris', () => {
  it('shows both URIs verbatim, because they are pasted somewhere else character for character', async () => {
    const wrapper = await mountSuspended(RedirectUris, { props: { uris: URIS } })

    expect(wrapper.text()).toContain('https://apus.example.dev/t/acme/auth/callback')
    expect(wrapper.text()).toContain('https://apus.example.dev/t/acme/auth/silent-renew')
  })

  it('says this is a step someone still has to take, not a status report', async () => {
    // The whole reason this exists: nothing in the cluster reports the failure, so the person
    // who just created the tenant has to be told here or they walk away thinking it is done.
    const wrapper = await mountSuspended(RedirectUris, { props: { uris: URIS } })

    expect(wrapper.text()).toContain('identity provider')
  })

  it('renders nothing at all for a tenant with no instance', async () => {
    // Not an empty box with a heading: a tenant without an application instance has no such
    // step pending, and a "Redirect URIs" section with nothing under it reads like a bug.
    const wrapper = await mountSuspended(RedirectUris, { props: { uris: [] } })

    expect(wrapper.text().trim()).toBe('')
  })
})
