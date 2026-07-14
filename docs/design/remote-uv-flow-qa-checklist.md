# Remote UV-Flow B1-Enroll — UX-QA-Plan + Copy-Review (CYP-542, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-542 (Phase A/B1) · Stand 2026-07-14 · Status: **UX-QA-Plan, ratifikations-reif**
> (ungated Parallelarbeit während Dev B1 baut; **Design/QA, kein Code**). PO `1526530477…`.
> **Soll-Vorlage:** meine UV-UX-Specs `remote-uv-flow-{ux-spec,tags,keys}.md` @ `cffbf9ff`+`a0115e4c` (capability-tiering +
> HG2-Diceware-Default) **gegen** den **gebauten** CYP-460-Dialog (`OperatorAuthDialog`). Gegroundet READ-ONLY @ `eb705236`.
> **Scope:** Kohärenz + tote-Enden-Prüfung des **geplanten** E2E-Flows (nichts ist verdrahtet → Kriterien sind „die
> Verdrahtung muss X sicherstellen"). **Out-of-scope:** credential-neutrale Zähler-Copy Passphrase-Pfad = **CYP-543**;
> zxcvbn-Härtung = **CYP-544**.

## Lese-Legende
- **Soll** = Spec-Anker (meine `-ux-spec`/`-keys`/`-tags`) · **Ist** = gebauter Stand @ `eb705236` (file:line).
- **Status:** ✅ Soll+Ist kohärent · 🟡 PLAN (Soll definiert, Ist noch nicht gebaut — Verdrahtungs-Kriterium) ·
  ⚠ **GAP/Entscheid** (Lücke oder offene Design-Entscheidung → an PO, **nicht** selbst ausführen).

---

## A. Enroll (Diceware-Default · Passphrase-Set+Meter · blocklisted/unter-Floor · keine Sackgasse)

| # | Check | Soll | Ist @ eb705236 | Status |
|---|---|---|---|---|
| A1 | Diceware-One-Click-Default **sichtbar + prominent** (nicht bloß Hinweis) | ux-spec §4.1.1, `ENROLL_SUGGESTED` + `remote_pop_enroll_suggested` | `OperatorAuthStep.Enroll` rendert **nur** Disclosure-Text, **kein** Default (`OperatorAuthDialog.kt:94-118`) | 🟡 PLAN |
| A2 | **Accept-per-Klick** = starkes Credential by-construction | `remote_pop_enroll_suggest_use` (HG2) | nicht gebaut | 🟡 PLAN |
| A3 | **Regenerate** würfelt neue Diceware | `ENROLL_SUGGEST` + `remote_pop_enroll_suggest` („Andere vorschlagen") | nicht gebaut | 🟡 PLAN |
| A4 | Generierter Credential **angezeigt + notierbar** (nie masked-at-generation) | `remote_pop_enroll_suggested_save` (HG2-Ehrlichkeit) | n/a | 🟡 PLAN |
| A5 | **type-your-own sekundär** mit Strength-Meter (Text-Level-Label, Farbe nie alleiniger Träger) | §4.1.3, `ENROLL_TYPE_OWN` + `ENROLL_STRENGTH` + `remote_pop_strength_{weak,fair,strong}` | `severityColor(WARN)`-Pattern existiert (`:110`), aber kein Meter | 🟡 PLAN |
| A6 | **Setz + Bestätigung** = zweifache Eingabe; Commit erst wenn `confirm==set` | §4.1.4, `ENROLL_PIN_CONFIRM` | Enroll-Step hat **kein** Feld (nur `enrollPinSet`-Text) | 🟡 PLAN |
| A7 | **unter-Floor** (Entropie < ≥64-bit) → klare Fehler-Copy, **retryable**, Feld aktiv, **kein** Sackgassen-State | §4.1.5, `enrollError.tooWeak` + `remote_pop_enroll_too_weak` (Fehler-Ton ≠ errorContainer) | Taxonomie-Pattern gebaut (lokal=retryable, `:133-139`); tooWeak-Cause noch nicht | 🟡 PLAN |
| A8 | **Mismatch** → klare Fehler-Copy, retryable | `enrollError.mismatch` + `remote_pop_enroll_mismatch` (credential-neutral) | nicht gebaut | 🟡 PLAN |
| A9 | ⚠ **blocklisted** (lang genug für Entropie-Floor, aber Common-Phrase auf Blocklist) → **eigene** ehrliche Copy **oder** bewusst auf `tooWeak` gemappt? | **nicht in meiner Spec** — Floor ist entropie-strukturell, Blocklist ist Muster-basiert | — | ⚠ **GAP → G1** |
| A10 | Session-only-Downgrade bleibt WARN-amber (`▲` + severityColor(WARN)), kein Über-Versprechen | `remote_pop_enroll_session_only` (Q5/GE6) | **gebaut** (`OperatorAuthDialog.kt:100-116`) | ✅ |
| A11 | ⚠ **Enroll-Erfolg → Weiterlauf**: nach gesetztem Credential treibt der Flow **automatisch** in AUTHENTICATING→CONNECTED (sitzt nicht) | ux-spec §4.2 (Ergebnis-Taxonomie), **aber Übergang Enroll→Auth nicht explizit spezifiziert** | Enroll heute nicht submit-fähig → Übergang undefiniert | ⚠ **GAP → G2** |

## B. AUTHENTICATING-Mount + DeviceNotEnrolled-Route (Nutzer landet nie im Nirwana)

| # | Check | Soll | Ist @ eb705236 | Status |
|---|---|---|---|---|
| B1 | AUTHENTICATING mountet den gebauten `OperatorAuthDialog` statt INERT-Spinner | ux-spec §4.2, `popPrompt`-Slot | `RemoteConnectingView(popPrompt = null)` → Spinner (`HubConnectSelection.kt:258, 291-294`) | 🟡 PLAN |
| B2 | ⚠ **DeviceNotEnrolled → Enroll-Step** (Credential-Collection), **nicht** nur Retry | ux-spec §4.2 (Enroll-Routing) | **Ist = nur Retry-Button** (`connectRemote`), Kommentar: „in-flow enroll step is CYP-525 Inc 3" (`:346-365`) → **Retry re-hittet DeviceNotEnrolled = Loop-Sackgasse** bis Enroll-Step mountet | ⚠ **GAP → G3** |
| B3 | DeviceNotEnrolled ist WARN-amber advisory (nicht error-red, nicht terminal) + eigener Node `error(deviceNotEnrolled)` ≠ authRejected | Enumeration-Guard (HE) | **gebaut korrekt** (`:346-360`, WARN + eigener Tag) | ✅ |
| B4 | **OperatorUvFailed** (wrong-PIN/cancel) = retryable, ≠ terminal AuthRejected | ux-spec §5 (H2) | **gebaut** (`:376-378` RetryableRemoteFailure) | ✅ |
| B5 | ⚠ **Hand-off inline-Attempts ↔ connect-level OperatorUvFailed**: die Inline-`WrongPin`+`ATTEMPTS`/`LOCKED_OUT` (im Dialog, vor der Assertion) vs. das connect-level `OperatorUvFailed`→LOST — **wo ist die Grenze?** (Sinnvoll: Inline besitzt Retries/Lockout, bubbelt erst bei Abbruch/Lockout zu LOST — sonst zwei konkurrierende Retry-Flächen) | Inline: `OperatorAuthDialog.kt:140-147` · Bubble: `RemoteHubSession` UvFailed→LOST | ⚠ **GAP → G4** |
| B6 | Terminal (AuthRejected/TrustChanged) = **kein** Retry (fail-closed hard block) | §5 (H2) | **gebaut** (`:326-342`, kein Retry-Button) | ✅ |
| B7 | `granted` → weiter zur Hub-Liste **ohne** Erfolgs-Grün | ux-spec §4.2 | Muster konsistent (kein tertiary als Status) | 🟡 PLAN |

## C. 1-UV-für-N-Hint ehrlich (eine Ceremony, N Signings)

| # | Check | Soll | Ist @ eb705236 | Status |
|---|---|---|---|---|
| C1 | Hint present ⇔ Fensterung greift real (N>1 / cachingUv aktiv) — kein Phantom bei Einzel-Hub | ux-spec §4.3, `UV_COVERAGE` | `CachingUserVerification` gebaut (`PerTunnelPoP.kt`), **UI stumm** | 🟡 PLAN |
| C2 | Text kommuniziert **eine Bestätigung → mehrere Hubs**, nicht „jeder einzeln" | `remote_pop_uv_coverage` „Eine Bestätigung autorisiert die Hubs, die du jetzt öffnest — nicht jeden einzeln." | — | ✅ (Copy korrekt, s. Copy-Review CR3) |
| C3 | **Ehrlich begrenzt:** „die du **jetzt** öffnest", **nicht** „ganze Sitzung"; nach Fenster-Ablauf **neuer** Prompt (nie stille Re-Auth) | ux-spec §4.3 (H4) | Fenster `OPERATOR_UV_REUSE_WINDOW_MS` gebaut; Re-Prompt-Sichtbarkeit = Verdrahtung | 🟡 PLAN |
| C4 | ⚠ **Ceremony vs Signing** klar: eine UV-**Ceremony**, aber **N distinkte** kryptographische Signaturen (nicht ein geteiltes Token) — soll die UX das explizit machen, oder ist die aktuelle Copy ausreichend (kein Overstatement eines geteilten Secrets)? | Copy impliziert kein geteiltes Token; explizite Signing-Aussage wäre Over-Disclosure | — | ⚠ **Entscheid → G5** |
| C5 | Coverage-Hint neutral/advisory (`onSurfaceVariant`, optional `ⓘ`), **kein** Erfolgs-Grün | §6 | — | 🟡 PLAN |

## D. Cross-cut (Honesty-Töne · WCAG · DE/EN · fail-closed · Enumeration)

| # | Check | Status |
|---|---|---|
| D1 | Töne konsistent: **WARN-amber** = Downgrade/advisory (Session-only, DeviceNotEnrolled) · **error-Ton (nicht errorContainer)** = lokal-retryable (mismatch/tooWeak/wrongPin/lockout) · **errorContainer** = **nur** terminal Hub-Reject | ✅ Muster gebaut+spezifiziert |
| D2 | WCAG 1.4.1: Strength-Level-Label als **Text** (`_weak/_fair/_strong`), `▲`-Glyph als **separater** Node — Farbe nie alleiniger Träger | 🟡 PLAN (Meter neu) / ✅ (Glyph-Pattern gebaut) |
| D3 | DE=Default + EN-Parität, Argument-Anzahl identisch (alle net-new Keys) | ✅ (keys.md Self-Validation) |
| D4 | **fail-closed:** `Unavailable`/`keystoreUnavailable` **blockt** Connect, kein Fake-Erfolg | ✅ (`remote_pop_keystore_unavailable` gebaut) |
| D5 | **keine Enumeration:** un-enrolled → Enroll (nie „falsche PIN"); Feedback nur Count/Cooldown | ✅ (Node-Trennung gebaut, B3) |
| D6 | `pathHint` benennt den **echten** Pfad (Passphrase/PIN/Biometrie) — HG/HG2 | ✅ gebaut (`OperatorAuthDialog.kt:71-76`) |

---

## Copy-Review (ratifikations-reif)
- **CR1 — Enroll-Diceware-Set (A1–A4):** `remote_pop_enroll_suggested` „Empfohlen — 6 zufällige Wörter" + `_suggest_use`
  „Diese Passphrase verwenden" + `_suggest` „Andere vorschlagen" + `_suggested_save` „Notiere sie sicher — du brauchst sie
  bei jeder Anmeldung." → **kohärent, ehrlich, kein Bit-Literal**. EN-Parität ok.
- **CR2 — Gate-Fehler:** `remote_pop_enroll_mismatch` (credential-neutral „Eingaben stimmen nicht überein") + `_too_weak`
  („Passphrase zu schwach — 6 zufällige Wörter oder 12+ Zeichen") + `_too_short` (`%1$s`=Min). → klar, retryable-tonal.
- **CR3 — 1-UV-für-N:** `remote_pop_uv_coverage` sagt korrekt „eine Bestätigung → mehrere Hubs, nicht jeden einzeln"; **kein
  Overstatement** eines geteilten Secrets, **kein** „ganze Sitzung". ✅
- **CR4 — Reuse frozen (CYP-460):** `remote_pop_wrong_pin`/`_locked` sind „PIN"-Copy → am Passphrase-Pfad schiefer Wortlaut.
  **Bewusst NICHT hier behandelt = CYP-543** (credential-neutrale Zähler-Copy, PO-gefiled). Kein Retext im Rahmen CYP-542.
- **CR5 — pathHint** (`remote_pop_path_hint` „Bestätigung über %1$s") ist credential-flexibel — `%1$s` trägt den echten
  Pfadnamen (App-Passphrase/App-PIN/Touch-ID). ✅

---

## Gaps / Entscheide → an PO (nicht selbst ausführen)
- **G1 (A9) — Blocklist-Reject-Copy:** mein Floor ist entropie-strukturell (`tooWeak` ⇔ < ≥64 bit). Eine **lange, aber
  common** Passphrase (Blocklist/Dictionary-Treffer) braucht eine Entscheidung: **auf `tooWeak` mappen** (eine Fehler-Copy,
  simpel) **oder** distinkter Cause `enrollError.blocklisted` mit eigener Copy (präziser, H1-distinct-truth). Hängt an
  **CYP-544** (zxcvbn liefert das Signal). **Empfehlung:** distinkt (`blocklisted` ≠ „zu kurz/schwach strukturell"), damit
  die Copy ehrlich sagt *warum* — aber PO-Ruling, ich rate nicht.
- **G2 (A11) — Enroll→Auth-Übergang:** die Spec definiert die Enroll-Felder + die Auth-Taxonomie, aber **nicht explizit den
  automatischen Weiterlauf** nach gesetztem Credential. **Kriterium:** kein „Credential gesetzt, aber Nutzer sitzt" — nach
  Enroll-Erfolg treibt der Flow selbsttätig in AUTHENTICATING→CONNECTED. Soll ich das als §4.2-Ergänzung spezifizieren?
- **G3 (B2) — DeviceNotEnrolled-Loop (der schärfste Befund):** Ist heute = **nur Retry**, der DeviceNotEnrolled **erneut**
  triggert (Loop-Sackgasse), weil nichts enrollt (`:346-365`). Meine §4.2-Route (DeviceNotEnrolled→Enroll-Step) **behebt
  das**; solange sie nicht verdrahtet ist, ist der Zustand ein Nirwana. **Kriterium:** DeviceNotEnrolled MUSS zur
  Credential-Collection führen, nicht zu retry-that-reloops. (Verdrahtungs-Blocker, kein Spec-Loch — aber der PO sollte
  wissen, dass „Retry allein" heute die Falle ist.)
- **G4 (B5) — Inline-Attempts ↔ OperatorUvFailed-Grenze:** wo besitzt der **Inline-Dialog** die Retries/Lockout
  (`WrongPin`/`ATTEMPTS`/`LOCKED_OUT`) und **wann** bubbelt es zum connect-level `OperatorUvFailed`→LOST? Sinnvoll: inline
  retryt, bubbelt **erst** bei Abbruch/Lockout — sonst zwei konkurrierende Retry-Flächen (Doppel-Ehrlichkeits-Risiko). Ist
  ein **Dev-Mechanik-Seam** (S-UV1) — brauche ich für die spätere §-QA eine Ruling.
- **G5 (C4) — Ceremony-vs-Signing-Explizitheit:** aktuelle Copy impliziert **kein** geteiltes Token (gut). Soll die UX
  explizit „N eigene Signaturen, eine Bestätigung" machen, oder bleibt es implizit (Over-Disclosure vermeiden)? Neige zu
  **implizit belassen** — aber PO-Präferenz.

## Ratifikations-Ask
Ruling zu **G1–G5**; danach wird diese Checkliste die **§-QA-Rubrik** für die verdrahteten Nähte (post-B1-Build). Kein
Bau, keine develop-Berührung; docs-only auf `feature/CYP-542-uv-ui-spec`.
