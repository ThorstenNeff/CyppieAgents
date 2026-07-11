# Fehler-Notices brauchen einen eigenen Ton — UX-Spec

> Owner: UIUX-Designer · Ticket **CYP-385** · Stand 2026-07-11 · Basis **`origin/develop` = `6b9f89bd`** · Scope **WASM-App**
> Docs-only. Klein: **1 Feld, 1 `when`, 1 a11y-Key.** Reuse des vorhandenen Fehler-Tons, keine neue Farbe.
> Ursprung: CYP-383 §3 — „bereit" und „Verbindung verloren" sind heute **farbgleich**.

---

## 0. Das Problem in einem Bild

Alle vier `AgentEvent.Notice`-Zeilen rendern heute **identisch** — `onSurfaceVariant`, `labelSmall`:

| Notice | Herkunft | Wesen | Farbe heute |
|---|---|---|---|
| „Agent bereit" (CYP-383) | `init` | **gut** | `onSurfaceVariant` |
| „Session gestartet · Agent: …" | Stub | neutral | `onSurfaceVariant` |
| „Verbindung zum Agenten verloren" | `AgentViewModel:216` | **Störung** | `onSurfaceVariant` |
| „Turn-Fehler: …" | `StreamJsonMapper:130` | **Fehler** | `onSurfaceVariant` |

> **Gut und kaputt tragen dieselbe Farbe.** Der Operator liest „bereit" und „verloren" im selben leisen Grau —
> die Zeile, die beruhigt, sieht aus wie die, die warnt.

---

## 1. Der Ton existiert bereits — Reuse, keine Erfindung

`ResultRow` (`AgentWindow.kt:735`) trennt Erfolg und Fehler schon **ehrlich**:

```kotlin
val container = if (event.isError) errorContainer else surfaceVariant
val content   = if (event.isError) onErrorContainer else onSurfaceVariant
```

**Notice bekommt genau dieses Muster** — dieselbe Codeform eine Zeile weiter, kein neuer Farbslot, kein
Design-System-Umbau.

---

## 2. Der Fix: eine Severity auf der Notice

`AgentEvent.Result` trägt schon `isError: Boolean`. `AgentEvent.Notice` bekommt das Pendant, aber
**dreistufig**, weil eine Verbindungs-Unterbrechung und ein echter Fehler **nicht** dasselbe sind (§4):

```kotlin
enum class NoticeSeverity { INFO, WARNING, ERROR }

data class Notice(
    override val id: String,
    val text: String,
    override val tsMs: Long,
    val severity: NoticeSeverity = NoticeSeverity.INFO,   // Default INFO → alle heutigen Aufrufer bleiben unverändert
) : AgentEvent
```

**Kein `:protocol`-Eingriff.** `Notice` ist ein Client-`AgentEvent`, nicht der Wire-Typ. Die Severity wird
dort **abgeleitet, wo die Notice entsteht** — der Mapper weiß, ob ein Ereignis `init` (INFO) oder `Turn-Fehler`
(ERROR) ist; `AgentViewModel` weiß, dass der Socket-Verlust WARNING ist. Kein Ratespielraum.

### 2.1 Die Ton-Tabelle (alle Werte gerechnet, hell / dunkel)

| Severity | Zeilen | Ton | Kontrast gegen `surface` | ≥ 4,5:1 (Text) |
|---|---|---|---|---|
| **INFO** | bereit · session · key-changed | `onSurfaceVariant`, kein Hintergrund | 8,69 / 9,80 | ✅ |
| **WARNING** | „Verbindung verloren" | `onSurfaceVariant` **+ führender Marker** (§4) | 8,69 / 9,80 | ✅ |
| **ERROR** | „Turn-Fehler" | Text in `error` | **6,54 / 11,09** | ✅ |

**ERROR als Textfarbe, nicht als Band.** Ein `errorContainer`-Band (wie `ResultRow`) trüge auch (12,77 / 7,17),
ist aber eine **Karte**, kein **Zeilen**-Element — eine Notice ist eine Zeile. Der `error`-Text ist der leichtere,
zur Zeilennatur passende Griff und besteht AA. *(Wenn der PO die volle `ResultRow`-Konsistenz will — Band statt
Textfarbe —, ist das eine Zeile Code mehr; ich nenne die Zahl, entscheide es aber nicht.)*

---

## 3. WCAG 1.4.1 — Farbe ist nie der alleinige Träger

Das ist die Regel des Tages, und sie gilt auch hier: der **Wortlaut** trägt die Bedeutung, die Farbe verstärkt.

- „Turn-Fehler" **sagt** Fehler; der `error`-Ton verstärkt.
- „Verbindung zum Agenten verloren" **sagt** die Störung; der Marker verstärkt.
- a11y: ERROR- und WARNING-Notices bekommen einen eigenen Prefix statt „Hinweis:".

| Key | DE | EN |
|---|---|---|
| `a11y_notice` (vorhanden) | „Hinweis: %1$s" | „Notice: %1$s" |
| `a11y_notice_alert` (**neu**) | „Warnung: %1$s" | „Alert: %1$s" |

