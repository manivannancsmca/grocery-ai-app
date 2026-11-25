import { expect, type Page, test } from '@playwright/test'
import { ADMIN_EMAIL, ADMIN_PASSWORD } from '../src/api'
import { signIn } from '../src/ui'

/**
 * The left menu stays put while a page scrolls, in a laptop-sized window that is shorter than the admin menu: it runs
 * from under the top bar to the window's bottom edge at any scroll position, collapsed or expanded, and only its list
 * of links scrolls, above the docked "Ask AI" launcher.
 *
 * Regression: the collapsed menu's screen-reader section names (absolutely positioned) escaped the list's scroll box
 * and made the page taller than the layout; scrolled to the end, the menu was pushed up over the top bar, ended halfway
 * down the window and left the launcher floating on the page.
 */
test.use({ viewport: { width: 1350, height: 635 } })

/** Where the menu, its list, the launcher and the top bar are, scrolled to the end of the page. */
async function measureAtTheEnd(page: Page) {
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
  return page.evaluate(() => {
    const header = document.querySelector('header')!
    const list = document.getElementById('desktop-navigation')!
    const launcher = [...document.querySelectorAll('button')].find((b) => /^Open the assistant/.test(b.getAttribute('aria-label') ?? ''))!
    const box = launcher.getBoundingClientRect()
    const atLauncher = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
    return {
      scrollY: window.scrollY,
      pageHeight: document.documentElement.scrollHeight,
      layoutHeight: header.parentElement!.getBoundingClientRect().height,
      windowHeight: window.innerHeight,
      header: header.getBoundingClientRect().toJSON() as DOMRect,
      menu: list.parentElement!.getBoundingClientRect().toJSON() as DOMRect,
      list: list.getBoundingClientRect().toJSON() as DOMRect,
      listScrolls: list.scrollHeight > list.clientHeight,
      launcher: box.toJSON() as DOMRect,
      launcherOnTop: atLauncher !== null && launcher.contains(atLauncher),
    }
  })
}

for (const state of ['collapsed', 'expanded'] as const) {
  test(`layout — the ${state} left menu stays put while the page scrolls`, async ({ page }) => {
    await signIn(page, { email: ADMIN_EMAIL, password: ADMIN_PASSWORD, fullName: '' })
    if (state === 'collapsed') await page.getByRole('button', { name: 'Collapse menu' }).click()
    const menuWidth = state === 'collapsed' ? 80 : 256

    // A short page (the empty-state approvals queue fits the window) and a long one (a page of orders).
    for (const path of ['/admin/approvals', '/admin/orders']) {
      await test.step(path, async () => {
        await page.goto(path)
        await expect(page.locator('main h1').first()).toBeVisible()
        // The docked launcher appears once the assistant is set up for the signed-in admin (after the first page).
        await expect(page.getByRole('button', { name: /^Open the assistant/ })).toBeVisible()
        // The menu has finished sliding to its width (its border may sit inside or outside the measured box).
        await expect.poll(async () => Math.abs((await measureAtTheEnd(page)).menu.width - menuWidth)).toBeLessThanOrEqual(1)
        const at = await measureAtTheEnd(page)

        expect(at.pageHeight, 'nothing makes the page longer than the layout').toBe(Math.round(at.layoutHeight))
        expect(at.header.top, 'the top bar stays at the top').toBe(0)
        expect(at.menu.top, 'the menu starts under the top bar').toBe(at.header.bottom)
        expect(at.menu.bottom, 'the menu reaches the bottom of the window').toBe(at.windowHeight)
        expect(at.listScrolls, 'the links scroll inside the menu (longer than this window)').toBe(true)
        expect(at.launcher.top, '"Ask AI" sits below the links').toBeGreaterThanOrEqual(at.list.bottom - 1)
        expect(at.launcher.bottom, '"Ask AI" stays inside the menu').toBeLessThanOrEqual(at.menu.bottom)
        expect(at.launcherOnTop, 'nothing covers "Ask AI"').toBe(true)
      })
    }
  })
}
