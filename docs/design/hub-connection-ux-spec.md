# Hub-Verbindungs- & Modus-UX — Design-Spec (Epic CYP-395, Phase 1: Lokal-Modus)

> Status: **Spec-Closure — ratifiziert 2026-07-11 (Q1–Q8 geruled)** · docs-only, kein Bau · Owner: UX/UI · Begleitkonzept: `../../13-cyppie-hub-architektur.md`
> Eingefrorene Companion-Files (Haus-Konvention, Vorlage für Devs `hubconnect_*`-Screens = S-L): `hub-connection-keys.md` · `hub-connection-tags.md` · `hub-connection-tokens.json`.
> Client-Architektur parallel beim Developer — **Nahtstellen über den PO** (siehe §12).
> Reihenfolge dieses Dokuments: **Screens/Zustände/Copy** zuerst, dann **ratifizierte Entscheidungen** (§11) und **Nahtstellen** (§12).

Diese Spec deckt **meinen Strang** ab: Erststart-Flow (Konzept-Sequenz A), Login→Hub-Auswahl→Modus (Sequenz B),
Lokal-Connect-Zustände und die maskierte Credential-Eingabe. Sie ist gegen den **echten heutigen Code** gegroundet
(Reuse-Karte §3) und maritim + Material 3.

**Phase-1-Rahmung (verbindlich):** Es wird der **Lokal-Modus** gebaut (Frontend am selben Rechner/Netz wie der Hub).
**Remote-Modus ist sichtbar, aber deaktiviert** („kommt bald"). Alles Remote-Spezifische (E2E/Noise, Control-Plane-Relay)
ist in dieser Spec nur so weit sichtbar, wie die UI es **ehrlich als „noch nicht verfügbar"** ausweisen muss.

---

## 1. Kern-Ehrlichkeit (die tragenden Design-Entscheidungen)

Diese sechs Punkte sind die Disclosure-Wirbelsäule der Spec; die Screens (§4–§7) setzen sie um, die Teeth (§13)
prüfen sie.

- **H1 — Registry-Presence ist *advisory*, kein Verbindungs-Versprechen.** Die Hub-Liste liefert `online/offline/lastSeen`
  aus der **Behauptung der Control Plane** (Presence via Connector-WS). Sie kann veraltet sein und sagt im **Lokal-Modus
  gar nichts** darüber, ob *dieses* Frontend den Hub im LAN erreicht. Deshalb werden **zwei getrennte Wahrheiten** nie
  vermischt: (a) **Registry-Presence** (CP sagt online/offline — Hinweis, gedämpft dargestellt) auf der Hub-Zeile;
  (b) **meine Verbindung** (CONNECTING/LIVE/DISCONNECTED — Bodenwahrheit) während/nach dem Connect. Presence darf sich
  nie als etablierte Sitzung ausgeben.
- **H2 — Remote ist ehrlich deaktiviert.** In Phase 1 ist Remote **nicht** klickbar (kein Fake-Klick der dann
  fehlschlägt), mit klarer „kommt bald"-Auszeichnung; Lokal ist die einzige handlungsfähige Option und **nicht**
  vorausgewählt auf Remote.
- **H3 — Credentials werden nie zurückgerendert; *hinterlegt* ≠ *validiert*.** Exakte Wiederverwendung des bestehenden
  API-Key-Musters (Client sieht nur `***<letzte4>`, nie Klartext, §3.1). Zwei getrennte Zustände: **hinterlegt** (im
  Keystore) vs **validiert** (Anthropic-Testcall bestanden). „Validierung nicht erreichbar" (Anthropic down) ist ein
  **WARN**, nicht ein **Fehler** — anders als „Key ungültig".
- **H4 — Zero-Knowledge-Disclosure, aber nur so weit garantiert.** Die Architektur garantiert: Credentials gehen in den
  **lokalen Keystore**, **nie** an die Control Plane (Konzept Seq A). Diese *ehrliche* Zusicherung als Micro-Copy ist
  wertvoll — aber **exakt auf Credentials** begrenzt, keine pauschale „wir sehen gar nichts"-Aussage (das Remote-E2E-Argument
  ist Phase-2 und nicht mein Scope).
