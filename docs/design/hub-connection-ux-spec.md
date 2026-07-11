# Hub-Verbindungs- & Modus-UX — Design-Spec (Epic CYP-395, Phase 1: Lokal-Modus)

> Status: **Erster Design-Aufschlag** · docs-only, kein Bau · Owner: UX/UI · Begleitkonzept: `../../13-cyppie-hub-architektur.md`
> Client-Architektur parallel beim Developer — **Nahtstellen über den PO** (siehe §12).
> Reihenfolge dieses Dokuments: **Screens/Zustände/Copy** zuerst, dann **offene UX-Entscheidungen** (§11) und **Nahtstellen** (§12).

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
**Area** nötig — Vorschlag `connect` (0 Kollision, wie `auth` sie einführte); Rename/Frozen-Contract mit QA (CYP-7)
abstimmen. i18n: DE-Default `values/strings.xml`, EN `values-en/strings.xml`, flache `snake_case`-Keys je Feature,
a11y-Strings `a11y_*`. Neue Key-Familie **`connect_*`**, maskiertes Feld spiegelt `settings_apikey_*`.

---

## 4. Sequenz A — Erststart nach Installation

Fünf Schritte; jeder ist ein Zustand eines gemeinsamen **Onboarding-Steppers** (zentrierte `AuthFormCard`, 400dp,
maritim). Fortschritt sichtbar (z. B. „Schritt 2 von 4"), damit der Nutzer weiß, wie viel bleibt.

### A0 — Hub-Vorbereitung (kurz, systemseitig)
Beim Erststart erzeugt der Hub sein Ed25519-Keypair (privat bleibt lokal). UI: knapper Lade-/Willkommens-Zustand,
**kein** Fortschrittsbalken der etwas verspricht. Copy `connect_prepare_title` „Cyppie wird vorbereitet…". Sofort weiter,
sobald der Hub bereit ist. Reuse `LoadingScreen` (`auth/AuthGate.kt`).

### A1 — „Melde dich an, um diesen Hub zu registrieren"
Route in den **bestehenden** `AuthGate` (Login **oder** Register + OIDC). **Kontext-Copy oben** macht klar, *warum* die
Anmeldung: `connect_register_intro` „Melde dich an, um diesen Hub deinem Konto zuzuordnen." Kein Neubau der Auth-Form;
nur die Intro-Zeile ist neu. Nach erfolgreichem Login (verifizierte `UserTier`) → A2.

### A2 — Hub-Registrierung
Der Hub tauscht Device-Code + Public-Key gegen die CP; die CP registriert `{hubId, ownerId, pubKey, name, defaultPort}`.
- **Zustände:** `registering` („Hub wird registriert…", neutral `onSurfaceVariant`) → `registered` (kurzes „Hub registriert").
- **Hub-Name:** die CP verlangt einen `name`. **Offene Entscheidung Q1** (§11): Auto-Name (Hostname) vs. Nutzereingabe.
  Default-Vorschlag der Spec: **vorbefülltes, editierbares Namensfeld** (Hostname als Default), damit Mehr-Hub-Listen
  später unterscheidbar sind. Feld `connect.register.name`, Copy `connect_register_name_label` „Name dieses Hubs".
- **Device-Code:** Bei Desktop (Frontend+Hub co-lokal) läuft der Austausch i. d. R. **automatisch** (kein getippter
  Code). Ob je ein Code angezeigt/eingegeben werden muss, ist **Q7/Nahtstelle S-4** — die Spec hält den Screen offen
  für einen optionalen `connect_register_devicecode`-Zustand.
- **Fehler:** CP nicht erreichbar → `connect_register_error_offline` „Registrierung braucht Internet. Erneut versuchen."
  (errorContainer, Retry). Bereits registriert (Re-Run) → idempotent weiter zu A3/Workspace.

### A3 — „Hinterlege deine Anthropic-Credentials"
**Wiederverwendung §3.1** (maskiertes Feld), aber als Erststart-Variante. Ablauf:
1. **Eingabe** — write-only Feld, `PasswordVisualTransformation`, Text-Label-Reveal. Copy `connect_creds_title`
   „Hinterlege deine Anthropic-Credentials", Feld-Placeholder `connect_creds_placeholder` „sk-ant-…".
2. **Zero-Knowledge-Zeile (H4)** — dezente Micro-Copy unter dem Feld: `connect_creds_privacy` „Bleibt auf diesem Gerät
   (Keystore) — geht nie an cyppie-agents.com." (onSurfaceVariant, informativ, **kein** Marketing-Grün).
3. **Validierung** — nach „Speichern & prüfen" ein Testcall gegen Anthropic:
   - `validating` „Credentials werden geprüft…" (neutral).
   - `validated` „Credentials gültig — hinterlegt." (INFO-Ton, **kein** Erfolgs-Grün; `secondary`/onSurface + Glyph `ⓘ`).
   - `invalid` „Key ungültig — bitte prüfen." (**Fehler**, errorContainer).
   - `unreachable` „Konnte nicht geprüft werden (Anthropic nicht erreichbar) — gespeichert, später erneut prüfen."
     (**WARN-Amber**, `warnContainer`, distinkt von `invalid`). → **H3**.
4. **Maskierte Bestätigung** danach: Statuszeile `***<letzte4>` (`connect_creds_masked` „Hinterlegt: %1$s"), Feld leert.

### A4 — „Hub bereit"
Erfolgs-Abschluss → Übergang in den Hub-Workspace. Copy `connect_ready_title` „Hub bereit", Button
`connect_ready_enter` „Loslegen". **Gating (Q5, §11):** Übergang setzt **mindestens hinterlegte** Credentials voraus;
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
  - `online` → gefüllter Punkt **neutral** (nicht grün!) + Label `connect_presence_online` „online". Ton: `onSurface`
    zurückhaltend; bewusst **kein** `tertiary`-Grün (Brand-Akzent, würde Erfolg/Verbindung überzeichnen).
  - `offline` → hohler/gedämpfter Punkt + `connect_presence_offline` „offline · zuletzt gesehen %1$s" mit **relativer**
    Zeit (`vor 3 Min`, `gestern`), `onSurfaceVariant`.
  - Presence-Punkt **immer mit Label** (1.4.1), nie Farbe allein; a11y `a11y_connect_presence` „Presence laut Registry:
    %1$s".
- **Auswahl** → öffnet B3 (Modus) für diese Zeile. Ein `online`-Hub ist **nicht** garantiert lokal erreichbar — die
  Wahrheit ist der Connect (§6).
- **Zeilen-Zustand bei bereits laufendem Connect:** die Zeile zeigt **meine Verbindung** (CONNECTING/LIVE) getrennt von
  der Presence (siehe §6), damit H1 sichtbar bleibt.

**Leerer Zustand:** keine Hubs für dieses Konto → Hinweis + primäre Aktion „Hub registrieren" (→ Seq A ab A2). Copy
`connect_hubs_empty` „Noch kein Hub registriert." + `connect_hubs_register` „Hub registrieren".
**Fehler:** `GET /hubs` scheitert (CP nicht erreichbar) → errorContainer-Banner `connect_hubs_error` „Hub-Liste nicht
erreichbar. Erneut versuchen." (**H5**). **Q6/Nahtstelle S-1:** ob eine **gecachte** Hub-Liste einen Offline-Lokal-Connect
erlaubt, hängt an der Client-Architektur — als offener Zustand markiert, nicht erfunden.

### B3 — Modus-Wahl (Lokal | Remote)
Segmentierte Auswahl **nach** der Hub-Wahl (ein Hub kann perspektivisch über beide Wege erreichbar sein):
- **Lokal** — aktiv, Default-Fokus. `connect_mode_local` „Lokal", Subtext `connect_mode_local_sub` „Direkt im selben
  Netz — privat und schnell."
- **Remote** — **deaktiviert (H2)**, nicht klickbar, Reuse `TonedHint(GATED)`-Idiom. `connect_mode_remote` „Remote",
  Badge/Subtext `connect_mode_remote_soon` „kommt bald". **Nicht** vorausgewählt, **kein** Fake-Klick.
- Primäre Aktion `connect_mode_connect` „Verbinden" → §6 (nur Lokal handlungsfähig in Phase 1).
- **Q3 (§11):** Platzierung (eigener Schritt vs. Toggle in der Hub-Zeile) — Spec-Default = eigener kompakter Schritt.

---

## 6. Lokal-Connect-Zustände (Bodenwahrheit — erbt §3.3)

Ein einziger, ehrlicher Fortschritt vom Klick „Verbinden" bis zur Sitzung. **Erbt das Connecting-Idiom** (neutral
`onSurfaceVariant`, nie grün) und das LIVE/Offline-Idiom.

| Zustand | Bedeutung | Darstellung | Copy-Key |
|---|---|---|---|
| `attempting` | Verbindungsversuch `localhost:<port>` | neutral `onSurfaceVariant`, Spinner/`●`-neutral | `connect_state_attempting` „Verbinde mit deinem Hub…" |
| `handshake` | JWT-Vorlage, Hub verifiziert gg. gecachten CP-Public-Key | neutral `onSurfaceVariant` | `connect_state_handshake` „Sichere Verbindung wird aufgebaut…" |
| `connected` (LIVE) | Sitzung etabliert | LIVE-Idiom `●` + `primary` (§3.3) | `connect_state_connected` „Verbunden" |
| Fehler ↓ | | errorContainer-Banner + **typisierte Ursache** + Retry | |
| `hub_offline` | Registry sagt offline / kein Prozess | errorContainer | `connect_error_hub_offline` „Hub ist offline. Starte den Hub und versuche es erneut." |
| `port_unreachable` | Port/Host nicht erreichbar | errorContainer | `connect_error_port` „Hub unter %1$s nicht erreichbar. Läuft er in diesem Netz?" |
| `handshake_failed` | JWT-Verify scheitert (Key-Mismatch) | errorContainer | `connect_error_handshake` „Sichere Verbindung fehlgeschlagen. Melde dich neu an." |
| `never_online` | Nie online authentifiziert → kein gecachter CP-Key | errorContainer/WARN | `connect_error_never_online` „Diese erste Anmeldung braucht einmal Internet." (**H5**) |

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

### 7.4 Keystore-Disclosure (H6, optional, zurückhaltend)
Höchstens **eine** plattformbewusste Zeile („im Schlüsselbund dieses Geräts gesichert") — keine pauschale
Hardware-Sicherheitszusage. **Q8 (§11)** ob diese Zeile mitgeliefert wird.

---

## 8. Maritim + Material-3-Notizen

- **Formen/Layout:** zentrierte `AuthFormCard` (400dp) für Onboarding/Login/Register/Credentials; Hub-Liste als
  ruhige, breitere Liste. Reuse bestehender M3-Komponenten (`OutlinedTextField`, `TextButton`, `Card`).
- **Farb-Disziplin (kritisch):** **`tertiary`/`#40D6A0` nie für Status** (Presence, „verbunden", „gültig"). Presence
  neutral; LIVE = `primary` + `●`; Fehler = `error`/errorContainer; WARN = `warnContainer`-Amber; INFO = `secondary`.
- **Nie Farbe-allein (1.4.1):** Presence, Modus-Disabled, Connect-Zustände tragen immer **Form + Label** (+ a11y).
- **Dark/Light:** beide Schemata über `maritimeColorScheme(dark)`; keine hartkodierten Farben in der Spec-Umsetzung.

---

## 9. testTag-Kontrakt (provisorisch — friert mit Spec-Closure)

Neue Area `connect` (mit QA/CYP-7 abstimmen). Provisorisch, weil §11-Entscheidungen Struktur noch bewegen können:

```
connect.onboarding.stepper            connect.hubs.list
connect.prepare                       connect.hubs.row.<hubId>
connect.register.name                 connect.hubs.row.<hubId>.presence
connect.register.submit               connect.hubs.empty
connect.register.error                connect.hubs.error
connect.creds.input                   connect.mode.local
connect.creds.reveal                  connect.mode.remote            (disabled)
connect.creds.masked                  connect.mode.connect
connect.creds.validating              connect.state.attempting
connect.creds.validated               connect.state.handshake
connect.creds.invalid                 connect.state.connected
connect.creds.unreachable             connect.state.error.<cause>
connect.ready.enter
```
**Fail-closed-Anker (für §-QA):** `connect.mode.remote` existiert, ist aber non-interaktiv; `connect.state.connected`
erscheint **nie** vor echtem LIVE; Presence-Tag trägt kein Erfolgs-Grün.

---

## 10. Copy (provisorisch, DE-Default + EN — friert mit Spec-Closure)

Key-Familie `connect_*` / `a11y_connect_*`. Maskiertes Feld spiegelt `settings_apikey_*`.

| Key | DE | EN |
|---|---|---|
| `connect_prepare_title` | Cyppie wird vorbereitet… | Setting up Cyppie… |
| `connect_register_intro` | Melde dich an, um diesen Hub deinem Konto zuzuordnen. | Sign in to link this hub to your account. |
| `connect_register_name_label` | Name dieses Hubs | This hub's name |
| `connect_register_error_offline` | Registrierung braucht Internet. Erneut versuchen. | Registration needs an internet connection. Try again. |
| `connect_creds_title` | Hinterlege deine Anthropic-Credentials | Add your Anthropic credentials |
| `connect_creds_placeholder` | sk-ant-… | sk-ant-… |
| `connect_creds_privacy` | Bleibt auf diesem Gerät (Keystore) — geht nie an cyppie-agents.com. | Stays on this device (keystore) — never sent to cyppie-agents.com. |
| `connect_creds_masked` | Hinterlegt: %1$s | Stored: %1$s |
| `connect_creds_validating` | Credentials werden geprüft… | Checking credentials… |
| `connect_creds_validated` | Credentials gültig — hinterlegt. | Credentials valid — stored. |
| `connect_creds_invalid` | Key ungültig — bitte prüfen. | Key invalid — please check. |
| `connect_creds_unreachable` | Konnte nicht geprüft werden (Anthropic nicht erreichbar) — gespeichert, später erneut prüfen. | Couldn't verify (Anthropic unreachable) — saved, check again later. |
| `connect_ready_title` | Hub bereit | Hub ready |
| `connect_ready_enter` | Loslegen | Get started |
| `connect_hubs_title` | Wähle deinen Hub | Choose your hub |
| `connect_hubs_empty` | Noch kein Hub registriert. | No hub registered yet. |
| `connect_hubs_register` | Hub registrieren | Register a hub |
| `connect_hubs_error` | Hub-Liste nicht erreichbar. Erneut versuchen. | Can't reach the hub list. Try again. |
| `connect_presence_online` | online | online |
| `connect_presence_offline` | offline · zuletzt gesehen %1$s | offline · last seen %1$s |
| `connect_mode_local` | Lokal | Local |
| `connect_mode_local_sub` | Direkt im selben Netz — privat und schnell. | Direct on the same network — private and fast. |
| `connect_mode_remote` | Remote | Remote |
| `connect_mode_remote_soon` | kommt bald | coming soon |
| `connect_mode_connect` | Verbinden | Connect |
| `connect_state_attempting` | Verbinde mit deinem Hub… | Connecting to your hub… |
| `connect_state_handshake` | Sichere Verbindung wird aufgebaut… | Establishing a secure connection… |
| `connect_state_connected` | Verbunden | Connected |
| `connect_error_hub_offline` | Hub ist offline. Starte den Hub und versuche es erneut. | Hub is offline. Start the hub and try again. |
| `connect_error_port` | Hub unter %1$s nicht erreichbar. Läuft er in diesem Netz? | Hub not reachable at %1$s. Is it running on this network? |
| `connect_error_handshake` | Sichere Verbindung fehlgeschlagen. Melde dich neu an. | Secure connection failed. Please sign in again. |
| `connect_error_never_online` | Diese erste Anmeldung braucht einmal Internet. | This first sign-in needs an internet connection once. |
| `a11y_connect_presence` | Presence laut Registry: %1$s | Registry presence: %1$s |
| `a11y_connect_mode_remote_disabled` | Remote-Modus — kommt bald, noch nicht verfügbar. | Remote mode — coming soon, not available yet. |

*(Relative-Zeit-Formatierung für `%1$s` in offline/lastSeen: Hausformat wie bei bestehenden Zeitstempeln —
Nahtstelle zur vorhandenen Zeitformatierung, konsistent halten.)*

---

## 11. Offene UX-Entscheidungen (für PO/Auftraggeber)

1. **Q1 — Hub-Benennung:** Auto-Name (Hostname) vs. editierbares Namensfeld bei A2? *Spec-Default: vorbefülltes,
   editierbares Feld* (Mehr-Hub-Unterscheidbarkeit). Wo umbenennen (später in Settings)?
2. **Q2 — Presence-Vertrauen:** Bestätigung, dass Registry-`online` **gedämpft/advisory** dargestellt wird (nie
   Erfolgs-Grün, nie als „verbunden" lesbar) — die zwei Wahrheiten (H1) getrennt.
3. **Q3 — Modus-Platzierung:** eigener Schritt (Spec-Default) vs. Toggle in der Hub-Zeile. Und: Remote in Phase 1
   **sichtbar-deaktiviert** bestätigt (so vom PO vorgegeben).
4. **Q4 — Erststart-Erkennung & Einstiege:** leere Hub-Liste nach Login → Register-Flow; nicht-leer → Auswahl. „Hub
   registrieren" auch später als „weiteren Hub hinzufügen" (Mehr-Hub) — Einstiegspunkte bestätigen.
5. **Q5 — Credential-Validierungs-Policy:** „Hub bereit" setzt **hinterlegt** voraus; bei `unreachable` mit **WARN**
   fortfahren erlaubt, bei `invalid` blockieren. Bestätigen.
6. **Q6 — Offline-Lokal / gecachte Hub-Liste:** Soll ein Offline-Lokal-Connect über eine **gecachte** Hub-Liste möglich
   sein (H5)? Hängt an Client-Arch (Nahtstelle S-1) — jetzt designen oder deferren?
7. **Q7 — Device-Code-Sichtbarkeit:** Läuft die Hub-Registrierung bei Desktop **automatisch** (kein getippter Code),
   oder braucht es je einen sichtbaren Device-Code-Zustand? (Nahtstelle S-4.)
8. **Q8 — Disclosure-Copy:** Zero-Knowledge-Zeile bei Credentials (H4) mitliefern (Spec-Default: ja, auf Credentials
   begrenzt)? Optionale Keystore-Zeile (H6) ja/nein?

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
- **Drift-Hinweis:** neue `connect_*`-Keys + `connect.*`-Tags landen mit Devs Slice → **Re-Sync mit Tester (CYP-7)**;
  Key/Tag-Timing mit dem konsumierenden Modul abstimmen.

---

## 13. Acceptance-Teeth (für spätere §-QA)

1. **Presence-Ehrlichkeit (H1):** Registry-`online/offline` nie in `tertiary`-Grün; immer Punkt **+ Label**; `lastSeen`
   relativ sichtbar bei offline; Registry-Presence nie als „verbunden" lesbar.
2. **Zwei Wahrheiten getrennt:** während Connect zeigt die UI **meine Verbindung** (CONNECTING/LIVE) getrennt von der
   Registry-Presence; „Verbunden" erst bei echtem LIVE.
3. **Remote ehrlich deaktiviert (H2):** `connect.mode.remote` non-interaktiv, „kommt bald", nicht vorausgewählt, kein
   Fake-Klick; a11y `a11y_connect_mode_remote_disabled`.
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
9. **DE/EN-Parität:** jeder `connect_*`-Key in beiden Sprachdateien; a11y-Strings vorhanden.
10. **Reuse statt Divergenz:** maskiertes Feld = `ApiKeySection`-Muster; Login = bestehender `AuthGate`; Connect-Status
    = bestehendes `ConnectionStatus`-Idiom — keine divergenten Einmal-Teile.

---

*Provisorisch bis Spec-Closure: die endgültigen `hub-connection-keys.md` / `-tags.md` / `-tokens.json` (Haus-Konvention)
landen, sobald die §11-Entscheidungen geruled sind — vorher würden Keys/Tags bei jeder Entscheidung driften. Nichts an
diesem Deliverable ist gebaut; es ist Design-Input für den parallelen Client-Strang (Nahtstellen über den PO).*
