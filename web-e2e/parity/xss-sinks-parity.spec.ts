import { test, expect } from './fixtures';

/**
 * CYP-422 §B-1 — XSS inertness at the CLIENT-render sinks (React text-escaping), the client axis paired with
 * Backend2's server-side sink axis (JSON-inert reads / WS Frame.Text / serialized names). Every agent-/user-
 * supplied string must render as an escaped text child — never parsed into an element, never firing script.
 * The harness embeds the payload `<img src=x onerror="window.__xssFired=true">` in a transcript row, a comm
 * message body, and (here) an agent name. Discriminating pair per sink: no injected <img> element + onerror
 * never runs. An innerHTML render would fail BOTH.
 */
const AGENT = 'po';
const xssFired = (page: import('@playwright/test').Page) =>
  page.evaluate(() => (window as { __xssFired?: boolean }).__xssFired ?? false);

test.describe('B-1 · XSS inertness at the client-render sinks', () => {
  test('agent transcript: a payload in assistant text renders INERT', async ({ page }) => {
    await page.goto('/');
    const transcript = page.locator(`[data-testid="agent-window.${AGENT}"] [data-testid="transcript"]`);
    await expect(transcript.locator('> li')).toHaveCount(3);
    await expect(transcript).toContainText('onerror'); // escaped text, never swallowed
    await expect(transcript.locator('img')).toHaveCount(0);
    expect(await xssFired(page)).toBe(false);
  });

  test('comm body: a payload in a message body renders INERT', async ({ page }) => {
    await page.goto('/');
    const timeline = page.locator('[data-testid="comm-timeline"]');
    await expect(timeline).toContainText('COMM-SEED from po');
    await expect(timeline).toContainText('onerror');
    await expect(timeline.locator('img')).toHaveCount(0);
    expect(await xssFired(page)).toBe(false);
  });

  test('agent name: a payload in an agent name renders INERT in the roster', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('[data-testid="agentMgmt.panel"]')).toBeVisible();

    // add an agent whose NAME is the payload (id is a plain slug); the roster renders a.name as a text child.
    await page.locator('[data-testid="agentMgmt.addButton"]').dispatchEvent('click');
    await page.locator('[data-testid="agentMgmt.add.id.input"]').fill('xssname');
    await page.locator('[data-testid="agentMgmt.add.name.input"]').fill('<img src=x onerror="window.__xssFired=true">');
    await page.locator('[data-testid="agentMgmt.add.worktree.input"]').fill('xssname-wt');
    await page.locator('[data-testid="agentMgmt.add.confirm"]').dispatchEvent('click');

    const item = page.locator('[data-testid="agentMgmt.item.xssname"]');
    await expect(item).toBeVisible();
    await expect(item).toContainText('onerror'); // name rendered as escaped text
    await expect(item.locator('img')).toHaveCount(0);
    expect(await xssFired(page)).toBe(false);

    // cleanup — leave the shared harness as found.
    await page.locator('[data-testid="agentMgmt.item.xssname.remove"]').dispatchEvent('click');
    await page.locator('[data-testid="agentMgmt.remove.confirm"]').dispatchEvent('click');
    await expect(item).toHaveCount(0);
  });
});
