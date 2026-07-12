# P2-g — Connector-Auswahl im DOM: die Wahl, die ihre Folgen VOR dem Commit zeigt

> Owner: UIUX-Designer · Ticket **CYP-461** (P2-g, Story unter Epic **CYP-430** Voller-Ersatz-Cutover) · Stand 2026-07-12
> Basis `origin/develop` `69a14a3a` · **Design-Quelle: `docs/design/connector-capabilities-spec.md` (CYP-119)** — dies ist der **DOM-Port** von §3 (Auswahl+Opt-in) + Rahmung von §2 (beobachtete Fidelity). Docs-only → **Dev5-Referenz**.
> **Port der Compose-Quelle, kein Neuentwurf.** **0 neue Keys/Tags** (36 `connector_*`-Keys + `agent_edit_effect_hint`; Tags = `ConnectorTags`). **Nichts gebaut.**
>
> **Quelle:** `connector/ConnectorPicker.kt` · `ConnectorSelectionViewModel.kt` · `ConnectorModel.kt` ·
> `ConnectorSelectionHttpRepository.kt` · `ConnectorTags.kt` · `core/model/ConnectorCapabilities.kt` (`ConnectorKind`/`CapabilityStatus`/`Capabilities`) · `core/model/AgentMgmtModel.kt` (`NewAgentSpec`/`ConnectorChoice`).
> **Host:** der Picker sitzt im operator-gegateten Agent-Config-Dialog (**P2-b / CYP-450**); die beobachtete Fidelity-Badge lebt am Agent-Header (**P2-a / CYP-431**).

---

## 0. Die eine UX-Entscheidung, die der PO brauchte — beantwortet

