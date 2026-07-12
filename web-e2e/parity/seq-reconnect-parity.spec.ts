import { test, expect } from './fixtures';

/**
 * CYP-422 §A3 — the agent transcript feed (/ws/agent) parity: seq-`?since` replay + reconnect-idempotency, driven
 * against the real harness same-origin. A real 09-catalog row the old UI had. Three real findings shaped this rig:
 *  1. /ws/agent authenticates via the same-origin Kratos session COOKIE (CYP-454) — no ?token= from the client. The
 *     token-based hermetic harness has no cookie, so the parity Vite proxy injects the operator Bearer on the WS
 *     upgrade (a proxy modelling the operator's authenticated upstream; the auth mode is orthogonal to this tooth).
 *  2. The harness FakeProcess used emptyFlow → /ws/agent closed right after replay (reconnect storm). The rig's
 *     spawner now holds stdout open (OpenFakeProcess), mirroring a live agent.
 *  3. A success ResultEvent is mapper-suppressed (0 rows). So the transcript corpus is seeded on `po` as
 *     ROW-producing AssistantEvents ("TRANSCRIPT-SEED-*"); `backend`'s frame-only success corpus stays intact.
 */
const AGENT = 'po';
const SEED_ROWS = 3; // WebE2eSeed.TRANSCRIPT_ROWS (AssistantEvents on `po`)

test.describe('A3 · agent transcript /ws/agent — seq-?since replay + reconnect idempotency', () => {
  test('the transcript replays the seeded seq corpus (3 assistant rows, each once)', async ({ page }) => {
    await page.goto('/');
    const transcript = page.locator(`[data-testid="agent-window.${AGENT}"] [data-testid="transcript"]`);
    // the 3 seeded AssistantEvents replay over /ws/agent on connect → 3 rows, rendered as escaped text.
    await expect(transcript.locator('> li')).toHaveCount(SEED_ROWS);
    await expect(transcript).toContainText('TRANSCRIPT-SEED-0');
  });

  test('a reconnect is idempotent — the seq>cursor guard + dedup add NO duplicate rows', async ({ page }) => {
    // count /ws/agent socket opens so we can PROVE a reconnect actually fired (else "no dup" is a trivial pass).
    let agentWsOpens = 0;
    page.on('websocket', (ws) => {
      if (ws.url().includes('/ws/agent')) agentWsOpens += 1;
    });

    await page.goto('/');
    const rows = page.locator(`[data-testid="agent-window.${AGENT}"] [data-testid="transcript"] > li`);
    await expect(rows).toHaveCount(SEED_ROWS);
    const opensBefore = agentWsOpens;

    // Force a reconnect: drop the network so every socket closes, then restore — the reconnecting socket re-opens
    // /ws/agent with ?since=<cursor>, replaying only seq > cursor and deduping by seq.
    await page.context().setOffline(true);
    await page.waitForTimeout(700);
    await page.context().setOffline(false);

    // the reconnect fired (a fresh /ws/agent open) …
    await expect.poll(() => agentWsOpens, { timeout: 15_000 }).toBeGreaterThan(opensBefore);
    // … and the transcript is UNCHANGED — no rows re-appended (idempotent).
    await expect(rows).toHaveCount(SEED_ROWS);
    await page.waitForTimeout(1000);
    await expect(rows).toHaveCount(SEED_ROWS); // stable after settle
  });
});
