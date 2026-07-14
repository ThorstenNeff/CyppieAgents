# Auth-OIDC-Legibilität — bessere States für Browser-Handoff / Fehler / Timeout (CYP-575 + CYP-576)

> Owner: UIUX-Designer · Auftraggeber-Mandat (Deadline ~morgen 12:00) · Stand 2026-07-14 · Status: **Design-Spec,
> ratifikations-reif.** **Design/Copy/QA, kein Code.** Gegroundet READ-ONLY gg. develop `58cf2207`.
> **Erweitert** die bestehende `auth-spec.md` §6 (GitHub-OIDC, CYP-185/CYP-474) — **kein** neuer Doc-Satz, additiv.
> Begleit: `auth-oidc-legibility-keys.md` (neue Keys DE+EN) + `auth-oidc-legibility-tags.md` (neue Tags).
> **Pair mit Team-2-UIUX2** (Flächen-Split §7, über PO).

## 0. Das Problem (Live-Dogfood, gegroundet)
Der OIDC-Login (`AuthGate.kt`, `GithubUiState`) hat **blinde Warte-Zustände**:
- **CYP-575 — Browser öffnete sich nicht (silent):** `GithubUiState.BrowserHandoff(url)` rendert **nur** „Continuing in your
  browser…" (`remote_login_browser_handoff`, `AuthGate.kt:222-233`) und ruft `onOpenExternalUrl(url)`. Öffnet der Host den
  Browser **nicht**, sitzt der Nutzer im **Blindflug** — die `url` **ist im State**, wird aber **nie angezeigt**, es gibt
  **keinen** manuellen Ausweg und **kein** Timeout.
- **CYP-576 — Login schlug fehl (`error?id=…`):** der `Error`-State **wird** erreicht (`onGithubReturn` → `SessionState.None`
  → `GithubUiState.Error`, `AuthViewModel.kt:292`), aber die Copy `auth_github_error` = „GitHub-Anmeldung fehlgeschlagen
  **oder abgebrochen**." **konfundiert Fehler und Abbruch** (Honesty-Smell) und ist **nicht aktionabel** (nur ein
  `AnnouncingHint`, **kein** Retry, kein Fingerzeig auf die E-Mail-Alternative).
- **Timeout — fehlt ganz:** `BrowserHandoff` wartet **unbegrenzt** auf `onAwaitLoopbackReturn`. Kommt nie ein
  Loopback-Return, bleibt „Continuing in your browser…" **für immer** stehen.

**Leitsatz:** kein Warte-Zustand ohne Ausweg. Jeder Pending-State trägt **entweder** einen sichtbaren Fallback (die URL)
**oder** einen Timeout mit Retry. Nie ein totes „Continuing…".

## 1. Reuse-Grounding (keine neue Sprache erfinden)
- **States:** `GithubUiState` (`AuthViewModel.kt:90-107`) = `Idle`/`Redirecting(url)`/`BrowserHandoff(url)`/`Returning(native)`/`Error`. Wir **erweitern** dieses eine Modell.
- **Ton/Hints:** `HintTone { EFFECT_DEFERRED, GATED, INFO, ERROR }` (`TonedHint.kt:32`) + `AnnouncingHint(text,tone,tag,mode)` (`AuthComponents.kt:195`). **Kein WARN-Ton** in dieser Fläche → Timeout = **INFO** (advisory-neutral), **nicht** ERROR.
- **Copy-Affordanz:** das Selektion+Copy-Muster (`SelectionContainer` + Copy-Button) wie bei `DicewareReveal`/Recovery-Codes; die URL ist **kein** Secret → einfacher, aber gleiche Optik-Sprache.
- **Namespace:** Keys `auth_github_*` (auth-keys.md §P2), Tags `auth.github.*` (auth-tags.md, scopeId=github). Neue Elemente bleiben **hier**.
- **Farben:** Maritime-Rollen by-name (`onSurfaceVariant` neutral · `error` retryable-Fehler · `primary` Aktion) — kein Hardcode, kein `tertiary`/Grün als Status.

---

## 2. Zustand 1 — Browser-Handoff-Pending (erweitert `BrowserHandoff`, deckt CYP-575)
**Ziel:** die stille „Browser öffnet sich"-Phase wird **ehrlich + selbst-heilbar**: der Nutzer sieht die URL und kommt
auch dann weiter, wenn der Browser nicht aufging.

