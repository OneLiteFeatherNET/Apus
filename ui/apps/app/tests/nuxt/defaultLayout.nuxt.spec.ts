import { computed, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import DefaultLayout from '~/layouts/default.vue'

/**
 * A real render, through Nuxt's own component auto-registration -- not a typecheck. This is the
 * belt that catches what `vue-tsc --noEmit` and `nuxt build` both silently let through: a template
 * referencing a component under its bare filename (`<AppHeader />`) instead of the
 * directory-prefixed name Nuxt actually registers it under (`LayoutAppHeader`, since
 * `app/components/layout/AppHeader.vue` sits one directory below `app/components/`). An unresolved
 * tag compiles fine and renders as an empty custom element at runtime -- neither static check
 * flags it, only mounting the real tree does.
 *
 * `default.vue` was exactly this bug once, and `AppHeader.vue` carried the same mistake one level
 * down. The nav is role-gated, so a principal has to be mocked or the assertions below would pass
 * against a header that rendered nothing at all.
 */
mockNuxtImport('useAuth', () => {
  return () => ({
    user: ref({ profile: { email: 'builder@example.net', sub: 'builder' } }),
    principal: ref({ subject: 'builder', tenant: 'acme', roles: ['tenant-operator'] }),
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

  it('resolves and renders AppHeader and AppNav, not just an empty custom element', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    const wrapper = await mountSuspended(DefaultLayout, {
      slots: { default: () => 'tenant dashboard content' }
    })

    // AppHeader.vue's own content -- present only if `<LayoutAppHeader />` actually resolved and
    // rendered its subtree (which itself only renders if `<LayoutAppNav />` inside *it* did too).
    expect(wrapper.text()).toContain('Apus')
    expect(wrapper.find('header').exists()).toBe(true)

    // AppNav.vue's own content, nested two levels deep (default.vue -> AppHeader -> AppNav).
    expect(wrapper.find('nav[aria-label="Main"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Worlds')

    // The layout's own slot content, proving the layout rendered past the header.
    expect(wrapper.text()).toContain('tenant dashboard content')

    const failedToResolve = warnSpy.mock.calls.some(call =>
      call.some(arg => typeof arg === 'string' && arg.includes('Failed to resolve component'))
    )
    expect(failedToResolve).toBe(false)
  })

  it('marks the current page in the navigation', async () => {
    // aria-current is how a screen-reader user learns which of four sibling links they are
    // standing on; a colour-only "active" state tells them nothing.
    const wrapper = await mountSuspended(DefaultLayout, {
      slots: { default: () => 'content' }
    })

    expect(wrapper.find('[aria-current="page"]').exists()).toBe(true)
  })
})
