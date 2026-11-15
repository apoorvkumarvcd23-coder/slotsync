import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e', testMatch: 'uat.spec.ts', workers: 1, retries: 0,
  timeout: 120000,
  use: { baseURL: process.env.SLOTSYNC_BASE_URL || 'http://127.0.0.1:8080', trace: 'off', actionTimeout: 15000 },
  reporter: 'list',
});
