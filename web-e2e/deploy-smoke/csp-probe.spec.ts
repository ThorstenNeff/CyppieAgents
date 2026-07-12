import { test, expect } from '@playwright/test';

/**
 * CYP-422 Post-Deploy §1.3 — CSP ACTIVE-probe against the LIVE staging stand (unauth, no login). "Doc HAVE ≠
 * ENFORCED": a CSP header that's present but not actually enforced is worthless — so we inject a nonce-less inline
 * <script> and prove the browser BLOCKS it (a script-src securitypolicyviolation fires + the script never runs).
 *
 * NOTE on redirect: an unauth `/` may 302 to Kratos (a different origin/CSP). If so, this probes whatever renders;
 * the SPA's authenticated CSP + behavior is confirmed in the GUIDED logged-in step. Point SMOKE_URL at the served
 * web-ts shell if the deploy serves it before the client redirect. Header presence is in deploy-smoke-headers.sh.
 */
const URL = process.env.SMOKE_URL;

test.describe('Post-Deploy §1 · CSP active-probe (unauth)', () => {
  test.skip(!URL, 'set SMOKE_URL=https://<staging-web-ts-url>');

  test('§1.3 a nonce-less inline <script> is BLOCKED by CSP (actively enforced) + no console errors on load', async ({ page }) => {
    const consoleErrors: string[] = [];
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
    page.on('pageerror', (e) => consoleErrors.push(String(e)));

    await page.addInitScript(() => {
      (window as unknown as { __csp: string[] }).__csp = [];
      document.addEventListener('securitypolicyviolation', (e) => {
        (window as unknown as { __csp: string[] }).__csp.push((e as SecurityPolicyViolationEvent).violatedDirective);
      });
    });
    await page.goto('/');

    // inject a NONCE-less inline script that would set a flag; a nonce-based script-src (no unsafe-inline) must block it.
    await page.evaluate(() => {
      const s = document.createElement('script');
      s.textContent = 'window.__cspProbeRan = true;';
      document.body.appendChild(s);
    });

    // the injected inline script must NOT have executed …
    expect(await page.evaluate(() => (window as unknown as { __cspProbeRan?: boolean }).__cspProbeRan ?? false)).toBe(false);
    // … and a script-src CSP violation must have been REPORTED (proves active enforcement, not mere header presence).
    const violations = await page.evaluate(() => (window as unknown as { __csp?: string[] }).__csp ?? []);
    expect(violations.join('|')).toContain('script-src');

    // §4.1 hygiene: the landing renders with no console errors / uncaught exceptions.
    expect(consoleErrors, `console errors:\n${consoleErrors.join('\n')}`).toHaveLength(0);
  });
});
