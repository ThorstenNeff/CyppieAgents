# Hub-Verbindungs-UX — i18n-Keys (Epic CYP-395, Phase 1 Lokal-Modus)

> Owner: UIUX-Designer · Epic CYP-395 · Stand 2026-07-11 · Status: **Spec-Closure (Q1–Q8 geruled)**; eingefroren als
> Vorlage für Devs `connect_*`-Screens (S-L). Begleit-Spec: `hub-connection-ux-spec.md`.
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml` @ develop `a2f66ae8`):
> **Underscore-Realkeys** (keine Punkte), positionsbasierte Argumente `%1$s`. **DE = Default** (`values/`), **EN**
> (`values-en/`). Parität Pflicht. Modul `:app:shared` compose.resources → **Shared-Key-Sync mit der Impl timen**: die
> `connect_*`-Keys landen mit Devs S-L-Slice; nach Key-Landung muss das konsumierende Modul re-syncen, sonst bricht ein
> Shared-Check.
> Maskiertes Credential-Feld **spiegelt** `settings_apikey_*` (CYP-D3), Login **reused** `auth_*` (CYP-176).

## Erststart (Seq A)
| Key | DE | EN |
|---|---|---|
| `connect_prepare_title` | Cyppie wird vorbereitet… | Setting up Cyppie… |
| `connect_register_intro` | Melde dich an, um diesen Hub deinem Konto zuzuordnen. | Sign in to link this hub to your account. |
| `connect_register_name_label` | Name dieses Hubs | This hub's name |
| `connect_register_error_offline` | Registrierung braucht Internet. Erneut versuchen. | Registration needs an internet connection. Try again. |
| `connect_ready_title` | Hub bereit | Hub ready |
| `connect_ready_enter` | Loslegen | Get started |

## Credentials (Seq A / §7) — Muster `settings_apikey_*`
| Key | DE | EN |
|---|---|---|
| `connect_creds_title` | Hinterlege deine Anthropic-Credentials | Add your Anthropic credentials |
| `connect_creds_placeholder` | sk-ant-… | sk-ant-… |
| `connect_creds_privacy` | Bleibt auf diesem Gerät (Keystore) — geht nie an cyppie-agents.com. | Stays on this device (keystore) — never sent to cyppie-agents.com. |
| `connect_creds_keystore` | Im Schlüsselbund dieses Geräts gesichert. | Secured in this device's keychain. |
| `connect_creds_masked` | Hinterlegt: %1$s | Stored: %1$s |
| `connect_creds_validating` | Credentials werden geprüft… | Checking credentials… |
| `connect_creds_validated` | Credentials gültig — hinterlegt. | Credentials valid — stored. |
| `connect_creds_invalid` | Key ungültig — bitte prüfen. | Key invalid — please check. |
| `connect_creds_unreachable` | Konnte nicht geprüft werden (Anthropic nicht erreichbar) — gespeichert, später erneut prüfen. | Couldn't verify (Anthropic unreachable) — saved, check again later. |

> **`connect_creds_privacy` (H4/Q8)** = Zero-Knowledge-Zeile, **exakt auf Credentials begrenzt** — keine pauschale
> „wir sehen nichts"-Aussage. **`connect_creds_keystore` (H6/Q8)** = optionale, zurückhaltende, plattformbewusste
> Zeile — **keine** Hardware-/Secure-Enclave-Zusage. **`connect_creds_validated` ist INFO, nie Erfolgs-Grün**;
> **`connect_creds_unreachable` = WARN**, distinkt von **`connect_creds_invalid` = Fehler** (H3).
> **`%1$s` in `connect_creds_masked`** = server-maskierter Wert `***<letzte4>` (aus `Secrets.mask()`) — **kein Klartext**,
> der Client maskiert nie selbst.

## Hub-Auswahl (Seq B)
| Key | DE | EN |
|---|---|---|
| `connect_hubs_title` | Wähle deinen Hub | Choose your hub |
| `connect_hubs_empty` | Noch kein Hub registriert. | No hub registered yet. |
| `connect_hubs_register` | Hub registrieren | Register a hub |
| `connect_hubs_error` | Hub-Liste nicht erreichbar. Erneut versuchen. | Can't reach the hub list. Try again. |
| `connect_presence_online` | online | online |
| `connect_presence_offline` | offline · zuletzt gesehen %1$s | offline · last seen %1$s |

> **Presence (H1/Q2) advisory:** `connect_presence_online/offline` beschreiben die **Registry-Behauptung** der Control
> Plane — nie in `tertiary`-Grün, nie als „verbunden" lesbar. **`%1$s` in `_offline`** = **relative** Zeit (Hausformat,
> wie bestehende Zeitstempel). Ein `online`-Hub ist **nicht** garantiert lokal erreichbar — Bodenwahrheit = Connect (§6).

## Modus-Wahl (Seq B / §5)
| Key | DE | EN |
|---|---|---|
| `connect_mode_local` | Lokal | Local |
| `connect_mode_local_sub` | Direkt im selben Netz — privat und schnell. | Direct on the same network — private and fast. |
| `connect_mode_remote` | Remote | Remote |
| `connect_mode_remote_soon` | kommt bald | coming soon |
| `connect_mode_connect` | Verbinden | Connect |

> **Remote (H2/Q3) ehrlich deaktiviert:** `connect_mode_remote` non-interaktiv, `connect_mode_remote_soon`=„kommt
> bald", nicht vorausgewählt, kein Fake-Klick.

## Lokal-Connect-Zustände (§6) — Idiom `ConnectionStatus`
| Key | DE | EN |
|---|---|---|
| `connect_state_attempting` | Verbinde mit deinem Hub… | Connecting to your hub… |
| `connect_state_handshake` | Sichere Verbindung wird aufgebaut… | Establishing a secure connection… |
| `connect_state_connected` | Verbunden | Connected |
| `connect_error_hub_offline` | Hub ist offline. Starte den Hub und versuche es erneut. | Hub is offline. Start the hub and try again. |
| `connect_error_port` | Hub unter %1$s nicht erreichbar. Läuft er in diesem Netz? | Hub not reachable at %1$s. Is it running on this network? |
| `connect_error_handshake` | Sichere Verbindung fehlgeschlagen. Melde dich neu an. | Secure connection failed. Please sign in again. |
| `connect_error_never_online` | Diese erste Anmeldung braucht einmal Internet. | This first sign-in needs an internet connection once. |

> **`attempting`/`handshake` neutral** (`onSurfaceVariant`), **nie** grün/LIVE vorzeitig; **`connected` = LIVE-Idiom**
> (`●`+`primary`), erst bei echtem LIVE. **`%1$s` in `connect_error_port`** = Host:Port (z. B. „localhost:4711").
> Fehlerursachen typisiert vom Backend (Nahtstelle S-2) — **Client rät sie nicht**. `connect_error_never_online` = H5/Q6.

## a11y
| Key | DE | EN |
|---|---|---|
| `a11y_connect_presence` | Presence laut Registry: %1$s | Registry presence: %1$s |
| `a11y_connect_mode_remote_disabled` | Remote-Modus — kommt bald, noch nicht verfügbar. | Remote mode — coming soon, not available yet. |

## Reuse (bestehende Keys/Muster — NICHT neu anlegen; verifiziert @ `a2f66ae8`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `settings_apikey_masked` / `settings_apikey_placeholder` | CYP-D3 (SettingsPanel) | **Muster** für `connect_creds_masked`/`_placeholder` (identisches maskiertes-Feld-Verhalten; Server maskiert) |
| `a11y_settings_apikey_input` / `a11y_settings_apikey_reveal` | CYP-D3 | a11y des Credential-Felds/Reveal-Toggles im Erststart-Screen **wiederverwenden** (kein neuer a11y-Key nötig) |
| `workspace_operator_only` | CYP-99/geteilt | Operator-Gate-Hinweis, wo Credential-Setzen operator-gated ist (Muster, `TonedHint(GATED)`) |
| `auth_*` (Login/Register/OIDC) | CYP-176 (`AuthGate`) | **kompletter** Login/Register-Screen in Seq A1/B1 — kein Neubau, nur `connect_register_intro` als Kontextzeile davor |
| `comm_status_connecting` / `comm_status_offline` | CYP (CommPanel) | **Idiom-Vorbild** (nicht Key-Reuse) für Connect/Offline-Töne |

## Gehalten / deferred (kein Phantom-Key in Phase 1)
- **`connect_register_devicecode`** — Slot für einen **sichtbaren** Device-Code (Remote-Hub, headless-startend).
  **Q7/R6 ratifiziert: Desktop = automatisch, kein getippter Code** → dieser Key wird in **Phase 1 NICHT angelegt/gerendert**.
  Vorgemerkt, damit die Screen-Naht bekannt ist; landet erst, wenn Remote-Registrierung gebaut wird.

## Self-Validation
- **35 neue Keys**, alle DE+EN befüllt, gleiche Argument-Anzahl je Sprache: 33 `connect_*` + 2 `a11y_connect_*` = **35**.
  (Aufschlüsselung: Seq-A 6 + Credentials 9 + Hub-Auswahl 6 + Modus 5 + Connect-Zustände 7 = 33 content; +2 a11y.)
- **Argument-Keys (genau ein `%1$s`, DE=EN):** `connect_creds_masked`, `connect_presence_offline`, `connect_error_port`,
  `a11y_connect_presence` = **4 Keys mit 1 Arg**; alle übrigen **0 Arg**. DE/EN-Argument-Anzahl identisch.
- **Kein interpolierter, content-tragender oder sensibler Klartext** — `%1$s` ist nur: maskierter Wert (`***<letzte4>`,
  server-erzeugt), relative Zeit, Host:Port, Presence-Wort. **Kein Secret/Token/Endpoint/Klartext-Key** in irgendeiner Copy.
- **Kollision:** **0 exakte Kollision** — der `connect_*`-Namespace ist greenfield (`grep name="connect[^o]"` @ `a2f66ae8`
  liefert **nur** die bestehende `connector_*`-Familie, die grep-disjunkt ist: „connector_" ist **kein** Substring von
  „connect_"). ⚠**Lesbarkeits-Notiz (kein Defekt):** `connect_*` steht **visuell nah** an der großen `connector_*`-Familie
  (CYP-119/137). Tooling-sicher (disjunkt), von PO als Area/Prefix `connect` benannt — **bewusst gehalten**; wenn mehr
  Distanz gewünscht, Alternativ-Prefix `hubconnect_*` (PO-Entscheid, nicht still ändern).
- **DE/EN-Parität:** jede Zeile beidseitig (kein Waisen-Key).
- **Honesty-Keys verankert:** `connect_creds_validated`=INFO nie-Grün · `connect_creds_unreachable`=WARN ≠
  `connect_creds_invalid`=Fehler · `connect_presence_*`=advisory nie-Grün/nie-„verbunden" · `connect_mode_remote_soon`=ehrlich
  deaktiviert · `connect_error_never_online`=H5. Jeder Key ist in `hub-connection-ux-spec.md` / `-tags.md` verankert.