> Ein Key für WARNING **und** ERROR: der Screenreader unterscheidet „etwas stimmt nicht" von „reine Info" —
> feiner zu splitten (Warnung ≠ Fehler auch akustisch) lohnt den zweiten Key im MVP nicht. **1 neuer a11y-Key.**

---

## 4. Die Ehrlichkeits-Entscheidung: Verbindungsverlust ist **kein** Fehler

Hier liegt der eigentliche Designgehalt, und er zeigt in die **Gegenrichtung** der naheliegenden Lösung.

Ein `Turn-Fehler` ist **terminal** — der Turn ist gescheitert, nichts heilt ihn. Ein Verbindungsverlust ist
**selbstheilend**: der Adapter reconnectet vom Seq-Cursor, der Server spielt die Historie **lückenlos** nach
(`AgentWindow.kt:274`-Umfeld, CYP-204). Und die Kopfzeile trägt bereits den `ReconnectingChip` als **live**
laufenden Marker.

> **Den Verbindungsverlust rot wie einen echten Fehler zu malen, wäre die Übertreibung, die ich sonst
> anprangere — Alarm für etwas, das sich in Sekunden selbst repariert.** Genau das Spiegelbild der
> Untertreibung, die „bereit" heute ins neutrale Grau steckt.

Deshalb **drei** Töne, nicht zwei:

- **INFO** (neutral): der Normalfall, gut oder belanglos.
- **WARNING** (neutral gefärbt, aber **markiert**): „etwas ist unterbrochen, aber es erholt sich" — die
  `Verbindung-verloren`-Zeile. Der Marker (ein führendes, kollisionsgeprüftes Zeichen oder ein dünner
  `outline`-Rand links) hebt sie aus dem Grau, **ohne** die Alarmfarbe zu verbrauchen.
- **ERROR** (`error`-Text): „das ist wirklich schiefgegangen" — `Turn-Fehler`.

**Das löst die exakte Klage des PO** — „bereit" (INFO) und „Verbindung verloren" (WARNING) sind jetzt
verschieden — **und** bleibt ehrlich, weil es einen recoverbaren Zustand nicht zum Fehler aufbläst.

> **Wenn der PO nur zwei Töne will** (kleiner): INFO + ERROR, und Verbindungsverlust wandert nach **INFO**
> (bleibt neutral, weil recoverbar und vom Header-Chip getragen). Dann sind „bereit" und „verloren" aber wieder
> farbgleich — was die Klage **nicht** löst. **Deshalb empfehle ich die drei Töne.** Der Preis ist ein
> Enum-Wert und ein Marker, kein neuer Farbslot.

---

## 5. Abnahme

1. **Ton je Severity, beide Themes:** ERROR rendert `error`-Text (nicht `onSurfaceVariant`); INFO/WARNING
   nicht `error`. **Mutationsprobe:** `Turn-Fehler` auf INFO ⇒ **rot**.
2. **Verbindungsverlust ist WARNING, nicht ERROR** *(die Ehrlichkeits-Prüfung)*: die Zeile trägt **nicht**
   `error`. **Mutationsprobe:** conn-error auf ERROR ⇒ **rot** — Alarm für einen recoverbaren Zustand.
3. **Farbe nicht alleiniger Träger:** ERROR/WARNING-Notice trägt a11y-Prefix „Warnung:"/… **und** ihren
   Wortlaut. **Mutationsprobe:** Prefix zurück auf „Hinweis:" ⇒ **rot**.
4. **Default INFO:** ein `Notice(...)` ohne `severity` rendert wie heute. **Mutationsprobe:** Default auf ERROR
   ⇒ jede Alt-Notice wird rot ⇒ **rot** (schützt die Bestandsaufrufer).

**Test 2 ist der eigentliche.** Die übrigen prüfen Anzeige und Rückwärtskompatibilität; Test 2 prüft, dass wir
eine Störung nicht zum Fehler übertreiben.

---

## 6. Self-Validation

- **Reuse:** der Fehler-Ton ist der aus `ResultRow` (`isError`), eine Zeile weiter. Kein neuer Farbslot, kein
  Design-System-Umbau.
- **Kontraste gerechnet**, hell **und** dunkel: `error`-Text 6,54 / 11,09 (AA), Container-Alternative 12,77 /
  7,17, INFO/WARNING 8,69 / 9,80.
- **Die Designentscheidung zeigt gegen die bequeme Lösung** (§4): Verbindungsverlust ist WARNING, nicht ERROR,
  weil er selbstheilt — Alarm dafür wäre dieselbe Unehrlichkeit wie „bereit" im Grau, nur invers.
- **1 Enum, 1 optionales Feld (Default INFO → 0 Bestandsbrüche), 1 neuer a11y-Key.** Kein `:protocol`-Eingriff.
- **WCAG 1.4.1 gewahrt:** Wortlaut trägt, Farbe verstärkt, Screenreader-Prefix unterscheidet.
- **Eine Zahl offen gelassen** (§2.1): Textfarbe vs. `errorContainer`-Band — Produktgeschmack, PO entscheidet.
- **Docs-only.**
