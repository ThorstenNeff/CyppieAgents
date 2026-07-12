# web-ts In-App-Login (a) — API-Flow-Credentials im DOM, rigoros gehärtet

> Owner: UIUX-Designer · **Fix-Pfad (a) für CYP-515** (Team-Call GO, PO1-autorisiert, 8h-Fenster) · **kehrt CYP-470 redirect-only um** · Stand 2026-07-12
> Basis `origin/develop` `2128ea62` · **Design-Quelle: `docs/design/auth-spec.md` (CYP-176)** — dies ist der **DOM-Port** der live-akzeptierten branded-Login-Fläche über den Kratos-**API-Flow** (`/self-service/login/api`). Docs-only → **Dev5-Referenz, high-stakes.**
> **CYP-Key ausstehend → Branch beschreibend, re-anker bei Ticket.** **(b) redirect-only = `CYP-518`** (deferred Auftraggeber-Härtung, mein Security-Lean — nicht dieser Spec).
> **Quelle (Port):** `auth/AuthGate.kt` (LoginScreen/Register/Verify/Forgot) · `AuthViewModel.kt` (Phase/AuthUiState) · `AuthRepository.kt` (Honesty-Contract) · `AuthTags.kt` (48 Tags) · `auth_*`-Keys. **0 neue Keys/Tags erwartet** (die branded-Keys/Tags existieren bereits, waren für genau diesen Flow gedacht).

---

## 0. Kontext — warum (a), und was zurück in web-ts muss

**Entscheidung (Team-Call):** web-ts matcht die **live-akzeptierte** Posture des abgelösten WASM — **In-App-Credentials via Kratos-API-Flow** (`/login/api`, kein Browser-Redirect, kein `?flow=`-Loop). Meine CYP-470 redirect-only war eine **strengere** Wahl (SPA fasst nie Credentials an) — sie ist als **CYP-518** deferred (Auftraggeber-Call).

**Konsequenz:** die ganze Ehrlichkeits-/Sicherheits-Last, die redirect-only an **Kratos** ausgelagert hatte, kommt **zurück in web-ts** — denn web-ts rendert jetzt selbst das Credential-Formular. **Das ist die höchst-stakes Fläche der App (rohe Credentials im DOM).** Diese Spec bringt die CYP-176-Honesty **plus** die DOM-spezifische XSS/CSP-Härtung (CYP-455/456-Disziplin) zurück.

---

## 1. Der Honesty-Contract (Port CYP-176 §1) — muss zurück in web-ts

Der DOM-Login **muss** dieselben Ehrlichkeits-Regeln tragen, die redirect-only an Kratos delegiert hatte:

