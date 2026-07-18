# CYP-515 — web-ts Auth-Redirect-Loop: UX-Anteil (Login-Render + honest `?flow=`-Guard) — Dev5-Spec

> Owner: UIUX-Designer (Team-2) · **Ungated High-Fill** (parallel zu Dev5s Loop-Logik-Fix; nicht auf Team-1 `.deb`) ·
> Stand 2026-07-18 · **Design/Copy, kein Code** (ich spec/verifiziere, Dev5 baut). Gegroundet READ-ONLY @ develop
> `025b17ae`. web-ts nutzt **inline Strings** (keine i18n-Keys) → Copy = literale Strings in `authModel.ts:AUTH_TEXT`.
>
> **Kernaussage vorab (Reuse-Befund, am Objekt gemessen):** das **Login-Formular existiert schon voll gehärtet**
> (`LoginScreen.tsx`, CYP-515 (a)), und die **Redirect/Loading-Copy existiert schon** (`AUTH_TEXT.redirectingSignin`
> = „Weiterleitung zur Anmeldung…", heute **unverdrahtet/reserviert**). Es ist **nichts Neues zu erfinden** — der
> echte Delta ist **ein Guard + Verdrahtung**. Details §0.

---

## 0. Was schon existiert (Reuse) vs. der echte Delta

**Schon gebaut + getestet (NICHT neu bauen — reuse):**
- **`LoginScreen.tsx`** — das gehärtete Credential-Surface (CYP-515 (a)): Felder (E-Mail `autocomplete=username` ·
  Passwort `type=password`/`current-password` + Reveal-Toggle), **ONE generic error** (`role=alert`,
  `loginErrorGeneric`, keine Enumeration), **429 honest** (`role=status`, `rateLimitedText`, kein Auto-Retry),
  **Submit = Label-Swap** (kein Spinner), **clear-after-submit**, **kein Success-Grün**. `LoginScreen.render.test.tsx`
  lockt es. → **Das ist „das Formular, das die Loop-Schleife ersetzt". Es existiert.**
- **`AuthGate.tsx`** — resolve-then-render Session-Gate (whoami zuerst, kein Flash): `resolving`(role=status,
  `AUTH_TEXT.loading`) · `none`→`LoginScreen` · `unverified`→Verify-Gate · `active`. Ein globaler /api-401 flippt
  **in-app** zurück auf `none` (kein window-Redirect → **Session-Expiry kann die Loop nicht neu einführen**).
- **`loginFlow.ts`** — Kratos same-origin **Browser-Flow**: `GET /self-service/login/browser` mit `Accept: json`
  (**kein 303-Redirect**) → `{id, csrf}` → `POST /self-service/login?flow=<id>` (Creds im **Body**), **fresh per
  submit** (nie stale), **fail-closed** bei malformed/missing csrf, **direct-fetch** (Login-401 feuert NICHT den
  globalen Re-Auth-Hook → keine Loop auf dem Login-Surface).
- **Copy schon da (in `AUTH_TEXT`, aber UNVERDRAHTET — reserviert für genau diesen Fall):**
  `redirectingSignin: 'Weiterleitung zur Anmeldung…'` · `sessionExpired: 'Sitzung abgelaufen – neue Anmeldung…'`.
- **OIDC-Redirect/Loading/Error-States** sind bereits designt in `docs/design/oidc-enroll-loading-error-ux-spec.md`
  (Redirecting/Returning Progress-Notices, Split-Error, Timeout-Eskalation) — **ein Progress-Vokabular**, hier
  spiegeln, nicht divergieren.

**Der echte Delta (das einzig Fehlende) = der inbound-`?flow=`-Guard.**
Der SPA **liest heute `?flow=` NICHT aus der URL** (grep-belegt: `?flow=` existiert nur im **ausgehenden** POST in
`loginFlow.ts:74`; **kein** `location.search`/`URLSearchParams.get('flow')` inbound). Der `AuthGate.tsx:6`-Kommentar
sagt es explizit: **„the `?flow=` guard is deferred to the OIDC-P2 screen per spec §5".** Genau dort loopt es: landet
der Browser (Kratos-initiierter Redirect — OIDC/social-Return oder Kratos-gehosteter Link) auf dem SPA **mit
`?flow=<id>`**, und der SPA **triggert einen frischen Flow-Init** statt den vorhandenen Flow zu **rendern/fortzusetzen**
→ Init redirected wieder mit `?flow=` → **stille Endlos-Schleife**. **Dies** ist der PO/Dev5-Fix; **dies** speche ich.

---

## 1. Der `?flow=<id>`-Guard — UX-Kontrakt (Dev5 besitzt die Logik)

Wenn der SPA-Einstieg (`main.tsx`/`AuthGate`) mit **`?flow=<id>`** in der URL geladen wird:
1. **Rendern/Fortsetzen statt Re-Init:** den **vorhandenen `LoginScreen`** zeigen (Passwort-Surface), gebunden an
   den bereits laufenden Kratos-Flow — **NIE** `GET /self-service/login/browser` neu auslösen. Das bricht die Loop.
2. **`?flow=` nach dem Konsumieren aus der URL entfernen** (`history.replaceState`, Dev5) — damit ein **Reload**
   nicht erneut in den Guard-Pfad fällt und (falls der Flow inzwischen abgelaufen ist) nicht re-loopt.
3. **Nur ein wohlgeformter Flow rendert das Formular.** Fehlt `id`/`csrf` oder ist der Flow **stale/expired** →
   **fail-closed** in den honest Error-Zustand (§3), **nicht** blind ein frischer Init (das wäre die Loop).

> **Naht-Ehrlichkeit:** ob „Fortsetzen" = den `?flow=`-Flow direkt submitten oder frisch initialisieren-**einmal**
> ist Dev5s Kratos-Logik. Die **UX-Invariante**: **auf `?flow=` niemals eine automatische Re-Init-Kette** — genau
> eine Auflösung (Formular **oder** honest Error), nie ein selbst-nachladender Zyklus.

## 2. Der honest Zwischenzustand „Weiterleitung zur Anmeldung…" (reuse `redirectingSignin`)

Während der Guard den `?flow=`-Landing auflöst (kurzer Moment vor Formular **oder** Error):
- **Copy:** `AUTH_TEXT.redirectingSignin` (**existiert**, „Weiterleitung zur Anmeldung…") — **verdrahten**, nicht neu erfinden.
- **Form/A11y:** ein `role="status"` / `aria-live="polite"`-Notice (spiegelt `AuthGate` `resolving`-Loading), im
  `auth-screen`-Container. **Kein** Spinner-Pflicht (Label reicht, konsistent zu §2.3⑧ „no spinner").
- **BOUNDED, kein Dauer-Refresh:** der Zustand ist **transient** — er endet deterministisch in Formular **oder**
  Error. **Er darf sich nicht selbst neu laden** (das war der Bug). Optional (Dev5): eine großzügige Timeout-Grenze
  → bei Überschreitung honest Error (§3), **kein** stiller Retry — analog der Timeout-Eskalation in
  `oidc-enroll-loading-error-ux-spec.md` §5.

## 3. Der Fehlerfall — Flow-Init/Resolution fehlgeschlagen (statt stiller Loop)

Wenn der `?flow=`-Flow nicht auflösbar ist (malformed/expired/transport) **oder** der Timeout greift:
- **Honest Error statt Loop:** ein `role="alert"` / `aria-live="assertive"`-Notice (spiegelt `LoginScreen`
  `phase==='error'`). **Kein** stiller Re-Init, **kein** Dauer-Refresh.
- **Copy:** Reuse `AUTH_TEXT.loginErrorGeneric` („Anmeldung fehlgeschlagen. Bitte prüfe deine Eingaben.") passt
  **nicht ganz** (hier ist es kein Credential-Fehler, sondern ein Flow-Init-Fehler). **Empfehlung: 1 neuer honest,
  nicht-enumerierender String** (inline in `AUTH_TEXT`, s. §7): **„Anmeldung konnte nicht gestartet werden. Bitte
  erneut versuchen."** — nennt den Zustand ehrlich, ohne Server-Detail/Enumeration.
- **Manueller Retry (nie Auto):** ein **„Erneut versuchen"**-Button, der **auf Nutzer-Aktion** genau **einen**
  frischen Flow-Init auslöst (→ zurück zum normalen in-app `LoginScreen`/`none`-Pfad). **Nie** automatisch — das
  Wiederherstellen der Auto-Kette wäre die Loop zurück. (Reuse das Muster „Feld-Edit/Klick clears transient",
  `LoginScreen.tsx:33`.)

## 4. Das Formular selbst — 100 % Reuse

**Keine neuen Felder/Fehlerzustände/Submit-Logik.** Der gerenderte Login = der **bestehende `LoginScreen`**
(Felder/Reveal/generic-error/429/label-swap/clear-after-submit unverändert). Der Guard **wählt nur, wann** er
rendert; das **Wie** ist schon gebaut + getestet. Kein zweiter Formular-Dialekt.

## 5. A11y — bestehende Muster spiegeln (0 neue Muster)

| Zustand | Rolle | Live | Präzedenz (bestehend) |
|---|---|---|---|
| „Weiterleitung zur Anmeldung…" (§2) | `role="status"` | `polite` | `AuthGate` `resolving`-Loading |
| Flow-Init-Fehler (§3) | `role="alert"` | `assertive` | `LoginScreen` `phase==='error'` |
| das Formular (§4) | (unverändert) | — | `LoginScreen` wie gebaut |

Konsistent zur CYP-288-Linie **error=assertive / status=polite** (Load-Fehler = Alert; transienter Progress = Status).

## 6. Honesty-Invarianten (der Kern von CYP-515)

- **Kein stiller Loop:** `?flow=` löst **deterministisch** in Formular **oder** honest Error auf — nie ein
  selbst-nachladender Zyklus. **Das ist der Fix.**
- **Kein Dauer-Refresh als „Loading":** der Zwischenzustand ist **bounded + transient**, kein perpetuierlicher Spinner.
- **Fail-closed, nie optimistisch:** malformed/expired Flow → Error/Login, **nie** ein blinder frischer Init und
  **nie** ein optimistisches „eingeloggt".
- **Generic, keine Enumeration:** der Flow-Init-Error nennt **keinen** Server-Grund (wie der bestehende generic
  Login-Error) — honest ohne Leak.
- **Manueller Retry only:** Recovery ist immer eine **Nutzer-Aktion**, nie eine automatische Re-Init-Kette.

## 7. Copy (web-ts inline `AUTH_TEXT`) — Reuse-first, **max. 1 neuer String**

| Zweck | String | Status |
|---|---|---|
| Zwischenzustand (§2) | `redirectingSignin` = „Weiterleitung zur Anmeldung…" | **existiert** (unverdrahtet) → verdrahten |
| Session-Expiry-Variante | `sessionExpired` = „Sitzung abgelaufen – neue Anmeldung…" | **existiert** (unverdrahtet) → falls Expiry-Pfad |
| Flow-Init-Fehler (§3) | **NEU:** `flowInitFailed` = „Anmeldung konnte nicht gestartet werden. Bitte erneut versuchen." | **1 neuer inline-String** (honest, nicht-enumerierend) |
| Retry-Button (§3) | Reuse bestehende Retry-Wording — **„Erneut versuchen"** (deckungsgleich zu web-ts EventBrowse/CYP-288) | reuse |

> **Nur 1 neuer String.** web-ts ist inline → kein Shared-Key-Sync-Flag nötig (kein `values/`-Landing); der String
> lebt in `authModel.ts:AUTH_TEXT`. Falls das Team einen KMP-Parity-Key will (`auth_flow_init_failed`), ist das ein
> optionaler Folge-Add, **nicht** hier.

## 8. Acceptance-Teeth (meine §-QA — web-ts render-tests, Vitest/testing-library, Muster = `LoginScreen.render.test.tsx`)

Diskriminierend (jeder Zahn schließt eine falsche Impl aus):
1. **Landing mit `?flow=<id>` (wohlgeformt)** → der **`LoginScreen`** rendert (`auth.login.form` present); **KEIN**
   erneuter `GET /self-service/login/browser` (Fetch-Spy: 0 Re-Init-Aufrufe) → **beweist: Loop gebrochen**.
2. **`?flow=` wird nach Konsum aus der URL entfernt** (`history.replaceState`-Spy / `location.search` leer) →
   Reload re-loopt nicht.
3. **Zwischenzustand** zeigt `redirectingSignin` mit `role="status"` **und ist transient** (kein Selbst-Reload:
   nach Auflösung ist der Status weg, Formular/Error da).
4. **Flow-Init-Fehler** → `role="alert"` + `flowInitFailed`-Copy + Retry-Button; **kein** automatischer Re-Init
   (Fetch-Spy: Re-Init **nur** nach Retry-Klick, 0 automatisch) → **beweist: kein stiller Loop**.
5. **Fail-closed:** malformed/missing-`id` `?flow=` → Error-Zustand, **nicht** ein blinder frischer Init.
6. **Reuse-Integrität:** die Formular-Felder/Fehler/Submit sind die bestehenden `LoginScreen`-Knoten (keine
   Duplikat-Testids) — kein zweiter Formular-Dialekt.

**Tool-Grenze (ehrlich):** T1–T6 sind **web-ts render-test-messbar** (JSDOM/testing-library, kein Browser nötig).
Kein Pixel/Runtime-Claim ohne Bestätigung; die Loop-Freiheit ist über den **Fetch-Spy** (0 Auto-Re-Init) beweisbar,
nicht nur visuell.

## 9. Self-Validation + 1 Klärung

- **Reuse-first, kein Duplikat:** Formular (`LoginScreen`) + Interim-Copy (`redirectingSignin`) + OIDC-States
  (`oidc-…-spec`) alle **bestehend** → verdrahten/spiegeln; **nur 1 neuer Fehler-String** + der Guard-Kontrakt.
- **Gegroundet @ `025b17ae`:** `LoginScreen.tsx` · `AuthGate.tsx:6` (§5-deferred `?flow=`-Guard) · `loginFlow.ts` ·
  `authModel.ts:AUTH_TEXT` (redirectingSignin/sessionExpired unverdrahtet, grep-belegt) · SPA liest `?flow=` heute
  nicht inbound (grep-belegt).
- **Honesty-Kern:** kein stiller Loop · bounded Interim · fail-closed · generic (keine Enumeration) · nur manueller Retry.
- **A11y:** 0 neue Muster (status/alert wie bestehend), CYP-288-konsistent.
- **⚠ 1 Klärung an PO (nicht-blockend — ich habe die wahrscheinlichste Lesart gespect):** ist der Rest-CYP-515-UX
  wirklich der **inbound-`?flow=`-Guard** (§5-deferred, reuse `LoginScreen`) — passend zu deiner Formulierung „SPA
  muss bei `?flow=<id>` das Formular rendern statt Flow-Init neu triggern"? **Oder** hast du beobachtet, dass das
  **bestehende gehärtete `LoginScreen` in einem deployten Szenario NICHT erreicht** wird (Regression/Bypass, z. B.
  ein Kratos-gehosteter Redirect, der den SPA umgeht)? Ersteres → diese Spec trifft; Letzteres → der Fix ist Dev5-Code
  (SPA-Einstieg erreichen) und mein Teil schrumpft auf den Reuse-Verweis. **Ich habe gegen Ersteres gespect** (deine
  Wortwahl + der Code-Stand deuten klar darauf).
- Kein Bau; docs-only auf `feature/CYP-515-auth-redirect-loop-ux-spec` (Basis `025b17ae`).
