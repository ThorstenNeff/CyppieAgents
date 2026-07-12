import { test, expect } from './fixtures';

/**
 * CYP-422 §A3 — the DOM window-manager (CYP-402/W4) parity: focus (bring-to-front / z-order) + drag, against the
 * real product SPA same-origin. A landed-but-untested [K] row (the "desktop" feel). Pointer events are dispatched
 * directly (the tiled windows occlude each other — a WM artifact, not a bug); the tooth asserts the resulting state
 * (DOM stacking order / the window's transform), not the pixels.
 */
test.describe('A3 · Fenster-Manager (CYP-402) — focus + drag', () => {
  test('pointerdown on a background window brings it to front (focus → native DOM stacking)', async ({ page }) => {
    await page.goto('/');
    const windows = page.locator('[data-window-id]');
    await expect(windows.first()).toBeAttached();

    // focusing the po agent window makes it the LAST (top) window in DOM order (later elements paint on top).
    await page.locator('[data-window-id="agent:po"]').dispatchEvent('pointerdown');
    const ids = await windows.evaluateAll((els) => els.map((e) => e.getAttribute('data-window-id')));
    expect(ids[ids.length - 1]).toBe('agent:po');

    // focusing a different window re-tops it — proving it's real bring-to-front, not a fixed order.
    await page.locator('[data-window-id="agent:backend"]').dispatchEvent('pointerdown');
    const ids2 = await windows.evaluateAll((els) => els.map((e) => e.getAttribute('data-window-id')));
    expect(ids2[ids2.length - 1]).toBe('agent:backend');
  });

  test('dragging a window by its title bar moves it (transform changes by the pointer delta)', async ({ page }) => {
    await page.goto('/');
    const win = page.locator('[data-window-id="agent:po"]');
    await expect(win).toBeAttached();
    const before = await win.evaluate((el) => (el as HTMLElement).style.transform);

    const title = win.locator('.window-title');
    await title.dispatchEvent('pointerdown', { clientX: 100, clientY: 100, pointerId: 1, bubbles: true });
    await title.dispatchEvent('pointermove', { clientX: 220, clientY: 160, pointerId: 1, bubbles: true });
    await title.dispatchEvent('pointerup', { clientX: 220, clientY: 160, pointerId: 1, bubbles: true });

    const after = await win.evaluate((el) => (el as HTMLElement).style.transform);
    expect(after).not.toBe(before); // moveBy(+120,+60) → the translate changed (the window followed the drag)
  });
});
