import { test, expect } from '@playwright/test';

/**
 * Infra smoke — proves the whole rig is real: the `webServer` booted the REAL Ktor platform (via the
 * `:e2e:webE2eServer` Gradle task), a real browser loaded the same-origin fixture, and the fixture's
 * `window.cyppie` surface is present. If this reds, nothing downstream is trustworthy.
 */
test('the real Ktor harness is up and the fixture loads same-origin', async ({ page, request, baseURL }) => {
  const health = await request.get(`${baseURL}/api/health`);
  expect(health.ok()).toBeTruthy();
  expect((await health.text()).trim()).toBe('ok');

  await page.goto('/');
  await expect(page.locator('h1')).toHaveText('web-e2e reference client'); // fixture rendered
  await expect(page.locator('#agent-events')).toBeAttached();              // the mount points exist (empty ⇒ not "visible")
  await expect(page.locator('#comm-messages')).toBeAttached();
  expect(await page.evaluate(() => typeof window.cyppie?.connectAgent)).toBe('function');
});