- **H5 — Lokal-Offline hat eine Erst-Online-Vorbedingung.** Der Hub verifiziert das JWT offline über den **gecachten**
  CP-Public-Key — aber erst **nach** einer erfolgreichen Online-Anmeldung. Die Hub-Liste (`GET /hubs`) braucht die CP
  erreichbar. Ist die CP bei der Anmeldung nicht erreichbar, ist das ein **ehrlicher Fehlerzustand** („Anmeldung braucht
  einmalig Internet"), kein stiller Hänger.
- **H6 — Keystore-Sicherheit variiert je Plattform.** Keine pauschale „Secure-Enclave"-Zusage überall. Wenn überhaupt,
  plattformbewusste, zurückhaltende Copy (§7.4).

---

## 2. Zielplattform & Scope

- **Primär Desktop** (JVM). Web/Android/iOS teilen die `commonMain`-Screens (KMP), plattformspezifisch bleiben nur
  Keystore-Anbindung und ggf. der OS-Login-Umweg. Diese Spec beschreibt die **plattformneutrale** UX; Keystore-Details
  sind Nahtstelle (§12).
- **Enthalten:** Erststart-Registrierung (Seq A), Login→Hub-Auswahl→Modus (Seq B), Lokal-Connect-Zustände & Fehlerfälle,
  maskierte Credential-Eingabe.
- **Nicht enthalten (Phase-2/andere Stränge):** Remote-Connect-Flow (nur „kommt bald"-Zustand), E2E-Handshake-UX,
  Projekt-/Team-Umschalten, Ressourcen-/Kapazitätswarnungen des Hubs.

---

## 3. Reuse-Karte (gegen echten Code gegroundet)

**Leitsatz meiner Rolle:** neue Screens **erben** bestehende Muster statt divergente Einmal-Teile zu erfinden.

### 3.1 Maskierte Credential-Eingabe — `ApiKeySection`
`app/shared/src/commonMain/kotlin/com/tneff/cyppieagents/settings/SettingsPanel.kt:145-223`.
- Read-only maskierte Statuszeile `***<letzte4>` aus **Server**-`Secrets.mask()` (`server/.../boot/Secrets.kt:49-52`);
  Client bekommt nur `ApiKeyState(set, masked)` (`ConfigRepository.kt:37`) — **kein Klartext auf der Leitung**.
- Write-only-Feld: `OutlinedTextField` + `PasswordVisualTransformation`, Reveal-Toggle als **Text-Label** (kein Emoji,
  CYP-99/54), Reveal entmaskiert **nur die aktuelle Eingabe**.
- Operator-Gate über `editable` (`SettingsViewModel.kt:22,49,102,129`; `isOperatorAccess`), fail-closed.
- „Gespeichert ≠ aktiv"-Amber-Hinweis via `TonedHint(HintTone.EFFECT_DEFERRED)`.
- Primitive schon extrahiert: `AuthPasswordField`/`AuthEmailField`/`AuthFormCard`/`AuthTitle`
  (`auth/AuthComponents.kt:96-187`), Karten-Breite `AUTH_FORM_MAX_WIDTH = 400.dp`.

### 3.2 Auth/Login — existiert vollständig (CYP-176, Kratos)
`auth/AuthGate.kt:95-118` → Login/Register/Forgot/Reset/VerifyPending/VerifySuccess, inkl. **GitHub-OIDC-Button**,
E-Mail-Verify-Gate, Reset per 6-stelligem Code. Session = Kratos-Cookie / `X-Session-Token` (`SharedHttpClient.kt`),
**kein** getipptes Token. → **Seq A/B „Login" ist WIEDERVERWENDUNG**, kein Neubau; net-new ist nur die
**Hub-Registrierung** (post-Login) und die **Hub-Auswahl**.

### 3.3 Verbindungs-Status-Idiom — existiert
`ConnectionStatus { CONNECTING, LIVE, DISCONNECTED }` (`eventlog/EventReducer.kt:6`, Spiegel in `comm/CommReducer.kt:13`).
- **LIVE** = `●` + `primary` (`EventTailPanel.kt:143-148`).
- **Offline** = Text-Banner auf `errorContainer`/`onErrorContainer` (`EventTailPanel.kt:183-191`, `CommPanel.kt:311-330`).
- **Connecting/Reconnecting** = **neutral `onSurfaceVariant`**, Form+Label, **nie** grün/`tertiary`
  (`AgentWindow.kt:611-629` `ReconnectingChip`, testTag `agent.<id>.reconnecting`).
→ Die Lokal-Connect-Zustände (§6) **erben dieses Idiom 1:1**.

### 3.4 Maritime-Theme-Tokens — `ui/MaritimeTheme.kt`
primary `#0A5AA0`/`#6FBEEA` · secondary `#0F5B88`/`#93CCEA` · **tertiary `#0B7E9C`/`#40D6A0` = BRAND-Akzent, NIE Status**
(KDoc-Invariante `:42-48`) · error rot · onSurface/onSurfaceVariant für Text/Neutral · WARN = Amber über
`warnContainer(dark)` `#4A3A10`/`#FFC857` bzw. `railColor` (`eventlog/EventVisuals.kt:74-116`). Severity trägt zusätzlich
**Glyph** (`⚠▲ⓘ·`), nie Farbe allein.

### 3.5 testTag-Konvention & i18n
Prefixlos, gepunktet `<area>[.<scopeId>].<element>[.<qualifier>]` (`docs/TEST-CONTRACT.md`, `AuthTags.kt:5`). Neue
**Area** nötig — Vorschlag `hubConnect` (0 Kollision, wie `auth` sie einführte); Rename/Frozen-Contract mit QA (CYP-7)
abstimmen. i18n: DE-Default `values/strings.xml`, EN `values-en/strings.xml`, flache `snake_case`-Keys je Feature,
a11y-Strings `a11y_*`. Neue Key-Familie **`hubconnect_*`**, maskiertes Feld spiegelt `settings_apikey_*`.

---

## 4. Sequenz A — Erststart nach Installation

Fünf Schritte; jeder ist ein Zustand eines gemeinsamen **Onboarding-Steppers** (zentrierte `AuthFormCard`, 400dp,
maritim). Fortschritt sichtbar (z. B. „Schritt 2 von 4"), damit der Nutzer weiß, wie viel bleibt.

### A0 — Hub-Vorbereitung (kurz, systemseitig)
Beim Erststart erzeugt der Hub sein Ed25519-Keypair (privat bleibt lokal). UI: knapper Lade-/Willkommens-Zustand,
**kein** Fortschrittsbalken der etwas verspricht. Copy `hubconnect_prepare_title` „Cyppie wird vorbereitet…". Sofort weiter,
sobald der Hub bereit ist. Reuse `LoadingScreen` (`auth/AuthGate.kt`).

### A1 — „Melde dich an, um diesen Hub zu registrieren"
Route in den **bestehenden** `AuthGate` (Login **oder** Register + OIDC). **Kontext-Copy oben** macht klar, *warum* die
Anmeldung: `hubconnect_register_intro` „Melde dich an, um diesen Hub deinem Konto zuzuordnen." Kein Neubau der Auth-Form;
nur die Intro-Zeile ist neu. Nach erfolgreichem Login (verifizierte `UserTier`) → A2.

### A2 — Hub-Registrierung
Der Hub tauscht Device-Code + Public-Key gegen die CP; die CP registriert `{hubId, ownerId, pubKey, name, defaultPort}`.
- **Zustände:** `registering` („Hub wird registriert…", neutral `onSurfaceVariant`) → `registered` (kurzes „Hub registriert").
- **Hub-Name:** die CP verlangt einen `name`. **Offene Entscheidung Q1** (§11): Auto-Name (Hostname) vs. Nutzereingabe.
  Default-Vorschlag der Spec: **vorbefülltes, editierbares Namensfeld** (Hostname als Default), damit Mehr-Hub-Listen
  später unterscheidbar sind. Feld `hubConnect.register.name`, Copy `hubconnect_register_name_label` „Name dieses Hubs".
- **Device-Code (ratifiziert R6/Q7):** Bei Desktop (Frontend+Hub co-lokal) läuft der Austausch **automatisch** —
  **kein getippter Code**, kein sichtbarer Device-Code-Zustand in Phase 1. Die **Screen-Naht bleibt offen** für einen
  späteren sichtbaren Code (Remote-Hub, headless-startend): optionaler `hubconnect_register_devicecode`-Zustand ist
  vorgesehen, aber **nicht gebaut/gerendert** in Phase 1.
- **Fehler:** CP nicht erreichbar → `hubconnect_register_error_offline` „Registrierung braucht Internet. Erneut versuchen."
  (errorContainer, Retry). Bereits registriert (Re-Run) → idempotent weiter zu A3/Workspace.

### A3 — „Hinterlege deine Anthropic-Credentials"
**Wiederverwendung §3.1** (maskiertes Feld), aber als Erststart-Variante. Ablauf:
1. **Eingabe** — write-only Feld, `PasswordVisualTransformation`, Text-Label-Reveal. Copy `hubconnect_creds_title`
   „Hinterlege deine Anthropic-Credentials", Feld-Placeholder `hubconnect_creds_placeholder` „sk-ant-…".
2. **Zero-Knowledge-Zeile (H4)** — dezente Micro-Copy unter dem Feld: `hubconnect_creds_privacy` „Bleibt auf diesem Gerät
   (Keystore) — geht nie an cyppie-agents.com." (onSurfaceVariant, informativ, **kein** Marketing-Grün).
3. **Validierung** — nach „Speichern & prüfen" ein Testcall gegen Anthropic:
   - `validating` „Credentials werden geprüft…" (neutral).
   - `validated` „Credentials gültig — hinterlegt." (INFO-Ton, **kein** Erfolgs-Grün; `secondary`/onSurface + Glyph `ⓘ`).
   - `invalid` „Key ungültig — bitte prüfen." (**Fehler**, errorContainer).
   - `unreachable` „Konnte nicht geprüft werden (Anthropic nicht erreichbar) — gespeichert, später erneut prüfen."
     (**WARN-Amber**, `warnContainer`, distinkt von `invalid`). → **H3**.
4. **Maskierte Bestätigung** danach: Statuszeile `***<letzte4>` (`hubconnect_creds_masked` „Hinterlegt: %1$s"), Feld leert.

### A4 — „Hub bereit"
Erfolgs-Abschluss → Übergang in den Hub-Workspace. Copy `hubconnect_ready_title` „Hub bereit", Button
`hubconnect_ready_enter` „Loslegen". **Gating (Q5, §11):** Übergang setzt **mindestens hinterlegte** Credentials voraus;
bei `unreachable`-Validierung darf man mit **WARN** fortfahren (Key kann gültig sein, Anthropic transient down); bei
`invalid` blockiert der Screen bis Korrektur.

---

## 5. Sequenz B — Login → Hub-Auswahl → Modus-Wahl

### B1 — Login
Bestehender `AuthGate` (§3.2). Nach verifizierter Session → B2. Erst-Login-ohne-Internet → ehrlicher Fehler (**H5**).

### B2 — Hub-Auswahl (Liste)
`GET /hubs` liefert `[{hubId, name, online, defaultPort, lastSeen, metadata}]`. Darstellung als Liste zentrierter Zeilen
(maritim). **Pro Hub-Zeile:**
- **Name** (`onSurface`, prominent), sekundär `hubId`/Port-Metadaten gedämpft (`onSurfaceVariant`).
- **Registry-Presence (H1, advisory, gedämpft):**
  - `online` → gefüllter Punkt **neutral** (nicht grün!) + Label `hubconnect_presence_online` „online". Ton: `onSurface`
    zurückhaltend; bewusst **kein** `tertiary`-Grün (Brand-Akzent, würde Erfolg/Verbindung überzeichnen).
  - `offline` → hohler/gedämpfter Punkt + `hubconnect_presence_offline` „offline · zuletzt gesehen %1$s" mit **relativer**
    Zeit (`vor 3 Min`, `gestern`), `onSurfaceVariant`.
  - Presence-Punkt **immer mit Label** (1.4.1), nie Farbe allein; a11y `a11y_hubconnect_presence` „Presence laut Registry:
    %1$s".
- **Auswahl** → öffnet B3 (Modus) für diese Zeile. Ein `online`-Hub ist **nicht** garantiert lokal erreichbar — die
  Wahrheit ist der Connect (§6).
- **Zeilen-Zustand bei bereits laufendem Connect:** die Zeile zeigt **meine Verbindung** (CONNECTING/LIVE) getrennt von
  der Presence (siehe §6), damit H1 sichtbar bleibt.

**Leerer Zustand:** keine Hubs für dieses Konto → Hinweis + primäre Aktion „Hub registrieren" (→ Seq A ab A2). Copy
`hubconnect_hubs_empty` „Noch kein Hub registriert." + `hubconnect_hubs_register` „Hub registrieren".
**Fehler:** `GET /hubs` scheitert (CP nicht erreichbar) → errorContainer-Banner `hubconnect_hubs_error` „Hub-Liste nicht
erreichbar. Erneut versuchen." (**H5**). **Q6 ratifiziert = DEFERRED:** **kein** Offline-Lokal-Connect über eine gecachte
Hub-Liste in Phase 1. **H5 bleibt verbindlich** — die erste Anmeldung/Hub-Liste braucht die CP erreichbar; CP-nicht-erreichbar
ist ein **ehrlicher Fehlerzustand**, kein stiller Hänger und kein aus dem Cache vorgetäuschter „online"-Zustand.

### B3 — Modus-Wahl (Lokal | Remote)
Segmentierte Auswahl **nach** der Hub-Wahl (ein Hub kann perspektivisch über beide Wege erreichbar sein):
- **Lokal** — aktiv, Default-Fokus. `hubconnect_mode_local` „Lokal", Subtext `hubconnect_mode_local_sub` „Direkt im selben
  Netz — privat und schnell."
- **Remote** — **deaktiviert (H2)**, nicht klickbar, Reuse `TonedHint(GATED)`-Idiom. `hubconnect_mode_remote` „Remote",
  Badge/Subtext `hubconnect_mode_remote_soon` „kommt bald". **Nicht** vorausgewählt, **kein** Fake-Klick.
- Primäre Aktion `hubconnect_mode_connect` „Verbinden" → §6 (nur Lokal handlungsfähig in Phase 1).
- **Q3 (§11):** Platzierung (eigener Schritt vs. Toggle in der Hub-Zeile) — Spec-Default = eigener kompakter Schritt.

---

## 6. Lokal-Connect-Zustände (Bodenwahrheit — erbt §3.3)

Ein einziger, ehrlicher Fortschritt vom Klick „Verbinden" bis zur Sitzung. **Erbt das Connecting-Idiom** (neutral
`onSurfaceVariant`, nie grün) und das LIVE/Offline-Idiom.

| Zustand | Bedeutung | Darstellung | Copy-Key |
|---|---|---|---|
| `attempting` | Verbindungsversuch `localhost:<port>` | neutral `onSurfaceVariant`, Spinner/`●`-neutral | `hubconnect_state_attempting` „Verbinde mit deinem Hub…" |
| `handshake` | JWT-Vorlage, Hub verifiziert gg. gecachten CP-Public-Key | neutral `onSurfaceVariant` | `hubconnect_state_handshake` „Sichere Verbindung wird aufgebaut…" |
| `connected` (LIVE) | Sitzung etabliert | LIVE-Idiom `●` + `primary` (§3.3) | `hubconnect_state_connected` „Verbunden" |
| Fehler ↓ | | errorContainer-Banner + **typisierte Ursache** + Retry | |
| `hub_offline` | Registry sagt offline / kein Prozess | errorContainer | `hubconnect_error_hub_offline` „Hub ist offline. Starte den Hub und versuche es erneut." |
| `port_unreachable` | Port/Host nicht erreichbar | errorContainer | `hubconnect_error_port` „Hub unter %1$s nicht erreichbar. Läuft er in diesem Netz?" |
| `handshake_failed` | JWT-Verify scheitert (Key-Mismatch) | errorContainer | `hubconnect_error_handshake` „Sichere Verbindung fehlgeschlagen. Melde dich neu an." |
| `never_online` | Nie online authentifiziert → kein gecachter CP-Key | errorContainer/WARN | `hubconnect_error_never_online` „Diese erste Anmeldung braucht einmal Internet." (**H5**) |

**Ehrlichkeits-Regel:** `attempting`/`handshake` dürfen **nie** so aussehen, als wäre schon verbunden (kein Grün, kein
LIVE-`●` vorzeitig). „Verbunden" erscheint **erst** bei tatsächlichem LIVE. Registry-`online` allein rechtfertigt kein
„Verbunden" (**H1**). **Nahtstelle S-2:** die typisierten Fehlerursachen + der `handshake`-Zwischenzustand sind neu
gegenüber dem heutigen `ConnectionStatus{CONNECTING,LIVE,DISCONNECTED}` — der Feed muss sie liefern (siehe §12).

---

## 7. Credential-Eingabe (Detail — Wiederverwendung + Ehrlichkeit)

### 7.1 Muster
Exakt `ApiKeySection` (§3.1): **Server maskiert**, Client rendert nie Klartext; write-only Feld; Text-Label-Reveal nur
für die aktuelle Eingabe.

### 7.2 Zustände (H3)
`unset` → `entering` → `validating` → { `validated` | `invalid` | `unreachable` } → maskierte Bestätigung.
- **`validated` ist INFO, kein Erfolgs-Grün** (konsistent zur Haus-Regel „grün nie Status").
- **`unreachable` ≠ `invalid`:** WARN-Amber vs. Fehler-Rot — der Nutzer muss „Key falsch" von „Anthropic gerade nicht
  erreichbar" unterscheiden können.

### 7.3 „hinterlegt" vs „validiert"
Zwei Wahrheiten getrennt beschriften: die maskierte Zeile sagt **hinterlegt** (`***<letzte4>`); ein separater,
zurückgezogener Zustand sagt **validiert** (letzter erfolgreicher Testcall). Nie „validiert" behaupten ohne echten
bestandenen Testcall.

### 7.4 Keystore-Disclosure (H6, ratifiziert Q8 = ja, zurückhaltend)
**Q8 ratifiziert:** die optionale Keystore-Zeile wird **mitgeliefert**, aber **zurückhaltend und plattformbewusst** —
höchstens **eine** Zeile („im Schlüsselbund dieses Geräts gesichert", Key `hubconnect_creds_keystore`, `onSurfaceVariant`),
**keine** pauschale Hardware-Sicherheitszusage (kein „Secure Enclave" überall). Formulierung neutral, kein Marketing.

---

## 8. Maritim + Material-3-Notizen

- **Formen/Layout:** zentrierte `AuthFormCard` (400dp) für Onboarding/Login/Register/Credentials; Hub-Liste als
  ruhige, breitere Liste. Reuse bestehender M3-Komponenten (`OutlinedTextField`, `TextButton`, `Card`).
- **Farb-Disziplin (kritisch):** **`tertiary`/`#40D6A0` nie für Status** (Presence, „verbunden", „gültig"). Presence
  neutral; LIVE = `primary` + `●`; Fehler = `error`/errorContainer; WARN = `warnContainer`-Amber; INFO = `secondary`.
- **Nie Farbe-allein (1.4.1):** Presence, Modus-Disabled, Connect-Zustände tragen immer **Form + Label** (+ a11y).
- **Dark/Light:** beide Schemata über `maritimeColorScheme(dark)`; keine hartkodierten Farben in der Spec-Umsetzung.

---

## 9. testTag-Kontrakt (Übersicht — maßgeblich: `hub-connection-tags.md`)

Neue Area `hubConnect` (mit QA/CYP-7 abstimmen). Diese Übersicht spiegelt den **eingefrorenen** `hub-connection-tags.md`:

```
hubConnect.onboarding.stepper            hubConnect.hubs.list
hubConnect.prepare                       hubConnect.hubs.row.<hubId>
hubConnect.register.name                 hubConnect.hubs.row.<hubId>.presence
hubConnect.register.submit               hubConnect.hubs.empty
hubConnect.register.error                hubConnect.hubs.error
hubConnect.creds.input                   hubConnect.mode.local
hubConnect.creds.reveal                  hubConnect.mode.remote            (disabled)
hubConnect.creds.masked                  hubConnect.mode.remote.soon      („kommt bald"-Marker)
hubConnect.creds.validating              hubConnect.mode.connect
hubConnect.creds.validated               hubConnect.state.attempting
hubConnect.creds.invalid                 hubConnect.state.handshake
hubConnect.creds.unreachable             hubConnect.state.connected
hubConnect.ready.toWorkspace             hubConnect.state.error.<cause>
```
**Fail-closed-Anker (für §-QA):** `hubConnect.mode.remote` existiert, ist aber non-interaktiv; `hubConnect.state.connected`
erscheint **nie** vor echtem LIVE; Presence-Tag trägt kein Erfolgs-Grün.

---

## 10. Copy (Übersicht — maßgeblich: `hub-connection-keys.md`)

Key-Familie `hubconnect_*` / `a11y_hubconnect_*`. Maskiertes Feld spiegelt `settings_apikey_*`. Diese Tabelle spiegelt den
**eingefrorenen** `hub-connection-keys.md`:

| Key | DE | EN |
|---|---|---|
| `hubconnect_prepare_title` | Cyppie wird vorbereitet… | Setting up Cyppie… |
| `hubconnect_register_intro` | Melde dich an, um diesen Hub deinem Konto zuzuordnen. | Sign in to link this hub to your account. |
| `hubconnect_register_name_label` | Name dieses Hubs | This hub's name |
| `hubconnect_register_error_offline` | Registrierung braucht Internet. Erneut versuchen. | Registration needs an internet connection. Try again. |
| `hubconnect_creds_title` | Hinterlege deine Anthropic-Credentials | Add your Anthropic credentials |
| `hubconnect_creds_placeholder` | sk-ant-… | sk-ant-… |
| `hubconnect_creds_privacy` | Bleibt auf diesem Gerät (Keystore) — geht nie an cyppie-agents.com. | Stays on this device (keystore) — never sent to cyppie-agents.com. |
| `hubconnect_creds_keystore` | Im Schlüsselbund dieses Geräts gesichert. | Secured in this device's keychain. |
| `hubconnect_creds_masked` | Hinterlegt: %1$s | Stored: %1$s |
| `hubconnect_creds_validating` | Credentials werden geprüft… | Checking credentials… |
| `hubconnect_creds_validated` | Credentials gültig — hinterlegt. | Credentials valid — stored. |
| `hubconnect_creds_invalid` | Key ungültig — bitte prüfen. | Key invalid — please check. |
| `hubconnect_creds_unreachable` | Konnte nicht geprüft werden (Anthropic nicht erreichbar) — gespeichert, später erneut prüfen. | Couldn't verify (Anthropic unreachable) — saved, check again later. |
| `hubconnect_ready_title` | Hub bereit | Hub ready |
| `hubconnect_ready_enter` | Loslegen | Get started |
| `hubconnect_hubs_title` | Wähle deinen Hub | Choose your hub |
| `hubconnect_hubs_empty` | Noch kein Hub registriert. | No hub registered yet. |
| `hubconnect_hubs_register` | Hub registrieren | Register a hub |
| `hubconnect_hubs_error` | Hub-Liste nicht erreichbar. Erneut versuchen. | Can't reach the hub list. Try again. |
| `hubconnect_presence_online` | online | online |
| `hubconnect_presence_offline` | offline · zuletzt gesehen %1$s | offline · last seen %1$s |
| `hubconnect_mode_local` | Lokal | Local |
| `hubconnect_mode_local_sub` | Direkt im selben Netz — privat und schnell. | Direct on the same network — private and fast. |
| `hubconnect_mode_remote` | Remote | Remote |
| `hubconnect_mode_remote_soon` | kommt bald | coming soon |
| `hubconnect_mode_connect` | Verbinden | Connect |
| `hubconnect_state_attempting` | Verbinde mit deinem Hub… | Connecting to your hub… |
| `hubconnect_state_handshake` | Sichere Verbindung wird aufgebaut… | Establishing a secure connection… |
| `hubconnect_state_connected` | Verbunden | Connected |
| `hubconnect_error_hub_offline` | Hub ist offline. Starte den Hub und versuche es erneut. | Hub is offline. Start the hub and try again. |
| `hubconnect_error_port` | Hub unter %1$s nicht erreichbar. Läuft er in diesem Netz? | Hub not reachable at %1$s. Is it running on this network? |
| `hubconnect_error_handshake` | Sichere Verbindung fehlgeschlagen. Melde dich neu an. | Secure connection failed. Please sign in again. |
| `hubconnect_error_never_online` | Diese erste Anmeldung braucht einmal Internet. | This first sign-in needs an internet connection once. |
| `a11y_hubconnect_presence` | Presence laut Registry: %1$s | Registry presence: %1$s |
| `a11y_hubconnect_mode_remote_disabled` | Remote-Modus — kommt bald, noch nicht verfügbar. | Remote mode — coming soon, not available yet. |

*(Relative-Zeit-Formatierung für `%1$s` in offline/lastSeen: Hausformat wie bei bestehenden Zeitstempeln —
Nahtstelle zur vorhandenen Zeitformatierung, konsistent halten.)*

---

## 11. Ratifizierte Entscheidungen (Q1–Q8, PO 2026-07-11)

Alle acht Punkte sind geruled → **Spec-Closure**. Keys/Tags/Tokens sind damit eingefroren (Companion-Files).

1. **Q1 — Hub-Benennung: editierbares Feld, Hostname vorbefüllt** (A2). Umbenennen später in Settings (Folge-Story,
   nicht Phase-1-Screen). Feld `hubConnect.register.name`, Key `hubconnect_register_name_label`.
2. **Q2 — Presence advisory/gedämpft, als Prinzip bestätigt:** Registry-`online` nie Erfolgs-Grün, nie als „verbunden"
   lesbar; die zwei Wahrheiten (H1) bleiben getrennt. Deckt sich mit Krypto-Reviewer-Befund F8.
3. **Q3 — Modus = eigener Schritt** (B3), **Remote sichtbar-deaktiviert** („kommt bald"), nicht vorausgewählt.
4. **Q4 — Erststart-Erkennung:** leere Hub-Liste nach Login → **Register-Flow**; nicht-leer → **Auswahl**. „Hub
   registrieren" ist auch der spätere „weiteren Hub hinzufügen"-Einstieg (Mehr-Hub).
5. **Q5 — Credential-Policy:** „Hub bereit" setzt **hinterlegt** voraus; `unreachable` → **mit WARN fortfahren**
   erlaubt; `invalid` → **blockieren** bis Korrektur.
6. **Q6 — DEFERRED:** **kein** Offline-Lokal-Connect über gecachte Hub-Liste in Phase 1. **H5 bleibt** — erste
   Anmeldung braucht einmal Internet, CP-nicht-erreichbar = ehrlicher Fehlerzustand (↔ Reviewer-F1 Offline-Auth).
7. **Q7 — ratifiziert R6: Device-Code automatisch** auf Desktop (kein getippter Code, kein sichtbarer Zustand in
   Phase 1). **Screen-Naht für sichtbaren Code offen gehalten** (Remote-Hub später) — Slot vorgesehen, nicht gebaut
   (↔ Reviewer-F7).
8. **Q8 — Disclosure-Copy: JA.** Zero-Knowledge-Zeile bei Credentials (`hubconnect_creds_privacy`) — **auf Credentials
   begrenzt**, keine pauschale „wir sehen nichts"-Aussage (↔ Reviewer-F4). **Optionale Keystore-Zeile: JA**, aber
   zurückhaltend/plattformbewusst (`hubconnect_creds_keystore`, keine Hardware-Zusage).

---

## 12. Nahtstellen zum Developer (über den PO)

Die Client-Architektur baut der Developer parallel — diese Contracts liefern die Wahrheiten, die die Screens ehrlich
darstellen:

- **S-1 — Hub-Listen-Contract:** `GET /hubs` → `[{hubId, name, online, defaultPort, lastSeen, metadata}]`. `lastSeen`
  als Timestamp (für relative Formatierung). Presence-Quelle = Connector-WS der CP. (Klärt Q6-Offline-Cache.)
- **S-2 — Lokal-Connect-Feed:** typisierter Fortschritt `attempting → handshake → connected(LIVE)` **plus** typisierte
  Fehlerursache (`HUB_OFFLINE`/`PORT_UNREACHABLE`/`HANDSHAKE_FAILED`/`NEVER_ONLINE`). Erweitert das heutige
  `ConnectionStatus{CONNECTING,LIVE,DISCONNECTED}` um `handshake` + Ursachen. **Client darf die Ursache nicht raten**
  (sonst gehedgte, unehrliche Fehlertexte).
- **S-3 — Credential-Validierungs-Outcome:** Tri-State `VALIDATED / INVALID / UNREACHABLE` (damit „Key falsch" ehrlich
  von „Anthropic down" trennbar, H3). Spiegelt/erweitert den bestehenden `ApiKeyState{set, masked}` um `{validated?,
  reason}`.
- **S-4 — Registrierungs-Outcome & Identität:** `{hubId, name, pubKey}` registriert + Bestätigung; ob/wie ein
  Device-Code an die UI muss (Q7).
- **S-5 — Maskierungs-Contract bleibt server-seitig:** `Secrets.mask()` + `ApiKeyState{set, masked}` **1:1
  wiederverwenden** — Client maskiert nie selbst. Der Erststart-Credential-Screen ist eine Variante desselben Contracts.
- **Drift-Hinweis:** neue `hubconnect_*`-Keys + `hubConnect.*`-Tags landen mit Devs Slice → **Re-Sync mit Tester (CYP-7)**;
  Key/Tag-Timing mit dem konsumierenden Modul abstimmen.

---

## 13. Acceptance-Teeth (für spätere §-QA)

1. **Presence-Ehrlichkeit (H1):** Registry-`online/offline` nie in `tertiary`-Grün; immer Punkt **+ Label**; `lastSeen`
   relativ sichtbar bei offline; Registry-Presence nie als „verbunden" lesbar.
2. **Zwei Wahrheiten getrennt:** während Connect zeigt die UI **meine Verbindung** (CONNECTING/LIVE) getrennt von der
   Registry-Presence; „Verbunden" erst bei echtem LIVE.
3. **Remote ehrlich deaktiviert (H2):** `hubConnect.mode.remote` non-interaktiv, „kommt bald", nicht vorausgewählt, kein
   Fake-Klick; a11y `a11y_hubconnect_mode_remote_disabled`.
4. **Credentials (H3):** Feld rendert nie Klartext zurück; nur `***<letzte4>`; `hinterlegt` ≠ `validiert` sichtbar
   getrennt; `unreachable` = WARN-Amber, `invalid` = Fehler-Rot, `validated` = INFO **kein Grün**.
5. **Zero-Knowledge-Copy (H4):** falls geliefert, exakt auf Credentials begrenzt, deckt sich mit der Architektur-Garantie
   (Keystore-lokal, nie CP).
6. **Erst-Online-Vorbedingung (H5):** CP-nicht-erreichbar bei Login/Hub-Liste = ehrlicher Fehlerzustand, kein stiller
   Hänger; `never_online`-Connect-Fehler klar benannt.
7. **Connect-Zustände erben das Idiom (§3.3):** `attempting`/`handshake` neutral `onSurfaceVariant`, nie grün/LIVE
   vorzeitig; Fehler auf errorContainer mit **typisierter** Ursache-Copy.
8. **Farbe nie allein (1.4.1) & Maritim:** alle Zustände Form+Label+a11y; keine hartkodierten Farben; Dark/Light über
   `maritimeColorScheme`.
9. **DE/EN-Parität:** jeder `hubconnect_*`-Key in beiden Sprachdateien; a11y-Strings vorhanden.
10. **Reuse statt Divergenz:** maskiertes Feld = `ApiKeySection`-Muster; Login = bestehender `AuthGate`; Connect-Status
    = bestehendes `ConnectionStatus`-Idiom — keine divergenten Einmal-Teile.

---

*Spec-Closure erreicht (Q1–Q8 geruled): die eingefrorenen Companion-Files `hub-connection-keys.md` / `-tags.md` /
`-tokens.json` (Haus-Konvention) sind die Vorlage, gegen die Dev **S-L** (die `hubconnect_*`-Screens) baut. testTag-Area
`hubConnect` mit Tester (CYP-7) abstimmen. Nichts an diesem Deliverable ist gebaut; es ist Design-Input für den parallelen
Client-Strang (Nahtstellen über den PO). Provisorische §9/§10-Blöcke im Spec-Doc = Übersicht; **maßgeblich sind die
eingefrorenen Companion-Files**.*
