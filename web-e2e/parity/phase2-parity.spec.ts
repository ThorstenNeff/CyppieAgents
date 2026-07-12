import { test, memberTest, expect, SEED } from './fixtures';

/**
 * CYP-422 §A-P2 parity — the three Phase-2 surfaces that landed since the rig was built, driven against the REAL
 * Ktor harness in the CONFIRMED production topology (SAME-ORIGIN, via the Vite proxy). Each surface is proven from
 * BOTH postures: the operator-positive shape AND the member-posture gate discriminator — the operator-positive
 * test alone does not prove a gate. No extra seeding: with no run-state event the lifecycle is UNKNOWN, with no
 * key the api-key status is "not set"; the deeper seeded teeth (full state×enablement matrix, clear-after-save
 * round-trip, seq-gap, XSS-at-detail) are staged follow-ups noted to the PO.
 *
 * MEMBER-POSTURE GATE — staged, not dropped (no silent caps). The member gate discriminators (event-log OMITTED,
 * lifecycle/api-key present-but-DISABLED for a non-operator) need a member page that assembles. But this harness
 * authenticates REST by BEARER token only (E2ePlatform.agentToken/OPERATOR_TOKEN), while a real member serve
 * authenticates by cookie/session — so a no-token member page can't reach the API and never assembles. These
 * tests are therefore skipped with an explicit reason until the harness grows a member-session (cookie) seam; the
 * member-gating is meanwhile covered by the web-ts unit render tests (EventLogView/App .render.test.tsx). The
 * operator-positive teeth below do NOT by themselves prove the gate — that is the honest scope of this first cut.
 */
const MEMBER_SEAM_PENDING =
  'Harness is Bearer-token-only; a member serve authenticates REST by cookie/session, so a no-token member page ' +
  'cannot assemble. Needs a harness member-session seam. Member-gating meanwhile covered by web-ts unit render tests.';

// ── A-P2-a · Lifecycle-Header (CYP-431/445) ─────────────────────────────────────────────────────────────────
test.describe('A-P2-a · Lifecycle-Header — non-optimistic status + operator enablement', () => {
  test('operator: RUNNING (harness boots the agent) → honest label + the RUNNING enablement row (§8.4 Start-off)', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator(`[data-testid="lifecycle.header.${SEED.AGENT}"]`)).toBeVisible();

    // server-confirmed run-state: the FakeSpawner boots the seeded agent → RUNNING. Label "Aktiv", dot a FILLED
    // primary disc (the resolved run-colour, since no action is pending — non-optimistic still holds: this is the
    // CONFIRMED state, not a click-optimistic guess).
    await expect(page.locator(`[data-testid="lifecycle.status.${SEED.AGENT}"]`)).toContainText('Aktiv');
    const dot = page.locator(`[data-testid="lifecycle.dot.${SEED.AGENT}"]`);
    await expect(dot).toHaveAttribute('data-shape', 'fill');
    await expect(dot).toHaveAttribute('data-role', 'primary');

    // enablement matrix @ RUNNING + operator: Start OFF (the CYP-445 §8.4 discriminator — a single-flag gate would
    // leave it clickable), Stopp on, Neustart on.
    await expect(page.locator(`[data-testid="lifecycle.start.${SEED.AGENT}"]`)).toHaveAttribute('aria-disabled', 'true');
    await expect(page.locator(`[data-testid="lifecycle.stop.${SEED.AGENT}"]`)).toHaveAttribute('aria-disabled', 'false');
    await expect(page.locator(`[data-testid="lifecycle.restart.${SEED.AGENT}"]`)).toHaveAttribute('aria-disabled', 'false');
    await expect(page.locator(`[data-testid="lifecycle.operatorOnly.${SEED.AGENT}"]`)).toHaveCount(0);
  });
});

