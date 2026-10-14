import { defineConfig } from '@playwright/test';
export default defineConfig({ testDir: './e2e', timeout: 30000, use: { baseURL: process.env.UI_BASE_URL ?? 'http://127.0.0.1:5173', trace: 'retain-on-failure' }, reporter: [['list'], ['html', { open: 'never' }]], workers: 1 });
