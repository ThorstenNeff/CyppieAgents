import { test, expect } from './fixtures';

/**
 * CYP-422 §A6 — Event-Log Live-Tail PAUSE (CYP-448) parity, from UIUX's discriminating spec. The tail freezes at
 * the pause tip; newer events buffer (never lost, never shown as live); resume catches up. Driven against the real
 * harness same-origin; the live probe event is emitted via /test/emit-xss (the /ws/events feed streams live).
 */
test.describe('A6 · Event-Log Live-Tail Pause (CYP-448)', () => {
  test('pause freezes the tail + buffers new events (paused indicator); resume catches up', async ({ page }) => {
    const eventsWs = page.waitForEvent('websocket', (ws) => ws.url().includes('/ws/events'));
    await page.goto('/');
    await expect(page.locator('[data-testid="event-log"]')).toBeVisible();
    await eventsWs;
    await page.waitForTimeout(300);

    // NOTE (finding, reported): the tail never reaches "Live" — the server /ws/events emits no CaughtUp, so
    // `caughtUp` stays false and event-log-live never shows (status stuck "Verlauf lädt…"). The live-in-freeze
    // discriminator (UIUX tooth 2) is thus weakened here; the BUFFERING discriminator (tooth 1) carries the proof.

    // PAUSE → the paused indicator is shown (and live is — correctly — absent).
    await page.locator('[data-testid="event-log-pause"]').dispatchEvent('click');
    await expect(page.locator('[data-testid="event-log-paused"]')).toBeVisible();
    await expect(page.locator('[data-testid="event-log-live"]')).toHaveCount(0);

    // tooth 1 (the strong discriminator): a live event arriving WHILE paused BUFFERS (bufferedCount rises) — it is
    // not dropped and not shown in the frozen view.
    const emit = await page.request.get('http://127.0.0.1:8791/test/emit-xss');
    expect(emit.ok()).toBe(true);
    await expect(page.locator('[data-testid="event-log-buffered"]')).toContainText('1');

    // tooth 3: RESUME → the pause + buffered cue clear, and the once-buffered event is now in the tail (caught up,
    // not discarded).
    await page.locator('[data-testid="event-log-pause"]').dispatchEvent('click');
    await expect(page.locator('[data-testid="event-log-paused"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="event-log-buffered"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="event-log-rows"]')).toContainText('onerror');
  });

  // tooth 4 (revoke WS 1008 clears the pause) is STAGED: it needs a server-side seam to close the operator's
  // /ws/events with code 1008 (an ACL revoke), which the hermetic harness has no trigger route for. The
  // revoke→NOT_PAUSED transition is unit-covered (eventLogStore.onEventsClose(1008)); e2e awaits a revoke seam.
  test('revoke (WS 1008) clears the pause', async () => {
    test.skip(true, 'Needs a harness seam to close /ws/events with 1008 (ACL revoke). Unit-covered in eventLogStore.');
  });
});
