import { computed, ref } from 'vue'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import DefaultLayout from '~/layouts/default.vue'

/**
 * A real render through Nuxt's own component auto-registration -- not a typecheck. It catches
 * what `vue-tsc --noEmit` and `nuxt build` both silently let through: a template referencing a
 * component under its bare filename (`<ConsoleHeader />`) instead of the directory-prefixed name
 * Nuxt actually registers it under (`LayoutConsoleHeader`, since
 * `app/components/layout/ConsoleHeader.vue` sits one directory below `app/components/`). An
 * unresolved tag compiles fine and renders as an empty custom element at runtime; only mounting
 * the real tree flags it.
 *
 * The tenant app has the same test for the same reason -- its layout shipped exactly this bug
 * once. This app's shell was written fresh against that lesson, so this is the belt that keeps
 * it that way.
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

  it('resolves and renders ConsoleHeader, not just an empty custom element', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    const wrapper = await mountSuspended(DefaultLayout, {
      slots: { default: () => 'platform dashboard content' }
    })

    // ConsoleHeader.vue's own template content -- only present if `<LayoutConsoleHeader />`
    // actually resolved and rendered its subtree.
    expect(wrapper.find('header').exists()).toBe(true)
    expect(wrapper.text()).toContain('Apus')
    expect(wrapper.text()).toContain('Console')

    // The layout's own slot content, proving the layout rendered past the header.
    expect(wrapper.text()).toContain('platform dashboard content')

    const failedToResolve = warnSpy.mock.calls.some(call =>
      call.some(arg => typeof arg === 'string' && arg.includes('Failed to resolve component'))
    )
    expect(failedToResolve).toBe(false)
  })

  it('links back to the tenant app with a plain anchor', async () => {
    const wrapper = await mountSuspended(DefaultLayout, {
      slots: { default: () => 'content' }
    })

    // Not <ULink to>: the tenant app is a separate application and this router cannot resolve
    // its routes. A router link would render as a dead element rather than navigate.
    const link = wrapper.find('a[href="/"]')
    expect(link.exists()).toBe(true)
    expect(link.text()).toContain('Tenant app')
  })
})