1. **Keine Account-Enumeration.** Login-Fehler ist **generisch** (`auth_login_error_generic` — dieselbe Meldung für falsches Passwort **und** nicht-existierenden Account, `auth.login.error`). Register + Forgot antworten **neutral**, identisch ob der Account existiert oder nicht („Falls ein Konto existiert, …", `ForgotRequest.sentTo`).
2. **Rate-Limit ehrlich (429):** jede Operation kann `RateLimited(retryAfter)` zurückgeben (`auth.login.rateLimited`/`auth.register.rateLimited`) — **nie** ein gefälschtes „gesendet".
3. **Deep-Link-Tokens (Verify/Reset) sind opak:** nie gerendert, nie geloggt, nie als Tag/Key-Segment, nie in der sichtbaren URL belassen (§2.2).
4. **Phase-Achse überall gleich** (`idle/loading/error/rate-limited`) — **kein Spinner** (Repo-Konvention), Felder disabled während `loading`.

> **Load-bearing (auth-spec §7):** Enumeration-Sicherheit **lebt oder stirbt server-seitig** — der Server muss **konstant-zeitlich + uniform** antworten (Login), und Register/Forgot **unabhängig** von Account-Existenz. Die neutrale UI-Wortwahl ist nur die **halbe** Mitigation. **⟂Backend/Kratos-Auflage, im Spec markiert.**

---

## 2. State-Machine (Port CYP-176 §2 / `AuthViewModel`)

`AuthUiState`: **Unauthenticated(login)** · **Register** · **ForgotRequest**(`sentTo`→neutrale Zeile) · **ResetSetNew**(Code-Entry, CYP-227) · **AuthedUnverified**(Verify-Gate, = mein CYP-470 §2 Verify-Gate, **reuse**) · **VerifySuccess**(`tokenInvalid`→ehrlicher Fehler statt stillem Erfolg) · **Verified**(tier). Jede form-tragende Fläche rendert die **Phase-Achse** (§1.4).

**Screens (Port §3–§5), Tags = `AuthTags` (existieren):**
- **Login** (`auth.login.*`): `email` · `password` (`type=password`) · `passwordReveal` · `submit` · `toRegister` · `toForgot` · `error` (generisch) · `rateLimited`.
- **Register** (`auth.register.*`): `email` · `password` · `passwordConfirm` · `submit` · neutrale Notice · `error`/`rateLimited`.
- **Verify-Pending** (`auth.verify.*`): `email` · `gateHint` · `resend`(neutral) · `logout` — **kein App-Zugang** (guarded 401). *(= mein CYP-470-Verify-Gate; reuse, jetzt Teil dieser State-Machine.)*
- **Forgot/Reset** (neutral, opake Tokens).

---

## 3. DOM-Credential-Rigor (der KERN dieser Spec — was (a) verlangt, das (b) nicht musste)

**Höchst-stakes: rohe Credentials sind jetzt im web-ts-DOM. Diese Regeln sind bindend, mit-Abnahme-Zahn.**

### 3.1 Session = server-gesetztes **httpOnly-First-Party-Cookie**, NIE ein JS-gehaltenes Token
- Der Login-POST (Erfolg) führt dazu, dass der **Server ein First-Party httpOnly Session-Cookie setzt** (der bestehende Cookie-Pfad — `rest.ts` sendet schon `credentials:'include'`; der Server akzeptiert das Cookie). **web-ts hält KEIN Session-Token in JS.**
- **NIEMALS** ein Kratos-Session-Token in `localStorage`/`sessionStorage`/JS-Variable persistieren — das wäre XSS-exfiltrierbar **und** überlebt Reloads. (Der `X-Session-Token` des rohen API-Flows wird **server-/proxy-seitig** in das httpOnly-Cookie umgesetzt; die SPA sieht ihn nie.) **⟂Backend/Deploy-Naht: der Login-Endpoint muss das httpOnly-Cookie setzen** (nicht der SPA ein Token zurückgeben) — das ist die XSS-härteste (a)-Form und hält (a) auf der Cookie-Achse fast so stark wie (b).
- Reload/Session-Persistenz trägt das httpOnly-Cookie (nicht JS). whoami (`/api/auth/me`) liest die Session über das Cookie — unverändert zu CYP-470 §3 (whoami treibt operator, fail-closed).

### 3.2 Der Credential-Wert ist **transient-only**
- `password`-Input `type="password"`, `autocomplete="current-password"` (Login) / `"new-password"` (Register); **kein** persistierender `name`, der reflektiert.
- Der Wert wird **NIE** reflektiert: nicht in `data-*`/`aria-*`/`title`/andere Attribute, nicht in den DOM-Baum außerhalb des `value`, **nie geloggt** (`console.*`/Telemetrie), **nie in einer Fehlermeldung**. Nach erfolgreichem Submit **Feld leeren**.
- Existiert **nur** transient im Input-`value` + im POST-Body (HTTPS same-origin). Kein Paste-Handler, der ihn spiegelt.

### 3.3 Kein `innerHTML` (CYP-456-Disziplin)
- **Ausschließlich JSX** — **kein** `dangerouslySetInnerHTML` in den Auth-Screens. Die server-getriebenen **neutralen Meldungen** (Kratos-`sentTo`/Fehler) werden als **Text** gerendert (JSX-escaped), **nie** als HTML injiziert (`noInnerHtml.test`-Muster gilt hier verschärft).

### 3.4 CSP-Fit (CYP-455-Disziplin) — **keine** CSP-Änderung nötig
- Der Login-POST geht **same-origin** (`/.ory/kratos/public/self-service/login/api` bzw. same-origin-Proxy) → `connect-src 'self'` deckt ihn ✓. Kein externer Host, **kein** `unsafe-eval`/`blob:` nötig → die bestehende gehärtete CSP (script-src self+nonce, object-src none, frame-ancestors none) trägt unverändert.
- **CSRF:** der API-Flow-CSRF-Token (aus dem Flow-Init) wird beim Submit mitgesendet, **nie** gerendert/geloggt/als Tag.

---

## 4. Ehrlichkeits-/Sicherheits-Invarianten (Basis der Abnahme)

1. **Generischer Login-Fehler — keine Enumeration** (§1.1); Register/Forgot neutral, account-existenz-unabhängig.
2. **Rate-Limit ehrlich (429/retryAfter)**, nie gefälschtes „gesendet" (§1.2).
3. **Kein Session-Token in JS/`localStorage`** — httpOnly-Cookie, server-gesetzt (§3.1).
4. **Credential-Wert transient**, nie reflektiert/geloggt/im Fehler, nach Submit geleert (§3.2).
5. **Kein `innerHTML`**; server-Meldungen als Text (§3.3).
6. **Same-origin, CSP unverändert; CSRF-Token opak** (§3.4).
7. **Deep-Link-Tokens opak** (nie im DOM/URL/Log) (§1.3).
8. **Phase-Achse, kein Spinner**, Felder disabled während loading (§1.4).

---

## 5. Abnahme-Zähne (diskriminierend — je mit der falschen Impl, die er ablehnt)

1. **Keine Enumeration.** Falsches Passwort **und** unbekannter Account → **identische** generische Meldung. **Mutation:** „Konto existiert nicht" / unterscheidbare Fehler / unterschiedliche Antwortzeit sichtbar gemacht ⇒ rot.
2. **Neutrales Register/Forgot.** **Mutation:** „E-Mail bereits vergeben" / „kein Konto zu dieser E-Mail" ⇒ rot.
3. **Rate-Limit ehrlich.** **Mutation:** ein 429 als gefälschtes „gesendet"/„ok" ⇒ rot.
4. **★ Kein JS-Session-Token.** **Mutation:** ein Kratos-Session-Token in `localStorage`/`sessionStorage`/JS-Variable (statt httpOnly-Cookie) ⇒ rot (XSS-Exfil-Pfad).
5. **★ Credential nie geleakt.** **Mutation:** Passwort-Wert in `data-*`/`aria`/`title`/`console`/Telemetrie/Fehler-String / bleibt nach Submit im `value` ⇒ rot.
6. **Kein `innerHTML`.** **Mutation:** `dangerouslySetInnerHTML` in einem Auth-Screen / server-Meldung als HTML injiziert ⇒ rot.
7. **CSP/same-origin.** **Mutation:** Login-POST an einen externen Host / `unsafe-eval` nötig / CSP aufgeweicht ⇒ rot.
8. **Deep-Link-Token opak.** **Mutation:** Reset-/Verify-Token gerendert/geloggt/als Tag/in sichtbarer URL belassen ⇒ rot.

---

## 6. DOM-/A11y-Spezifika + Reuse

- **Formulare:** `<form>` mit `email` (`type=email`, `autocomplete`) + `password` (§3.2); `submit` disabled bei leeren Feldern/`loading`; Fehler `role="alert"`, Rate-Limit `role="status"`; Reveal-Toggle Text-Label (kein Emoji, CYP-99), togglet `type` password↔text **nur am Input**. a11y-Reuse auth-spec §5.6.
- **Mount:** der Gate wrappt den Desktop (auth-spec §8.1) — **ersetzt** den gemergten CYP-470 redirect-only `AuthGate.tsx` (dessen `None→redirectToLogin` entfällt; `None`→Login-**Screen** statt Redirect). whoami/verified/operator-Ableitung (CYP-470 §3) + Logout (jetzt Kratos-Logout **oder** Session-Cookie-Invalidierung server-seitig) + 401→Re-Auth **bleiben**.
- **Keys/Tags 0 neu:** `auth_*` (Login/Register/Forgot/Reset/Verify/Error/RateLimited/a11y) + `AuthTags` (48) existieren gg. `strings.xml`/`auth-tags.md` — beim Landen final gegenprüfen; taucht ein DOM-Element ohne Key auf, liefere ich ihn impl-nah ([[shared-key-landing]]).

**Nichts gebaut — Spec + Dev5-Referenz, high-stakes.** (a) matcht die live-akzeptierte Posture; die Credential-Grenze ist damit **auf dem Niveau des akzeptierten WASM**, nicht darüber. Der Kern dieser Spec ist der **DOM-Credential-Rigor** (§3): httpOnly-Cookie statt JS-Token, transienter Wert, kein `innerHTML`, CSP unverändert — plus der **volle** CYP-176-Honesty-Contract, zurück in web-ts. Die stärkere redirect-only-Posture wartet als **CYP-518** auf den Auftraggeber.