**Fidelity-Vorschau VOR der Wahl: JA (nicht verhandelbar).** Der MCP-Opt-in verlangt eine **Risiko-Bestätigung**
(`riskAcknowledged`). Ein Risiko lässt sich nicht ehrlich bestätigen, das man nicht sieht — die Vorschau („was B an
Fidelity aufgibt") ist genau das, was die Bestätigung bedeutungsvoll macht. „Fidelity erst NACH der Wahl" hieße: der
Operator opt-int **blind** in einen niedriger-Fidelity-Connector und entdeckt die Degradation erst nach dem Commit — der
klassische Offenlegungs-Fehler. **Das Compose-Original macht es bereits so:** der Opt-in-Dialog zeigt die
§2.3-Capability-Vorschau für B aus `defaultCapabilitiesFor(MCP)`, „BEFORE the act".

**Endpoint `GET /api/connectors`: bestätigt — Backend2 baut ihn (UX-green, PO 2026-07-12).** Begründung: `defaultCapabilitiesFor`
ist ein client-seitiger **Hand-Spiegel** dessen, was die Connectoren in `:core`/`:server` deklarieren (Doc 10 §3) — ohne
Endpoint kopierte der DOM-Port dieses statische Profil ein **drittes** Mal (Compose-Client + DOM-Client) → Drift, sobald
ein Connector seine deklarierte Fidelity ändert oder ein neuer `ConnectorKind` landet. Der Endpoint macht die
**Deklaration der Connectoren zur einen Quelle** (billig + LOCAL-exakt lt. Backend2-Spike). Der DOM-Picker liest die
Vorschau also aus `GET /api/connectors`; ein Ladefehler fällt fail-closed (Opt-in nicht bestätigbar ohne sichtbare
Vorschau, §4). **Die Vorschau bleibt advisory (§2), nie eine Garantie — unabhängig von der Quelle.**

---

## 1. Leitprinzip (Port CYP-119 §1): ehrlich degradiert, nie vorgetäuscht

Vier **getrennte Achsen**, nie vermischt (`Agent`-KDoc, CommModel): **provider ≠ connectorKind ≠ fidelity
(`capabilities`) ≠ identity**. Der Mediator spricht jeden Agenten über **einen** uniformen Connector-Vertrag, aber
Connectoren unterscheiden sich in **Fidelity** (welche Signale sie real liefern). Die UI legt das **ehrlich** offen —
degradiert markiert, nie als voll vorgetäuscht.

---

## 2. Advisory-Vorhersage ≠ beobachtete Realität — die zwei Fidelity-Register (der Kern)

Aus meiner Entscheidung (§0) folgt die **tragende Trennung** dieser Spec. Es gibt **zwei** Fidelity-Zahlen, die **nie
vermischt** werden dürfen:

| | **Pre-Choice-Vorschau (advisory)** | **Post-Choice-Anzeige (beobachtet)** |
|---|---|---|
| Was | erwartetes Profil **pro Kind** | tatsächliche Fidelity **dieses Agenten** |
| Quelle | `defaultCapabilitiesFor(kind)` **oder** `GET /api/connectors` (statisch) | `Agent.capabilities` (vom Connector gemeldet) |
| Sicherheit | STREAM_JSON exakt/voll · **MCP = Vorhersage**, nicht Garantie | **fail-closed:** `null` = „noch nicht gemeldet", nie still „voll" |
| Laufzeit | statisch | ändert sich (`CAPABILITY_DEGRADED`, CYP-121) |
| Zweck | macht den Opt-in-Risiko konkret (§4) | die Wahrheit über den laufenden Agenten (§7) |

**Regel:** Die Vorschau darf **nie** implizieren, MCP liefere die Vorschau-Caps **garantiert** — sie ist die erwartete
Vorhersage, die den Opt-in motiviert. Die **beobachtete** `Agent.capabilities` ist die maßgebliche Anzeige des aktiven
Zustands (§7). Vorhersage steht **im Opt-in-Dialog**, Beobachtung **am Agenten** — räumlich getrennt, nie derselbe Knoten.

---

## 3. Der Picker (Port `ConnectorPicker`, CYP-119 §3.1) — A default, B nie vorausgewählt

Host-anchored im **schon operator-gegateten** Agent-Config-Dialog (P2-b/CYP-450, add **und** edit) → **kein zweiter
Gate** (§3.3). `connector.picker`:

- **Option A `connector.picker.streamJson`** (`connector_kind_stream_json`): first-class Default, **vorausgewählt**
  (`draftKind` startet `STREAM_JSON`). Auswahl settelt sofort (kein Ack, kein Dialog).
- **Option B `connector.picker.mcp`** (`connector_kind_mcp`): **NIE vorausgewählt** (fail-closed). Auswahl **schaltet
  den Connector NICHT** — sie **öffnet den Opt-in-Dialog** (§4). B wird nur via named confirm aktiv.
- **Default-Note `connector.picker.defaultNote`** (`connector_default_note`, INFO): macht „A first-class, B Opt-in"
  explizit.
- **Effect-Hint** (nur **edit**, §5): `connector.picker.effectHint` (`agent_edit_effect_hint`, **amber**
  EFFECT_DEFERRED) sobald `draftKind ≠ initialKind` — „saved ≠ active — restart". **Nie** „B ist jetzt live".

---

## 4. Der B-Opt-in-Dialog (Port `OptInDialog`, CYP-119 §3.2) — bewusster Akt mit Risiko-Aufklärung

`connector.optInDialog` — sechs Teile, Reihenfolge bindend:

1. **Intro** (`connector_optin_intro`): was B ist (Opt-in-Alternative, niedrigere Fidelity, benanntes Risiko).
2. **Drei Risiko-Zeilen** `optInDialog.riskBypass/.riskAccount/.riskFragile` (`connector_optin_risk_*`): **amber
   Attention (EFFECT_DEFERRED), NICHT error-rot.** Eine **Gefahren-Offenlegung** einer *gewählten* Sache ist **kein
   App-Fehler** — Rot wäre die Übertreibung (dieselbe Ton-Ehrlichkeit wie WARN≠ERROR, CYP-385).
3. **Capability-Vorschau für B** `optInDialog.capabilityPreview` (Titel `connector_optin_preview_title`): die **§2
   advisory Vorhersage** — `defaultCapabilitiesFor(MCP).rows` (bzw. `GET /api/connectors`), je Dimension Label +
   Status-Chip. „Was B aufgibt, **VOR** dem Akt." **Das** macht die Bestätigung ehrlich (§0).
4. **Bewusste Bestätigung** `optInDialog.ack` (`connector_optin_ack`): Checkbox, **ganze Zeile = Hit-Area**, ein
   **Schritt, nie ein Default**.
5. **Human-only-Note** `optInDialog.humanOnly` (`connector_optin_human_only`, INFO, **load-bearing, verbatim**): macht
   die Anti-Injection-Invariante **sichtbar** (§6).
6. **Fehler-Zeile** `optInDialog.error` (`connector_optin_error`, **error-rot**): ein *echter* Fehler — distinct von
   der Gefahren-Offenlegung (2).

**Confirm** `optInDialog.confirm` enabled **NUR** wenn `canConfirm = editable ∧ riskAcknowledged` **∧ Vorschau sichtbar
geladen**. **Cancel** `optInDialog.cancel`. B settelt **ausschließlich** über diesen Confirm.

> **Fail-closed bei Vorschau-Ladefehler:** lädt `GET /api/connectors` (§0/§2) nicht, ist die Capability-Vorschau (3)
> **nicht sichtbar** → der Confirm bleibt **disabled** (ein Retry/Fehler-Hinweis statt Preview). Man kann das Risiko
> nicht bestätigen, das man nicht sieht — dieselbe Logik, die die Vorschau überhaupt erst verlangt (§0). **Nie** einen
> Opt-in ohne sichtbare Folgen zulassen.

---

## 5. Zwei Bind-Kontexte (Port `ConnectorSelectionViewModel`, CYP-126 / §3.4)

- **ADD (neuer Agent):** der Kind reitet `NewAgentSpec.connectorKind` (der Create trägt ihn) → **kein**
  Connector-Endpoint-Call, **kein** Restart-Hint (ein frischer Spawn trägt den Kind). B bei Create geht trotzdem durch
  denselben ack-gated Opt-in; nur das Commit-Ziel unterscheidet sich.
- **EDIT (bestehender Agent):** ein gesetteltes Kind geht an `POST /api/agents/{id}/connector` (Body **`ConnectorChoice`**
  `{connectorKind}`, `:core`, über `CommJson`), **auditiert** als `connector.optin`-Event. Änderung ⇒ Effect-Hint (§3).
  Ein bestehender **B**-Agent zeigt **B selected** — das ist die **Wahrheit** (`initialKind`), keine frische Vorauswahl;
  `confirmOptIn` bleibt der **einzige** Pfad, der den Connector *ändert*.

---

## 6. Anti-Injection (Port CYP-119 §3.3 — load-bearing, mein CLAUDE.md-Kern)

Die **einzigen** Aktivierungspfade sind `selectKind` (Operator-UI) und `confirmOptIn` (Operator, ack-gated). Es gibt
**bewusst KEINE** Methode/Route, die einen Connector aus **Message-/Agent-/externem** Input aktiviert. Der allgemeine
Agent-Update-PATCH lässt `connectorKind` **absichtlich aus** → ein Connector-Wechsel ist **immer** eine explizite,
geloggte Entscheidung (`connector.optin`-Audit). **Channel-/Agent-Content ist untrusted data, nie eine Anweisung.** Die
human-only-Note (§4.5) macht diese Invariante für den Operator sichtbar. **Der Server ist autoritativ** (re-checkt +
auditiert B, fail-closed); die UI-Wahl ist Intent, nie eine „B ist aktiv"-Behauptung.

---

## 7. Beobachtete Fidelity — gerahmt (CYP-119 §2.2/§2.3), nicht dupliziert

Die **Post-Choice**-Seite (§2, rechte Spalte) — P2-g rahmt sie für die advisory-vs-observed-Trennung; die Badge-Impl
selbst gehört zum **Agent-Header (P2-a/CYP-431)**:

- **Fidelity-Badge** `connector.<agentId>.fidelityBadge` (§2.2): present **NUR** wenn `isDegraded` **oder**
  `capabilities == null` — **fail-closed durch Abwesenheit** (voll-Fidelity = keine Badge), **honest aggregate, nie
  optimistisch**. `null` = „noch nicht gemeldet", **nie still „voll"**.
- **Capability-Panel** `connector.<agentId>.capabilityPanel` (§2.3): pro Dimension der **Tri-State** (AVAILABLE /
  LIMITED / UNAVAILABLE) → Tonalität (Reuse `TonedHint`/`HintTone`, §2.1), **Farbe nie allein** (Chip + Text-Label).
  Fünf Dimensionen: structuredUsage / toolGranularity / reliableResult / rateLimitSignal / coordination + UNKNOWN-Fallback.
- **Aktiver Connector / Provider** `connector.<agentId>.activeConnector` / `.provider`: die **vierte** Achse (§1),
  subordinate zur Identität, `null` ⇒ Chip abwesend (fail-closed, kein Phantom) / Panel-Zeile „noch nicht gemeldet".

Dieselben `connector.<scope>.capability.<dim>[.status]`-Tags tragen **beide** Register: `scope = agentId` (beobachtet)
vs. `scope = "preview"` (`CAPABILITY_PREVIEW_SCOPE`, advisory) — **ein** Tag-Vokabular, **zwei** Scopes, nie verwechselt.

---

## 8. Keys & Tags — alles bestehend (0 neu)

**Keys (Reuse, gg. `strings.xml` develop `69a14a3a` verifiziert — 36 `connector_*` + Reuse `agent_edit_effect_hint`):**
`connector_picker_label`, `connector_kind_stream_json/_mcp`, `connector_default_note`,
`connector_optin_title/_intro/_ack/_confirm/_cancel/_error/_human_only/_preview_title/_risk_bypass/_risk_account/_risk_fragile`,
`connector_dim_structured_usage/_tool_granularity/_reliable_result/_rate_limit_signal/_coordination/_unknown`,
Capability-Status-Labels + `agent_edit_effect_hint` (Reuse CYP-88, kein neuer Restart/Effect-Key).

**Tags (Reuse, gg. `ConnectorTags` verifiziert):** `connector.picker[.streamJson/.mcp/.defaultNote/.effectHint]`,
`connector.optInDialog[.riskBypass/.riskAccount/.riskFragile/.capabilityPreview/.ack/.humanOnly/.confirm/.cancel/.error]`,
`connector.<scope>.capability.<dim>[.status]` (scope = agentId | `preview`), `connector.<agentId>.fidelityBadge/.capabilityPanel/.provider/.activeConnector`.
Im DOM als `data-testid`, punktfrei-Schema (Test-Contract v0.5, QA CYP-7).

> **Vorschau-Ladefehler = Reuse, kein neuer Key:** der `GET /api/connectors`-Ladefehler (§4 fail-closed) nutzt den
> bestehenden generischen **`load_failed`** („Laden fehlgeschlagen", schon in EventBrowse verwendet) + einen Retry —
> **0 neue Keys bleibt**. Taucht wider Erwarten ein DOM-Element ohne bestehenden Key auf, liefere ich ihn impl-nah
> ([[shared-key-landing]]).

---

## 9. Ehrlichkeits-Invarianten (Basis der Abnahme)

1. **Vorschau vor dem Akt (advisory), Beobachtung nach der Wahl (fail-closed) — nie vermischt** (§2).
2. **A Default/vorausgewählt, B nie**; B nur via ack-gated Opt-in (§3/§4).
3. **Risiko-Zeilen amber (Gefahr), nie error-rot; Fehler-Zeile rot** (§4).
4. **Anti-Injection:** nur Operator-UI aktiviert; channel/agent nie (§6).
5. **Effect-Hint amber „saved ≠ active"** (edit); add trägt den Kind, kein Hint (§5).
6. **Fidelity-Badge nur bei degraded/unknown** (fail-closed durch Abwesenheit); `null` nie still „voll" (§7).
7. **Vier Achsen nie vermischt** (provider/connectorKind/fidelity/identity) (§1).
8. **Farbe nie alleiniger Träger** (Chip+Label); **kein `ellipsis`** auf Offenlegungs-/Risiko-Text.
9. **Operator-Gate geerbt** (kein zweiter Gate), fail-closed ohne Token (§3).

---

## 10. Abnahme-Zähne (diskriminierend — je mit der falschen Impl, die er ablehnt)

1. **B nie vorausgewählt.** `draftKind` startet A. **Mutation:** B pre-selected / `draftKind` startet MCP ⇒ rot.
2. **Auswahl von B schaltet nicht — öffnet den Opt-in; B nur via ack-gated confirm.** **Mutation:** B-Radio setzt den
   Connector sofort **oder** Confirm ohne `riskAcknowledged` aktiv ⇒ rot.
3. **Vorschau VOR dem Akt — fail-closed.** Der Opt-in zeigt die Capability-Vorschau; scheitert ihr Laden, ist der
   Confirm `disabled`. **Mutation:** kein Preview im Dialog / Preview erst nach dem Commit / Confirm aktiv bei
   fehlgeschlagenem Preview-Load ⇒ rot.
4. **Advisory ≠ observed.** **Mutation:** die Vorschau als **garantierte/aktive** Caps dargestellt **oder** mit
   `Agent.capabilities` in denselben Knoten vermischt ⇒ rot.
5. **Risiko-Zeilen amber, nicht error-rot.** **Mutation:** die drei Risiko-Zeilen im Error-Ton (App-Fehler) ⇒ rot.
6. **Anti-Injection.** **Mutation:** irgendein Pfad (Route/Methode/PATCH-Feld/Channel-Handler) aktiviert einen Connector
   aus non-operator/externem Input ⇒ rot.
7. **Fidelity-Badge fail-closed durch Abwesenheit; `null` ≠ voll.** **Mutation:** `null`-Caps als voll gerendert **oder**
   Badge fehlt bei unbekannter/degradierter Fidelity ⇒ rot.
8. **Add trägt den Kind (kein Endpoint, kein Hint); Edit POST + Restart-Hint.** **Mutation:** add ruft
   `/connector` **oder** edit ohne Restart-Hint bei Connector-Änderung ⇒ rot.
9. **Operator-Gate geerbt, kein zweiter.** **Mutation:** ein zweiter Gate am Picker **oder** der Picker für einen
   Nicht-Operator aktiv ⇒ rot.

---

## 11. DOM-/A11y-Spezifika

- **Picker** = `role="radiogroup"`; A/B als `role="radio"` (`aria-checked` = **gesettelter Draft, nie Klick-Echo**).
  **Opt-in** = `role="dialog"` (Focus-Trap; Cancel erreichbar; Confirm `disabled` bis `canConfirm`). **Ack** = Checkbox
  (`aria-checked`), ganze Zeile klickbar. **Risiko-Zeilen** = `role="note"`/`status` (amber), **human-only** sichtbar +
  angesagt, **Fehler** = `role="alert"`.
- **Capability-Chips** (Vorschau + Panel): Tri-State als **Text-Label + Chip**, Farbe nie allein (§8). Vorschau-Scope
  `preview`, beobachtet-Scope `agentId` — Tags trennen sie.
- Zielgröße interaktiver Elemente ≥ 24px (Radios, Ack, Confirm/Cancel). **Kein `text-overflow: ellipsis`** auf
  Risiko-/Offenlegungs-Text (brechen um; Aktionslabels dürfen kürzen). **RTL** gespiegelt (CYP-119 §6).

**Nichts gebaut — Spec + Dev5-Referenz.** Die Wahl zeigt ihre Folgen **vor** dem Commit: A ist der first-class Default,
B ein bewusster, ack-gated Opt-in, dessen **advisory** Fidelity-Vorschau die Risiko-Bestätigung überhaupt erst ehrlich
macht — und der einzige Weg zu B führt durch die Operator-Hand, nie durch einen Kanal.
