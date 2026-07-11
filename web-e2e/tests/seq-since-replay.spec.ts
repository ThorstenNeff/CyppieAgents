import { test, expect } from '@playwright/test';
import { OPERATOR_TOKEN, SEED_AGENT, SEED_EVENT_COUNT } from './harness';

/**
 * Tooth A — **seq `?since` replay, end-to-end against the agent-events feed.** `/ws/agent?since=N` must
 * replay exactly the durable transcript with `seq > N` (gapless, in order), per CYP-198/384. The corpus is
 * pre-seeded seq 1..SEED_EVENT_COUNT; connecting with `since=2` must yield exactly seq 3..N.
 *
 * This is the drift-catcher the type-stripped vitest units miss: it goes through the REAL store + REAL
 * socket, so a regression in the `?since` cursor or the wire shape reddens here.
 */
test('agent-events ?since=N replays exactly seq>N, gapless and ordered', async ({ page }) => {
  await page.goto('/');
  const SINCE = 2;
  await page.evaluate(
    ([agent, since, token]) => window.cyppie.connectAgent(agent as string, since as number, token as string),
    [SEED_AGENT, SINCE, OPERATOR_TOKEN],
  );

  const expected = Array.from({ length: SEED_EVENT_COUNT - SINCE }, (_, i) => SINCE + 1 + i); // [3,4,5]
  await expect.poll(() => page.evaluate(() => window.cyppie.agentSeqs)).toEqual(expected);
});

test('agent-events with no cursor replays the whole transcript (seq 1..N)', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(
    ([agent, token]) => window.cyppie.connectAgent(agent as string, 0, token as string),
    [SEED_AGENT, OPERATOR_TOKEN],
  );
  const all = Array.from({ length: SEED_EVENT_COUNT }, (_, i) => i + 1); // [1,2,3,4,5]
  await expect.poll(() => page.evaluate(() => window.cyppie.agentSeqs)).toEqual(all);
});
