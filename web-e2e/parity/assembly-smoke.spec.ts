import { test, expect, SEED } from './fixtures';

/**
 * Phase-1 assembly smoke — the REAL product SPA boots against the REAL Ktor harness cross-origin, fetches the
 * channel/ACL snapshot, and assembles its windows. This is the foundation: if the app can't reach the server
 * (CORS/globals/auth), nothing downstream is real.
 *
 * It also pins the Phase-1 SCOPE boundary empirically: the merged assembly (CYP-425) opens per-agent windows
 * (transcript + Orchestration↔Shell toggle) and the ACL window; the Comm timeline (CommPanel) is NOT yet
 * mounted — App.tsx says it "integrates when CYP-424 merges". So Comm-timeline parity is a pending row, not a
 * failing one.
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

test('SCOPE (empirical): the Comm timeline is not yet in the assembly (CYP-424 pending)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('[data-testid="app-root"]')).toBeAttached();
  // Documents the Phase-1 boundary at the object: CommPanel is not mounted until CYP-424.
  await expect(page.locator('[data-testid="comm-panel"]')).toHaveCount(0);
  await expect(page.locator('[data-testid="comm-timeline"]')).toHaveCount(0);
});
