# Hub-Verbindungs-UX — testTag-Vertrag (Epic CYP-395, Phase 1 Lokal-Modus)

> Owner: UIUX-Designer · Epic CYP-395 · Stand 2026-07-11 · Status: **Spec-Closure (Q1–Q8 geruled)**; eingefroren als
> Vorlage für Devs `hubconnect_*`-Screens (S-L). Begleit-Spec: `hub-connection-ux-spec.md`.
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): **prefixless** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`,
> Segment-Werte `[A-Za-z0-9-]+` (camelCase, **keine Punkte** im Wert). **Geteilte API mit QA (CYP-7) — nicht still
> umbenennen, über den PO koordinieren.**
> **Neue Area `hubConnect`** (0 Kollision gg. bestehende `*Tags.kt` @ develop `a2f66ae8` — wie `auth` sie einführte).
> ✅**Distanz zur Area `connector` (ConnectorTags, CYP-119, Agenten-Connector) bewusst hergestellt** — PO-Ruling
> 2026-07-11: Area `hubConnect` gewählt, damit ein `grep connect` **nicht** zwei Domänen fängt (der Agenten-`connector`
> ist ein anderes, **sicherheitsrelevantes** Konzept) und „Hub" der definierende CYP-395-Begriff bleibt.
> Vorschlag: eigenes `HubConnectTags`-Objekt (analog `AuthTags`).

## Onboarding / Erststart (Seq A)
| Tag (Funktion/Konstante) | Wert | Zweck |
|---|---|---|
| `stepper` | `hubConnect.onboarding.stepper` | Host-Anchor des Onboarding-Steppers (Schritt-Fortschritt). |
| `prepare` | `hubConnect.prepare` | A0 Hub-Vorbereitungs-/Ladezustand. |
| `registerName` | `hubConnect.register.name` | A2 editierbares Hub-Namensfeld (Hostname vorbefüllt, Q1). |
| `registerSubmit` | `hubConnect.register.submit` | A2 Registrieren-Aktion. |
| `registerError` | `hubConnect.register.error` | A2 Registrierungsfehler (CP nicht erreichbar), errorContainer. |
| `readyToWorkspace` | `hubConnect.ready.toWorkspace` | A4 „Loslegen" → Hub-Workspace (Nav; folgt CYP-7-`to<Target>`-Konvention). |

> **Bedeutungs-Klarstellung `hubConnect.register.*`:** hier = einen **Hub** bei der Control Plane registrieren (Seq A2) —
> **nicht** User-Signup. Das ist ein **anderes** „register" als `auth.register.*` (CYP-176, End-User-Registrierung); die
> beiden „register"-Bedeutungen nicht verwechseln (für künftige Test-Autoren).

## Credentials (Seq A / §7) — Muster `settings.apiKey.*`
| Tag | Wert | Zweck |
|---|---|---|
| `credsInput` | `hubConnect.creds.input` | write-only Credential-Feld (PasswordVisualTransformation). |
| `credsReveal` | `hubConnect.creds.reveal` | Reveal-Toggle (Text-Label, kein Emoji) — entmaskiert nur die aktuelle Eingabe. |
| `credsMasked` | `hubConnect.creds.masked` | read-only maskierte Statuszeile `***<letzte4>` (server-maskiert). |
| `credsValidating` | `hubConnect.creds.validating` | Validierungs-Zwischenzustand (neutral). |
| `credsValidated` | `hubConnect.creds.validated` | **INFO** „gültig — hinterlegt" (nie Erfolgs-Grün). |
| `credsInvalid` | `hubConnect.creds.invalid` | **Fehler** „Key ungültig" (errorContainer). |
| `credsUnreachable` | `hubConnect.creds.unreachable` | **WARN** „nicht geprüft (Anthropic down)" — distinkt von `credsInvalid` (H3). |

> **a11y:** Credential-Feld/Reveal tragen die bestehenden `a11y_settings_apikey_input` / `a11y_settings_apikey_reveal`
> (Reuse, kein neuer a11y-Key). **Fail-closed:** `credsMasked` rendert **nie** Klartext, nur `***<letzte4>`.

## Hub-Auswahl (Seq B)
| Tag (Funktion) | Wert | Zweck |
|---|---|---|
| `hubsList` | `hubConnect.hubs.list` | Container der Hub-Liste. |
| `hubRow(hubId)` | `hubConnect.hubs.row.<hubId>` | eine Hub-Zeile (Name + Metadaten + Presence). |
| `hubPresence(hubId)` | `hubConnect.hubs.row.<hubId>.presence` | **Registry-Presence** der Zeile (advisory, H1) — trägt Punkt **+ Label**, nie `tertiary`-Grün, nie als „verbunden" lesbar; a11y `a11y_hubconnect_presence`. |
| `hubsEmpty` | `hubConnect.hubs.empty` | Leerer Zustand → primäre Aktion „Hub registrieren". |
| `hubsError` | `hubConnect.hubs.error` | `GET /hubs` fehlgeschlagen (CP nicht erreichbar), errorContainer (H5). |

## Modus-Wahl (Seq B / §5)
| Tag | Wert | Zweck |
|---|---|---|
| `modeLocal` | `hubConnect.mode.local` | Lokal-Option (aktiv, Default-Fokus). |
| `modeRemote` | `hubConnect.mode.remote` | **Remote-Option — non-interaktiv/disabled** („kommt bald", H2/Q3); nicht vorausgewählt; a11y `a11y_hubconnect_mode_remote_disabled`. |
| `modeRemoteSoon` | `hubConnect.mode.remote.soon` | „kommt bald"-Marker der Remote-Option (assertierbares distinktes Element; `hubconnect_mode_remote_soon`). |
| `modeConnect` | `hubConnect.mode.connect` | „Verbinden" → Lokal-Connect (§6). |

## Lokal-Connect-Zustände (§6) — Idiom `ConnectionStatus`
| Tag (Funktion) | Wert | Zweck |
|---|---|---|
| `stateAttempting` | `hubConnect.state.attempting` | Verbindungsversuch (neutral `onSurfaceVariant`, nie grün). |
| `stateHandshake` | `hubConnect.state.handshake` | Handshake (neutral). |
| `stateConnected` | `hubConnect.state.connected` | **LIVE** `●`+`primary` — erscheint **nie** vor echtem LIVE. |
| `stateError(cause)` | `hubConnect.state.error.<cause>` | Connect-Fehler mit **typisierter** Ursache. `<cause>` ∈ `hubOffline` / `portUnreachable` / `handshakeFailed` / `neverOnline` (vom Backend, Nahtstelle S-2 — **Client rät nicht**). errorContainer. |

## Fail-closed-Anker (für §-QA)
- `hubConnect.mode.remote` **existiert**, ist aber **non-interaktiv** (kein Klick-Durchgriff) → Remote ehrlich deaktiviert.
- `hubConnect.state.connected` erscheint **nie** vor echtem LIVE; `hubConnect.hubs.row.<id>.presence` (Registry) ist **getrennt**
  von `hubConnect.state.*` (meine Verbindung) → zwei Wahrheiten nie vermischt (H1).
- `hubConnect.creds.masked` rendert **nie** Klartext; `hubConnect.creds.unreachable` ≠ `hubConnect.creds.invalid` (WARN vs Fehler).
- Presence-Tag trägt **kein** Erfolgs-Grün; alle Zustände Form+Label (WCAG 1.4.1).

## Reuse (bestehende Tags/Areas — NICHT neu anlegen; verifiziert @ `a2f66ae8`)
| Reuse-Tag/Area | Quelle | Rolle hier |
|---|---|---|
| Area `auth` (`AuthTags` — `auth.login.*`/`auth.register.*`/`auth.login.github`) | CYP-176 | **kompletter** Login/Register/OIDC-Screen in Seq A1/B1 — Hub-Registrierung setzt darauf auf, kein neuer Login-Tag |
| Area `settings` (`SettingsTags` — `settings.apiKey.input/reveal/masked`) | CYP-D3 | **Muster/Vorbild** für `hubConnect.creds.*` (identisches maskiertes-Feld-Verhalten; neue Render-Stelle ⇒ eigene `hubConnect.creds.*`-Tags, kein Wert-Reuse) |

## Gehalten / deferred (kein Phantom-Tag in Phase 1)
- **`hubConnect.register.devicecode`** — Slot für einen **sichtbaren** Device-Code (Remote-Hub). **Q7/R6: Desktop
  automatisch** → in Phase 1 **nicht** angelegt/gerendert; vorgemerkt für die spätere Remote-Registrierung.

## Self-Validation
- **26 neue Tags** in Area `hubConnect` (7 Onboarding/Ready inkl. `ready.toWorkspace` + 7 Creds + 5 Hub-Auswahl inkl.
  `presence` + 4 Modus inkl. `mode.remote.soon` + 4 Connect-Zustände inkl. `stateError(cause)`). Instanz-scoped:
  `hubRow`/`hubPresence` embedden `<hubId>`; `stateError` trägt Qualifier `<cause>`.
- **0 Kollision:** Area `hubConnect` ist neu und **bewusst distanziert** von Area `connector` (ConnectorTags) — Distanz-Notiz oben (PO-Ruling 2026-07-11).
- **Geteilte API mit QA (CYP-7):** Area + Werte über den PO mit dem Tester abstimmen, bevor Dev sie fest verdrahtet
  (frozen-Contract-Konvention wie `AuthTags`).
- Jeder Tag ist in `hub-connection-ux-spec.md` (§9) verankert und trägt einen Copy-/a11y-Key aus `hub-connection-keys.md`.
