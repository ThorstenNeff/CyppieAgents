import { test, expect, SEED } from './fixtures';

/**
 * Phase-1 assembly smoke — the REAL product SPA boots against the REAL Ktor harness cross-origin, fetches the
 * channel/ACL snapshot, and assembles its windows. This is the foundation: if the app can't reach the server
 * (CORS/globals/auth), nothing downstream is real.
 *
 * It also pins the Phase-1 SCOPE boundary empirically: the merged assembly (CYP-425) opens per-agent windows
 * (transcript + Orchestration↔Shell toggle) and the ACL window. As of develop 7accf768 the Comm window is now
 * mounted too — CYP-424 (Comm-Panel/Timeline) + CYP-438 (CommPanel integrated into App) have landed since this
 * rig was built. The former "Comm not yet mounted" boundary guard has therefore become a positive assembly
 * assertion; deeper Comm-timeline parity (history/live dedup, byTs-sort, send/403) is the next row to add.
 */
test('the SPA boots against the real harness and assembles agent windows + ACL', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('[data-testid="app-root"]')).toBeAttached();

  // agents are derived from the seeded po-backend channel's membership → a window each for po + backend
  await expect(page.locator(`[data-testid="agent-window.${SEED.AGENT}"]`)).toBeVisible();
  await expect(page.locator('[data-testid="agent-window.po"]')).toBeVisible();

  // the ACL window is assembled
  await expect(page.locator('[data-testid="acl-panel"]')).toBeVisible();
});

test('the Comm window is now assembled (CYP-424 + CYP-438 landed) and its feed is distinct from agent-events', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('[data-testid="app-root"]')).toBeAttached();

  // The Comm window is mounted. What was a "not yet mounted" boundary guard is now a positive assembly assertion.
  const comm = page.locator('[data-testid="comm-panel"]');
  await expect(comm).toBeVisible();

  // Channels are SERVER-filtered (only ACL-readable arrive, never client-filtered) → the seeded po-backend spoke
  // is present in the operator's channel list.
  await expect(comm.locator(`[data-testid="comm.channel.${SEED.CHANNEL}"]`)).toBeVisible();

  // The timeline shows the comm-seeded messages (COMM-SEED) — and NOT the agent-transcript seeds (TRANSCRIPT-SEED)
  // nor the Event-Log markers (BROWSE-SEED). The discriminating check: the feeds are distinct — agent-events and
  // event-log content never leak into the comm timeline.
  const timeline = comm.locator('[data-testid="comm-timeline"]');
  await expect(timeline).toBeVisible();
  await expect(timeline).toContainText('COMM-SEED');
  await expect(timeline).not.toContainText('TRANSCRIPT-SEED');
  await expect(timeline).not.toContainText('BROWSE-SEED');
});
