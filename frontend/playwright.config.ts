import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 45000,
  expect: { timeout: 12000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: 'http://127.0.0.1:5278', channel: process.env.PLAYWRIGHT_CHANNEL,
    trace: 'retain-on-failure', screenshot: 'only-on-failure',
    viewport: { width: 1440, height: 1000 } },
  webServer: {
    command: 'node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5278',
    url: 'http://127.0.0.1:5278',
    reuseExistingServer: false,
    env: { BACKEND_HOST: '127.0.0.1', BACKEND_PORT: process.env.BACKEND_PORT || '8093' }
  }
})
