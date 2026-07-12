import { test, expect } from './fixtures';

/**
 * CYP-422 §A4 — the Comm timeline (CommPanel) parity: channel history (REST) + live + dedup-by-Message.id, driven
 * against the real harness same-origin. A real 09-catalog row. /ws/comm still authenticates via ?token= (CYP-454
 * left comm/terminal on the ticket-token path), so — unlike /ws/agent — there is NO cookie gap here. The harness
 * seeds two messages ("COMM-SEED from po" / "…from backend") in the po-backend spoke; the app auto-selects the
 * first channel → loads its history via GET /api/channels/{id}/messages (deduped by id, overlaps the live feed).
 */
test.describe('A4 · Comm timeline /ws/comm — channel history (REST) + dedup-by-id on reconnect', () => {
  test('the timeline loads the seeded channel history via REST — each message rendered once', async ({ page }) => {
    await page.goto('/');
    const timeline = page.locator('[data-testid="comm-timeline"]');
    await expect(timeline).toBeVisible();
    // po-backend is auto-selected → GET /api/channels/po-backend/messages → the two seeded messages render.
    await expect(timeline).toContainText('COMM-SEED from po');
    await expect(timeline).toContainText('COMM-SEED from backend');
    await expect(page.locator('[data-testid^="comm.message."]')).toHaveCount(2);
  });

  test('a sent message is deduped by Message.id — the POST echo over /ws/comm adds NO duplicate row', async ({ page }) => {
    await page.goto('/');
    const rows = page.locator('[data-testid^="comm.message."]');
    await expect(rows).toHaveCount(2);

    // Send via the composer (operator posture). App.tsx folds the message from BOTH the POST response AND its
    // /ws/comm echo (same Message.id) → dedup → exactly ONE new row (S6 risk: idempotency over message.id).
    const input = page.locator('[data-testid="comm-panel"] [data-testid="composer-input"]');
    await input.fill('COMM-SEND-dedup-probe');
    await input.press('Enter');

    await expect(rows).toHaveCount(3);
    await expect(page.locator('[data-testid="comm-timeline"]')).toContainText('COMM-SEND-dedup-probe');
    // stable: the /ws/comm echo of the just-posted message must NOT add a 4th row.
    await page.waitForTimeout(1000);
    await expect(rows).toHaveCount(3);
  });
});
