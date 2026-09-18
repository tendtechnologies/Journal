// @ts-check
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  webServer: {
    // Zero-dependency static server — this repo has no build step and the
    // smoke test shouldn't need one either.
    command: 'python3 -m http.server 4173',
    port: 4173,
    reuseExistingServer: !process.env.CI,
  },
  use: { baseURL: 'http://localhost:4173' },
});
