# CYP-703 Agent-Message-Core — Agent-Scoped Gap-Map (web-ts)

**Für:** PO/PL → speist Backend2s 704/705/706-Finalisierung · **Von:** UIUX2 (Team-2) · **Baseline:** develop `f8a0960c` (am Objekt gemessen 2026-07-19)
**Modus:** Gap-Messung am Objekt (nicht aus dem Ticketstatus geschlossen), **semantisch getaggt.**

---

## §0 Die Tagging-Regel (PL-Schärfung) — die HALT-Grenze läuft entlang der SEMANTIK, nicht der Nummer
Jeder Gap trägt **`human-identity: j/n`** — der Test ist **semantisch**, nicht „704 vs 706":
> **Legt das Feature eine Menschen-Identität OFFEN oder macht es einen Menschen ADRESSIERBAR?**
- **`n`** = agent-scoped (zeigt/adressiert nur Agenten oder Kanäle) → **zerlegbar-frei** (Team-2 baubar).
- **`j`** = macht einen **Menschen** namentlich sichtbar/adressierbar → **PLs gehaltene Weiche → STOP-zu-PO, nie selbst bauen** (berührt die pausierte Selbst-Identitäts-Weiche; content-free `AuthMe`).
*(z. B. Agent-run-state/busy = `n` egal welche Nummer; ein Feld unter freier Nummer, das einen Menschen namentlich zeigt = `j`.)*

**★ Identität heißt selten „Identität" (Dev5-Insight) — AKTIV prüfen, nicht reaktiv:** Menschen-Identität wohnt in `identityId`, Session-Subjekt, ACL-Prinzipal, `AuthMe`-Erweiterung, „wer ist der aktuelle Nutzer". Ein Gap ist **`j`, sobald das Feature wissen/zeigen müsste, WELCHER Mensch** — auch unter einem nicht-„identity"-Namen. Ein Agenten-only-Fakt (run-state, `@agent`-Mention, per-Agent-Unread) = **`n`**; ein Feld, das einen Menschen **namentlich auflöst/zeigt** = **`j`**. Jede Zeile unten ist so geprüft — **auf die Frage „müsste das WISSEN/ZEIGEN, welcher Mensch?", nicht nach „identity"-Wörtern gesucht.** (Die (n)-Gaps unten lösen ausschließlich **Agent-Ids/Kanäle** auf, nie einen Menschen.)

## §1 Gap-Map (gemessen am Objekt)
| Achse / Gap | Status (am Objekt) | human-identity | Verdikt |
|---|---|---|---|
| **Mention DISPLAY** (inline @-Agent-Chips) | **BUILT** — `mentionModel.ts` (`mentionSegments`, fail-closed Roster-Resolution) + `CommPanel.tsx:152-163` | **n** | ✅ komplett |
| **Mention channel-level cue** (welche Kanäle @-Agent-Mentions tragen — Nav-Highlight/Filter) | **FEHLT** (kein Nav-Cue; Mention nur inline im offenen Timeline) | **n** *(highlightet @-Agent, nicht @-Mensch)* | **(n) frei** |
| **Mention @-Mensch** (ein Mensch wird mentioned / adressierbar / Notify) | — | **j** | **(j) PL-Weiche → STOP** |
| **Unread pro-Kanal** (Nav-Badges, 3-Zustände read/unread/unknown) | **BUILT** — 705, `unreadModel.channelUnread` + `CommPanel.tsx:80-91` + Cursor-Pfad gemergt | **n** | ✅ komplett |
| **Unread comm-weit** (Count seit letztem Fokus) | **BUILT** — CYP-646, `App.tsx:260-271` → `WindowActivityBadge` | **n** | ✅ komplett |
| **Agent-Präsenz per-Window** (run-state-Dot + busy) | **BUILT** — CYP-431 `lifecycleStatus.statusDotSpec` (`LifecycleHeader`) + CYP-641 `WindowActivityBadge` (busy) | **n** | ✅ komplett |
| **Agent-Präsenz konsolidiert** (Roster-Übersicht auf einen Blick) | **PARTIAL** — `AgentManagementPanel.tsx:114` zeigt run-state als **Text-LABEL** (`lifecycleLabel`), aber **KEIN Status-Dot** (`statusDotSpec` ist per-window-only) und **KEIN busy** | **n** *(Agent-Prozess-State, kein Mensch)* | **(n) frei — Completeness-Gap** |
| **Menschen-Präsenz** („ist ein Mensch da / ansprechbar") | — | **j** | **(j) PL-Weiche → STOP** |

## §2 Die (n) zerlegbar-freien Gaps (agent-scoped, Team-2 baubar)
1. **Mention channel-level cue (agent-scoped):** ein Cue an Kanal-Buttons, wenn ein Kanal **@-Agent-Mentions** enthält — Completeness der Mention-Highlight über die inline-Chips hinaus (heute unsichtbar, solange man den Kanal nicht offen hat). **Reuse** `mentionSegments` über die Kanal-Nachrichten. **Honesty:** fail-closed (kein Cue aus unaufgelöstem Roster — [[absence-reads-as-all-clear]]-Schwester), **viewer-independent** (kein „me").
   - **★ Grenze:** die STÄRKERE Version — „**DEINE** Mentions" (einen Menschen adressierbar machen) — ist **(j) PL-Weiche**. Die freie Version ist rein „Kanal enthält eine @-Agent-Mention", **kein Mensch**.
2. **Konsolidierter Agent-Präsenz-Indikator:** der Roster zeigt run-state nur als **Text-Label** — der Completeness-Gap = der **honest Status-Dot** (ring-für-UNKNOWN, `statusDotSpec`, colour-never-sole via Shape) **+ busy-Overlay** (`busyByAgent`) in einer konsolidierten Übersicht (nicht nur per-Window). **Reuse** `statusDotSpec`/`lifecycleLabel` (CYP-431) + `busyByAgent` (CYP-641). **Honesty:** un-beobachtet → **UNKNOWN** (ring), nie „offline" ([[token-contrast-is-role-dependent]]: Dot als Glyph/Shape ≥3:1); busy fail-closed (unknown≠busy).

## §3 Die (j) PL-Weiche-Items (STOP — nie selbst bauen)
- **Mention @-Mensch** (ein Mensch namentlich mentioned/adressierbar/Notify) — `human-identity: j`.
- **Menschen-Präsenz** (ein Mensch als anwesend/ansprechbar gezeigt) — `human-identity: j`.
- Beide berühren die **gehaltene Selbst-Identitäts-/Adressierbarkeits-Weiche** (content-free `AuthMe`; CYP-704-NOTIFY pausiert). → **STOP-zu-PO.**

## §4 Übergabe
- **Getaggter Output → Backend2s 704/705/706-Finalisierung.** Die **(n)-Gaps** sind in zwei parallele Stories zerlegbar: **(a)** Mention-channel-cue (agent-scoped), **(b)** konsolidierter Agent-Präsenz-Dot+busy. Beide **reuse-schwer** (mentionSegments · statusDotSpec/lifecycleLabel/busyByAgent).
- **Unread ist agent-scoped komplett** (705 pro-Kanal + 646 comm-weit) — kein Gap.
- **(j)-Items** = PLs-Weiche, nicht in dieser Zerlegung.
- **Kein Gap aus der Nummer geschlossen** — jede Zeile am Objekt gemessen + semantisch getaggt.
