import { afterEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import DefaultLayout from '~/layouts/default.vue'

/**
 * A real render, through Nuxt's own component auto-registration -- not a typecheck. This is
 * the belt that catches what `vue-tsc --noEmit` and `nuxt build` both silently let through:
 * a template referencing a component under its bare filename (`<AppHeader />`) instead of the
 * directory-prefixed name Nuxt actually registers it under (`LayoutAppHeader`, since
 * `app/components/layout/AppHeader.vue` sits one directory below `app/components/`). An
 * unresolved tag compiles fine and renders as an empty custom element at runtime -- neither
 * static check flags it, only mounting the real tree does.
 *
 * `default.vue` was exactly this bug (`<AppHeader />`), and `AppHeader.vue` itself carried the
 * same mistake one level down (`<AppNav />` instead of `<LayoutAppNav />`) -- both fixed
 * alongside this test. If either regresses, this test fails on two independent signals: the
 * header/nav content goes missing from the rendered output, and Vue's own
 * "Failed to resolve component" dev warning fires.
 */
describe('layouts/default.vue', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('resolves and renders AppHeader and AppNav, not just an empty custom element', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    const wrapper = await mountSuspended(DefaultLayout, {
      slots: { default: () => 'tenant dashboard content' }
    })

    // AppHeader.vue's own template content -- only present if `<LayoutAppHeader />` actually
    // resolved and rendered its subtree (which itself only renders if `<LayoutAppNav />` inside
    // *it* resolved too).
    expect(wrapper.text()).toContain('Apus')
    expect(wrapper.find('header').exists()).toBe(true)

    // AppNav.vue's own content, nested two levels deep (default.vue -> AppHeader -> AppNav).
    expect(wrapper.find('nav[aria-label="Main"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Account')

    // The layout's own slot content, proving the layout itself rendered past the header.
    expect(wrapper.text()).toContain('tenant dashboard content')

    const failedToResolve = warnSpy.mock.calls.some((call) =>
      call.some((arg) => typeof arg === 'string' && arg.includes('Failed to resolve component'))
    )
    expect(failedToResolve).toBe(false)
  })
})
