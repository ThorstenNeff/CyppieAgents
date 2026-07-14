# Remote UV-Flow B1-Enroll — UX-QA-Plan + Copy-Review (CYP-542, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-542 (Phase A/B1) · Stand 2026-07-14 · Status: **RATIFIZIERT — kanonische §-QA-Rubrik**
> (PO-Rulings G1–G5 `1526532076…`/`1526532077…` eingefaltet; post-B1-Build maßgeblich). **Design/QA, kein Code**.
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
| A9 | **blocklisted** (lang genug, aber Common-Phrase) → **distinkte** ehrliche Copy die *warum* sagt (≠ tooWeak) | **G1 RULED distinkt:** `enrollError.blocklisted` + `remote_pop_enroll_blocklisted`; Signal `meets()` in B1 vorhanden (kein CYP-544-Blocker) | nicht gebaut | 🟡 PLAN (G1 ✅ geruled) |
| A10 | Session-only-Downgrade bleibt WARN-amber (`▲` + severityColor(WARN)), kein Über-Versprechen | `remote_pop_enroll_session_only` (Q5/GE6) | **gebaut** (`OperatorAuthDialog.kt:100-116`) | ✅ |
| A11 | **Enroll-Erfolg → Weiterlauf**: kein „gesetzt, Nutzer sitzt"; auto-treibt AUTHENTICATING→CONNECTED; in-hand-Passphrase = **erste UV** (kein sofortiger 2. Prompt, `DecryptedKeyHold`-Window) | **G2 RULED:** ux-spec §4.2 ergänzt (Enroll→seal→AUTH mit in-hand-Passphrase; Startpunkt des 1-UV-für-N-Fensters) | nicht gebaut | 🟡 PLAN (G2 ✅ geruled) |

## B. AUTHENTICATING-Mount + DeviceNotEnrolled-Route (Nutzer landet nie im Nirwana)

| # | Check | Soll | Ist @ eb705236 | Status |
|---|---|---|---|---|
| B1 | AUTHENTICATING mountet den gebauten `OperatorAuthDialog` statt INERT-Spinner | ux-spec §4.2, `popPrompt`-Slot | `RemoteConnectingView(popPrompt = null)` → Spinner (`HubConnectSelection.kt:258, 291-294`) | 🟡 PLAN |
| B2 | **DeviceNotEnrolled → Enroll-Step** (Credential-Collection), **nicht** nur Retry | ux-spec §4.2 (Enroll-Routing) | **Ist = nur Retry-Button** (`:346-365`) → **Loop-Sackgasse** bis Enroll-Step mountet | ⚠→ **G3 RULED harte AC** der Enroll+Wiring-Slice: kein DeviceNotEnrolled→Retry-Sackgasse darf nach der Slice bleiben (PO relayt an Dev) |
| B3 | DeviceNotEnrolled ist WARN-amber advisory (nicht error-red, nicht terminal) + eigener Node `error(deviceNotEnrolled)` ≠ authRejected | Enumeration-Guard (HE) | **gebaut korrekt** (`:346-360`, WARN + eigener Tag) | ✅ |
| B4 | **OperatorUvFailed** (wrong-PIN/cancel) = retryable, ≠ terminal AuthRejected | ux-spec §5 (H2) | **gebaut** (`:376-378` RetryableRemoteFailure) | ✅ |
| B5 | **EINE Retry-Fläche:** Inline-Dialog besitzt Retry/Lockout (`WrongPin`/`ATTEMPTS`/`LOCKED_OUT`); bubbelt zu connect-level `OperatorUvFailed`→LOST **nur** bei Abbruch / Lockout-erschöpft | **G4 RULED (mein Modell adoptiert):** Inline: `OperatorAuthDialog.kt:140-147` · Bubble: `RemoteHubSession` UvFailed→LOST | 🟡 PLAN (G4 ✅ geruled, §-QA-Kriterium) |
| B6 | Terminal (AuthRejected/TrustChanged) = **kein** Retry (fail-closed hard block) | §5 (H2) | **gebaut** (`:326-342`, kein Retry-Button) | ✅ |
| B7 | `granted` → weiter zur Hub-Liste **ohne** Erfolgs-Grün | ux-spec §4.2 | Muster konsistent (kein tertiary als Status) | 🟡 PLAN |

## C. 1-UV-für-N-Hint ehrlich (eine Ceremony, N Signings)

