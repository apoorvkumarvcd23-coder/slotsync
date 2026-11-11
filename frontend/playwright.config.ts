import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: 0,
  use: { baseURL: process.env.SLOTSYNC_BASE_URL || 'http://127.0.0.1:8080', trace: 'off' },
  reporter: 'list',
});
