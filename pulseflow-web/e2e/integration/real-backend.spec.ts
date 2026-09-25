import { expect, test } from '@playwright/test'

test.describe('Real Backend E2E', () => {
  test('Browser → Spring Boot → Campaign → Delivery → Attribution', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: '登录 PulseFlow' })).toBeVisible()
    await page.getByLabel('Operator ID').fill(process.env.PULSEFLOW_E2E_OPERATOR_ID || '1024')
    await page.getByLabel('本地访问口令').fill(process.env.PULSEFLOW_E2E_PASSWORD || 'pulseflow-local')
    await page.getByRole('button', { name: '进入控制台' }).click()
    await expect(page).toHaveURL(/dashboard/)

    await page.getByRole('link', { name: 'Campaigns' }).click()
    await expect(page.getByRole('heading', { name: 'Campaigns' })).toBeVisible()
    await page.locator('.el-table__row').first().click()
    await expect(page).toHaveURL(/campaigns\/\d+/)
    await expect(page.getByText('触达表现')).toBeVisible()

    await page.getByRole('link', { name: 'Users' }).click()
    await page.locator('.el-table__row').first().click()
    await expect(page).toHaveURL(/users\/\d+/)
    await expect(page.getByText('Event Timeline')).toBeVisible()
  })
})