| # | Check | Soll | Ist @ eb705236 | Status |
|---|---|---|---|---|
| C1 | Hint present ⇔ Fensterung greift real (N>1 / cachingUv aktiv) — kein Phantom bei Einzel-Hub | ux-spec §4.3, `UV_COVERAGE` | `CachingUserVerification` gebaut (`PerTunnelPoP.kt`), **UI stumm** | 🟡 PLAN |
| C2 | Text kommuniziert **eine Bestätigung → mehrere Hubs**, nicht „jeder einzeln" | `remote_pop_uv_coverage` „Eine Bestätigung autorisiert die Hubs, die du jetzt öffnest — nicht jeden einzeln." | — | ✅ (Copy korrekt, s. Copy-Review CR3) |
| C3 | **Ehrlich begrenzt:** „die du **jetzt** öffnest", **nicht** „ganze Sitzung"; nach Fenster-Ablauf **neuer** Prompt (nie stille Re-Auth) | ux-spec §4.3 (H4) | Fenster `OPERATOR_UV_REUSE_WINDOW_MS` gebaut; Re-Prompt-Sichtbarkeit = Verdrahtung | 🟡 PLAN |
| C4 | **Ceremony vs Signing:** kein Overstatement eines geteilten Tokens (die Nicht-Overstatement-Eigenschaft ist das Kriterium) | **G5 RULED implizit belassen:** aktuelle Copy ehrlich+ausreichend; „N Signaturen" explizit = Over-Disclosure/Jargon ohne Nutzerwert | ✅ (Copy erfüllt, kein geteiltes-Token-Implikat) |
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
  („Passphrase zu schwach …") + `_too_short` (`%1$s`=Min) + **`_blocklisted`** („Diese Passphrase ist zu verbreitet — sie
  steht auf einer Liste bekannt-schwacher Passphrasen. Bitte eine andere.", G1). → klar, retryable-tonal, **distinkte
  Ursachen** (H1). Enroll-Copy darf spezifisch-ehrlich sein (kein Enumeration-Oracle — Nutzer setzt eigenes Secret).
- **CR3 — 1-UV-für-N:** `remote_pop_uv_coverage` sagt korrekt „eine Bestätigung → mehrere Hubs, nicht jeden einzeln"; **kein
  Overstatement** eines geteilten Secrets, **kein** „ganze Sitzung". ✅
- **CR4 — Reuse frozen (CYP-460):** `remote_pop_wrong_pin`/`_locked` sind „PIN"-Copy → am Passphrase-Pfad schiefer Wortlaut.
  **Bewusst NICHT hier behandelt = CYP-543** (credential-neutrale Zähler-Copy, PO-gefiled). Kein Retext im Rahmen CYP-542.
- **CR5 — pathHint** (`remote_pop_path_hint` „Bestätigung über %1$s") ist credential-flexibel — `%1$s` trägt den echten
  Pfadnamen (App-Passphrase/App-PIN/Touch-ID). ✅

---

## Rulings G1–G5 (PO-ratifiziert `1526532076…`/`1526532077…`, eingefaltet)
- **G1 (A9/CR2) → DISTINKT.** Eigener Cause `enrollError.blocklisted` ≠ `tooWeak` + `remote_pop_enroll_blocklisted`
  (ehrliches *warum*). **Kein CYP-544-Blocker:** B1 hat das Blocklist-Boolean schon (`meets() = Floor ∧ !blocklist`).
  CYP-543-Neutralität betrifft nur den **Auth**-Pfad — Enroll-Copy darf spezifisch-ehrlich sein (kein Enumeration-Oracle).
- **G2 (A11) → JA, §4.2-Ergänzung geliefert.** Enroll-Erfolg → seal → **selbsttätig** AUTHENTICATING→CONNECTED; die
  in-hand-Passphrase dient als **erste UV** (`DecryptedKeyHold`-≤120s-Window) → **kein** sofortiger zweiter Prompt; zugleich
  Startpunkt des 1-UV-für-N-Fensters. Kriterium „kein gesetzt-aber-Nutzer-sitzt" bestätigt.
- **G3 (B2) → harte AC der Enroll+Wiring-Slice.** DeviceNotEnrolled→Enroll-Route ist die Lösung; **kein** DeviceNotEnrolled→
  Retry-Sackgasse darf nach der Slice übrig bleiben. Verdrahtungs-Blocker, kein Spec-Loch. PO relayt an Dev.
- **G4 (B5) → mein Modell adoptiert.** Inline-Dialog besitzt Retry/Lockout; bubbelt zu `OperatorUvFailed`→LOST **nur** bei
  Abbruch / Lockout-erschöpft. **EINE** Retry-Fläche. §-QA-Kriterium.
- **G5 (C4) → IMPLIZIT belassen.** Aktuelle Coverage-Copy ehrlich+ausreichend; „N Signaturen" explizit = Over-Disclosure.
  Kriterium = Nicht-Overstatement (kein geteiltes-Token-Implikat) — erfüllt.

## Status
Diese Checkliste ist die **kanonische §-QA-Rubrik** für die verdrahteten Nähte **post-B1-Build**. Kein Bau, keine
develop-Berührung; docs-only auf `feature/CYP-542-uv-ui-spec`.
