# End-User-Authentifizierung — testTags (CYP-176)

> Owner: UIUX-Designer · Epic CYP-176 · Stand 2026-07-01 · Status: Vorschlag — **testTag-Sync-Punkt** (PO koordiniert CYP-7 zwischen UIUX/Dev/Tester)
> Schema (Test-Contract v0.5 §2, Dev/QA-Vertrag CYP-7): **prefixlos** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`,
> Segmentwerte `[A-Za-z0-9-]+` (camelCase ok, **keine Punkte** im Wert). **Area `auth` ist NEU** — 0 Kollision gg. die
> bestehenden 13 `*Tags.kt` (Code = Source of Truth, @ develop `09def21`: `comm/agent/agentMgmt/settings/window/
> eventBrowse/eventTail/projectSwitcher/projectMgmt/aclMatrix/productLead/connector/crossProject`).
> Empfohlener Träger: neues `auth/AuthTags.kt` (`object AuthTags`), Stil wie `CommTags`/`SettingsTags`.
> **Nicht still umbenennen** — Änderungen über den PO koordinieren (Shared-API mit QA/CYP-7).

## Neue Tags (Area `auth`)

### Gate / Bootstrap
| Tag | Element |
|---|---|
| `auth.gate` | Wurzel-Container des Gates (entscheidet State; wrappt Desktop oder Auth-Screen) |
| `auth.loading` | Boot-Zustand „Anmeldung wird geprüft…" (§5.3) |

### Login (`scopeId = login`)
| Tag | Element | liveRegion |
|---|---|---|
| `auth.login.form` | Formular-Container (zentriert, `AUTH_FORM_MAX_WIDTH`) | — |
| `auth.login.email` | E-Mail-Eingabe | — |
| `auth.login.password` | Passwort-Eingabe (maskiert) | — |
| `auth.login.passwordReveal` | Reveal-Toggle (Text-Label, kein Emoji) | — |
| `auth.login.submit` | Anmelden-Button | — |
| `auth.login.toRegister` | Link → Registrieren | — |
| `auth.login.toForgot` | Link → Passwort vergessen | — |
| `auth.login.error` | generischer Login-Fehler (`HintTone.ERROR`) | **Assertive** |
| `auth.login.rateLimited` | Drosselung 429 (`HintTone.EFFECT_DEFERRED`) | **Assertive** |
| `auth.login.github` | P2 „Mit GitHub anmelden"-Button | — |

### Registrieren (`scopeId = register`)
| Tag | Element | liveRegion |
|---|---|---|
| `auth.register.form` | Formular-Container | — |
| `auth.register.email` | E-Mail-Eingabe | — |
| `auth.register.password` | Passwort-Eingabe | — |
| `auth.register.passwordReveal` | Reveal-Toggle | — |
| `auth.register.passwordConfirm` | Passwort-bestätigen-Eingabe | — |
| `auth.register.submit` | Konto-erstellen-Button | — |
| `auth.register.toLogin` | Link → Anmelden | — |
| `auth.register.error` | Validierungs-/Server-Fehler (generisch) | **Assertive** |
| `auth.register.rateLimited` | Drosselung 429 (`HintTone.EFFECT_DEFERRED`) | **Assertive** |

### E-Mail bestätigen (`scopeId = verify`)
| Tag | Element | liveRegion |
|---|---|---|
| `auth.verify.pending` | Pending-Container (= hartes Gate, §4.1) | — |
| `auth.verify.email` | echo'te E-Mail-Adresse (Orientierung) | — |
| `auth.verify.gateHint` | „bitte bestätigen"-Hinweis (`HintTone.GATED`) | — |
| `auth.verify.resend` | „Mail erneut senden"-Button | — |
| `auth.verify.resendResult` | neutrale Resend-Meldung / Drosselung | **Polite** |
| `auth.verify.logout` | „Abmelden"-Ausweg | — |
| `auth.verify.success` | Success-Container (nach Verify-Deep-Link) | — |
| `auth.verify.continue` | „Weiter" → Desktop/Login | — |
| `auth.verify.error` | Token ungültig/abgelaufen (`HintTone.ERROR`) | **Assertive** |

### Passwort vergessen (`scopeId = forgot`)
| Tag | Element | liveRegion |
|---|---|---|
| `auth.forgot.form` | Formular-Container | — |
| `auth.forgot.email` | E-Mail-Eingabe | — |
| `auth.forgot.submit` | „Link senden"-Button | — |
| `auth.forgot.toLogin` | Link → Anmelden | — |
| `auth.forgot.sent` | neutrale „falls Konto…"-Bestätigung (`HintTone.INFO`) | **Polite** |
| `auth.forgot.rateLimited` | Drosselung 429 (`HintTone.EFFECT_DEFERRED`) | **Assertive** |

### Passwort zurücksetzen (`scopeId = reset`)
| Tag | Element | liveRegion |
|---|---|---|
| `auth.reset.form` | Formular-Container | — |
| `auth.reset.password` | Neues-Passwort-Eingabe | — |
| `auth.reset.passwordReveal` | Reveal-Toggle | — |
| `auth.reset.passwordConfirm` | Bestätigen-Eingabe | — |
| `auth.reset.submit` | „Passwort ändern"-Button | — |
| `auth.reset.success` | Erfolg → Anmelden (`HintTone.INFO`) | **Polite** |
| `auth.reset.tokenInvalid` | Link ungültig/abgelaufen (`HintTone.ERROR`) | **Assertive** |
| `auth.reset.error` | generischer Reset-Fehler | **Assertive** |
| `auth.reset.rateLimited` | Drosselung 429 (`HintTone.EFFECT_DEFERRED`) | **Assertive** |

### P2 — GitHub OIDC-Zustände (`scopeId = github`)
| Tag | Element | liveRegion |
|---|---|---|
| `auth.github.redirecting` | „Weiter zu GitHub…" (Controls disabled) | — |
| `auth.github.returning` | „Anmeldung wird abgeschlossen…" (Rücksprung) | — |
| `auth.github.error` | OIDC-Fehler/-Abbruch (`HintTone.ERROR`) | **Assertive** |

---

## liveRegion-Vertrag (NEU, additiv — §8.2 der Spec)

**Neues, additives a11y-Muster** (der Code hat heute keine `liveRegion`). Dev setzt an die oben mit **Assertive**/
**Polite** markierten Knoten `Modifier.semantics { liveRegion = LiveRegionMode.Assertive }` bzw. `.Polite`:
- **Assertive** = Fehler + Drosselung (unterbricht, weil handlungsrelevant).
- **Polite** = neutrale Erfolg-/„falls Konto…"-/Resend-Bestätigungen (nicht unterbrechend).

Der `TonedHint` selbst bleibt unverändert; die liveRegion sitzt am selben Knoten wie der Tag. **QA/CYP-7 kennt diese
Knoten** über die Tags — der Announce ist damit test-verankert.

---

## Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Tags gesamt: 48** — Area `auth`. Aufschlüsselung: Gate/Boot 2 · login 10 · register 9 · verify 9 · forgot 6 ·
  reset 9 · github 3. (Der GitHub-Button ist `auth.login.github`, unter Login geführt; die 3 `auth.github.*`-Tags sind
  die reinen OIDC-Zustände.)
- **Announce-Knoten (liveRegion): 13.** **Assertive (10)** = `login.error`, `login.rateLimited`, `register.error`,
  `register.rateLimited`, `verify.error`, `forgot.rateLimited`, `reset.tokenInvalid`, `reset.error`,
  `reset.rateLimited`, `github.error`. **Polite (3)** = `verify.resendResult`, `forgot.sent`, `reset.success`.
- **Refine 2026-07-01 (CYP-177-Dev-Flag):** `auth.register.rateLimited` + `auth.reset.rateLimited` ergänzt (46→48).
  Grund: §7 drosselt Register (§7.3) **und** Reset (§7.5), aber die tags.md gab nur login/forgot einen dedizierten
  `rateLimited`-Knoten — Inkonsistenz. Der ehrliche 429 (`EFFECT_DEFERRED` amber, **≠** rotes `ERROR`) braucht auf
  **allen vier** drosselbaren Aktionen einen eigenen Knoten, damit die 429-Honesty-Invariante überall gleich
  test-verankert ist. **Keine neuen Keys** (reused `auth_rate_limited`/`auth_rate_limited_wait`).
- **0 Kollision:** Area `auth` existiert in keiner `*Tags.kt` (im Push-Schritt via `grep` gegengeprüft).
- **Kein Secret in Tags:** kein Token/Passwort/Endpunkt als Segment (Deep-Link-Token werden **nie** zu Tags).
- **Reuse:** keine bestehenden Tags reused (neue Fläche); die Reveal-Toggle-Tags folgen dem `SettingsTags`-Muster (eigener Tag, kein Reuse-Zwang).
