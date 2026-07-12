import { test, expect } from './fixtures';

/**
 * CYP-422 §A3 — "Nachricht an den Agenten senden" (CYP-403) parity: the agent-window composer sends a UserTurn on
 * /ws/agent (the mediator injects it on the session stdin — 05-D7: the input is a MESSAGE, not a shell prompt).
 * A landed-but-untested [K] row. The observable is the sent WS frame: the typed text reaches the socket as a
 * UserTurn {text}. (No transcript echo to assert against — the hermetic FakeProcess doesn't echo — so the frame
 * IS the proof the send reached the real feed.)
 */
test.describe('A3 · Composer send (CYP-403) — UserTurn on /ws/agent', () => {
  test('typing a message + Enter posts a UserTurn {text} frame on /ws/agent', async ({ page }) => {
    const sent: string[] = [];
    page.on('websocket', (ws) => {
      if (ws.url().includes('/ws/agent')) {
        ws.on('framesent', (f) => sent.push(typeof f.payload === 'string' ? f.payload : ''));
      }
    });

    await page.goto('/');
    const input = page.locator('[data-testid="agent-window.po"] [data-testid="composer-input"]');
    await expect(input).toBeVisible();
    await input.fill('PARITY-USERTURN-probe');
    await input.press('Enter');

    // the composer sends the message as a UserTurn frame on the agent's /ws/agent socket.
    await expect.poll(() => sent.join('|'), { timeout: 10_000 }).toContain('PARITY-USERTURN-probe');
  });
});
