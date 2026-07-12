# Desktop-Native Remote-Operator-UX — i18n-Keys (CYP-460, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-460 (Epic CYP-427) · Stand 2026-07-12 · Status: **Spec-Closure (Q-Rulings gefolded;
> Q2/Q3 Threat-Model-parametrisiert, Q6 Recovery offen/eskaliert)**; eingefroren als UI-Vorlage. Begleit-Spec:
> `desktop-remote-operator-ux-spec.md`. Ergänzung zu CYP-429.
> Konvention (verifiziert @ develop `817a674c`): Underscore-Realkeys, `%1$s`-Args, DE-Default + EN, Parität Pflicht.
> Neue Familien **`remote_pop_*`** / **`remote_login_*`** (greenfield, 0 Kollision verifiziert; Distanz zu `connector_*`).
> **Reuse** CYP-429 `remote_trust_*` / `remote_context_*` / `remote_e2e_indicator` / `remote_relay_dropped` (TOFU/Relay/Kontext).

## Native Login (§4)
| Key | DE | EN |
|---|---|---|
| `remote_login_browser_handoff` | Weiter im Browser … | Continuing in your browser… |
| `remote_login_browser_return` | Zurück zur App … | Returning to the app… |

## DevicePoP — Prompt & Pfad (§5.1/§5.3)
| Key | DE | EN |
|---|---|---|
| `remote_pop_path_hint` | Bestätigung über %1$s | Confirming via %1$s |
| `remote_pop_pin_title` | App-PIN eingeben | Enter your app PIN |
| `remote_pop_pin_body` | Bestätige mit deiner App-PIN, um remote auf deine Hubs zuzugreifen. | Confirm with your app PIN to access your hubs remotely. |
| `remote_pop_biometric_title` | Mit %1$s bestätigen | Confirm with %1$s |

## DevicePoP — Fehler & Fallback (§5.3)
| Key | DE | EN |
|---|---|---|
| `remote_pop_wrong_pin` | Falsche PIN. Noch %1$s Versuche. | Wrong PIN. %1$s attempts left. |
| `remote_pop_locked` | Zu viele Fehlversuche. Erneut in %1$s. | Too many attempts. Try again in %1$s. |
| `remote_pop_biometric_failed` | Biometrie fehlgeschlagen — nutze deine App-PIN. | Biometrics failed — use your app PIN. |
| `remote_pop_rejected` | Vom Hub abgelehnt. Bitte neu anmelden. | Rejected by the hub. Please sign in again. |
| `remote_pop_keystore_unavailable` | Schlüsselbund nicht verfügbar. | Keychain not available. |

## DevicePoP — Enroll (§5.2)
| Key | DE | EN |
|---|---|---|
| `remote_pop_enroll_title` | Dieses Gerät einrichten | Set up this device |
| `remote_pop_enroll_pin` | App-PIN festlegen | Set an app PIN |
| `remote_pop_enroll_session_only` | Gilt nur für diese Sitzung — sicherer Geräte-Schlüsselbund folgt. | Applies to this session only — secure device keychain to come. |

> **Honesty-Anker:** `remote_pop_wrong_pin`/`remote_pop_locked` = **lokal** (Versuch vor Assertion, Zähler/Lockout) ≠
> `remote_pop_rejected` = **hub-seitig terminal** (`RemoteFailure.AuthRejected`, kein stiller Retry) — H2, nie vermischt.
> `remote_pop_biometric_failed` → **Fallback auf PIN** (Raw-Pfad, H1). `remote_pop_path_hint`/`_biometric_title` `%1$s` =
> Pfad-Name („Touch-ID"/„Windows Hello") — nie Biometrie-Optik ohne Biometrie. **`remote_pop_enroll_session_only` (Q5):**
> ehrlich „nur für diese Sitzung" solange `DEVICE_SECURE` named-not-built — **keine** Hardware-/Geräte-Persistenz-Zusage.
> `%1$s` in `_wrong_pin`/`_locked` = Versuchszahl/Wartezeit (Threat-Model-parametrisiert, Q2/Q3). Recovery (Q6) = **keine
> Copy** bis Auftraggeber-Ruling (kein vorgetäuschter Ein-Klick-Recovery).

## Reuse (bestehende Keys — NICHT neu anlegen; verifiziert @ `817a674c`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `remote_trust_first_*` / `remote_trust_wordlist_label` / `remote_trust_hex_label` / `remote_trust_qr_label` / `remote_trust_oob` / `remote_trust_changed_*` / `remote_trust_pinned` | CYP-429 | TOFU nativ (§6) — **identisch**, keine Varianten |
| `remote_e2e_indicator` / `remote_relay_dropped` / `remote_context_operating` | CYP-429 | E2E-Indikator (nur Nutzlast, §7/H5) / Relay-Drop / Kontext-Banner |
| `auth_*` (Login/Passwort) | CYP-176 `AuthGate` | Email/Passwort-Login nativ (§4) |
| `hubconnect_*` | CYP-395/419 | Hub-Liste/Connect (nach PoP) |

## Self-Validation
- **14 neue Keys**, alle DE+EN befüllt, gleiche Argument-Anzahl je Sprache: 12 `remote_pop_*` + 2 `remote_login_*` = **14**.
- **Argument-Keys (genau ein `%1$s`, DE=EN):** `remote_pop_path_hint`, `remote_pop_biometric_title`, `remote_pop_wrong_pin`,
  `remote_pop_locked` = **4**; alle übrigen **0 Arg**. DE/EN-Argument-Anzahl identisch.
- **Kein content-tragender/sensibler Klartext** — `%1$s` = Pfad-Name / Versuchszahl / Wartezeit; **kein Secret/PIN/
  Fingerprint-Rohwert** in einer Copy.
- **Kollision:** **0** — `remote_pop_*`/`remote_login_*` greenfield (@ `817a674c`); Distanz zu `connector_*` gewahrt.
- **DE/EN-Parität:** jede Zeile beidseitig.
- Jeder Key ist in `desktop-remote-operator-ux-spec.md` (§11) und `-tags.md` verankert.