**Layout (unter dem „oder"-Divider, im GitHub-Block):**
1. **Primärzeile** (unverändert): `remote_login_browser_handoff` „Weiter im Browser… " — `bodySmall`, `onSurfaceVariant`. Tag `remote.login.browserHandoff` (bestehend).
2. **★ Fallback-Block (NEU):** `auth_github_handoff_fallback` „Falls sich der Browser nicht öffnet, öffne diesen Link:" — `bodySmall`, `onSurfaceVariant` (neutral, **kein** Alarm).
   - **Die URL sichtbar + selektierbar** (`SelectionContainer`), `bodySmall` monospace, `onSurfaceVariant`, `surfaceVariant`-Container 8dp — abschreib-/klickbar. Tag `auth.github.handoffUrl`.
   - **Copy-Affordanz:** `auth_github_url_copy` „Link kopieren" — TextButton, `primary`. Tag `auth.github.handoffCopy`. (`→UIUX2`: Clipboard-Interaktion + a11y.)
3. **Honesty:** die Copy sagt **„öffnet sich… falls nicht, nutze diesen Link"** — **nie** „Browser geöffnet" (H3, kein falscher Erfolg). Die URL ist **kein** Secret (OIDC-Authorize-URL, State/PKCE-gebunden) → **keine** Egress-Disclosure nötig (anders als Diceware).

**Ton:** durchweg neutral/INFO. Kein Glyph nötig (informativer Text).

## 3. Zustand 2 — Timeout (neuer `GithubUiState.TimedOut(url)`, deckt die Endlos-Hänger)
**Ziel:** nach X Sekunden ohne Loopback-Return wird der Hänger **sichtbar** und **retrybar** — statt ewigem „Continuing".

**Auslöser (Seam → Dev/VM):** ein Timer ab Eintritt in `BrowserHandoff` (Vorschlag **X = 30 s**, als tunbare Konstante;
lang genug für einen echten Browser-Login-Start, kurz genug um nicht zu stranden). Kein Loopback-Return bis X → `TimedOut(url)`.

**Layout:**
- `auth_github_timeout` „Das dauert länger als erwartet." — `bodySmall`, **INFO-Ton** (`onSurfaceVariant`, neutral). Tag `auth.github.timedOut`. **Announce: Polite** (Status-Änderung, kein Interrupt).
- **Die Fallback-URL bleibt sichtbar** (Reuse §2 Fallback-Block) — der manuelle Weg bleibt offen.
- **Retry:** `auth_github_retry` „Erneut versuchen" — `Button`/`OutlinedButton` `primary`, `onClick = startGithub` (neuer Handoff, neue URL). Tag `auth.github.retry`.
- Das **E-Mail/Passwort-Formular bleibt** darüber sichtbar (der immer-präsente Ausweg).

**Honesty (verbindlich):** Timeout = **advisory, NICHT Fehler.** Nichts ist kaputt — es ist nur langsam. Deshalb **INFO/neutral**,
**nicht** `error`-rot (das würde fälschlich „fehlgeschlagen" implizieren). Retryable. (Wenn Team später einen WARN-Amber-Ton
in der Auth-Fläche will: additiv, aber INFO ist hier die ehrliche Wahl.)

## 4. Zustand 3 — Auth-Error-State aktionabel (erweitert `Error`, deckt CYP-576)
**Ziel:** ein **echter Fehler** sagt *was* schiefging **und** *was jetzt zu tun ist* — statt eines toten, konfundierten Hinweises.

**Honesty-Kern — Fehler ≠ Abbruch entkonfundieren:**
Die aktuelle Copy „fehlgeschlagen **oder abgebrochen**" mischt zwei distinkte Wahrheiten. CYP-576 (`error?id=…`) ist ein
**echter Server-Fehler**, **kein** Nutzer-Abbruch. Einem Server-Fehler die Formulierung „oder abgebrochen" anzuhängen,
**schiebt dem Nutzer eine Handlung unter, die er nicht getan hat** (Disclosure-Unehrlichkeit).

- **Echter Fehler** (`error?id=` / `SessionState.None` / `GithubStart.Error`): `auth_github_error` **retextet** →
  „Die GitHub-Anmeldung wurde nicht abgeschlossen. Bitte erneut versuchen oder oben mit E-Mail anmelden." — **error-Ton**
  (`HintTone.ERROR`), **Assertive** (bestehend), **+ Retry-Button** (`auth_github_retry`). Tag `auth.github.error` (+ Retry `auth.github.retry`).
- **Nutzer-Abbruch** (falls das Backend „access_denied"/User-Cancel unterscheiden kann): `auth_github_cancelled` „GitHub-
  Anmeldung abgebrochen." — **neutral/INFO** (kein Alarm; der Nutzer hat bewusst gestoppt). Tag `auth.github.cancelled`.
  Announce: Polite.
  - **Seam (→ Backend/Dev, CYP-576):** das erfordert, dass die Rückkehr **error vs. cancel** trägt (der OIDC-`error`-Param
    unterscheidet `access_denied` von echten Fehlern). **Kann das Backend es (noch) nicht:** dann **einen** String, aber
    **entkonfundiert** — „Die GitHub-Anmeldung wurde nicht abgeschlossen." (das „oder abgebrochen" **entfällt**, weil es dem
    Nutzer eine falsche Ursache unterstellen kann). Der ehrliche Minimal-Fix ohne Backend-Änderung.

**Aktionabilität:** der `Button` „Mit GitHub anmelden" ist im Error-State bereits **re-enabled** (`!githubBusy`), aber das
ist **implizit**. Der neue **Retry-Button** macht den Ausweg **explizit**, und der Verweis „…oder oben mit E-Mail anmelden"
signposted die immer-präsente Alternative.

---

## 5. Honesty-Doktrin (die Achse, die zählt)
- **Kein falscher Erfolg:** nie „Browser geöffnet"/„angemeldet", solange nur *versucht* wird (H3).
- **Kein toter Warte-Zustand:** jeder Pending-State hat Fallback-URL **oder** Timeout+Retry.
- **Ton folgt der Wahrheit:** Handoff/Timeout = **neutral/INFO** (arbeitend/langsam, nicht kaputt) · echter Fehler = **error-Ton** (aktionabel) · Abbruch = **neutral** (kein Alarm). **Nie** Timeout als Fehler-rot.
- **Fehler ≠ Abbruch:** dem Nutzer nie einen Abbruch unterstellen, wenn der Server scheiterte.
- **Die Alternative ist immer da:** das E-Mail/Passwort-Login bleibt sichtbar — der OIDC-Pfad ist nie die einzige Tür.

## 6. WCAG / Cross-cut
- **1.4.1 Farbe nie alleiniger Träger:** jeder Zustand trägt **Text** (Handoff/Timeout/Error self-describing); Retry ist ein Button mit Label.
- **Live-Regions:** Error = **Assertive** (Interrupt, bestehend) · Timeout/Handoff-Fallback = **Polite** (Status). (`→UIUX2` verdrahtet.)
- **Kontrast:** Maritime-AA-Rollen; INFO/neutral = `onSurfaceVariant`, error = `colorScheme.error`.
- **DE = Default + EN-Parität** (alle Keys in `-keys.md`).

## 7. Flächen-Split mit Team-2-UIUX2 (über PO, keine Doppelarbeit)
Beide editieren den `AuthGate`-GitHub-Block — **klarer Split:**
- **UIUX (ich):** die **Zustände + visuelles Design + Copy/Keys + Tags + Honesty** (Fehler-vs-Abbruch, kein-falscher-Erfolg, advisory-Timeout, Fallback-URL) + die Acceptance-Teeth (§9).
- **UIUX2 (Team-2):** die **Interaktion + a11y** — Fokus-Management bei State-Wechseln, die **Copy-Button-Interaktion** (Clipboard) der Fallback-URL, die **Live-Region-Verdrahtung** (Assertive Error / Polite Timeout), IME/Tastatur, URL-Selektions-Interaktion.
- **Naht:** ich liefere die visuellen Zustände + Farb-/Ton-/Label-Paarung, an denen UIUX2s a11y ansetzt (analog CYP-542 UIUX/UIUX2-Split). Grenzfälle über den PO.

## 8. Nahtstellen (Seams → Dev/Backend)
- **Dev/VM:** (a) `BrowserHandoff` die `url` **rendern** (ist schon im State); (b) **Timer** ab `BrowserHandoff` (X≈30 s, tunbar) → neuer `GithubUiState.TimedOut(url)`; (c) **Retry** = `startGithub()` re-invoke; (d) Error-State den Retry-Button + retextete Copy tragen.
- **Backend (CYP-576):** die OIDC-Rückkehr soll **`access_denied`/Cancel** von **echten Fehlern** unterscheidbar machen (der `error`-Param trägt es), damit `auth_github_cancelled` (neutral) ≠ `auth_github_error` (error-Ton) ehrlich getrennt sind. **Fallback ohne Backend-Änderung:** ein entkonfundierter Fehler-String (kein „oder abgebrochen").
- **CYP-575:** der sichtbare Fallback-URL-Block + Timeout deckt den silent-Browser-Fail **UX-seitig** — unabhängig davon, ob der Host-`onOpenExternalUrl` später robuster wird.

## 9. Acceptance-Teeth (die §-QA prüft das später gg. Bau)
1. `BrowserHandoff` zeigt die **URL sichtbar + kopierbar**; die Copy sagt nie „Browser geöffnet".
2. Nach **X s** ohne Return → **sichtbarer Timeout** (INFO, nicht error-rot) + **Retry** + URL bleibt.
3. **Error** ist **aktionabel** (Retry-Button + E-Mail-Verweis) und die Copy **unterstellt keinen Abbruch** bei echtem Server-Fehler.
4. Abbruch (falls unterscheidbar) = **neutral**, kein Alarm.
5. **Kein** Pfad endet in einem toten „Continuing…" ohne Ausweg.
6. Töne: Handoff/Timeout **neutral/INFO** · Error **error-Ton** · **nie** `tertiary`/Grün als Status · Farbe nie alleiniger Träger.
7. DE=Default + EN-Parität; Live-Regions Assertive(Error)/Polite(Timeout).

## 10. Self-Validation
- **Reuse-first:** erweitert `GithubUiState` + `auth_github_*`/`auth.github.*` + `HintTone`/`AnnouncingHint` — **kein** divergenter One-off.
- **Gegroundet** gg. `AuthGate.kt`/`AuthViewModel.kt`/`AuthTags.kt`/`TonedHint.kt` @ `58cf2207` (file:line), nicht gg. Annahme.
- **Honesty zentral:** kein falscher Erfolg · Fehler≠Abbruch entkonfundiert · Timeout advisory · Fallback immer.
- **UIUX2-Split** explizit (keine Doppelarbeit); Backend/Dev-Seams benannt. Kein Bau, keine develop-Berührung; docs-only.
