# CYP-515 — web-ts Auth: honest Login-**Fehlerpfad** bei Kratos-Proxy-Contract-Bruch — Dev5-Spec

> Owner: UIUX-Designer (Team-2) · **Ungated High-Fill** · Stand 2026-07-18 · **Design/Copy, kein Code** (ich
> spec/verifiziere, Dev5 baut). Gegroundet READ-ONLY @ develop `025b17ae`. web-ts nutzt **inline Strings** (keine
> i18n-Keys, DE-only in `authModel.ts:AUTH_TEXT`).
>
> **⟳ REFOKUSSIERT nach PO-Klärung (msg 1528018855):** Dev5s Ansatz **eliminiert den Redirect komplett** (None→in-app
> `LoginScreen`, Kratos same-origin `Accept: application/json` = 200-JSON, kein 303 → **`?flow=` erscheint nie in der
> URL**, Ory-Muster/Assist2-bestätigt). ⟹ **Kein `?flow=`-Guard, kein „Weiterleitung"-Interim nötig** — beide
> **gebankt für OIDC-P2** (§9). **Der gefragte Teil = der honest ERROR-State** für den realen Restrisiko-Pfad
> (Assist2): **bricht der gehärtete Proxy den JSON-Contract, wird jeder Login still als „abgelehnt" gerendert.**

---

## 0. Reuse-Befund (validiert) + Scope

