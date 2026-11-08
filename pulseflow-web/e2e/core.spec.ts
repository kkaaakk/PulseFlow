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
  await expect(page.getByText('近 30 天支付订单为 0', { exact: false })).toBeVisible()
  await page.getByRole('link', { name: 'Users' }).click()
  await page.getByText('演示用户 A').click()
  await expect(page).toHaveURL(/users\/1024/)
  await expect(page.getByText('Event Timeline')).toBeVisible()
  await expect(page.getByText('近 7 天活跃天数')).toBeVisible()
  await expect(page.getByText('近 30 天支付订单', { exact: true })).toBeVisible()
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


// Compatibility path: the result envelope has no independent database record ID.
test('Delivery detail uses the task send result and exposes clicks and attribution', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/login')
  await page.getByRole('button', { name: '进入控制台' }).click()
  await page.getByRole('link', { name: 'Deliveries' }).click()
  await expect(page.getByRole('heading', { name: 'Deliveries' })).toBeVisible()
  await page.getByRole('button', { name: '查看详情' }).first().click()
  const drawer = page.getByRole('dialog')
  await expect(drawer.getByText('发送结果', { exact: true })).toBeVisible()
  await expect(drawer.getByText('SENT', { exact: true })).toBeVisible()
  await expect(drawer.getByRole('heading', { name: /点击/ })).toBeVisible()
  await expect(drawer.getByRole('heading', { name: /归因/ })).toBeVisible()
  expect(errors).toEqual([])
})
