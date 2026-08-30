import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e/integration',
  fullyParallel: false,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:5174',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5174',
    url: 'http://127.0.0.1:5174',
    reuseExistingServer: true,
    timeout: 120_000,
    env: {
      VITE_DEMO_MODE: 'false',
    },
  },
  projects: [
    { name: 'chromium-real-backend', use: { ...devices['Desktop Chrome'] } },
  ],
})
