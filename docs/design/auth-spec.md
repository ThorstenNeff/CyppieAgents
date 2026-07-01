# End-User-Authentifizierung & Login-Gate — UX/UI-Spec (CYP-176)

> Owner: UIUX-Designer · Epic **CYP-176** · Stand 2026-07-01
> Status: **Vorschlag — wartet auf PO-Gegenlesen. Alle §-Asks PO-eingefroren 2026-07-01 (§9).** Docs-only. **PO merged, nicht selbst mergen.**
> Ziel: Compose-Multiplatform **`commonMain`**, gegen die bestehende App-Shell/Theme. Kein neues Surface-Konzept —
> das Gate **wrappt** den bestehenden Desktop.
> Grounded gegen **develop `09def21`** (Reuse real verifiziert, siehe §8). Kein Server-Wissen im Client außer der
> API-Naht (§7). Begleit-Artefakte: `auth-tokens.json`, `auth-keys.md`, `auth-tags.md`.
> Reihenfolge: **P1** = Register/Login + Email-Bestätigung + Passwort-vergessen + Login-Gate. **P2** = „Mit GitHub
> anmelden" (klar getrennt, §6).

---

## 0. Was hier (nicht) entworfen wird

**Im Scope (Anzeige-/Interaktions-Schicht, docs-only):**
1. **Login-Gate als State-Machine** (§2), die **über** dem bestehenden `AgentShell`-`Column` sitzt und den Desktop
   (`ProjectSwitcherBar` + `WindowHost`) erst mountet, wenn `auth+verifiziert`.
