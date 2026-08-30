import { expect, test } from '@playwright/test'

test.describe('Real Backend E2E', () => {
  test('Browser → Spring Boot → MySQL/Redis/Kafka campaign flow', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: '登录 PulseFlow' })).toBeVisible()

    const password = process.env.PULSEFLOW_E2E_PASSWORD || 'pulseflow-local'
    await page.getByLabel('Operator ID').fill(process.env.PULSEFLOW_E2E_OPERATOR_ID || '1024')
    await page.getByLabel('本地访问口令').fill(password)
    await page.getByRole('button', { name: '进入控制台' }).click()
    await expect(page).toHaveURL(/dashboard/)

    await page.getByRole('link', { name: 'AI Copilot' }).click()
    await page.getByRole('button', { name: '生成 Campaign' }).click()
    await expect(page.getByText('预计人群')).toBeVisible()
    await expect(page.getByText(/Draft/)).toBeVisible()

    const firstRuleValue = page.getByLabel('规则值').first()
    await firstRuleValue.fill('4')
    await page.getByRole('button', { name: '保存规则' }).click()
    await expect(page.getByText('后端 Validator 作为最终裁决')).toBeVisible()
    await page.getByRole('button', { name: '刷新人群预估' }).click()
    await expect(page.getByText(/数据版本/)).toBeVisible()

    await expect(page.getByRole('radio', { name: /A ·/ })).toBeVisible()
    await page.getByRole('radio', { name: /B ·/ }).check()
    await page.getByRole('button', { name: '确认创建 Campaign' }).click()
    await expect(page).toHaveURL(/campaigns\/\d+/)
    await expect(page.getByRole('heading', { name: 'Campaign 详情' }).or(page.getByText('Campaign 设置'))).toBeVisible()

    await page.getByRole('link', { name: 'Users' }).click()
    await page.getByText('#1024').first().click()
    await expect(page).toHaveURL(/users\/\d+/)
    await expect(page.getByText('Event Timeline')).toBeVisible()
  })
})
