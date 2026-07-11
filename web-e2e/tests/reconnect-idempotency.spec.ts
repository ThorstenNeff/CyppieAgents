import { test, expect } from '@playwright/test';
import { OPERATOR_TOKEN, SEED_AGENT, SEED_EVENT_COUNT } from './harness';

/**
 * Tooth B — **reconnect idempotency (no dups over seq).** A client that has consumed up to seq K and
 * reconnects with `since=K` must receive NOTHING already seen — the `seq > cursor` guard (CYP-198). Two
 * assertions, so a regression can't hide behind the client-side dedup:
 *   1. the SERVER sent zero frames on the reconnect (`agentFrames === 0`) — isolates the server guard;
 *   2. the rendered DOM still shows exactly seq 1..N with no duplicate row — the end-to-end guarantee.
 */
test('reconnecting with the last cursor delivers no duplicate (server seq>cursor + DOM)', async ({ page }) => {
  await page.goto('/');

  await page.evaluate(
    ([agent, token]) => window.cyppie.connectAgent(agent as string, 0, token as string),
    [SEED_AGENT, OPERATOR_TOKEN],
  );
  const all = Array.from({ length: SEED_EVENT_COUNT }, (_, i) => i + 1); // [1..5]
  await expect.poll(() => page.evaluate(() => window.cyppie.agentSeqs)).toEqual(all);

  // Drop + reconnect with the last cursor (since = lastSeq). agentFrames is reset inside reconnectAgent().
  await page.evaluate(() => window.cyppie.reconnectAgent());

  // The server must send nothing already seen. Give any stray replay a chance to arrive, then assert none did.
  await page.waitForTimeout(750);
  expect(await page.evaluate(() => window.cyppie.agentFrames)).toBe(0);
  await expect.poll(() => page.evaluate(() => window.cyppie.agentSeqs)).toEqual(all); // no dup row
});
