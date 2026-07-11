import { test, expect, SEED } from './fixtures';

/**
 * CYP-422 Phase-1 parity — the merged, runnable surfaces of the product web-ts SPA against the REAL Ktor
 * harness (agent window / Orchestration↔Shell toggle + agent transcript, and the ACL matrix). Comm timeline
 * is out (CYP-424, see assembly-smoke). Every assertion goes through the real REST/WS path, operator posture
 * (the injected operator token).
 */

test.describe('agent window — Orchestration↔Shell toggle (09 §3, W8)', () => {
  test('the toggle renders operator-enabled and defaults to the server-confirmed Orchestration view', async ({ page }) => {
    await page.goto('/');
    const win = page.locator(`[data-testid="agent-window.${SEED.AGENT}"]`);
    await expect(win).toBeVisible();
    await expect(win.locator('[data-testid="mode-toggle"]')).toBeVisible();

    // operator posture (injected token) → both segments enabled, no "operator only" note (CYP-317 no-fake-switch)
    await expect(win.locator('[data-testid="mode-orchestration"]')).toHaveAttribute('aria-disabled', 'false');
    await expect(win.locator('[data-testid="mode-shell"]')).toHaveAttribute('aria-disabled', 'false');
    await expect(win.locator('[data-testid="mode-operator-only"]')).toHaveCount(0);

    // non-optimistic: the checked segment follows the SERVER-confirmed selection; default (no terminal-control
    // state) = Orchestration, and the orchestration body is the one shown.
    await expect(win.locator('[data-testid="mode-orchestration"]')).toHaveAttribute('aria-checked', 'true');
    await expect(win.locator('[data-testid="mode-shell"]')).toHaveAttribute('aria-checked', 'false');
    // the orchestration view is the shown one (ContentViewSwitch toggles `hidden`); shell view is hidden.
    await expect(win.locator('[data-testid="view-orchestration"]')).toBeVisible();
    await expect(win.locator('[data-testid="view-shell"]')).toBeHidden();
  });

  test('the structured stream-json transcript is mounted in the orchestration view (09 §3, no raw xterm)', async ({ page }) => {
    await page.goto('/');
    const win = page.locator(`[data-testid="agent-window.${SEED.AGENT}"]`);
    // Present (an empty transcript has no box, so assert attachment, not visibility) + the composer confirms the
    // orchestration body rendered.
    await expect(win.locator('[data-testid="transcript"]')).toBeAttached();
    await expect(win.locator('[data-testid="composer-input"]')).toBeVisible();
  });
});

test.describe('ACL matrix (09 §5, W9)', () => {
  test('renders the seeded hub-and-spoke grid with operator switches at the enforced values', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('[data-testid="acl-panel"]')).toBeVisible();
    await expect(page.locator('[data-testid="acl-matrix"]')).toBeVisible();

    // seeded channel row + the two agent columns (roster derived from channel membership)
    await expect(page.locator(`[data-testid="acl.row.${SEED.CHANNEL}"]`)).toBeVisible();
    await expect(page.locator(`[data-testid="acl.col.${SEED.AGENT}"]`)).toBeVisible();
    await expect(page.locator('[data-testid="acl.col.po"]')).toBeVisible();

    // operator → real switches (role=switch), NOT read-only chips; enforced aria-checked reflects the hub-and-
    // spoke default (backend has read+write in its po-backend spoke).
    const readSwitch = page.locator(`[data-testid="acl.cell.${SEED.CHANNEL}.${SEED.AGENT}.read"]`);
    await expect(readSwitch).toHaveAttribute('role', 'switch');
    await expect(readSwitch).toHaveAttribute('aria-checked', 'true');
    await expect(page.locator(`[data-testid="acl.cell.${SEED.CHANNEL}.${SEED.AGENT}.write"]`)).toHaveAttribute('aria-checked', 'true');
  });

  test('the Hub-and-Spoke preset restore is available to the operator (09 §5 guardrail)', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('[data-testid="acl-preset-open"]')).toBeVisible();
  });
});
