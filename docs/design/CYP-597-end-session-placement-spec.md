# CYP-597 — „Fern-Sitzung beenden" sicher platzieren (Live-UX-Safety-Fund)

> Owner: UIUX-Designer · Ticket **CYP-597** (Low, non-blocking, post-Live) · Stand 2026-07-15 · **Design/Placement, kein
> Code.** Gegroundet READ-ONLY gg. develop `a1593d19`. Ursprung: Live-Beobachtung (Mensch) — der destruktive Button steht
> zu zentral (direkt über „Fit windows"). Impl: Dev (Layout), über PO.

## 0. Grounding — zwei Korrekturen vorweg (Code-belegt)
1. **★ Der Confirm-Guard EXISTIERT bereits.** `RemoteRevokeControl` (`RemoteRevokeControl.kt:48`) öffnet bei Klick **NICHT**
   sofort das Teardown, sondern eine `AlertDialog`-Bestätigung (`RevokePhase.CONFIRMING`): Titel „Fern-Sitzung beenden?"
   + Body „Diese Verbindung wird sofort getrennt." + **ehrliche Scope-Note** „…nur diese Verbindung auf diesem Gerät —
   widerruft keine anderen Sitzungen." + Abbrechen. **Ein Fehlklick killt die Session NICHT sofort — er öffnet den Dialog.**
   → Die PO-Idee „erwäg einen Confirm-Guard" ist **schon erfüllt**; CYP-597 soll **keinen zweiten** bauen.
2. **Das echte Problem = Platzierung + Nachbarschaft.** Der Button rendert **trailing in der Remote-Context-Banner-Zeile**
   (`RemoteOperatingChrome.kt:78-85`: `Row { RemoteContextBanner(weight=1f) … RemoteRevokeControl() }`), present-iff
   remote-CONNECTED (G6). Diese Zeile sitzt **über** der zentralen `WindowManager`-„Fit windows"-Toolbar
   (`WindowManager.kt:314`) → der destruktive Button landet **im zentralen Fenster-Management-Fluss**, neben einer
   häufig-geklickten, harmlosen Aktion. Zusätzlich ist er bewusst ein **neutraler `TextButton`** (KDoc: „no scare-red") —
   richtig fürs Nicht-Erschrecken, aber in Kombination mit der zentralen Lage **lädt es zum Fehlklick ein**.

## 1. Empfehlung (Placement, der eigentliche Fix)
**Den „Fern-Sitzung beenden"-Control aus der zentralen Context-Zeile in das globale Top-Right-Utility-Cluster verlegen —
neben/über die Theme-Auswahl** (`ThemeModeToggle`), klar getrennt von der `WindowManager`-Fit-Toolbar. Genau der
Nutzer-Vorschlag: „ganz rechts oben, über der Theme-Auswahl".

**Warum top-right/Utility-Cluster:**
- **Konvention:** session-/settings-Level-Aktionen (destruktiv, selten) leben in der **Utility-Ecke**, nicht im
  Arbeits-Toolbar. Fenster-Management (Fit windows) ist häufig + harmlos → gehört in den Arbeits-Fluss; Session-beenden ist
  selten + destruktiv → gehört in die Ecke.
- **Fehlklick-Distanz:** räumlich weg von „Fit windows" → der versehentliche Treffer bei einer Fenster-Aktion entfällt.
- **Discoverability bleibt:** top-right ist auffindbar (dort sucht man Global-/Session-Controls), ohne im zentralen Weg zu liegen.

## 2. Was ERHALTEN bleibt (nicht anfassen)
- **Der bestehende Confirm-Guard** (`AlertDialog` + Scope-Note + Abbrechen) — unverändert. Safety ist damit schon abgedeckt.
- **`present-iff remote-CONNECTED` (G6)** + **G5-Exklusivität** (RECONNECTING → Relay-Drop-Banner, **kein** Revoke; LOST →
  absent): das Utility-Cluster zeigt den Revoke **nur** während einer aktiven Remote-Sitzung, sonst absent (fail-closed).
- **Neutraler Ton (kein scare-red):** die bewusste „no-scare-red"-Entscheidung bleibt — der Confirm trägt die Sicherheit,
  nicht Alarm-Farbe (konsistent mit der Honesty-Doktrin: rot = kaputt/terminal, nicht = „destruktive Aktion verfügbar").
- **Die Remote-Context-Banner** („du arbeitest remote auf <hub>") bleibt, wo sie ist — sie ist **informativ**, nicht
  destruktiv; **nur der Revoke-Control** wandert.

## 3. Optional (leichte Stärkung, nicht nötig)
- **Visuelle Gruppierung:** im Utility-Cluster den Revoke als **eigenständige Session-Aktion** lesbar machen (dezenter
  Separator/Icon-Label), damit er nicht als Theme-Peer missverstanden wird — weiterhin neutral, kein Alarm.
- **Kein** Zwang zu mehr Reibung (Doppel-Confirm o. Ä.): der eine `AlertDialog` + die Platzierung reichen; mehr Reibung
  bei einer legitimen, häufig-genug gebrauchten Aktion wäre über-korrigiert.

## 4. Keys / Tags — **keine neuen** (reine Relocation)
- **Copy:** `remote_revoke_end_action` / `remote_revoke_confirm_title` / `_confirm_body` / `_scope_note` / `_ttl_hint` /
  `remote_revoke_ended` — **alle bestehen**, unverändert reused.
- **Tags:** `remote.revoke.{end,confirm,scopeNote,ttlHint,ended}` (`RemoteRevokeTags`) — **alle bestehen**, wandern mit dem
  Control (per-Element-Tags bleiben gleich, nur der Mount-Ort ändert sich). **0 neue Keys, 0 neue Tags.**

## 5. Nahtstelle (Seam → Dev)
- **Composition-Root/Chrome-Layout (`AgentShell`/`RemoteOperatingChrome`):** den `RemoteRevokeControl(onEndSession, ttl)`-
  Aufruf aus der `RemoteOperatingChrome`-CONNECTED-Context-`Row` **heraus** und in das **Top-Right-Utility-Cluster** (bei
  `ThemeModeToggle`) verlegen — die **present-iff remote-CONNECTED**-Gating (G6/G5) dabei erhalten (der Cluster fragt den
  Session-State ab). Reine Layout-/Mount-Verschiebung; Control-interne Logik (Confirm/Teardown) unverändert.
- **UIUX2 (Interaktion/a11y):** Fokus-Reihenfolge im Utility-Cluster (Theme ↔ Revoke), damit der destruktive Control nicht
  der erste Tab-Stop ist. (Flächen-Split über PO.)

## 6. Self-Validation
- **Gegroundet** gg. `RemoteRevokeControl.kt` / `RemoteOperatingChrome.kt` / `WindowManager.kt` / `ThemeModeToggle.kt` @ `a1593d19` (file:line).
- **Korrektur belegt:** Confirm-Guard existiert (kein Duplikat spezifiziert); Fund ist Placement, nicht fehlende Bestätigung.
- **Reuse-first, 0 neue Keys/Tags** — reine Relocation; bestehende Copy/Tags/Confirm/G5-G6-Semantik erhalten.
- **Honesty:** neutraler Ton bleibt (kein scare-red) · Scope-Note-Ehrlichkeit bleibt · present-iff-remote bleibt. Kein Bau, docs-only auf `feature/CYP-597-end-session-placement`.
