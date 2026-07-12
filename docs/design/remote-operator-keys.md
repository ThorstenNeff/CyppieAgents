# Remote-Modus Operator-UX — i18n-Keys (CYP-429, Epic CYP-427 Phase 2)

> Owner: UIUX-Designer · Story CYP-429 (Epic CYP-427) · Stand 2026-07-11 · Status: **Spec-Closure (Q1–Q5 geruled)**;
> eingefroren als UI-Vorlage. Begleit-Spec: `remote-operator-ux-spec.md`.
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml` @ develop `4466ca20`):
> **Underscore-Realkeys**, positionsbasierte Argumente `%1$s`/`%2$s`. **DE = Default**, **EN** (`values-en/`). Parität Pflicht.
> Neue Key-Familie **`remote_*`** (greenfield, 0 Kollision verifiziert). Maskiertes/Login reused Phase-1 (`auth_*`, `hubconnect_*`).

## Auth-Schritt (§5, Passkey/WebAuthn-PoP)
| Key | DE | EN |
|---|---|---|
| `remote_authstep_title` | Bestätige mit deinem Passkey | Confirm with your passkey |
| `remote_authstep_body` | Bestätige mit deinem Passkey, um remote auf deine Hubs zuzugreifen. | Confirm with your passkey to access your hubs remotely. |
| `remote_authstep_error` | Passkey-Bestätigung fehlgeschlagen. Erneut versuchen. | Passkey confirmation failed. Try again. |

## Verbindungszustand (§7)
| Key | DE | EN |
|---|---|---|
| `remote_connect_relay` | Verbinde über die Control Plane… | Connecting via the control plane… |
| `remote_connect_e2e` | Sichere Verbindung (E2E) wird aufgebaut… | Establishing a secure (E2E) connection… |
| `remote_connect_trustcheck` | Hub-Identität wird geprüft… | Verifying hub identity… |
| `remote_connect_trust_provisional` | Vertrauensprüfung vorläufig — echtes Pinnen folgt. | Trust check provisional — real pinning to come. |
| `remote_error_relay` | Control Plane / Relay nicht erreichbar. | Control plane / relay not reachable. |
| `remote_relay_dropped` | Remote-Verbindung zu %1$s unterbrochen — verbinde neu… | Remote connection to %1$s lost — reconnecting… |
| `remote_conn_degraded` | Verbindung langsam/instabil | Connection slow/unstable |

## Trust-Affordances (§8)
| Key | DE | EN |
|---|---|---|
| `remote_e2e_indicator` | E2E-verschlüsselt via Relay | E2E-encrypted via relay |
| `remote_trust_pinned` | Identität gepinnt | Identity pinned |
| `remote_trust_first_title` | Erstverbindung mit %1$s | First connection to %1$s |
| `remote_trust_first_body` | Bestätige die Identität über einen anderen Kanal, wenn Sicherheit zählt, und pinne sie. | Confirm the identity via another channel if security matters, then pin it. |
| `remote_trust_wordlist_label` | Vergleichs-Wörter | Comparison words |
| `remote_trust_hex_label` | Fingerprint (Hex) | Fingerprint (hex) |
| `remote_trust_qr_label` | QR scannen | Scan QR |
| `remote_trust_oob` | Über einen anderen Kanal bestätigen | Confirm via another channel |
| `remote_trust_changed_title` | Identität von %1$s hat sich geändert | %1$s's identity has changed |
| `remote_trust_changed_body` | Das kann ein Angriff (MITM) oder eine legitime Neuinstallation sein. Nicht fortfahren, bis geklärt. | This could be an attack (MITM) or a legitimate reinstall. Don't proceed until you've confirmed. |

## Kontext + Wechsel (§6/§9)
| Key | DE | EN |
|---|---|---|
| `remote_context_operating` | Fern-Betrieb: %1$s | Remote session: %1$s |
| `remote_switch_transition` | Trenne von %1$s … verbinde mit %2$s | Disconnecting from %1$s … connecting to %2$s |

## a11y
| Key | DE | EN |
|---|---|---|
| `a11y_remote_context` | Fern-Betrieb über Relay: Hub %1$s, E2E-verschlüsselt. | Remote session via relay: hub %1$s, E2E-encrypted. |
| `a11y_remote_trust_changed` | Warnung: Hub-Identität geändert — mögliches MITM, nicht fortfahren. | Warning: hub identity changed — possible MITM, do not proceed. |

> **Honesty-Anker:** `remote_e2e_indicator` = **nur Transport** (CP=Ciphertext), nie als Hub-Vertrauen lesbar (H3) —
> getrennt von `remote_trust_pinned` (Hub-Authentizität/TOFU, H1). `remote_trust_changed_*` = **harter Block + OOB-Re-Pin,
> nie still** (H2/Q2). `remote_trust_first_*` + Wordlist/Hex/QR = mehrschichtige TOFU-Hilfe (Q4). `remote_conn_degraded` =
> **nur bei echter Degradation**, neutral, kein Alarm (Q3). `remote_switch_transition` = expliziter Teardown, ein Hub (Q5).
> **`remote_connect_trust_provisional` (CYP-475, §-QA-Befund ①):** am Trust-Check gerendert, solange echtes Pinnen
> (`dhPubKey`) RR5-nachgelagert ist — sagt **ehrlich „vorläufig"**, damit die Trust-Check-Zeile **nicht** echte
> Krypto-Verifikation impliziert (Übersagen-durch-Auslassung vermeiden; Präzedenz `remote_pop_enroll_session_only`).
> **Kein „RR5"-Jargon in der User-Copy** — nur „vorläufig / echtes Pinnen folgt".

## Reuse (bestehende Keys/Muster — NICHT neu anlegen; verifiziert @ `4466ca20`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `auth_*` (Login/OIDC) | CYP-176 `AuthGate` | zentraler Login (Seq-Start), kein Neubau |
| `hubconnect_*` / Area `hubConnect` | CYP-395/419 | Hub-Liste, Presence, Modus-Wahl (`mode.remote` **aktiviert**), Connect-Idiom |
| `workspace_role_indicator_operator` / `WorkspaceTags.ROLE_INDICATOR` | Workspace | Host des „Fern-Betrieb"-Kontext-Strips (§9) |
| `event_severity_warn` / `EventVisuals` WARN | CYP-300 | Trust-Änderungs-Alarm-Ton (§8.1) |

## Self-Validation
- **24 neue Keys** (inkl. CYP-475 `remote_connect_trust_provisional`), alle DE+EN befüllt, gleiche Argument-Anzahl je
  Sprache: 22 `remote_*` + 2 `a11y_remote_*` = **24**.
- **Argument-Keys:** 1-Arg (`%1$s`): `remote_relay_dropped`, `remote_trust_first_title`, `remote_trust_changed_title`,
  `remote_context_operating`, `a11y_remote_context` = **5**; 2-Arg (`%1$s`/`%2$s`): `remote_switch_transition` = **1**;
  alle übrigen **0 Arg**. DE/EN-Argument-Anzahl identisch.
- **Kein content-tragender/sensibler Klartext** — `%1$s`/`%2$s` sind nur Hub-Name (Anzeige) — **kein Secret/Key/Fingerprint-Rohwert**
  in einer Copy (Fingerprint/Wordlist/QR sind gerenderte Werte, keine Copy-Strings).
- **Kollision:** **0** — `remote_*` greenfield (`grep name="remote_"` @ `4466ca20` liefert nichts).
- **DE/EN-Parität:** jede Zeile beidseitig.
- Jeder Key ist in `remote-operator-ux-spec.md` (§13) und `-tags.md` verankert.