memberTest.describe('A-P2-a · Lifecycle-Header — operator gate (present-but-disabled, CYP-317)', () => {
  memberTest('member: controls PRESENT but ALL disabled + the operator-only note', async ({ page }) => {
    memberTest.skip(true, MEMBER_SEAM_PENDING);
    await page.goto('/');
    // the header assembles for the member too (present, not omitted) — the gate is about editing, not visibility.
    await expect(page.locator(`[data-testid="lifecycle.header.${SEED.AGENT}"]`)).toBeVisible();
    for (const control of ['start', 'stop', 'restart']) {
      await expect(page.locator(`[data-testid="lifecycle.${control}.${SEED.AGENT}"]`)).toHaveAttribute('aria-disabled', 'true');
    }
    await expect(page.locator(`[data-testid="lifecycle.operatorOnly.${SEED.AGENT}"]`)).toBeVisible();
  });
});

// ── A-P2-e · API-Key (CYP-433) — Klartext-nie-DOM ───────────────────────────────────────────────────────────
test.describe('A-P2-e · API-Key — masked-only status + write-only input, operator-enabled', () => {
  test('operator: section present, server-masked status only, write-only EMPTY password input', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('[data-testid="settings.section.apiKey"]')).toBeVisible();

    // §0.1 the stored key is NEVER plaintext in the DOM — only "Kein Schlüssel hinterlegt" or "Hinterlegt: ***last4".
    const masked = page.locator('[data-testid="settings.apiKey.masked"]');
    await expect(masked).toBeVisible();
    await expect(masked).toContainText(/Kein Schlüssel hinterlegt|Hinterlegt: /);

    // §0.2 the input is write-only + masked-by-default and starts EMPTY (the stored key is never loaded into it).
    const input = page.locator('[data-testid="settings.apiKey.input"]');
    await expect(input).toHaveAttribute('type', 'password');
    await expect(input).toHaveAttribute('autocomplete', 'new-password');
    await expect(input).toHaveJSProperty('value', '');
    await expect(input).toBeEnabled(); // operator
    await expect(page.locator('[data-testid="settings.apiKey.gateHint"]')).toHaveCount(0);
  });

  test('operator: save round-trip → the typed plaintext CLEARS and never lands in the DOM (§0.2 discriminating)', async ({ page }) => {
    await page.goto('/');
    const KEY = 'sk-ant-parity-clearaftersave-7777'; // >12 chars (CYP-104 plausibility); last-4 = 7777
    const input = page.locator('[data-testid="settings.apiKey.input"]');
    await expect(input).toBeVisible();

    // the typed plaintext is transiently in the input value — that is the ONLY place §0.2 permits it.
    await input.fill(KEY);
    await expect(input).toHaveJSProperty('value', KEY);
    // The settings window tiles UNDER others in the headless viewport, so its Save button is pointer-occluded (a
    // window-manager artifact, not a product bug). Dispatch the click DOM event directly on the button — it fires
    // React's onClick regardless of occlusion, exercising the real save→PUT→clear-after-save→leak path (the tooth's
    // subject), without depending on window focus mechanics.
    await page.locator('[data-testid="settings.apiKey.save"]').dispatchEvent('click');

    // success → the honest saved≠active effect hint appears, and §0.2 clear-after-save empties the input.
    await expect(page.locator('[data-testid="settings.apiKey.effectHint"]')).toBeVisible();
    await expect(input).toHaveJSProperty('value', '');

    // §0.1 the stored key now shows ONLY the server mask (***last4) — the last-4 confirms it is THIS key's mask,
    // and the full plaintext is never rendered.
    const masked = page.locator('[data-testid="settings.apiKey.masked"]');
    await expect(masked).toContainText('Hinterlegt:');
    await expect(masked).toContainText('7777');
    await expect(masked).not.toContainText(KEY);

    // The discriminating leak check: the ENTIRE api-key section's serialized HTML must not contain the plaintext
    // anywhere — no text node, no attribute (data-*/aria-*/title), no lingering value. (Drop clear-after-save →
    // the input still holds KEY → this fails.)
    const html = await page.locator('[data-testid="settings.section.apiKey"]').evaluate((el) => el.outerHTML);
    expect(html).not.toContain(KEY);
  });
});