2. **Sieben Screen-Zustände** (§3–§5): Login · Registrieren · Email-bestätigen (Pending/Success) ·
   Passwort-vergessen (Request + Set-New) · Fehler-/Erfolgs-States, je mit idle/loading/disabled/error/**rate-limited**.
3. **P2 GitHub-Login** (§6): Button + OIDC-Redirect-Zustände (Redirect/Return/Fehler), **additiv**, ohne P1 zu ändern.
4. **a11y** (§5.6): Rollen, **Fehler-Announce (liveRegion, NEU §8.2)**, Passwort-Sichtbarkeit, Feld-Label/Error-Kopplung.

**Außerhalb des Scope (Backend / andere Owner):**
- Der Auth-Server selbst (Register/Login/Verify/Reset-Endpunkte, Token-Lebensdauer, Session-Speicherung,
  konstant-zeitige Antworten) — **API-Naht in §7**, gebaut vom Backend. Dev baut die Screens **parallel gegen einen
  Auth-Stub**, der diese Naht erfüllt.
- Die **Session-Persistenz** (wo/ob das Session-Token client-seitig gehalten wird) — Plattform-`actual`-Detail, kein
  `commonMain`-Design; die Spec verlangt nur die **Zustände**, nicht den Speicher.
- Der bestehende **Operator-Token** (ACL/Mutations-Gate) bleibt **unangetastet** — siehe **§-Ask 2b** (Achsen-Trennung).

---

## 1. Leitprinzipien: ehrliche Auth-Disclosure

Auth ist eine **sensible** Fläche (Konten, Passwörter, Reset-Links). Dieselbe Disclosure-Ehrlichkeit wie bei
Connector-Fidelity / Cross-Projekt / Product-Lead gilt hier verschärft:

- **Kein Account-Leak (Enumeration-Sicherheit, §-Ask 1):** Register und Passwort-vergessen bestätigen **neutral**
  („Falls ein Konto zu dieser Adresse existiert, ist eine Mail unterwegs") — **nie** „existiert (nicht)".
  Login-Fehler sind **generisch** („E-Mail oder Passwort ist falsch"), nie „Konto existiert nicht" / „Passwort falsch".
- **Rate-Limit ehrlich (429), nie Fake-„gesendet":** Wird die Aktion gedrosselt, sagt die UI **ehrlich** „zu viele
  Versuche, bitte warten" — sie täuscht **nie** eine gesendete Mail oder einen erfolgreichen Versuch vor. Amber
  „Attention", **kein** rotes ERROR (der Nutzer hat nichts falsch gemacht).
- **Hartes Verifizierungs-Gate (§-Ask 2a):** `auth+unverifiziert` sieht **nicht** den Desktop — nur den
  „Email bestätigen"-Zustand mit Resend + Abmelden. Die UI stellt einen unverifizierten Nutzer **nie** als „drin" dar.
- **„Eingeloggt" ≠ „Operator/privilegiert" (§-Ask 2b):** Anmeldung gewährt **App-Zugang**, kein Mutationsrecht. Die
  bestehende Operator-Token-Achse (ACL/`editable`) bleibt orthogonal und unverändert; die Auth-UI impliziert **nie**
  Privileg aus dem bloßen Login.
- **Keine Secrets in der UI:** Passwortfelder maskiert per Default (Reveal opt-in, wie API-Key in Settings CYP-99);
  Reset-/Verify-**Token nie angezeigt**, nie in Tags/Keys/Logs; kein Passwort im a11y-Baum echo't.
- **Farbe nie alleiniger Träger (WCAG 1.4.1):** jeder Zustand trägt Glyph + Text (`TonedHint`), Sinn lebt im Text.
- **Loading = disabled + ehrlicher Text** (Codebase-Konvention: **kein `CircularProgressIndicator` im Repo**) — kein
  erfundener Spinner; in-flight → Controls disabled + Pending-Text (§5.4).

---

## 2. Login-Gate — State-Machine (das Herzstück)

Das Gate ist ein **`commonMain`-Composable** `AuthGate`, das **eine** `AuthUiState` liest und entweder einen
Auth-Screen **oder** — bei `Verified` — den bestehenden Desktop rendert. Es ersetzt in `App()` den direkten
`AgentShell`-Aufruf durch `AuthGate { AgentShell(...) }` (Mount-Punkt §8.1). **Kein** Desktop-Composable wird
verändert; der Desktop wird nur später gemountet.

### 2.1 Zustände & Übergänge

```
                    App-Start
                       │
                       ▼
                ┌────────────┐   Session prüfen (API-Naht §7.1)
                │  Loading   │   (bestehendes Session-Token?)
                └─────┬──────┘
          kein/ungült.│        gültig+verifiziert │        gültig+unverifiziert │
                      ▼                            ▼                            ▼
              ┌──────────────┐            ┌──────────────┐            ┌────────────────────┐
              │Unauthenticated│───login──▶│  Verified    │◀──verify──│ AuthedUnverified   │
              │  (Login-Hub)  │◀─logout───│ (DESKTOP)    │           │ („Email bestätigen")│
              └──────┬────────┘           └──────────────┘           └─────────┬──────────┘
                     │  ▲  ▲                                                    │
     register ┌──────┘  │  └──────┐ forgot                        resend / logout
              ▼         │         ▼                                            │
      ┌──────────────┐  │  ┌──────────────┐                                    │
      │  Register    │  │  │ ForgotRequest│                                    │
      └──────┬───────┘  │  └──────┬───────┘                                    │
   erfolgreich│         │ Link-Klick (Deep-Link, Token)                         │
   (→ Verify- │         │         ▼                                            │
    Pending)  │         │  ┌──────────────┐                                    │
              └────────▶│  │ ResetSetNew  │──erfolg──▶ Unauthenticated (Login) │
   Deep-Link  │         │  └──────────────┘                                    │
   (Verify-   ▼         │                                                      │
    Token) ┌─────────────────┐                                                 │
           │ VerifySuccess   │◀────────── Deep-Link Verify-Token ──────────────┘
           │ („bestätigt")   │──weiter──▶ Verified (falls Session) / Unauthenticated
           └─────────────────┘
```

**Übergangs-Regeln (Client, gegen die API-Naht §7):**

| Von | Ereignis | Nach | Ehrlichkeits-Anker |
|---|---|---|---|
| `Loading` | Session gültig+verifiziert | `Verified` (Desktop) | still, kein Flash von Login |
| `Loading` | Session gültig+**unverifiziert** | `AuthedUnverified` | **hartes Gate** (§-Ask 2a) |
| `Loading` | keine/ungültige Session | `Unauthenticated` | Default-Ziel = Login |
| `Unauthenticated` | Login OK + verifiziert | `Verified` | — |
| `Unauthenticated` | Login OK + unverifiziert | `AuthedUnverified` | Verify-Gate greift sofort |
| `Unauthenticated` | Login abgelehnt | `Unauthenticated` + **generischer** Fehler | **kein** Enumeration-Leak |
| `Unauthenticated` | Login gedrosselt (429) | `Unauthenticated` + **Rate-Limit** | ehrlich, kein Fake |
| `Unauthenticated` | „Registrieren" | `Register` | Sub-Screen im Gate |
| `Unauthenticated` | „Passwort vergessen" | `ForgotRequest` | Sub-Screen im Gate |
| `Register` | Erfolg | `AuthedUnverified` **oder** `VerifySuccess`-Pending-Ansicht | **neutrale** Bestätigung |
| `ForgotRequest` | Absenden | bleibt + **neutrale** „falls Konto…"-Meldung | **kein** Enumeration-Leak |
| Deep-Link Reset-Token | geöffnet | `ResetSetNew` | Token-ungültig → ehrlicher Zustand |
| `ResetSetNew` | Erfolg | terminaler `ResetSetNew(done)` (`auth.reset.success`) + Login-Link → `Unauthenticated` | **kein Auto-Login** — Re-Login mit neuem Passwort |
| Deep-Link Verify-Token | geöffnet | `VerifySuccess` | Token-ungültig → ehrlicher Zustand |
| `AuthedUnverified` | „Mail erneut senden" | bleibt + **neutrale** Resend-Meldung (rate-limited) | kein Fake |
| `AuthedUnverified` | „Abmelden" | `Unauthenticated` | Ausweg, nie eingesperrt |
| `AuthedUnverified` / `Verified` | Session abgelaufen (401 aus Desktop-Call) | `Unauthenticated` | fail-closed, ehrlich |

**`AuthUiState` (Design-Form, Wire in §7):** `sealed interface` mit `Loading` · `Unauthenticated` · `Register` ·
`ForgotRequest` · `ResetSetNew(tokenState)` · `AuthedUnverified(email)` · `VerifySuccess` · `Verified`. Jeder
formtragende Zustand hält zusätzlich `phase: Idle|Submitting|Error(code)|RateLimited(retryAfter?)` (§5.4) — **eine**
Phase-Achse, überall gleich gerendert.

### 2.2 Deep-Links (Verify- / Reset-Token)

Email-Bestätigung und Passwort-Reset kommen als **Links** in der Mail. Der Client empfängt einen **opaken Token**
(Plattform-`actual`: URL-Handler/Intent) und ruft die Naht (§7.2/§7.4). **Der Token wird nie gerendert, nie geloggt,
nie als Tag/Key materialisiert.** Ungültig/abgelaufen → **ehrlicher** Zustand (`auth.reset.tokenInvalid` /
`auth.verify` mit Fehler), nie ein stiller Erfolg.

---

## 3. Screens P1 — Login & Registrieren

Alle Auth-Screens teilen ein **zentriertes, in der Breite begrenztes Formular-Card** (`AUTH_FORM_MAX_WIDTH = 400.dp`,
Token §tokens) — auf dem Phone volle Breite mit Padding, auf Desktop **zentriert gecappt** (nicht edge-to-edge). Das
ist dieselbe Adaptiv-Haltung wie die Responsive-Trias (CYP-156/158/159): eine Breite, jede Formfaktor. Screen-Titel
tragen `semantics { heading() }` (Reuse `SectionHeading`).

### 3.1 Login (`Unauthenticated`)

**Aufbau (von oben):**
1. Titel „Anmelden" (`auth_login_title`, heading).
2. **E-Mail-Feld** — `OutlinedTextField`, `singleLine`, `label = auth_email_label`, a11y `a11y_auth_email`,
   `KeyboardType.Email`, `imeAction = Next`. Tag `auth.login.email`.
3. **Passwort-Feld** — `OutlinedTextField` + **Reveal-Toggle** (Reuse SettingsPanel-Muster §8: `visualTransformation
   = if (reveal) None else PasswordVisualTransformation()`; Toggle = `TextButton` mit **Text-Label** `auth_password_show`/
   `auth_password_hide`, **kein Emoji** CYP-54; a11y `a11y_auth_password_show`/`_hide`). `imeAction = Done` →
   `KeyboardActions{ onDone = submit }`. Tags `auth.login.password`, `auth.login.passwordReveal`.
4. **Submit** — `Button`, `enabled = phase != Submitting && fieldsNonBlank`, Label `auth_submit_login` (Submitting →
   `auth_submitting` als disabled Label). Tag `auth.login.submit`.
5. **Fehler-/Rate-Limit-Zeile** — `TonedHint` mit **liveRegion** (§8.2): Login-Fehler `auth_login_error_generic`
   (`HintTone.ERROR`), Drosselung `auth_rate_limited` (`HintTone.EFFECT_DEFERRED`, amber, nie ERROR). Tags
   `auth.login.error` / `auth.login.rateLimited`.
6. **Links** — „Konto erstellen" (`auth_link_to_register` → `Register`, Tag `auth.login.toRegister`) und „Passwort
   vergessen?" (`auth_link_forgot` → `ForgotRequest`, Tag `auth.login.toForgot`). Als `TextButton` (Rolle Button).
7. **P2 (getrennt, §6):** Divider „oder" (`auth_or_divider`) + „Mit GitHub anmelden" (`auth.login.github`).

**Ehrlichkeit:** Der generische Fehler unterscheidet **nicht** zwischen „kein Konto" und „falsches Passwort"
(§-Ask 1). Kein „Angemeldet bleiben"-Default, der Privileg oder Persistenz überverspricht (Session-Speicher = §7/actual).

### 3.2 Registrieren (`Register`)

**Aufbau:** Titel `auth_register_title` · E-Mail (`auth.register.email`) · Passwort + Reveal
(`auth.register.password`) · **Passwort-Bestätigen** (`auth.register.passwordConfirm`, zweites maskiertes Feld) ·
Passwort-Regel-Hinweis `auth_register_password_rule` (`HintTone.INFO`, **ehrliche** Mindestanforderung, kein Fake-
Stärke-Meter) · Submit `auth_submit_register` (`auth.register.submit`) · Link „Schon ein Konto? Anmelden"
(`auth_link_to_login` → `Login`).

**Validierung (client, vor Submit):** leere Felder → Submit disabled; E-Mail-Form offensichtlich ungültig →
`isError` am Feld + `auth_register_email_invalid`; Passwort ≠ Bestätigung → `isError` an beiden + `auth_register_pw_mismatch`.
Server-Ablehnung → generischer `auth_register_error_generic` (`ERROR`, liveRegion); Drosselung (429) →
`auth_rate_limited` (`EFFECT_DEFERRED` amber, Tag `auth.register.rateLimited`, Assertive) — ehrlich, nie Fake.
**Erfolg → `AuthedUnverified`** mit **neutraler** Meldung (§4), **nie** „Konto angelegt für <email>" als Existenz-Beweis.

---

## 4. Screens P1 — Email bestätigen (Pending / Success)

### 4.1 `AuthedUnverified` — Pending (auch das harte Gate, §-Ask 2a)

Erreicht nach Register-Erfolg **oder** beim Boot mit unverifizierter Session. **Der Desktop ist hier gesperrt.**

**Aufbau:** Titel `auth_verify_title` (heading) · Body `auth_verify_pending_body` (`%1$s` = E-Mail, echo't zur
Orientierung — Tag `auth.verify.email`) · **Gate-Hinweis** `auth_verify_gate_hint` (`HintTone.GATED`, neutral: „Bitte
bestätige deine E-Mail, um fortzufahren") · **„Mail erneut senden"** `auth_verify_resend` (`Button`,
`auth.verify.resend`) → Ergebnis **neutral** `auth_verify_resend_done` (`INFO`) bzw. Drosselung `auth_rate_limited`
(`EFFECT_DEFERRED`) — Tag `auth.verify.resendResult`, liveRegion · **„Abmelden"** `auth_logout` (`TextButton`,
`auth.verify.logout`) als Ausweg.

**Ehrlichkeit:** Die Resend-Bestätigung ist **neutral** (verrät nicht, ob die Adresse existiert). Der Body sagt „Falls
ein Konto zu %1$s existiert, ist eine Bestätigungs-Mail unterwegs" — **kein** Existenz-Beweis. Der Gate-Hinweis ist
**GATED** (neutral), **kein** ERROR — Unverifiziert ist kein Fehler des Nutzers.

### 4.2 `VerifySuccess` — Success

Erreicht über den Verify-Deep-Link (§2.2). **Aufbau:** Titel `auth_verify_success_title` (heading) · Body
`auth_verify_success_body` („E-Mail bestätigt.") — `HintTone.INFO` (kein neues Erfolgs-Grün; DS-Lücke notiert §10) ·
**„Weiter"** `auth_verify_continue` (`Button`, `auth.verify.continue`) → `Verified` (falls Session steht) sonst
`Unauthenticated`. Token ungültig/abgelaufen → **ehrlicher** Fehlerzustand (`auth_verify_token_invalid`, `ERROR`), nie
stiller Erfolg.

---

## 5. Screens P1 — Passwort vergessen (Request / Set-New) + Querschnitts-States

### 5.1 `ForgotRequest` — Anfordern

**Aufbau:** Titel `auth_forgot_title` (heading) · Body `auth_forgot_body` (Anleitung) · E-Mail-Feld
(`auth.forgot.email`) · Submit `auth_submit_forgot` (`auth.forgot.submit`) · Link „Zurück zur Anmeldung"
(`auth_link_to_login`) · nach Absenden **neutrale** Meldung `auth_forgot_sent` („Falls ein Konto zu %1$s existiert,
haben wir einen Link zum Zurücksetzen gesendet") — `INFO`, liveRegion, Tag `auth.forgot.sent`. Drosselung →
`auth_rate_limited` (`EFFECT_DEFERRED`). **Kein** Enumeration-Leak, **kein** Fake bei 429.

### 5.2 `ResetSetNew` — Neues Passwort setzen

Erreicht über den Reset-Deep-Link (§2.2). **Aufbau:** Titel `auth_reset_title` (heading) · **Neues-Passwort**-Feld +
Reveal (`auth.reset.password`, Label `auth_reset_new_label`) · **Bestätigen**-Feld (`auth.reset.passwordConfirm`) ·
Passwort-Regel-Hinweis (`auth_register_password_rule`, Reuse) · Submit `auth_submit_reset` (`auth.reset.submit`) ·
**Erfolg → terminaler `ResetSetNew(done)`-Zustand** mit `auth_reset_success` („Passwort geändert. Du kannst dich jetzt
anmelden.", Tag `auth.reset.success`, Polite) **+ Login-Link** → `Unauthenticated`. **Kein Auto-Login:** der Nutzer
meldet sich mit dem neuen Passwort neu an (sichere Praxis; der reset-scoped Success-Tag bleibt in seinem Scope statt im
Login-Screen zu rendern). · Drosselung (429) → `auth_rate_limited` (`EFFECT_DEFERRED`, Tag `auth.reset.rateLimited`) ·
**Token ungültig/abgelaufen** → `auth_reset_token_invalid` (`ERROR`, „Link ungültig oder abgelaufen. Fordere einen neuen
an.") + Link zurück zu `ForgotRequest`. Tags `auth.reset.success`/`auth.reset.tokenInvalid`/`auth.reset.error`/`auth.reset.rateLimited`.

### 5.3 Bootstrap / Loading

`Loading` beim App-Start (Session-Prüfung §7.1): neutrale, zentrierte Zeile `auth_loading` („Anmeldung wird
geprüft…"), Tag `auth.loading`. **Kein** Login-Flash bevor die Session-Prüfung antwortet (sonst „ausgeloggt"-Flackern
für eingeloggte Nutzer). Falls die Prüfung selbst scheitert (Netz) → `Unauthenticated` mit ehrlichem Hinweis, nie
stilles Weiterreichen zum Desktop.

### 5.4 Phase-Achse (idle / loading / disabled / error / rate-limited) — überall gleich

Jeder formtragende Screen rendert **eine** `phase`:

| Phase | Controls | Signal |
|---|---|---|
| `Idle` | aktiv | — |
| `Submitting` | **disabled** (Felder + Buttons `enabled=false`) | Button-Label → `auth_submitting`, **kein Spinner** |
| `Error(code)` | aktiv (Retry erlaubt) | `TonedHint(ERROR)` + liveRegion; **generisch** bei Auth-Fehlern |
| `RateLimited(retryAfter?)` | Submit **disabled** bis Ablauf | `TonedHint(EFFECT_DEFERRED)` amber; `retryAfter` → `auth_rate_limited_wait` („…in %1$s…"), sonst `auth_rate_limited` |

Der Server ist **Source of Truth** für Fehler/Drosselung; die UI **rät nie** und täuscht nie Erfolg vor.

### 5.5 Disabled / leere Felder

Submit ist disabled solange Pflichtfelder leer sind (kein Server-Roundtrip für offensichtlich Unvollständiges) — wie
`canSaveRepo`/`canConfirmAdd` in Settings/AgentMgmt. Disabled ≠ Fehler (kein rotes Feld für „noch leer").

### 5.6 a11y (Pflicht, Reuse + 1 NEU)

- **Feld-Label + `contentDescription`** je Eingabe (Reuse `LabeledField`/SettingsPanel-Muster); `isError` koppelt
  visuelle + semantische Fehlermarkierung.
- **Passwort-Sichtbarkeit:** Reveal-`TextButton` trägt sichtbares Text-Label **und** `contentDescription`
  (`a11y_auth_password_show`/`_hide`), Toggle wie CYP-99 (kein Emoji). Der Klartext wird nur im Feld sichtbar, nie
  separat im a11y-Baum echo't.
- **Fehler-Announce (NEU, §8.2):** Fehler-/Rate-Limit-/Neutral-Bestätigungs-`TonedHint` trägt `Modifier.semantics {
  liveRegion = LiveRegionMode.Assertive }` (Fehler) bzw. `.Polite` (neutrale Bestätigung/Resend), damit Screenreader
  den Zustandswechsel ansagen. **Das ist ein neues, additives a11y-Muster** (Code hat heute keine liveRegion) — Dev
  setzt es an genau diesen Knoten; QA/CYP-7 kennt den Tag.
- **Rollen:** Buttons/TextButtons tragen die Button-Rolle automatisch; Screen-Titel `heading()`.
- **Fokus/Keyboard:** `imeAction` Next→Done kettet die Felder; `KeyboardType.Email`/`Password` schaltet die passende
  Tastatur; Done submit't. Fokus-Reihenfolge = visuelle Reihenfolge (top-down, RTL-neutral via `Column`).

---

## 6. P2 — „Mit GitHub anmelden" (klar getrennt, additiv)

**Ändert P1 nicht.** Auf Login (und optional Register) unter einem Divider „oder" (`auth_or_divider`):
**`Button` „Mit GitHub anmelden"** (`auth_github_button`, Tag `auth.login.github`). Klick → **OIDC-Redirect**
(Plattform-`actual`: externer Browser/Custom-Tab; `commonMain` kennt nur die Zustände).

**OIDC-Zustände (eigene, ehrliche Abfolge):**

| Zustand | Anzeige | Tag | Ehrlichkeit |
|---|---|---|---|
| `Redirecting` | `auth_github_redirect` („Weiter zu GitHub…"), Controls disabled | `auth.github.redirecting` | kein Fake-„eingeloggt" |
| `Returning` | `auth_github_returning` („Anmeldung wird abgeschlossen…") beim Rücksprung mit Code | `auth.github.returning` | Code→Token = Backend |
| `Error` | `auth_github_error` („GitHub-Anmeldung fehlgeschlagen oder abgebrochen."), `ERROR`, liveRegion | `auth.github.error` | Abbruch ehrlich, kein stiller Retry |
| Erfolg | → normale Gate-Transition (`Verified`/`AuthedUnverified`) | — | GitHub-Erfolg **verifiziert nicht** automatisch die App-Email, außer der Server sagt es (kein Über-Claim) |

**Ehrlichkeit P2:** „Mit GitHub anmelden" ist eine **Identitäts-Assertion**, **kein** Privileg. Kein „Verifiziert durch
GitHub"-Badge, das mehr verspricht als die Naht liefert. Ob ein GitHub-Login die Email-Bestätigung ersetzt, entscheidet
der Server (`AuthedUnverified` bleibt möglich) — die UI nimmt es **nicht** vorweg.

---

## 7. API-Naht (für Dev-Stub + Backend) — Design-Form, keine Wire-Festlegung

Der Client spricht **eine** `AuthRepository`-Naht (analog `SettingsRepository`/`AgentManagementRepository`). Dev baut
die Screens gegen einen **Stub**, der diese Form erfüllt; Backend liefert die reale Implementierung. **Feldnamen
kursiv/offen** bis Backend-Gegenlesen (Fold später, wie CYP-119→CYP-120).

| Operation | Eingang | Ausgang (Design-Form) | Ehrlichkeits-Vertrag |
|---|---|---|---|
| §7.1 `session()` | — | `Loading→{Verified\|Unverified(email)\|None}` | Boot-Prüfung; None bei Netzfehler (fail-closed) |
| §7.2 `login(email,pw)` | Credentials | `{Verified\|Unverified\|Rejected\|RateLimited(retryAfter?)}` | Rejected **generisch**, konstant-zeitig (Backend) |
| §7.3 `register(email,pw)` | Credentials | `{Pending\|RateLimited\|InvalidInput}` | **neutral**: kein „existiert schon" (Backend antwortet gleich) |
| §7.4 `requestReset(email)` | Email | `{Accepted\|RateLimited}` | **immer** Accepted-artig (neutral), nie „kein Konto" |
| §7.5 `setNewPassword(token,pw)` | Deep-Link-Token + pw | `{Ok\|TokenInvalid\|RateLimited}` | Token opak, nie gerendert |
| §7.6 `verifyEmail(token)` | Deep-Link-Token | `{Ok\|TokenInvalid}` | — |
| §7.7 `resendVerification()` | (Session) | `{Accepted\|RateLimited}` | **neutral** |
| §7.8 `logout()` | (Session) | `Unauthenticated` | Ausweg aus dem Gate |
| §7.9 `githubStart()` / `githubComplete(code)` | — / OIDC-Code | Redirect-URL / `{Verified\|Unverified\|Error}` | P2; Code→Token backend-seitig |

> **Naht-Bedingung (an Backend, §-Ask 1):** Die Enumeration-Sicherheit lebt oder stirbt **serverseitig** — `login`
> muss konstant-zeitig + einheitlich ablehnen, `register`/`requestReset` müssen **unabhängig von der Existenz**
> dieselbe Antwort geben. Die neutrale UI-Sprache ist wirkungslos, wenn die API leakt. **Das ist die eine harte
> Backend-Kopplung dieser Spec.**

---

## 8. Reuse (gegen develop `09def21` real verifiziert) + die eine Naht-Neuerung

### 8.1 Mount-Punkt (Gate wrappt den Desktop)

`App.kt` heute: `MaterialTheme { AgentShell(modifier…) }`. **Änderung (Dev, minimal-invasiv):**
`MaterialTheme { AuthGate(authVm) { AgentShell(modifier…) } }` — `AuthGate` rendert bei `Verified` das
`content()` (= `AgentShell`), sonst den passenden Auth-Screen. **`AgentShell` selbst unverändert** (`Column{
ProjectSwitcherBar; BoxWithConstraints{ WindowHost } }` bleibt). Kein Eingriff in `WindowHost`/`ProjectSwitcherBar`.

### 8.2 Reuse-Tabelle

| Bedarf | Reuse-Quelle (verifiziert) | Neu? |
|---|---|---|
| Disclosure-Zeilen (Fehler/Gate/Info/Attention) | `ui/TonedHint.kt` (`HintTone` ERROR/GATED/INFO/EFFECT_DEFERRED, Glyph `✕`/`·`/`i`/`!`) | Reuse |
| Passwort-Feld + Reveal-Toggle | `settings/SettingsPanel.kt` (`PasswordVisualTransformation` + `TextButton`-Reveal, Text-Label, kein Emoji CYP-54) | Reuse-Muster |
| Beschriftetes Eingabefeld | `agentmgmt/…LabeledField` (OutlinedTextField + label + `isError` + a11y) | Reuse-Muster |
| Screen-Titel als heading | `SettingsPanel.SectionHeading` (`semantics{heading()}`) | Reuse-Muster |
| Dialog-Gerüst (falls Screen als Dialog) | `AlertDialog` (AgentMgmt) — **hier NICHT**: Auth = Vollflächen-Screens, kein Dialog | — |
| Adaptiv-Breite (zentriert, gecappt) | Haltung wie CYP-156/159 (`widthIn(max=…)`) | neuer Token `AUTH_FORM_MAX_WIDTH` |
| **Fehler-Announce (liveRegion)** | **kein Vorbild im Code** → **NEU, additiv** (`semantics{ liveRegion = LiveRegionMode.Assertive/Polite }`) | **NEU** |

**Die einzige Neuerung** ist die **liveRegion** an den Auth-Announce-Knoten (der PO hat Fehler-Announce explizit
verlangt). Sie ist additiv (keine bestehende Komponente ändert sich), wie FlowRow für die Trias eingeführt wurde. Dev
setzt sie an die in `auth-tags.md` markierten Knoten; QA/CYP-7 kennt die Tags.

---

## 9. §-Ask-Resolutions (PO-eingefroren 2026-07-01)

- **§-Ask 1 — Enumeration-sichere Formulierung (Disclosure). ✅ PO-BESTÄTIGT: JA, neutral/nicht-enumerierend.**
  Register/Forgot bestätigen **neutral**, Login-Fehler **generisch** (`auth_login_error_generic`, `auth_forgot_sent`,
  `auth_verify_pending_body`, `auth_verify_resend_done` eingefroren). Der Tippfehler-Trade-off ist akzeptiert
  (Security > Komfort bei Auth). **Naht (load-bearing, Backend-Owner):** die API muss **identisch + konstant-zeitig**
  antworten, sonst leakt sie trotz neutraler UI — der PO hält Backend daran fest; verankert in **Backends Design-Pass
  (Enumeration-Schutz + Timing-Resistenz)** und **Testers Security-Plan**. Die neutrale UI-Sprache ist die halbe Miete.
- **§-Ask 2a — Gate-Härte. ✅ PO-BESTÄTIGT: hart.** Unverifiziert = Desktop gesperrt, nur Verify+Resend+Logout. Der PO
  merkt an: reversible Produkt-Wahl — falls der Auftraggeber später „unverifiziert mit Banner am Desktop" will, weichen
  wir auf (dann wird `AuthedUnverified` ein Desktop-Overlay-Banner statt Vollscreen — kleiner, isolierter Folge-Slice).
- **§-Ask 2b — Achsen-Trennung Login vs. Operator-Token. ✅ PO-BESTÄTIGT als MVP-Default: orthogonal.** Login =
  App-Zugang, Operator-Token = Mutationsrecht (unverändert); die Auth-UI impliziert **nie** Privileg aus Login; der
  Operator-Token wird **nicht** angefasst. **Offene Auftraggeber-Architektur-Entscheidung (nicht mein Scope jetzt):**
  das Verhältnis End-User ↔ Operator legt Backends Design-Pass dem Auftraggeber vor. Falls später „eingeloggter Nutzer =
  Operator" (Single-User) entschieden wird, ist das ein **additiver Folge-Slice** — dann fasse ich die Gate-/`editable`-
  Achse an, **nicht** in CYP-176.

---

## 10. Selbst-Validierung & DS-Notizen

- **Keys:** siehe `auth-keys.md` — **DE+EN-Parität Pflicht**, snake_case `auth_*`/`a11y_auth_*`, 0 Kollision gg.
  `strings.xml` @ `09def21` (im Keys-Doc geprüft + gezählt).
- **Tags:** siehe `auth-tags.md` — Area `auth`, prefixlos, gg. bestehende `*Tags.kt` **kollisionsfrei** (Area neu).
  **Sync-Punkt** (PO koordiniert CYP-7): neue Keys **und** neue Area landen erst, wenn Dev das Modul konsumiert.
- **Token:** `auth-tokens.json` — überwiegend Reuse (`TonedHint`-Rollen + MaterialTheme), 1 neuer Layout-Token
  `AUTH_FORM_MAX_WIDTH`.
- **DS-Lücke notiert (nicht in dieser Spec gelöst):** kein dediziertes **Erfolgs-Grün** (`HintTone.SUCCESS`) — Auth
  nutzt `INFO` für „bestätigt/geändert". Anti-Hype-konform, aber ein echter Success-Ton wäre ein sauberer DS-Zuwachs
  (Parität-Inventar-Kandidat, kein CYP-176-Scope).
- **Disclosure-Invarianten (load-bearing, dürfen bei Impl nicht degradieren):** (1) neutrale/nicht-enumerierende
  Bestätigungen; (2) 429 ehrlich, nie Fake-„gesendet"; (3) hartes Verify-Gate; (4) „eingeloggt ≠ Operator"; (5) keine
  Token/Passwörter in UI/Tags/Keys/Logs. Diese prüfe ich in der UX-QA-Pass nach Dev-Impl.
