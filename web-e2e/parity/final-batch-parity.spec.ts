import { test, expect } from './fixtures';

/**
 * CYP-422 final ❌-batch — the last mandatory [K] parity rows (Full Replacement): A8 Product-Lead, A7 warden-family
 * in the Event-Log, and the A2 edit axis. Against the real harness same-origin.
 */

// ── A8 · Product-Lead (CYP-464) ─────────────────────────────────────────────────────────────────────────────
test.describe('A8 · Product-Lead (CYP-464) — on-demand snapshot (not live) + reports view', () => {
  test('operator: trigger a report → an immutable timestamped snapshot appears, opens with provenance', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('[data-testid="productLead.panel"]')).toBeVisible();
    // no live feed — the list is empty until an on-demand report is generated.
    await expect(page.locator('[data-testid="productLead.empty"]')).toBeVisible();

    // ON-DEMAND: trigger a 'defects' report → POST /api/reports generates a NEW immutable snapshot.
    await page.locator('[data-testid="productLead.trigger.defects"]').dispatchEvent('click');
    const snapshot = page.locator('li[data-testid^="productLead.snapshot."]').first();
    await expect(snapshot).toBeVisible();
    await expect(page.locator('[data-testid="productLead.empty"]')).toHaveCount(0);

    // SNAPSHOT-NOT-LIVE: open it → the detail is a timestamped moment (as-of) with provenance, not a live view.
    await snapshot.dispatchEvent('click');
    await expect(page.locator('[data-testid="productLead.detail"]')).toBeVisible();
    await expect(page.locator('[data-testid="productLead.detail.asOf"]')).toBeVisible();
    await expect(page.locator('[data-testid="productLead.detail.provenance"]')).toBeVisible();
  });
});

// ── A7 · Warden-family in the Event-Log (07/S11) ────────────────────────────────────────────────────────────
test.describe('A7 · Warden-Eskalationen — the stall.* family renders in the Event-Log', () => {
  test('operator: a seeded stall.escalated event renders via the shared EventRow (warden type)', async ({ page }) => {
    await page.goto('/');
    const table = page.locator('[data-testid="eventBrowse.table"]');
    await expect(table).toBeVisible();
    // the warden family is not a separate surface — it renders in the Event-Log's shared EventRow with its wire
    // type text (and the ☂ warden glyph). The seeded stall.escalated event is present in the REST history.
    await expect(table).toContainText('stall.escalated');
  });
});

// ── A2 · Agent-Management edit axis (closes A2) ──────────────────────────────────────────────────────────────
test.describe('A2 · Agent-Management edit axis (CYP-450) — closes A2', () => {
  test('operator: edit an agent → save → the saved≠active effect hint (restart to apply)', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('[data-testid="agentMgmt.panel"]')).toBeVisible();

    // open the edit dialog for the seeded backend agent (dispatch click — the window tiles under others).
    await page.locator('[data-testid="agentMgmt.item.backend.edit"]').dispatchEvent('click');
    await expect(page.locator('[data-testid="agentMgmt.edit.dialog"]')).toBeVisible();

    // change the persona then save → the server-confirmed edit surfaces the saved≠active hint (no restart control
    // here — the effect lands on the next spawn, non-optimistic).
    await page.locator('[data-testid="agentMgmt.edit.persona.input"]').fill('parity-edited-persona');
    await page.locator('[data-testid="agentMgmt.edit.save"]').dispatchEvent('click');
    await expect(page.locator('[data-testid="agentMgmt.edit.effectHint"]')).toBeVisible();
  });
});
