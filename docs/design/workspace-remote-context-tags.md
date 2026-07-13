# Workspace Remote-Context-Banner — testTag-Vertrag (CYP-527, Epic CYP-427)

> Owner: UIUX-Designer · **Dev-AC (copy-paste-fertig)** · Stand 2026-07-13 · Begleit-Keys: `workspace-remote-context-keys.md`.
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): **prefixless** `<area>[.<scopeId>].<element>`, Segment-Werte
> `[A-Za-z0-9-]+` (**camelCase**, kein Underscore, keine Punkte im Wert). **Geteilte API mit QA (CYP-7) — nicht still
> umbenennen, über den PO koordinieren.**
> Erweitert das bestehende **`WorkspaceTags` (Area `workspace`)** um **einen** Tag. Verifiziert gg. `WorkspaceTags.kt`
> @ develop `d9a3d6be`.

## Diese Slice baut — 1 Tag
| Tag (Konstante) | Wert | Zweck |
|---|---|---|
| `REMOTE_CONTEXT` | `workspace.remoteContext` | Die persistente WARN-Kontext-Zeile (Row) im Workspace — trägt `workspace_remote_context_partial` (Copy) + `a11y_workspace_remote_context` (contentDescription). Präsent **iff** `remoteContext == true` (siehe Guards). |

Kotlin (an `WorkspaceTags` anhängen):
```kotlin
/** Persistent, transport-getriebene Remote-Kontext-WARN-Zeile ("Remote verbunden — Daten noch nicht über den
 *  Tunnel"). Präsent ⇔ remoteContext==true (Remote-Pfad @ CONNECTED); absent lokal/nach Teardown. Eigener Node —
 *  NICHT [ROLE_INDICATOR] (andere Wahrheit: wer-bin-ich ≠ bediene-ich-fern). */
const val REMOTE_CONTEXT = "workspace.remoteContext"
```

## Binding & Guards — **present-iff** (der ehrliche Kern, Pflicht-AC)
Das Banner ist **transport-getrieben, nicht klick-getrieben.** Der erreichbare Diskriminator heute ist ein
client-only `remoteContext: Boolean`, in `AgentShell` durchgereicht (der echte `HubTransportMode.REMOTE` ist noch
nicht verdrahtet — `defaultMode()` = hart `LOCAL`; `remoteContext` ist der **semantisch äquivalente, erreichbare
Proxy**).

