# Desktop-Native Remote-Operator-UX — testTag-Vertrag (CYP-460, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-460 (Epic CYP-427) · Stand 2026-07-12 · Status: **Spec-Closure**; eingefroren als
> UI-Vorlage. Begleit-Spec: `desktop-remote-operator-ux-spec.md`. Ergänzung zu CYP-429.
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): prefixless `<area>[.<scopeId>].<element>[.<qualifier>]`, camelCase,
> keine Punkte im Wert. **Geteilte API mit QA (CYP-7) — über den PO koordinieren.**
> **Vertieft die bestehende Area `remote`** (CYP-429) nativ — `remote.authStep.*` + `remote.login.*`. Kein neuer Namespace
> (Konsistenz zu `remote_*`/`hubConnect`; Distanz zu `connector`). `remote.authStep.enroll` ist **Reuse** aus CYP-429.

## Native Login (§4)
| Tag | Wert | Zweck |
|---|---|---|
| `loginBrowserHandoff` | `remote.login.browserHandoff` | „Weiter im Browser …" (OIDC System-Browser-Handoff, Loopback RFC 8252). |
| `loginBrowserReturn` | `remote.login.browserReturn` | „Zurück zur App …" (Loopback-Rückkehr empfangen). |

## DevicePoP-Union (§5)
| Tag | Wert | Zweck |
|---|---|---|
| `popPathHint` | `remote.authStep.pathHint` | benennt den aktiven Pfad (PIN vs Biometrie, H1). |
| `popPinField` | `remote.authStep.pinField` | PIN/Passphrase-Feld (Raw, Reuse `AuthPasswordField`). |
| `popPinReveal` | `remote.authStep.pinReveal` | Reveal-Toggle (Text-Label, nie a11y-Leak). |
| `popBiometricPrompt` | `remote.authStep.biometricPrompt` | Fido2-Biometrie-Aufforderung (Touch-ID/Hello). |
| `popEnroll` | `remote.authStep.enroll` | **Reuse CYP-429** — Erst-Setup (Enroll). |
| `popEnrollPinSet` | `remote.authStep.enrollPinSet` | PIN setzen beim Raw-Enroll (§5.2). |
| `popAttempts` | `remote.authStep.attempts` | Versuchszähler (lokal, Threat-Model-parametrisiert). |
| `popLockedOut` | `remote.authStep.lockedOut` | temporäre Sperre nach N Fehlversuchen (≠ Hub-Reject). |
| `popError(cause)` | `remote.authStep.error.<cause>` | `<cause>` ∈ `pinWrong`/`biometricFailed`/`keystoreUnavailable`/`authRejected`/`needsEnroll`/`cancelled`. |

## Fail-closed-Anker (für §-QA)
- `remote.authStep.error.authRejected` = **terminal** (Hub-Reject, kein Retry-Tag) ≠ lokal `pinWrong`/`lockedOut` (H2).
- `remote.authStep.biometricPrompt`-Fehl → `pinField`-Fallback **sichtbar** (H1).
- `remote.authStep.pathHint` nennt den echten Pfad — nie Biometrie-Optik ohne Biometrie (Linux = immer PIN).
- Recovery (Q6): **kein** Tag/Flow bis Auftraggeber-Ruling (kein vorgetäuschter Ein-Klick-Recovery).

## Reuse (bestehende Tags/Areas — NICHT neu anlegen; verifiziert @ `817a674c`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `remote.authStep.enroll` / `remote.authStep.popPrompt` / `remote.authStep.error` | CYP-429 | Auth-Schritt-Basis; nativ vertieft |
| `remote.trust.{fingerprint,wordlist,hex,qr,pinPrompt,changedAlarm}` | CYP-429 | TOFU nativ (§6) |
| `remote.connect.*` / `remote.relayDrop` / `remote.context.*` | CYP-429 | Verbindungszustand/Relay/Kontext (§7) |
| Area `auth` (`AuthTags`, `auth.login.*`) | CYP-176 | Email/Passwort-Login nativ (§4) |

## Self-Validation
- **10 neue Tags** in der bestehenden Area `remote` (`login.browserHandoff`/`.browserReturn`, `authStep.pathHint`/
  `.pinField`/`.pinReveal`/`.biometricPrompt`/`.enrollPinSet`/`.attempts`/`.lockedOut`/`.error.<cause>`); `authStep.enroll`
  = Reuse. `popError` trägt Qualifier `<cause>`.
- **0 Kollision:** die zehn Werte existieren nicht in CYP-429s `remote.*`-Satz (verifiziert); Distanz zu `connector` gewahrt.
- **Geteilte API mit QA (CYP-7):** über den PO mit dem Tester abstimmen (Frozen-Contract).
- Jeder Tag ist in `desktop-remote-operator-ux-spec.md` (§10) verankert und trägt (wo Text) einen Copy-Key aus `-keys.md`.
