# Gateway Session-State + Revoke (§3) & Discovery (§4) · web-ts UX-Spec

**Für:** Dev5 · **Von:** UIUX2 (Team-2) · **Baseline:** develop `c72a05e1` (am Objekt gemessen 2026-07-19) · **Begleitet:** `gateway-basis-connect-ux-spec.md` (§2 Connect)
**Scope:** die restlichen Gateway-BASIS-Screens (Browser→Gateway→EIN Hub, same-origin). Team-2.
**Tooling-Grenze:** Zustände headless render-test-messbar; Runtime/Pixel = guided-human.

---

## §3 Session-State + Revoke — VERIFY-EXISTENCE: existiert weitgehend, reuse + ein kleiner Zusatz
**Der Session-Indicator + Logout EXISTIEREN schon** (CYP-515/470, `AuthGate.tsx`):
- `AuthGate.tsx:8`: *„Active → a content-free session indicator (role + logout) + the app"*. Der `auth.logout`-Button (`:97`) → `redirectToLogout` → **Kratos self-service logout** (`logoutUrl()`, `CYPPIE_LOGOUT_URL`, `main.tsx:32`).
- `AuthGate.tsx:52`: *„A protected /api 401 (session expired/revoked mid-session) → back to the in-app LoginScreen. IN-APP re-auth"* — **server-authoritativ, non-optimistisch** (der Zustand flippt auf den echten 401, nicht spekulativ).
- **content-free** (Rolle, keine Identität — `AuthMe`), konsistent mit dem enumeration-safe CYP-515-Design.

**Der Gateway-Zusatz (das einzig Neue, klein):** der Session-Indicator zeigt heute **Rolle + Logout**; für den Gateway-Kontext **den Verbindungs-Kontext + Tier ergänzen** — „verbunden als \<Rolle\> über **Browser-Gateway**" + der **CYP-676 `BROWSER_GATEWAY`-Tier-Badge** (dieselbe **immer-sichtbare** Disclosure wie im Connect, §2 BASIS-Spec). So ist die **Session ehrlich über ihre Sicherheits-Stufe**, nicht nur über die Rolle. Fail-closed default UNKNOWN (nie optimistic-NATIVE-green).

**Revoke / Trennen = der bestehende Logout** (Kratos-Session-Kill) → `LoginScreen`. **Non-optimistisch.** Terminaler WS-Revoke (1008) = der bestehende comm-`revoked`-Pfad („Zugriff entzogen", terminal ≠ reconnectable).

**Zustände (reuse, headless-messbar):**
| Zustand | Render |
|---|---|
| active | Session-Indicator: Rolle **+ Tier-Badge (BROWSER_GATEWAY, immer-sichtbar)** + Logout |
| session expired/revoked (401 mid-session) | → `LoginScreen` (in-app re-auth), non-optimistisch |
| WS revoked (1008) | comm-`revoked`-Banner (terminal) |
| Tier unbestimmt | Badge **UNKNOWN** (fail-closed, nie NATIVE) |

**Netto:** **reuse `AuthGate` + wire den Tier in den Session-Indicator.** **Kein neuer Session-Store.** Honesty-Zähne: (1) non-optimistisch (Zustand auf Server-401/Logout-Bestätigung, nie spekulativ); (2) Tier **auch im Session-Indicator** immer-sichtbar (nicht nur im Connect); (3) „revoked" terminal ≠ reconnectable; (4) content-free (Rolle, keine Identität).

## §4 Discovery — BOUNDARY-FINDING: für BASIS N/A (Team-1/Relay, edge-excluded), KEIN Team-2-Screen
Am Objekt geprüft, bevor ich einen Screen spezifiziere:
- **Discovery = `GET /api/cp/hubs`** (CYP-530, `HubDiscoveryRoutes` / `RestContract.kt:136`): *„the GUI's hub picker"*, owner-gefiltert, `online`/`lastSeen` advisory (H1), abgeleitet aus `RelayRendezvous`.
- **★ Es liegt unter `/api/cp/` (`CONTROL_PLANE_PREFIX`) — am Gateway-Edge AUSGESCHLOSSEN** (default-DENY, `GatewayServer.kt`: *„the `/api/cp` prefix … excluded from the data-plane allowlist"*). Discovery ist damit ein **Control-Plane/Relay-Feature** (Multi-Hub-Picker über Rendezvous) = **Team-1s Relay-Leg**, **nicht** im Browser-Gateway-Data-Plane.
- **Im BASIS-Modell (Browser→Gateway→EIN Hub, same-origin) gibt es nichts zu „entdecken":** genau **ein** Hub (die Origin), kein Hub-Picker. „Ist mein Hub erreichbar" = die **connection-state** (existiert schon: live/connecting/offline/revoked, comm) — **kein** separater Discovery-Screen nötig.
- **→ Discovery ist KEIN Team-2 web-ts-BASIS-Screen.** Ich spezifiziere hier **keinen** Discovery-Screen; ich flagge die **Grenze mit Objekt-Beleg** (`/api/cp/hubs` unter dem edge-excluded Prefix). Falls je der **Multi-Hub-Picker** in den Browser soll, ist das die **Team-1-Relay-Koordination** (wie die §5-Grenze im BASIS-Connect-Spec) — additive Entscheidung, nicht dieser Scope.

## §5 Übergabe-Flags
- **§3 build-ready (Team-2):** reuse `AuthGate` (Session-Indicator + Logout + 401-re-auth) **+ das eine Neue = Tier ins Session-Indicator wiren** (CYP-676, konsistent mit dem Connect-§2). Kein neuer Store.
- **§4 Discovery = OUT für Team-2-BASIS** (Control-Plane/Relay, edge-excluded = Team-1) — Boundary geflaggt, **kein Screen**. „Ist mein Hub da" deckt die bestehende connection-state.
- **Damit sind die Gateway-BASIS-Screens komplett:** Connect (§2 BASIS-Spec) + Session/Revoke (§3, reuse+Tier) + Discovery (§4, N/A-Boundary). **Der load-bearing neue Code über ALLE Gateway-Screens = das eine CYP-676-`BROWSER_GATEWAY`-Tier-Wiring** (Connect **und** Session-Indicator) — = PL-0009 Bedingung-1 (Trust-Delta ehrlich in der UI).