| # | Guard | Regel |
|---|---|---|
| G1 | **Quelle** | `remoteContext = true` **nur** wenn der **Remote-Pfad** `RemoteSessionState.conn == CONNECTED` erreicht hat — **nicht** schon bei Modus-Wahl (sonst behauptet's „verbunden" zu früh). |
| G2 | **Present-iff** | `workspace.remoteContext`-Node ist **präsent ⇔ `remoteContext == true`**. `false` ⇒ Node **absent** (nicht nur unsichtbar). |
| G3 | **Nie lokal** | Auf dem **Lokal-Pfad** (`ConnectingView.Connected` / `LocalHubTransport`) wird `remoteContext` **nie** gesetzt. (Das ist **nicht** das `entered`-Problem: `entered`/`onEnterWorkspace` feuert für beide Pfade; `remoteContext` ist remote-pfad-exklusiv.) |
| G4 | **Clear** | Bei **Teardown / `backToHubList()` / Wechsel-auf-lokal** wird `remoteContext = false` gesetzt → **kein stale-true** Banner nach dem Fern-Beenden. |
| G5 | **Forward-compat** | Landet später der echte Transport-Modus (RR5), wird `remoteContext` **von dort neu gespeist** — **Tag/Copy/Node unverändert** (stabile Naht). „Echt-remote vs. Stub" bleibt Sache der vorgelagerten Provisorisch-Disclosures im Connect-Flow. |

## Warum eigener Tag (nicht `ROLE_INDICATOR` wiederverwenden)
- `ROLE_INDICATOR` (`workspace.roleIndicator`, CYP-186) trägt eine **andere Wahrheit** — *wer bin ich*
  (Operator/Member) — ≠ *bediene ich fern + fließen Daten schon fern*. Zwei unabhängige Fakten (ein MEMBER auf einem
  Remote-Hub braucht **beide** Zeilen) → gemeinsames Tag verletzt das **H1-Anti-Konflations-Prinzip**.
- **Tester/CYP-7-Distinguierbarkeit:** mit geteiltem Tag ließe sich „Rolle da" nicht von „Remote-Banner da" trennen.
- Das Remote-Banner ist **bedingt** (`remoteContext==true`), der Rollen-Indikator **immer präsent** → ohnehin
  verschiedene Nodes.
- ⟹ **Eigener Node, eigenes Tag, eigene `contentDescription`** — **gleiche Bar-Nachbarschaft** erlaubt. Der
  `RemoteRevokeControl`-KDoc-Verweis auf `ROLE_INDICATOR` meint den **Mount-Ort/die Nachbarschaft**, **nicht** Tag-Sharing.

## Platzierung (Reuse des `overloadBanner`-Slot-Idioms)
Persistente Full-Width-Zeile **ganz oben im Workspace**, im **`ProjectSwitcherBar`**, gleiches Slot-Muster wie
`overloadBanner`/`capacityReadout` (optionale `@Composable`-Slots, `= {}`-Default → 0 Änderung für bestehende
Call-Sites/Tests). Empfehlung:
```kotlin
// ProjectSwitcherBar(...)-Signatur, analog zu overloadBanner:
remoteContextBanner: @Composable () -> Unit = {},   // present-iff remoteContext; AgentShell reicht ihn durch
// ... im Body, in der Nachbarschaft der ROLE_INDICATOR-Row (eigener Node):
remoteContextBanner()
```
`AgentShell` reicht den Slot **present-iff `remoteContext == true`** durch (leerer Default ⇒ absent, G2).

## Fail-closed-/Ton-Anker (für §-QA)
- **Ton:** `severityColor(Severity.WARN)` (Glyph `▲` + Text). **Kein** `errorContainer`/`HintTone.ERROR` (nicht
  „kaputt"), **kein** `tertiary`/Erfolgs-Grün (nicht „alles gut"). **Kein Tag trägt Erfolgs-Grün.**
- **Present-iff transport-getrieben** (G1–G4): das Banner erscheint **nie** auf einem Lokal-Pfad und **nie** stale
  nach Teardown. Läuft das Dogfood noch auf Stub/`LOCAL`-Transport, bleibt das Banner **korrekt absent** — keine
  falsche Remote-Behauptung.
- WCAG 1.4.1: Bedeutung im Text/`contentDescription`, `▲`+Amber nur Verstärkung.

## Reuse (bestehende Tags/Slots — NICHT neu anlegen; verifiziert @ `d9a3d6be`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `WorkspaceTags` (Area `workspace`) | CYP-80/186/417 | der Tag hängt an das bestehende Objekt (Area schon etabliert) |
| `workspace.roleIndicator` | CYP-186 | **Nachbar** in der Bar (NICHT geteilt) — der Remote-Node sitzt daneben |
| `overloadBanner`-Slot (`ProjectSwitcherBar`) | CYP-417 | Platzierungs-/Slot-Muster (optionaler Full-Width-`@Composable`) |

## Self-Validation
- **In dieser Slice gebaut: 1 Tag** (`REMOTE_CONTEXT = workspace.remoteContext`). Kein dynamischer Qualifier.
- **0 Kollision:** `workspace.remoteContext` verifiziert greenfield gg. `WorkspaceTags.kt` @ `d9a3d6be`
  (bestehend: `roleIndicator`/`operatorName`/`members`/`member.*`/`capacity`/`capacity.full`/`overloadBanner`/
  `overloadBanner.dismiss` — kein `remoteContext`).
- **Charset/Konvention:** `workspace.remoteContext` = camelCase-Segment, `[A-Za-z0-9-]+`, kein Underscore/Punkt im Wert. ✓
- **Geteilte API mit QA (CYP-7):** Wert über den PO mit Tester + DS abstimmen (Frozen-Contract).
- **Present-iff/Guards (G1–G5)** sind der behaviorale §-QA-Kern — testbar: present @ Remote-CONNECTED, absent lokal,
  absent nach Teardown, `%1$s` = `hub.name`.
- **Deferred:** die `●`-Vollform reuse **denselben** `workspace.remoteContext`-Node (Zustandsvariante, kein neuer
  Tag) → bleibt **1 Tag** auch nach CR3. Der Tag ist in `-keys.md` verankert und trägt Copy- + a11y-Key.
