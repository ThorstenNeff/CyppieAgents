# CYP-393 — Scroll-Retention (Auto-Follow lösen/resumen): kurze Semantik-Note

> Owner: UIUX-Designer · Ticket **CYP-393** · Stand 2026-07-11 · Basis **`origin/develop` = `9cea1ed6`** · Scope **Agenten-Transcript**
> **Kein Spec-Zwang** (PO) — eine Note für Developer5 + meinen QA-Blick. Baut mit CYP-392 in einem Zug.

Ziel: das Transcript **folgt** dem Tail (neue/streamende Inhalte scrollen ans Ende), **löst** aber, sobald der
Nutzer hochscrollt, und **resumt**, wenn er wieder unten ist. Vier Punkte, an denen ein naiver Ansatz bricht:

## 1. Folgen muss die **Tail-Wachstum** hören, nicht `events.size`

**Verifiziert:** `AgentEvent.AssistantText`-Deltas wachsen **in-place** in **ein** Item
(`TranscriptFolding.kt:29`, `it[idx] = existing.copy(text = existing.text + event.text)`) — die **Listengröße
bleibt konstant** während eine Antwort streamt. Das heutige `LaunchedEffect(events.size)` (`AgentWindow.kt:545`)
feuert deshalb **nicht** beim Streamen; nur ein **neues** Item snappt.

> **Folge:** Ein Auto-Follow, der nur auf `events.size` triggert, lässt den **streamenden** Tail aus dem Bild
> laufen — genau im häufigsten Fall (lange Assistant-Antwort). Der Follow-Trigger muss auf **Inhaltswachstum am
> Ende** hören: z. B. `snapshotFlow { listState.layoutInfo }` (Größenänderung des letzten Items) oder eine
> Tail-Signatur (letzte-Item-Textlänge). **Nicht** `events.size`.

## 2. „Am Boden" mit **Toleranz**, nicht exakt

Exakte Gleichheit (`!canScrollForward`) **flackert**: streamendes Wachstum und das Landen der
`animateScroll`-Animation lassen den Boden um Pixel wandern.

> **Regel:** „am Boden" = innerhalb einer kleinen **Toleranz** (Vorschlag **~48 dp / ~eine Zeile**). Developer5
> tunt den Wert; die Semantik ist Toleranz, nicht Gleichheit.

## 3. Lösen **nur bei Nutzer-Geste** — nie durch Inhaltswachstum

Das eigene Tippen des Agenten darf einen Nutzer, der **am Boden sitzt**, **nie** ent-pinnen.

> **Unterscheide Nutzer- von programmatischem Scroll über die `interactionSource`** (ein `DragInteraction` /
> Wheel / Tastatur) — **nicht** über `isScrollInProgress` (das ist auch während `animateScrollToItem` true).
> Nur eine **Nutzer**-Bewegung nach oben, weg vom Boden, löst den Pin.

## 4. Asymmetrie: eifrig lösen, bei Rückkehr resumen

- **Lösen:** jede Nutzer-Bewegung nach oben, die den Boden verlässt → Follow **aus** (sofort, kein Schwellwert
  zum Lösen).
- **Resumen:** der Nutzer scrollt wieder **innerhalb der Toleranz** (§2) an den Boden → Follow **an**.

Das ist das Standard-„stick to bottom"-Verhalten (Terminals, Log-Viewer, Chat) und das am wenigsten
überraschende.

---

## Randbedingungen

- **Erstes Laden / leer → Inhalt:** Follow startet **an** (das heutige Ans-Ende-Springen bleibt).
- **Tastatur/Screenreader:** Bild-auf / Pfeil-hoch **ist** eine Nutzer-Geste → löst (§3). Kein Sonderfall, kein
  Keyboard-Trap.
- **Zusammenspiel mit CYP-392:** gelöst → der Thumb sitzt **über** dem Boden (ehrliches „du bist im Rückblick");
  resumt → der Thumb fährt an den Boden. Die beiden **verstärken** sich, kein Konflikt.
- **Optional, nicht v1:** ein „zum Neuesten"-Chip, wenn gelöst **und** neue Inhalte unten anliegen. Der
  392-Scrollbalken signalisiert „mehr unten" bereits — für v1 **nicht** nötig; nenne ich, ziehe es nicht rein.

---

## Worauf ich beim QA schaue

1. **Streamende Antwort folgt** (nicht nur neue Nachrichten) — der wichtigste Fall, den `events.size` heute verfehlt.
2. **Am Boden sitzen bleibt gepinnt**, während der Agent tippt (kein Ent-Pinnen durch Inhaltswachstum).
3. **Hochscrollen löst**, Zurückscrollen an den Boden **resumt** — Toleranz, nicht exakt.
4. **Tastatur-Hochscrollen löst** genauso (a11y).
5. **392 + 393 zusammen:** Thumb-Position spiegelt den Follow-Zustand ehrlich.
