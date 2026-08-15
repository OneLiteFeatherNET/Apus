import { computed, ref } from 'vue'
import { describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import DefaultLayout from '~/layouts/default.vue'

/**
 * The console is a separate application now (design doc 2026-08-15, §2), which changes two
 * things in this app's header at once: the in-app `/platform` route is gone, and admins instead
 * get a plain link out to `/console/`.
 *
 * `useAuth` is mocked with a platform-admin principal on purpose. Both the old nav link and the
 * new console link are gated on that role, so without a signed-in principal neither renders and
 * an assertion about them would pass for entirely the wrong reason.
 */
mockNuxtImport('useAuth', () => {
  return () => ({
    user: ref({ profile: { email: 'admin@example.net', sub: 'admin' } }),
    principal: ref({ subject: 'admin', tenant: null, roles: ['platform-admin'] }),
    isAuthenticated: computed(() => true),
    init: async () => {},
    login: async () => {},
    logout: async () => {},
    trySilentSignin: async () => true,
    getAccessToken: async () => 'token'
  })
})

describe('the tenant app header, for a platform admin', () => {
  it('no longer offers an in-app /platform route', async () => {
    const wrapper = await mountSuspended(DefaultLayout, {
      slots: { default: () => 'content' }
    })

    // A router link here would resolve to nothing in this app and 404 in the browser.
    expect(wrapper.html()).not.toContain('/platform')
  })

  it('links out to the console instead', async () => {
    const wrapper = await mountSuspended(DefaultLayout, {
      slots: { default: () => 'content' }
    })

    const link = wrapper.find('a[href="/console/"]')
    expect(link.exists()).toBe(true)
    expect(link.text()).toContain('Platform console')
  })
})
