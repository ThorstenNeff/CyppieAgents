import { test, expect } from './fixtures';

/**
 * CYP-422 §A2 — Agent-Management (CYP-450) parity: the AgentManagementPanel against the real harness same-origin.
 * A landed-but-untested [K] row (completeness pass) — state-mutating + irreversible, so it would silently read as
 * "in the gate". Non-optimistic (the roster flips only after the server confirms via a refetch), server-authoritative
 * uniqueness, and the remove is an irreversible-guardrail alertdialog (worktree KEEP default + data-loss warning).
 */
test.describe('A2 · Agent-Management (CYP-450) — roster + add (non-optimistic, persists) + remove guardrail', () => {
  test('operator: the roster lists the seeded agents', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('[data-testid="agentMgmt.panel"]')).toBeVisible();
    await expect(page.locator('[data-testid="agentMgmt.item.po"]')).toBeVisible();
    await expect(page.locator('[data-testid="agentMgmt.item.backend"]')).toBeVisible();
  });

  test('operator: add → roster (non-optimistic) + spawn hint + survives reload; remove uses the irreversibility guardrail and cleans up', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('[data-testid="agentMgmt.panel"]')).toBeVisible();

    // open the add dialog (dispatch click — the window tiles under others).
    await page.locator('[data-testid="agentMgmt.addButton"]').dispatchEvent('click');
    await expect(page.locator('[data-testid="agentMgmt.add.dialog"]')).toBeVisible();
    await page.locator('[data-testid="agentMgmt.add.id.input"]').fill('probe');
    await page.locator('[data-testid="agentMgmt.add.name.input"]').fill('Probe Agent');
    await page.locator('[data-testid="agentMgmt.add.worktree.input"]').fill('probe-wt');
    await page.locator('[data-testid="agentMgmt.add.confirm"]').dispatchEvent('click');

    // NON-OPTIMISTIC: the item appears only after the server-confirmed roster refetch. create ≠ start → spawn hint.
    await expect(page.locator('[data-testid="agentMgmt.item.probe"]')).toBeVisible();
    await expect(page.locator('[data-testid="agentMgmt.add.spawnHint"]')).toBeVisible();

    // PERSISTS: reload → GET roster still has probe (server-created, not an optimistic client echo).
    await page.reload();
    await expect(page.locator('[data-testid="agentMgmt.item.probe"]')).toBeVisible();

    // REMOVE GUARDRAIL (B-4 irreversibility): an alertdialog naming the consequences, worktree KEEP by default,
    // and a data-loss warning ONLY on the destructive path — never a one-click delete.
    await page.locator('[data-testid="agentMgmt.item.probe.remove"]').dispatchEvent('click');
    const dialog = page.locator('[data-testid="agentMgmt.remove.dialog"]');
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveAttribute('role', 'alertdialog');
    await expect(page.locator('[data-testid="agentMgmt.remove.consequences"]')).toBeVisible();
    await expect(page.locator('[data-testid="agentMgmt.remove.worktreeChoice.keep"]')).toBeChecked();
    await expect(page.locator('[data-testid="agentMgmt.remove.worktreeWarning"]')).toHaveCount(0); // no warning until 'delete' chosen

    // confirm the (non-destructive KEEP) remove → the roster drops probe; leaves the shared harness as found.
    await page.locator('[data-testid="agentMgmt.remove.confirm"]').dispatchEvent('click');
    await expect(page.locator('[data-testid="agentMgmt.item.probe"]')).toHaveCount(0);
  });
});
