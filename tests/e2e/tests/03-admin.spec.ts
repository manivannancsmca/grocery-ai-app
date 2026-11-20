import { expect, test } from '@playwright/test'
import { ADMIN_EMAIL, ADMIN_PASSWORD, ok, until } from '../src/api'
import { signIn } from '../src/ui'
import { admin, chat, createAdminUser, createPendingVendor, randomSpot, uniqueTag } from '../src/world'

/**
 * Journey 3 (contract §4): an admin approves a vendor's application, checks the dead-letter operations page, decides an
 * AI approval (a four-eyes suspension proposed by another admin through the scripted assistant) and opens the reports.
 */
test.describe('Journey 3 — admin', () => {
  test('approve a vendor → dead letters → AI approval (four-eyes) → reports', async ({ page }) => {
    const tag = uniqueTag()
    const vendorName = `E2E Pending Mart ${tag}`

    const vendor = await test.step('setup (API): a vendor submits its application', async () =>
      createPendingVendor(tag, randomSpot(), vendorName),
    )

    await test.step('sign in as the super admin', async () => {
      await signIn(page, { email: ADMIN_EMAIL, password: ADMIN_PASSWORD, fullName: '' })
      await expect(page).toHaveURL(/\/admin$/)
    })

    await test.step('approve the vendor from the approvals queue', async () => {
      await page.goto('/admin/approvals?type=vendors')
      await expect(page.getByRole('heading', { level: 1, name: 'Approvals' })).toBeVisible()
      const review = page.getByRole('button', { name: `Review ${vendorName}` })
      const inFirstPage = await review.waitFor({ state: 'visible', timeout: 15_000 }).then(
        () => true,
        () => false,
      )
      if (inFirstPage) {
        await review.click()
      } else {
        // more than one page of applications waiting: find it in the vendor directory instead
        await page.goto(`/admin/vendors?status=PENDING_APPROVAL&q=${encodeURIComponent(vendorName)}`)
        await page.getByRole('button', { name: `View ${vendorName}` }).click()
      }
      const drawer = page.getByRole('dialog', { name: vendorName })
      await expect(drawer.getByText(`ops-${tag}@e2e.example.com`)).toBeVisible()
      await drawer.getByRole('button', { name: 'Approve' }).click()
      await expect(page.getByText(`${vendorName} approved.`)).toBeVisible()
      const record = await ok('GET', `/api/admin/vendors/${vendor.vendorId}`, { token: (await admin()).token })
      expect(record.status).toBe('APPROVED')
    })

    await test.step('operations: the dead letters page', async () => {
      await page.goto('/admin/operations')
      await expect(page.getByRole('heading', { level: 1, name: 'Operations' })).toBeVisible()
      await expect(page.getByRole('tab', { name: /Dead letters/ })).toBeVisible()
      // Either the table of dead letters or the empty state — both mean the page talked to every service.
      await expect(page.getByRole('table', { name: 'Dead letters' }).or(page.getByText('No dead letters'))).toBeVisible()
      await expect(page.getByRole('list', { name: 'Dead letters by service' })).toBeVisible()
    })

    const approval = await test.step('setup (API): an OPS_ADMIN asks the assistant to suspend the vendor', async () => {
      const ops = await createAdminUser('OPS_ADMIN', tag)
      const result = await chat('admin-bi', ops.token, `suspend vendor ${vendor.vendorId} because E2E check ${tag}`)
      expect(result.status).toBe(200)
      const card = result.approvals.at(-1)
      expect(card?.action).toBe('SUSPEND_VENDOR')
      expect(card?.fourEyes).toBe(true)
      return card
    })

    await test.step('the super admin approves the suspension in AI approvals', async () => {
      await page.goto('/admin/ai/approvals')
      await expect(page.getByRole('heading', { level: 1, name: 'AI approvals' })).toBeVisible()
      await page.getByRole('tab', { name: /^To decide/ }).click()
      const title = String(approval.summary?.title ?? approval.title ?? '')
      const open = title
        ? page.getByRole('button', { name: `Open request ${title}` })
        : page.getByRole('button', { name: /^Open request Suspend/ }).first()
      await open.click()
      const drawer = page.getByRole('dialog', { name: title || /Suspend/ })
      await expect(drawer.getByTitle(/a second admin has to/)).toHaveText('Four-eyes')
      await drawer.getByRole('button', { name: 'Approve' }).click()
      const confirm = page.getByRole('dialog', { name: 'Approve this request?' })
      await confirm.getByRole('textbox', { name: /Comment/ }).fill(`Confirmed by E2E ${tag}`)
      await confirm.getByRole('button', { name: 'Approve and run' }).click()
      await expect(drawer.getByText('Done')).toBeVisible({ timeout: 30_000 })
      await until('the vendor is SUSPENDED', async () => {
        const record = await ok('GET', `/api/admin/vendors/${vendor.vendorId}`, { token: (await admin()).token })
        return record.status === 'SUSPENDED'
      })
    })

    await test.step('reports', async () => {
      await page.goto('/admin/reports')
      await expect(page.getByRole('heading', { level: 1, name: 'Reports' })).toBeVisible()
      await expect(page.getByLabel('Key figures')).toBeVisible()
      await expect(page.getByRole('figure', { name: /^Orders/ })).toBeVisible()
      await page.getByRole('button', { name: 'Last 30 days' }).click()
      await expect(page.getByRole('button', { name: 'Last 30 days' })).toHaveAttribute('aria-pressed', 'true')
      await expect(page.getByRole('table', { name: 'Top stores' })).toBeVisible()
    })
  })
})
