import { test, expect } from '@playwright/test';
import { agentToken, SEED_AGENT, SEED_CHANNEL } from './harness';

/**
 * Tooth C — **XSS regression: a message body renders inert in the DOM.** A crafted payload is posted through
 * the REAL comm feed (`POST /api/channels/{id}/messages` as the member agent, `canWrite`), then rendered by
 * the reference client. The body must be literal text (`textContent`), never parsed as HTML.
 *
 * The `<img onerror>` probe is the sharp one: under `innerHTML` the browser creates the element, the load
 * fails, and `onerror` fires — setting `window.__xss`. Under the correct `textContent` path nothing is created
 * and nothing fires. Three assertions: the onerror never ran, no `<img>` was injected, and the visible text is
 * the raw payload.
 */
const XSS_PAYLOAD = '<img src=x onerror="window.__xss=1">hello';

test('a comm message body with an XSS payload is rendered inert (no execution)', async ({ page, request, baseURL }) => {
  await page.goto('/');

  const res = await request.post(`${baseURL}/api/channels/${SEED_CHANNEL}/messages`, {
    headers: { Authorization: `Bearer ${agentToken(SEED_AGENT)}` },
    data: { body: XSS_PAYLOAD },
  });
  expect(res.ok(), `POST message failed: ${res.status()} ${await res.text()}`).toBeTruthy();

  const rendered = await page.evaluate(
    ([channel, token]) => window.cyppie.loadComm(channel as string, token as string),
    [SEED_CHANNEL, agentToken(SEED_AGENT)],
  );
  expect(rendered).toBeGreaterThan(0);

  const body = page.locator('#comm-messages .comm-body').first();
  await expect(body).toHaveText(XSS_PAYLOAD);                       // literal text, not parsed HTML
  expect(await page.locator('#comm-messages img').count()).toBe(0); // no element injected
  expect(await page.evaluate(() => (window as unknown as { __xss?: number }).__xss)).toBeUndefined(); // onerror never fired
});
