// Minimal smoke test — not a substitute for real coverage, just a floor
// under regressions in the app shell itself: does the page parse, does its
// top-level JS run without throwing, are the files the service worker and
// manifest depend on actually reachable. None of this can sign in (no
// Firebase credentials in CI), so it stops at the auth gate on purpose.
import { test, expect } from '@playwright/test';

test('the app shell loads without crashing before Firebase ever responds', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', err => pageErrors.push(err));

  await page.goto('/index.html');
  await expect(page).toHaveTitle('Journal');

  // The signed-out auth gate is the first real thing rendered. Reaching it
  // means index.html, css/app.css, js/app.js and js/boot.js all parsed and
  // ran without a top-level exception.
  await expect(page.locator('#auth-gate')).toBeVisible();

  // Firebase itself has no project to talk to in this test environment, so
  // filter out its own network/init errors — this test isn't meant to catch
  // those, only errors from our own code.
  const realErrors = pageErrors.filter(e => !/firebase/i.test(String(e)));
  expect(realErrors.map(String)).toEqual([]);
});

test('manifest and service worker are reachable', async ({ request }) => {
  const manifest = await request.get('/manifest.json');
  expect(manifest.ok()).toBeTruthy();

  const sw = await request.get('/sw.js');
  expect(sw.ok()).toBeTruthy();
  expect(await sw.text()).toContain('journal-v');
});
