import { test, expect } from '@playwright/test';

/**
 * CYP-422 Post-Deploy §1.1c — UNAUTH JS-redirect-LOOP detector (CYP-515 regression guard).
 *
 * WHY THIS EXISTS SEPARATELY FROM the curl §1.1b check: CYP-515 is a JS-level AuthGate loop
 * (`redirectToLogin()` via `window.location`), NOT an HTTP-302/303 chain. curl -L follows the one 303
 * to `/?flow=…`, gets the SPA HTML (200), and sees no HTTP loop → it would pass CYP-515 through (false
 * green). So the curl tooth (§1.1b) covers the HTTP-loop class; THIS tooth covers the JS-loop class.
 * Together they make the first, credential-free re-cut gate catch the whole CYP-515 class before a human.
 *
 * The mechanism: load `/` unauth and watch top-frame navigations. A JS loop keeps re-assigning
 * window.location and NEVER settles. Two independent, markup-agnostic LOOP signals (either failing =
 * CYP-515-class): (a) the navigation COUNT within the window exceeds a cap (a fast loop), and (b) the
 * page never went QUIET — still navigating in the final tail (a slow loop the count wouldn't catch).
 * A healthy flow settles into a stable login surface within a few hops.
 */
const URL = process.env.SMOKE_URL;
const OBSERVE_MS = 8000; // watch window after the initial load
const QUIET_MS = 2000;   // the tail must be navigation-free to count as "settled"
const NAV_CAP = 8;       // more top-frame navigations than this within OBSERVE = a (fast) loop

test.describe('Post-Deploy §1.1c · unauth JS login-loop detector (CYP-515)', () => {
  test.skip(!URL, 'set SMOKE_URL=https://<staging-web-ts-url>');

  test('§1.1c unauth `/` SETTLES into a stable login state — no JS-navigation loop', async ({ page }) => {
    const t0 = Date.now();
    const navs: { url: string; t: number }[] = [];
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) navs.push({ url: frame.url(), t: Date.now() - t0 });
    });

    // A mid-redirect goto can reject ("navigation interrupted"); we observe via the event stream, not the
    // goto promise. waitUntil:'commit' returns as soon as the first response commits.
    await page.goto('/', { waitUntil: 'commit' }).catch(() => { /* observed via framenavigated */ });
    await page.waitForTimeout(OBSERVE_MS);

    const count = navs.length;
    const lastNavAt = navs.length ? navs[navs.length - 1].t : 0;
    const quietTail = OBSERVE_MS - lastNavAt; // ms since the last top-frame navigation
    const trail = navs.map((n) => `  ${n.t}ms → ${n.url}`).join('\n');

    // (a) fast-loop signal: navigation count within the window stays under the cap.
    expect.soft(count, `top-frame navigations in ${OBSERVE_MS}ms (loop if > ${NAV_CAP}):\n${trail}`)
      .toBeLessThanOrEqual(NAV_CAP);
    // (b) slow-loop signal: the page went quiet (no navigation in the final QUIET_MS).
    expect.soft(quietTail, `ms since last top-frame navigation (settled needs >= ${QUIET_MS}):\n${trail}`)
      .toBeGreaterThanOrEqual(QUIET_MS);

    // …and it settled into an ACTUAL login surface (Kratos page / a login form), not a blank/error dead-end.
    // Markup-provisional: broaden/tune this matcher against the real Kratos page at the first re-cut; the
    // loop signals (a)/(b) above are the markup-agnostic CYP-515 catch and do not depend on it.
    const settledUrl = page.url();
    const loginSurface =
      /login|auth|kratos|\.ory|self-service/i.test(settledUrl) ||
      (await page.locator('input[type="password"], [name="identifier"], form[action*="login" i]').count()) > 0;
    expect.soft(loginSurface, `settled at ${settledUrl} but no login surface detected (matcher may need tuning)`)
      .toBe(true);
  });
});
