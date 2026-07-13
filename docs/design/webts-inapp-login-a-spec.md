# web-ts In-App-**Login-Core** (a) — Login rigoros; die 5 anderen Screens nur umrissen

> Owner: UIUX-Designer · **Fix-Pfad (a) für CYP-515, Login-Core** (Team-Call GO, PO1-Guardrail: nur Login-Core jetzt) · **kehrt CYP-470 redirect-only um** · Stand 2026-07-12
> Basis `origin/develop` `2128ea62` · **Design-Quelle: `docs/design/auth-spec.md` (CYP-176)** (DOM-Port via Kratos-**API-Flow**). Docs-only → **Dev5-Referenz, HÖCHST-STAKES (rohe Credentials im DOM).** Re-Cut nicht zeit-gecapped → **Korrektheit vor Tempo.**
> **CYP-Key ausstehend → Branch beschreibend.** **(b) Kratos-hostet-alle-6 = `CYP-518`** (deferred; mein Security-Lean; der 5-Screen-Outline §4 ist Scope-Input dafür).
> **Quelle:** `auth/AuthGate.kt`(LoginScreen) · `AuthViewModel.kt`(`Phase`) · `AuthRepository.kt`(Honesty) · `AuthTags.kt`(`auth.login.*`) · `auth_*`-Keys. **0 neue Keys/Tags.**

---

## 0. Scope-Cap (PO1-Guardrail) — anti-spekulativ

**Jetzt rigoros gespec't: NUR der Login-Core** (Login-Screen + Bootstrap/Phase + Session/Härtung). Er beweist den **CYP-515-Fix** (kein Redirect-Loop) + den (a)-Fallback (in-app-Credentials statt Browser-Redirect). **Die 5 anderen Screens** (Register · Forgot · Reset · Verify · GitHub) sind **nur umrissen** (§4) — **nicht** voll gespec't, bis **(a) bestätigt** ist: verwirft der Auftraggeber (a) für **(b)/CYP-518**, wären alle 6 throwaway (dieselbe Anti-Spekulativ-Logik wie beim Build). Der Outline geht als **Effort-Scope-Input in CYP-518**.

---

## 1. Honesty-Contract (Port CYP-176 §1) — kommt zurück in web-ts

Was redirect-only an Kratos auslagerte, trägt web-ts jetzt selbst: **keine Account-Enumeration** · **Rate-Limit ehrlich (429/`retryAfter`)** · **Deep-Link-Tokens opak** · **kein Success-Grün** (Erfolge neutral/INFO) · **Phase-Achse, kein Spinner**.

> **⟂Backend/Kratos (load-bearing):** Enumeration-Sicherheit **lebt/stirbt server-seitig** — Login **konstant-zeitlich + uniform**. Die neutrale UI ist die halbe Mitigation.

---

## 2. LOGIN-CORE (rigoros) — der Re-Cut-Kern

### 2.1 Der Login-Screen (`Unauthenticated`)
Zentriertes, breiten-begrenztes Formular (~400px; Phone voll+Padding, Desktop zentriert-gecappt). `auth.login.form`:
1. Titel `auth_login_title` (`role="heading"`).
2. **E-Mail** `auth.login.email` — `<input type="email">`, `autocomplete="username"`, `inputmode=email`.
3. **Passwort** `auth.login.password` — `<input type="password">` (§2.3) + **Reveal** `auth.login.passwordReveal` (Text-Label `auth_password_show`/`_hide`, **kein Emoji**; togglet `type` password↔text **nur am Input**, `contentDescription` `a11y_auth_password_show`/`_hide`).
4. **Submit** `auth.login.submit` (`auth_submit_login`; **disabled** bei leerem Feld / `submitting`).
5. **Fehler** `auth.login.error` = **generisch** `auth_login_error_generic` (`role="alert"`) · **Rate-Limit** `auth.login.rateLimited` (`auth_rate_limited`/`_wait`, **amber**, `role="status"`, nie ERROR).
6. Links `toRegister` / `toForgot` (Button-Rolle). **Kein „Angemeldet bleiben"-Default.** *(GitHub-Button = P2, §4/Outline.)*
- **Unverifizierter Login** → `AuthedUnverified` (das bestehende **CYP-470-Verify-Gate**, Outline §4) — kein App-Zugang.