memberTest.describe('A-P2-e · API-Key — operator gate present-but-disabled (NOT omitted, unlike the event-log)', () => {
  memberTest('member: section PRESENT (masked leaks nothing) but input disabled + gate hint', async ({ page }) => {
    memberTest.skip(true, MEMBER_SEAM_PENDING);
    await page.goto('/');
    await expect(page.locator('[data-testid="settings.section.apiKey"]')).toBeVisible();
    await expect(page.locator('[data-testid="settings.apiKey.input"]')).toBeDisabled();
    await expect(page.locator('[data-testid="settings.apiKey.gateHint"]')).toBeVisible();
  });
});

// ── A-P2-c · Event-Log (CYP-432) — operator-only mount-gating (defence-in-depth) ─────────────────────────────
test.describe('A-P2-c · Event-Log — operator-only mount-gating', () => {
  test('operator: the event window is mounted with a live-tail status', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('[data-testid="event-log"]')).toBeVisible();
    await expect(page.locator('[data-testid="event-log-status"]')).toBeVisible();
  });

  test('operator: a detail-borne XSS payload renders INERT (B-1 sink — text, no element, no onerror)', async ({ page }) => {
    // /ws/events streams LIVE (no history replay), so connect first, then emit the probe event live.
    const eventsWs = page.waitForEvent('websocket', (ws) => ws.url().includes('/ws/events'));
    await page.goto('/');
    const log = page.locator('[data-testid="event-log"]');
    await expect(log).toBeVisible();
    await eventsWs; // the operator's /ws/events socket is open
    await page.waitForTimeout(300); // let the server-side subscribe attach before we emit
    // Trigger the harness to append one Event-Log event whose `detail` carries the XSS payload (server-side, direct
    // to :8791 — not through the proxy, which only forwards /api + /ws).
    const emit = await page.request.get('http://127.0.0.1:8791/test/emit-xss');
    expect(emit.ok()).toBe(true);

    // the live event arrives → a row; the payload is rendered as escaped TEXT (never swallowed → "onerror" present).
    await expect(log.locator('[data-testid="event-log-rows"]')).toContainText('onerror');

    // INERT (the discriminating pair): the payload's <img> was NOT parsed into a real element (escaped text only),
    // and its onerror never ran. A `detail` rendered via innerHTML instead of a text child would fail BOTH.
    await expect(log.locator('img')).toHaveCount(0);
    expect(await page.evaluate(() => (window as { __xssFired?: boolean }).__xssFired ?? false)).toBe(false);
  });
});

memberTest.describe('A-P2-c · Event-Log — operator-only mount-gating (member gets NOTHING)', () => {
  memberTest('member: NO event window mounted AND no /ws/events socket opened (layers 1+2)', async ({ page }) => {
    memberTest.skip(true, MEMBER_SEAM_PENDING);
    const wsUrls: string[] = [];
    page.on('websocket', (ws) => wsUrls.push(ws.url()));
    await page.goto('/');

    // Prove the member page actually assembled a gate-able surface (else "event-log absent" is a trivial false-green):
    // the settings section is present for EVERYONE, so its presence is the assembly witness.
    await expect(page.locator('[data-testid="settings.section.apiKey"]')).toBeVisible();

    // layer 1: no event window at all — not even the operator-only placeholder (the window itself is never mounted).
    await expect(page.locator('[data-testid="event-log"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="event-log-operator-only"]')).toHaveCount(0);

    // layer 2: the /ws/events socket is never opened for a member (the handlers are wired only for the operator).
    await page.waitForTimeout(500);
    expect(wsUrls.some((u) => u.includes('/ws/events'))).toBe(false);
  });
});
