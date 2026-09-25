import { expect, test } from '@playwright/test'

test('登录 → Campaign Detail → User 360', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '登录 PulseFlow' })).toBeVisible()
  await page.getByLabel('Operator ID').fill('1024')
  await page.getByLabel('本地访问口令').fill('pulseflow-local')
  await page.getByRole('button', { name: '进入控制台' }).click()
  await expect(page).toHaveURL(/dashboard/)
  await page.getByRole('link', { name: 'Campaigns' }).click()
  await page.getByText('高活跃未购买用户召回').first().click()
  await expect(page).toHaveURL(/campaigns\/2001/)
  await expect(page.getByText('Campaign 设置')).toBeVisible()
  await page.getByRole('link', { name: 'Users' }).click()
  await page.getByText('演示用户 A').click()
  await expect(page).toHaveURL(/users\/1024/)
  await expect(page.getByText('Event Timeline')).toBeVisible()
})

test('Campaign list and performance view', async ({ page }) => {
  await page.goto('/login')
  await page.getByRole('button', { name: '进入控制台' }).click()
  await page.getByRole('link', { name: 'Campaigns' }).click()
  await expect(page.getByRole('heading', { name: 'Campaigns' })).toBeVisible()
  await page.getByText('高活跃未购买用户召回').first().click()
  await expect(page.getByText('触达表现')).toBeVisible()
  await expect(page.getByText('归因', { exact: true })).toBeVisible()
})