### 2.2 Bootstrap / Phase-Achse
- **Loading** (`auth.loading`, `auth_loading`) beim Start (Session-Prüfung) — **KEIN Login-Flash** vor der Antwort (sonst „ausgeloggt"-Flackern für Eingeloggte). Prüfung scheitert (Netz) → `Unauthenticated` mit ehrlichem Hinweis, nie stilles Durchreichen.
- **Phase** (eine je Screen): `Idle` aktiv · `Submitting` **Felder+Button disabled**, Label→`auth_submitting`, **KEIN Spinner** · `Error` aktiv+Retry, `role=alert`, **generisch** · `RateLimited(retryAfter?)` Submit **disabled bis Ablauf**, **amber**. **Server = Source of Truth; UI rät nie, täuscht nie Erfolg vor.** Disabled ≠ Fehler.

### 2.3 Die 4 Assist-Review-Achsen + Härtung (der KERN — rohe Credentials im DOM)

**① Passwort-Exfil-frei.** `type="password"`, `autocomplete="current-password"`; der Wert **NIE** in `data-*`/`aria-*`/`title`/anderem Attribut, **nie** im DOM außerhalb `value`, **nie** in `localStorage`/`sessionStorage`/`document.cookie`/`console`/Telemetrie/Fehler-String. **Body-POST** (Credentials im **Request-Body**, **nie** in der URL/Query → kein Referrer-/History-/Log-Leak). **Clear-after-submit** (Feld nach Submit leeren). **0 neue Inline-Scripts** — die CSP-Nonce-Naht (`script-src 'self' 'nonce-…'`) hält; **kein Muster hier braucht Inline-Script/`unsafe-eval`/`blob:`** → ein XSS kann kein Skript einschleusen, das das Feld liest. **Kein `innerHTML`/`dangerouslySetInnerHTML`** (server-Meldung als **Text**, JSX-escaped).

**② Enumeration-Safety.** Der **Client kollabiert ALLE** Auth-Fehler-Codes des Servers auf **EINEN** generischen String (`auth_login_error_generic`) — **auch** wenn der Server distinkte Codes liefert (defense-in-depth). **Nie** „User existiert nicht" / „falsches Passwort" unterscheidbar; keine Antwortzeit-Verzweigung im Client.

**③ Rate-Limit-Honesty + kein Loop (CYP-515-Lektion).** Ein 429 → ehrlicher amber `auth.login.rateLimited` (`retryAfter`→`auth_rate_limited_wait`), Submit disabled bis Ablauf — **kein Auto-Retry-Loop, kein Swallow**. **★ Login-401-Loop-Guard:** ein **fehlgeschlagener Login gibt selbst 401**; der CYP-470-Hook `setOnUnauthorized(redirectToLogin)` darf dadurch **NICHT** auf der Login-Fläche feuern (sonst Redirect-Schleife/Flackern, CYP-515-Klasse). **Regel:** der Login-Submit-401 wird **lokal** als generischer `auth.login.error` behandelt und triggert den **globalen Re-Auth-Hook nicht** (der Hook ist für 401 auf **geschützten** Routen / nicht wenn schon `Unauthenticated`). Fehler **einmal sichtbar**, manueller Retry.

**④ CSRF / Flow-Token.** Der API-Flow-CSRF-Token (aus Flow-Init) wird beim Submit **im Body** mitgesendet, **nie** gerendert/geloggt/als Tag. **Stale-Flow/Token → fail-closed:** abgelaufen ⇒ **NICHT** mit stalem Token submitten — Flow **neu initialisieren**, Nutzer neu tippen lassen; nie raten.

**Session = server-gesetztes httpOnly-First-Party-Cookie, NIE ein JS-Token.** Login-Erfolg ⇒ der **Server setzt das httpOnly Session-Cookie** (`rest.ts` `credentials:'include'`); **web-ts hält kein Session-Token in JS.** Die SPA liest den Response-Body **nie** → sieht kein Token. whoami/operator/401 (CYP-470 §3/§5) tragen über das Cookie unverändert.
> **RECONCILE (gemergter Impl `e8c6a86a`, Static-QA'd GO 2026-07-13):** die Impl erreicht die httpOnly-Cookie-Invariante über den **Kratos-BROWSER-Flow mit `Accept: application/json`** (`GET /self-service/login/browser` → Flow als JSON, kein 303; `POST /self-service/login?flow=<id>`), der die httpOnly-`ory_kratos_session` **nativ** setzt — **sauberer** als der im Spec-Text zitierte `/login/api`-mit-Proxy-Token-Swap (kein Custom-Proxy, kein `X-Session-Token` im Umlauf). **Die §2.3-Invariante (httpOnly-Cookie, kein JS-Token) hält; das Mechanismus-Detail ging an die klarere Naht.** (Backend2-verifiziert #4.)

**CSP unverändert:** Login-POST **same-origin** (`/.ory/kratos/.../login/browser` + `/login?flow=`) → `connect-src 'self'` ✓; kein externer Host; die gehärtete CSP (object-src none, frame-ancestors none, kein unsafe-eval) trägt ohne Änderung.

---

## 3. Login-Core — Invarianten + Abnahme-Zähne

**Invarianten:** ① Passwort nie exfiltrierbar (kein data-*/value-nach-submit/localStorage/cookie/log; Body-POST; 0 inline-script; kein innerHTML) · ② alle Auth-Fehler = ein generischer String · ③ 429 ehrlich, kein Auto-Retry/Swallow, **Login-401 kein globaler Re-Auth-Loop** · ④ CSRF opak + stale-fail-closed · Session=httpOnly-Cookie nie JS-Token · kein Success-Grün / Rate-Limit-amber-nicht-ERROR · Phase kein Spinner, **kein Login-Flash** vor Session-Prüfung.

**Zähne (diskriminierend — je mit der falschen Impl):**
1. **★ Passwort-Exfil-frei.** **Mut:** Passwort in `data-*`/`aria`/`title`/`console`/Telemetrie/Fehler / in `localStorage`/`sessionStorage`/`document.cookie` / in der URL-Query / bleibt nach Submit im `value` ⇒ rot.
2. **★ Kein JS-Session-Token.** **Mut:** Kratos-Session-Token in JS/`localStorage` statt httpOnly-Cookie ⇒ rot (XSS-Exfil).
3. **★ Enumeration.** Falsches Passwort **und** unbekannter Account → **identische** generische Meldung. **Mut:** unterscheidbare Meldung, auch bei distinkten Server-Codes ⇒ rot.
4. **★ Login-401-Loop-Guard.** Fehlgeschlagener Login → lokaler generischer Fehler, **kein** globaler Re-Auth-Redirect. **Mut:** Login-401 triggert `redirectToLogin` → Schleife/Flackern (CYP-515) ⇒ rot.
5. **Rate-Limit ehrlich.** **Mut:** 429 als fake-„ok"/Auto-Retry-Loop/Swallow ⇒ rot.
6. **CSRF stale→fail-closed.** **Mut:** Submit mit stalem CSRF/Flow-Token statt Re-Init ⇒ rot.
7. **Kein Inline-Script / `innerHTML`.** **Mut:** Inline-Script/`unsafe-eval`-Muster / `dangerouslySetInnerHTML` / server-Meldung als HTML ⇒ rot.
8. **Kein Success-Grün / kein Login-Flash.** **Mut:** Rate-Limit im ERROR-Ton / Login-Screen vor der Session-Prüfung gerendert ⇒ rot.

---

## 4. OUTLINE der 5 anderen Screens (für CYP-518 a-vs-b-Effort — **NICHT** voll gespec't)

Kurz-Umriss (Design-Quelle CYP-176 §3–§6; volle Spec erst wenn (a) bestätigt):
- **Verify (`AuthedUnverified`)** — **existiert schon** als CYP-470-Verify-Gate (Titel/E-Mail-Echo/GATED-Hinweis-neutral/Resend-neutral/Logout); im (a)-Vollausbau erbt es Rate-Limit-Honesty + neutrale Resend. Hartes Gate, kein App-Zugang.
- **Register** — E-Mail/Passwort+Reveal/**Bestätigen**/Passwort-Regel(INFO, kein Fake-Stärke-Meter)/Submit; Client-Validierung (leer/ungültige-Mail/Mismatch); **Erfolg→AuthedUnverified NEUTRAL** (nie „Konto angelegt für <email>"); generischer Fehler + 429-amber.
- **Forgot** — E-Mail/Submit; **neutrale** „Falls ein Konto existiert…"-Meldung (INFO); 429-amber; **kein** Enumeration-Leak.
- **Reset (Deep-Link)** — Neues-Passwort+Bestätigen; **Erfolg→terminaler neutraler Zustand** („Passwort geändert, jetzt anmelden", **kein** Auto-Login); **Token-invalid→ehrlicher Fehler**; opakes Token.
- **GitHub-OIDC (P2)** — „Mit GitHub anmelden" → OIDC-Redirect (Kratos macht State+PKCE); `verified=false`-Default → Verify-Gate. **OIDC IST ein Redirect** → der **CYP-470-§2.1-`?flow=`-Guard gilt** (kein Re-Redirect-Loop).

**Gemeinsame Achsen (erben alle 5 im Vollausbau):** Enumeration-Safety · Rate-Limit-Honesty · kein Success-Grün · a11y-Live-Regions (Fehler `assertive`/neutral `polite` — heute im Code fehlend, additiv) · XSS-Härtung (§2.3).

---

## 5. DOM/A11y + Reuse
- **Mount:** der Gate wrappt den Desktop — **ersetzt** den gemergten CYP-470 redirect-only `AuthGate.tsx` (`None`→Login-**Screen** statt Redirect; die `?flow=`-Guard-Naht bleibt nur für den **OIDC**-Return). whoami/verified/operator (CYP-470 §3) + Logout + 401→Re-Auth (mit §2.3-③-Guard) **bleiben**.
- **a11y (Login-Core):** Feld-Label + `contentDescription`; Reveal Text-Label + a11y (kein Emoji); Titel `heading`; `imeAction`-Kette Email→password→submit; **Fehler-Node `role="alert"`/`aria-live="assertive"`, Rate-Limit `role="status"`/`aria-live="polite"`** (additives Muster, im Ist-Code fehlend); RTL gespiegelt; Zielgröße ≥24px (Reveal/Submit).
- **Keys/Tags 0 neu** — `auth_*` + `auth.login.*` (`AuthTags`) verifiziert vorhanden; final gg. `auth-tags.md`/`strings.xml` beim Landen; liveRegion = Attribut (kein Key).

**Nichts gebaut — Login-Core-Spec rigoros + 5-Screen-Outline (CYP-518-Scope-Input).** Der Kern: rohe Credentials im DOM **exfil-frei** (Body-POST, kein data-*/log/localStorage/cookie, clear-after-submit, httpOnly-Cookie statt JS-Token), **Enumeration** auf einen String kollabiert, **429 ehrlich ohne Loop** (inkl. Login-401-Guard = CYP-515-Lektion), **CSRF stale-fail-closed**, **alles UNTER der strikten CSP als Passwort-Barriere** (kein Inline-Script/`unsafe-eval`). Der volle 6-Screen-Ausbau + die stärkere redirect-only-Posture warten als **CYP-518**.
