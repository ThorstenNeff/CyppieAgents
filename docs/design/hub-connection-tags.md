# Hub-Verbindungs-UX — testTag-Vertrag (Epic CYP-395, Phase 1 Lokal-Modus)

> Owner: UIUX-Designer · Epic CYP-395 · Stand 2026-07-11 · Status: **Spec-Closure (Q1–Q8 geruled)**; eingefroren als
> Vorlage für Devs `connect_*`-Screens (S-L). Begleit-Spec: `hub-connection-ux-spec.md`.
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): **prefixless** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`,
> Segment-Werte `[A-Za-z0-9-]+` (camelCase, **keine Punkte** im Wert). **Geteilte API mit QA (CYP-7) — nicht still
> umbenennen, über den PO koordinieren.**
> **Neue Area `connect`** (0 Kollision gg. bestehende `*Tags.kt` @ develop `a2f66ae8` — wie `auth` sie einführte).
> ⚠**Lesbarkeits-Notiz:** Area `connect` steht **nah** an der bestehenden Area `connector` (ConnectorTags, CYP-119) —
> tooling-disjunkt, vom PO als `connect` benannt; bewusst gehalten (Alt: `hubConnect` = PO-Entscheid, nicht still ändern).
> Vorschlag: eigenes `ConnectTags`-Objekt (analog `AuthTags`).

## Onboarding / Erststart (Seq A)
| Tag (Funktion/Konstante) | Wert | Zweck |
|---|---|---|
| `stepper` | `connect.onboarding.stepper` | Host-Anchor des Onboarding-Steppers (Schritt-Fortschritt). |
| `prepare` | `connect.prepare` | A0 Hub-Vorbereitungs-/Ladezustand. |
| `registerName` | `connect.register.name` | A2 editierbares Hub-Namensfeld (Hostname vorbefüllt, Q1). |
| `registerSubmit` | `connect.register.submit` | A2 Registrieren-Aktion. |
| `registerError` | `connect.register.error` | A2 Registrierungsfehler (CP nicht erreichbar), errorContainer. |
| `readyEnter` | `connect.ready.enter` | A4 „Loslegen" → Hub-Workspace. |

## Credentials (Seq A / §7) — Muster `settings.apiKey.*`
| Tag | Wert | Zweck |
|---|---|---|
| `credsInput` | `connect.creds.input` | write-only Credential-Feld (PasswordVisualTransformation). |
| `credsReveal` | `connect.creds.reveal` | Reveal-Toggle (Text-Label, kein Emoji) — entmaskiert nur die aktuelle Eingabe. |
| `credsMasked` | `connect.creds.masked` | read-only maskierte Statuszeile `***<letzte4>` (server-maskiert). |
| `credsValidating` | `connect.creds.validating` | Validierungs-Zwischenzustand (neutral). |
| `credsValidated` | `connect.creds.validated` | **INFO** „gültig — hinterlegt" (nie Erfolgs-Grün). |
| `credsInvalid` | `connect.creds.invalid` | **Fehler** „Key ungültig" (errorContainer). |
| `credsUnreachable` | `connect.creds.unreachable` | **WARN** „nicht geprüft (Anthropic down)" — distinkt von `credsInvalid` (H3). |

> **a11y:** Credential-Feld/Reveal tragen die bestehenden `a11y_settings_apikey_input` / `a11y_settings_apikey_reveal`
> (Reuse, kein neuer a11y-Key). **Fail-closed:** `credsMasked` rendert **nie** Klartext, nur `***<letzte4>`.

## Hub-Auswahl (Seq B)
| Tag (Funktion) | Wert | Zweck |
|---|---|---|
| `hubsList` | `connect.hubs.list` | Container der Hub-Liste. |
| `hubRow(hubId)` | `connect.hubs.row.<hubId>` | eine Hub-Zeile (Name + Metadaten + Presence). |
| `hubPresence(hubId)` | `connect.hubs.row.<hubId>.presence` | **Registry-Presence** der Zeile (advisory, H1) — trägt Punkt **+ Label**, nie `tertiary`-Grün, nie als „verbunden" lesbar; a11y `a11y_connect_presence`. |
| `hubsEmpty` | `connect.hubs.empty` | Leerer Zustand → primäre Aktion „Hub registrieren". |
| `hubsError` | `connect.hubs.error` | `GET /hubs` fehlgeschlagen (CP nicht erreichbar), errorContainer (H5). |

## Modus-Wahl (Seq B / §5)
| Tag | Wert | Zweck |
|---|---|---|
| `modeLocal` | `connect.mode.local` | Lokal-Option (aktiv, Default-Fokus). |
| `modeRemote` | `connect.mode.remote` | **Remote-Option — non-interaktiv/disabled** („kommt bald", H2/Q3); nicht vorausgewählt; a11y `a11y_connect_mode_remote_disabled`. |
| `modeConnect` | `connect.mode.connect` | „Verbinden" → Lokal-Connect (§6). |

## Lokal-Connect-Zustände (§6) — Idiom `ConnectionStatus`
| Tag (Funktion) | Wert | Zweck |
|---|---|---|
| `stateAttempting` | `connect.state.attempting` | Verbindungsversuch (neutral `onSurfaceVariant`, nie grün). |
| `stateHandshake` | `connect.state.handshake` | Handshake (neutral). |
| `stateConnected` | `connect.state.connected` | **LIVE** `●`+`primary` — erscheint **nie** vor echtem LIVE. |
| `stateError(cause)` | `connect.state.error.<cause>` | Connect-Fehler mit **typisierter** Ursache. `<cause>` ∈ `hubOffline` / `portUnreachable` / `handshakeFailed` / `neverOnline` (vom Backend, Nahtstelle S-2 — **Client rät nicht**). errorContainer. |

## Fail-closed-Anker (für §-QA)
- `connect.mode.remote` **existiert**, ist aber **non-interaktiv** (kein Klick-Durchgriff) → Remote ehrlich deaktiviert.
- `connect.state.connected` erscheint **nie** vor echtem LIVE; `connect.hubs.row.<id>.presence` (Registry) ist **getrennt**
  von `connect.state.*` (meine Verbindung) → zwei Wahrheiten nie vermischt (H1).
- `connect.creds.masked` rendert **nie** Klartext; `connect.creds.unreachable` ≠ `connect.creds.invalid` (WARN vs Fehler).
- Presence-Tag trägt **kein** Erfolgs-Grün; alle Zustände Form+Label (WCAG 1.4.1).

## Reuse (bestehende Tags/Areas — NICHT neu anlegen; verifiziert @ `a2f66ae8`)
| Reuse-Tag/Area | Quelle | Rolle hier |
|---|---|---|
| Area `auth` (`AuthTags` — `auth.login.*`/`auth.register.*`/`auth.login.github`) | CYP-176 | **kompletter** Login/Register/OIDC-Screen in Seq A1/B1 — Hub-Registrierung setzt darauf auf, kein neuer Login-Tag |
| Area `settings` (`SettingsTags` — `settings.apiKey.input/reveal/masked`) | CYP-D3 | **Muster/Vorbild** für `connect.creds.*` (identisches maskiertes-Feld-Verhalten; neue Render-Stelle ⇒ eigene `connect.creds.*`-Tags, kein Wert-Reuse) |

## Gehalten / deferred (kein Phantom-Tag in Phase 1)
- **`connect.register.devicecode`** — Slot für einen **sichtbaren** Device-Code (Remote-Hub). **Q7/R6: Desktop
  automatisch** → in Phase 1 **nicht** angelegt/gerendert; vorgemerkt für die spätere Remote-Registrierung.

## Self-Validation
- **25 neue Tags** in Area `connect` (7 Onboarding/Ready + 7 Creds + 5 Hub-Auswahl inkl. `presence` + 3 Modus +
  4 Connect-Zustände inkl. `stateError(cause)`). Instanz-scoped: `hubRow`/`hubPresence` embedden `<hubId>`;
  `stateError` trägt Qualifier `<cause>`.
- **0 Kollision:** Area `connect` ist neu; `grep`-disjunkt von Area `connector` (ConnectorTags). Lesbarkeits-Notiz oben.
- **Geteilte API mit QA (CYP-7):** Area + Werte über den PO mit dem Tester abstimmen, bevor Dev sie fest verdrahtet
  (frozen-Contract-Konvention wie `AuthTags`).
- Jeder Tag ist in `hub-connection-ux-spec.md` (§9) verankert und trägt einen Copy-/a11y-Key aus `hub-connection-keys.md`.