**Validiert (am Objekt, PO bestätigt „goldrichtig") — NICHT neu bauen:**
- **`LoginScreen.tsx`** existiert voll gehärtet (CYP-515 (a)): Felder/Reveal, ONE generic error (`role=alert`,
  `loginErrorGeneric`), 429 honest (`role=status`), Label-Swap-Submit, **clear-after-submit**, kein Success-Grün.
- **`AUTH_TEXT.redirectingSignin`** („Weiterleitung zur Anmeldung…") + `sessionExpired` — reserviert; **bleiben
  gebankt** (OIDC-P2), hier **nicht** verdrahtet.

**IN Scope (dieser Spec):** **ein honest Fehlerzustand**, der einen **systemischen** Login-Startfehler (Proxy bricht
den JSON-Contract) **ehrlich als solchen** zeigt — statt ihn als Credential-Ablehnung zu maskieren.
**OUT of Scope (gebankt OIDC-P2):** der inbound-`?flow=`-Guard + das „Weiterleitung"-Interim (§9).

---

## 1. Das Problem (Assist2s reale Restgefahr) — die Honesty-Lücke

`loginFlow.ts` startet pro Submit einen frischen Kratos-Browser-Flow: `GET /self-service/login/browser`
(`Accept: json`) → `{id, csrf}` parsen → `POST /self-service/login?flow=<id>` (Creds im Body). Heute kollabiert
**jeder** Fehlschlag zu **einem** generischen `rejected` (Enumeration-Safety, §2.3②) → die UI zeigt
`loginErrorGeneric` = **„Anmeldung fehlgeschlagen. Bitte prüfe deine Eingaben."**

**Bricht der gehärtete Proxy den JSON-Contract** (liefert Nicht-JSON / falschen Content-Type / HTML-Fehlerseite /
5xx / einen Flow **ohne `id`/`csrf`**), dann:
- `loginFlow.ts:65` (`!initRes.ok`) **oder** `:69` (`flow.id===''||flow.csrf===null`) → `{ kind: 'rejected' }`.
- ⟹ **jeder** Login — auch mit **korrekten** Credentials — wird als `rejected` gerendert: **„prüfe deine
  Eingaben."**

**Die Lücke (meine Lane):** das ist eine **systemische/Infra-Störung**, wird aber als **Credential-Urteil**
dargestellt. Der Nutzer mit **richtigem** Passwort wird angewiesen, seine (korrekte) Eingabe zu prüfen → tippt
endlos, misstraut sich selbst, während in Wahrheit **der Login-Dienst gebrochen** ist. **Stille Fehl-Attribution
eines Systemfehlers als Nutzerfehler.**

## 2. Die honest Trennung (der Kern) — Systemfehler ≠ Credential-Urteil

**Zwei ehrlich getrennte Fehlerklassen** — die Attribution wird korrekt, **ohne** Enumeration-Safety zu brechen:

| Auslöser | Klasse | Outcome | Copy | Rolle/Live |
|---|---|---|---|---|
| Login-Flow **kann nicht gestartet** werden: init `!ok` · Nicht-JSON/Contract-Bruch · Flow ohne `id`/`csrf` · Transport-Fehler **vor** dem Submit | **systemisch/Infra** | **NEU** `unavailable` | **`flowInitFailed`** = „Anmeldung konnte nicht gestartet werden. Bitte erneut versuchen." | `role="alert"` / assertive |
| Der **Credential-POST** wird abgelehnt (400/401 **nach** gestartetem Flow) | **Credential-Urteil** | `rejected` (unverändert) | `loginErrorGeneric` = „…prüfe deine Eingaben." | `role="alert"` / assertive |
| 429 | Throttle | `rateLimited` (unverändert) | `rateLimitedText` | `role="status"` / polite |

**⭐ Enumeration-Safety bleibt intakt (der sensible Punkt):** `flowInitFailed` feuert **bevor** irgendein
Identifier/Passwort von Kratos ausgewertet wird — `GET /self-service/login/browser` ist **credential-frei**. Der
Zustand ist damit **für jeden Nutzer und jede Eingabe identisch** (auch für leere/Müll-E-Mail) und **leakt nichts
über irgendein Konto** — er spiegelt nur **globale Infra-Gesundheit**. ⟹ **Die Trennung führt KEINE Enumeration
ein** (kein per-Konto-Oracle; „prüfe deine Eingaben" bleibt generisch für **jeden** Credential-Fehler,
wrong-password == no-such-user). Ein Health-Oracle auf öffentliche Proxy-Verfügbarkeit ist nicht sensibel.

## 3. loginFlow-Kontrakt (Dev5 baut die Logik)

Ich spece das **Mapping**, Dev5 verdrahtet es in `loginFlow.ts`:
- **init-Phase-Fehler → `unavailable`** (statt `rejected`): `!initRes.ok` (:65) · `await initRes.json()` wirft /
  liefert Nicht-JSON · `parseFlowInit` ⇒ `id===''||csrf===null` (:69) · ein `catch` (:87), der **vor** dem
  Credential-POST greift.
- **Credential-POST 400/401 → `rejected`** (unverändert, :81) — der einzige echte Credential-Pfad.
- **429 → `rateLimited`** (unverändert). **2xx → verified/unverified** (unverändert).
- `LoginResult` bekommt `| { kind: 'unavailable' }`; `LoginPhase` bekommt `| { kind: 'unavailable' }`.

> **Naht-Ehrlichkeit:** ein Transport-Fehler **auf dem POST selbst** (Netz weg mitten im Submit) ist **kein
> Credential-Urteil** → ich empfehle ihn ebenfalls als `unavailable` zu behandeln (honest „konnte nicht
> abgeschlossen werden" statt „prüfe deine Eingaben"). Feinjustierung = Dev5s Kratos-Kenntnis; **die UX-Regel:
> nur eine echte Server-Ablehnung des Submits (4xx) ist der „prüfe deine Eingaben"-Pfad, alles andere (nicht
> starten / nicht erreichen / Contract gebrochen) ist der honest „konnte nicht gestartet werden"-Pfad.**

## 4. LoginScreen-Render — der neue Fehlerzustand (Reuse des Error-Musters)

- **0 neues Formular:** Felder/Reveal/Submit unverändert (bestehendes `LoginScreen`).
- **Neuer Zweig** analog `phase.kind === 'error'` (`LoginScreen.tsx:114`): `phase.kind === 'unavailable'` →
  `<p role="alert" aria-live="assertive" data-testid="auth.login.unavailable">{AUTH_TEXT.flowInitFailed}</p>`.
  Distinkt vom bestehenden `auth.login.error` (Credential) — **zwei Testids, zwei Attributionen**.
- **Manueller Retry, nie Auto:** der Fehler zeigt, **das Formular bleibt bedienbar**; Retry = erneutes **Absenden**
  (der bestehende Submit). **`clear-after-submit` bleibt** (`LoginScreen.tsx:40`) → das Passwort wird auch hier
  neu getippt (Security unverändert; **kein** Auto-Re-Submit, **kein** Secret-Persistieren für Bequemlichkeit).
- **Ein Feld-Edit clear't den Transient** (bestehendes `clearTransient`, :33) — auf `unavailable` mit erweitern.

## 5. Copy (web-ts inline `AUTH_TEXT`, DE-only) — **max. 1 neuer String**

| String | Wert | Status |
|---|---|---|
| **`flowInitFailed`** | **„Anmeldung konnte nicht gestartet werden. Bitte erneut versuchen."** | **1 neuer inline-String** (honest: attribuiert an das **System**, nicht an die Nutzereingabe; nicht-enumerierend; actionable) |
| `loginErrorGeneric` | „Anmeldung fehlgeschlagen. Bitte prüfe deine Eingaben." | **unverändert** (bleibt der Credential-Pfad) |
| `redirectingSignin` / `sessionExpired` | (bestehend) | **gebankt** (OIDC-P2), hier nicht verdrahtet |

> **1 neuer String, inline** → **kein** Shared-Key-Sync-Flag (web-ts inline, kein `values/`-Landing). Optionaler
> KMP-Parity-Key `auth_flow_init_failed` = späterer Folge-Add, **nicht** hier.

## 6. A11y — 0 neue Muster

`unavailable` = `role="alert"` / `aria-live="assertive"` — **identisch** zum bestehenden Credential-Error-Muster
(`LoginScreen.tsx:114`). Distinkte `data-testid` (`auth.login.unavailable` vs `auth.login.error`), damit
Screenreader **und** Tests die zwei Attributionen unterscheiden. CYP-288-konsistent (error=assertive).

## 7. Honesty-Invarianten (der Kern von CYP-515)

- **Systemfehler ≠ Credential-Urteil:** ein Proxy-/Infra-/Contract-Bruch wird **als solcher** gezeigt („konnte
  nicht gestartet werden"), **nie** als „prüfe deine Eingaben" — der Nutzer wird nicht fälschlich an seiner
  korrekten Eingaben zweifeln gemacht. **Das ist der Fix.**
- **Enumeration-Safety bleibt (nicht verhandelt):** `flowInitFailed` ist credential-frei/global → **kein**
  per-Konto-Leak; `rejected` bleibt generisch. Die Trennung ist honest **und** enumeration-safe (§2).
- **Fail-closed, nie optimistisch:** kein blinder Retry, kein „eingeloggt" ohne 2xx-Session.
- **Nur manueller Retry:** Recovery = Nutzer-Aktion (erneut absenden), nie eine automatische Kette.
- **Kein stiller Zustand:** der Systemfehler ist **sichtbar + korrekt attribuiert**, nicht als generische
  Ablehnung verschluckt.

## 8. Acceptance-Teeth (meine §-QA — web-ts render-tests, Muster `LoginScreen.render.test.tsx`)

Diskriminierend (jeder Zahn schließt eine falsche Impl aus):
1. **init `!ok` / Nicht-JSON / Flow ohne id/csrf** → `auth.login.unavailable` (`flowInitFailed`, `role=alert`) —
   **NICHT** `auth.login.error`/`loginErrorGeneric`. **Beweist: Systemfehler korrekt attribuiert** (kein Fake-„prüfe
   deine Eingaben"). *(Eine Impl, die beide kollabiert, failt hier.)*
2. **Credential-POST 400/401 (Flow gestartet)** → **weiterhin** `auth.login.error`/`loginErrorGeneric` — der
   Credential-Pfad ist unverändert. *(Beweist: die Trennung hat den Credential-Fehler nicht kaputt gemacht.)*
3. **Enumeration-Safety:** `flowInitFailed`-Copy ist **identisch** unabhängig von der E-Mail (existierend vs nicht,
   leer vs Müll) — 0 per-Konto-Varianz. *(Beweist: kein Enumeration-Oracle eingeführt.)*
4. **Manueller Retry only:** nach `unavailable` kein **automatischer** Re-Submit (Fetch-Spy: erneuter Flow-Init
   **nur** nach Nutzer-Submit, 0 automatisch).
5. **clear-after-submit erhalten:** das Passwortfeld ist nach dem fehlgeschlagenen Submit leer (Security
   unverändert, auch auf `unavailable`).

**Tool-Grenze (ehrlich):** T1–T5 sind **web-ts render-test-messbar** (JSDOM/testing-library, kein Browser). Die
korrekte Attribution ist über die **distinkten Testids + den Fetch-Spy** beweisbar, nicht nur visuell.

## 9. Gebankt (OIDC-P2) + Self-Validation

- **Gebankt für OIDC-P2 (nicht CYP-515):** der inbound-`?flow=`-Guard (auf `?flow=<id>`-Landing den vorhandenen
  Flow rendern statt Re-Init) **und** das „Weiterleitung zur Anmeldung…"-Interim (`redirectingSignin`, role=status,
  bounded). Dev5s No-Redirect-Ansatz macht sie für CYP-515 **moot**; sie werden relevant, **falls** OIDC-P2 je
  einen Kratos-Redirect mit `?flow=` einführt. **Copy `redirectingSignin`/`sessionExpired` bleiben reserviert.**
- **Gegroundet @ `025b17ae`:** `loginFlow.ts:65/69/81/87` (die 4 Fehl-Pfade) · `LoginScreen.tsx:33/40/114`
  (clearTransient / clear-after-submit / Error-Zweig) · `authModel.ts:AUTH_TEXT` (DE-only inline).
- **Honesty-Kern:** Systemfehler ehrlich attribuiert · Enumeration-Safety bewahrt (der sensible Reconcile) · nur
  manueller Retry · fail-closed.
- **Reuse-first:** 0 neues Formular · 0 neue A11y-Muster · 1 neuer inline-String · 1 neuer `LoginResult`/`Phase`-Fall.
- **PO-Klärung aufgelöst:** kein Guard/Interim (No-Redirect), **der honest Error-Pfad ist der gefragte Teil** —
  hier gespect. Kein Bau; docs-only auf `feature/CYP-515-auth-redirect-loop-ux-spec` (Basis `025b17ae`).
