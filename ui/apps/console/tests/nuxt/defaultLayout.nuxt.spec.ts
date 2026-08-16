import { computed, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import DefaultLayout from '~/layouts/default.vue'

/**
 * A real render through Nuxt's own component auto-registration -- not a typecheck. It catches what
 * `vue-tsc --noEmit` and `nuxt build` both silently let through: a template referencing a component
 * under its bare filename (`<ConsoleHeader />`) instead of the directory-prefixed name Nuxt
 * actually registers it under (`LayoutConsoleHeader`, since `app/components/layout/` sits one
 * directory below `app/components/`). An unresolved tag compiles fine, renders as an empty custom
 * element, and only mounting the real tree flags it.
 *
 * The tenant app has the same test for the same reason -- its layout shipped exactly this bug once.
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

describe('layouts/default.vue', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('resolves the sidebar and the header, not just empty custom elements', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    const wrapper = await mountSuspended(DefaultLayout, {
      slots: { default: () => 'platform dashboard content' }
    })

    // ConsoleSidebar's own content: the wordmark and its three destinations.
    expect(wrapper.find('nav[aria-label="Console"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Tenants')
    expect(wrapper.text()).toContain('Overview')

    // ConsoleHeader's own content.
    expect(wrapper.find('header').exists()).toBe(true)
    expect(wrapper.text()).toContain('Management console')

    // The layout's own slot content, proving it rendered past both.
    expect(wrapper.text()).toContain('platform dashboard content')

    const failedToResolve = warnSpy.mock.calls.some(call =>
      call.some(arg => typeof arg === 'string' && arg.includes('Failed to resolve component'))
    )
    expect(failedToResolve).toBe(false)
  })

  it('says it is the platform, permanently and in the shell itself', async () => {
    // An admin has both applications open. This marker is what stops the two from being mistaken
    // for one another when no URL bar is visible -- a screenshot, a shared screen, a second
    // monitor.
    const wrapper = await mountSuspended(DefaultLayout, { slots: { default: () => 'content' } })

    expect(wrapper.text()).toContain('Platform')
  })

  it('links back to the tenant app with a plain anchor', async () => {
    const wrapper = await mountSuspended(DefaultLayout, { slots: { default: () => 'content' } })

    // Not <ULink to>: the tenant app is a separate application and this router cannot resolve its
    // routes. A router link would render a dead element rather than navigate.
    const links = wrapper.findAll('a[href="/"]')
    expect(links.length).toBeGreaterThan(0)
    expect(links.some(link => link.text().includes('Tenant app'))).toBe(true)
  })
})
