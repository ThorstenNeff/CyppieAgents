# op-po PO-Output-Fenster — Timeline-Anti-Flood (verbose PO-Turns)

> Owner: UIUX-Designer · Design-only, **kein Bau** · Stand develop `c9aa1a5f`.
> **Kontext, nicht Scope:** `CYP-787` (Server: `op-po`-Kanal-Seed + ACL) und `CYP-788` (Client:
> PO-Composer → `op-po`-Inbound) sind der **Schreib-Pfad** (Operator → PO). Dieses Dokument ist die
> **Lese-/Render-Seite** (PO-Antwort **im** op-po-Fenster) — ein **Geschwister-Anliegen**, kein Teil
> von 787/788. **Es braucht ein eigenes Bau-Ticket (PO schneidet es).** PL-Hinweis: PO-Turn-Output
> ist **verbose** (Worker-Koordinations-Interna) → das Timeline-Rendering darf **nicht fluten**.
> Verwandt: `CYP-738 §4` (Pre-Read) · `A11Y-ANNOUNCEMENTS.md` · das safe-but-silent-Prinzip (§4a).

---

## 1. Wo das flutet (am Code geerdet)

Das op-po-Fenster ist das **AgentWindow des PO-Agenten**: der Operator sieht die PO-Antwort als den
**`AgentEvent`-Transkript-Strom** des PO — gerendert von `AgentTranscript` (`AgentWindow.kt:715`), einer
**flachen `LazyColumn`** ohne jede Gruppierung/Kollaps. Jeder Event ist eine eigene Zeile:
`AssistantText` · `ToolCall` (status RUNNING/OK/ERROR) · `Result` (isError) · `UserTurn` ·
`IncomingSystem` · `Notice`.

Ein **PO-Turn** koordiniert Worker: er besteht aus **vielen** `ToolCall`/`Result`-Zeilen (hub-sends,
watches, Status-Abrufe — die „Interna") **plus** dem eigentlichen `AssistantText` (die Antwort an den
Operator). Heute rendert alles davon 1:1 als Zeilen → ein einziger PO-Turn kann das Fenster mit
Koordinations-Rauschen fluten, und die **eine Zeile, die der Operator wollte** — die Antwort — geht
darin unter.

> **Das Signal ist die Prosa, das Rauschen sind die Tool-Zeilen.** „Worker-Koordinations-Interna" =
> genau die `ToolCall`/`Result`-Läufe. Der Anti-Flood-Hebel setzt **dort** an — nicht an der Antwort.

---

## 2. Der Hebel: Tool-Läufe falten, Antwort stehen lassen

**Primär — `ToolRunGroup`:** ein zusammenhängender Lauf aus `ToolCall`/`Result`-Events (alles zwischen
zwei `AssistantText`-Blöcken) wird zu **einer** einklappbaren Zusammenfassungs-Zeile:

```
▸  12 Schritte                                        ⟵ eingeklappt (Default, wenn Lauf ≥ Schwelle)
▾  12 Schritte                                        ⟵ ausgeklappt: die 12 Zeilen darunter wie heute
   ✓ hub(send --to frontend …)
   ✓ hub(watch …)
   …
```

**Sekundär — `AssistantText` bleibt ungefaltet.** Die Prosa-Antwort ist das, was der Operator lesen
will; sie wird **nicht** eingeklappt. (Extrem-lange Prosa: optionaler Klapp — §5, bewusst nachrangig.)

**Schwellen-getrieben, nicht PO-Identität-getrieben.** Ein Lauf faltet **nur, wenn er ≥ `RUN_FOLD_THRESHOLD`
Schritte** hat (Vorschlag **4**). Damit:
- rendern **kurze** Läufe (Worker-Fenster, wenige Tool-Calls) **unverändert** — kein Kollaps, wo nichts
  flutet;
- falten sich **die langen** Läufe (überwiegend die PO-Turns) automatisch.

> **Warum Schwelle statt „if agent == po":** Das generalisiert statt zu Sonderfall-Code (mein
> Reuse-Mandat) und passt sich selbst an — der Kollaps greift **wo** geflutet wird, nicht **wer**
> flutet. Will der PO es strikt op-po-only, ist das ein Einzeiler-Gate — aber schwellen-getrieben ist
> ehrlicher und wiederverwendbar. **PO-Weiche, kein Alleingang.**

**Reine View-Ableitung — keine BE-Naht.** Die Gruppierung ist eine **View-seitige Ableitung** über die
bestehende `List<AgentEvent>` (aufeinanderfolgende `ToolCall`/`Result` → ein Lauf). **Kein neuer
`AgentEvent`-Typ, keine Reducer-Änderung, kein Protokoll-Change** — „reines Rendering", wie vom PL
gerahmt. (Gleiche Linie wie `CYP-742`: client-ableitbar, keine BE-Naht.)

---

## 3. Die Ehrlichkeits-Zähne (der Kern — hier lebt meine Lane)

Kollaps/Truncation darf **nie still verbergen**. Falten ist kein Wegwerfen. Drei harte Regeln:

**Zahn 1 — der Kollaps zeigt IMMER die Zahl.** Die eingeklappte Zeile trägt `%1$d Schritte`. Der
Operator sieht **vor dem Aufklappen**, dass — und wie viel — mehr da ist. Ein Kollaps ohne Zahl wäre
genau die stille Verkürzung, die die Wahrheit kappt (safe-but-silent: hat der degradierte/gefaltete
Zustand ein Signal? → **ja, die Zahl**).

**Zahn 2 — ein `ERROR` im Lauf faltet sich NIE lautlos weg.** Enthält der Lauf **irgendeinen**
`ToolCall.status == ERROR` **oder** `Result.isError == true`, dann:
- trägt die eingeklappte Kopfzeile einen **Fehler-Marker** — Glyph `✗` + `%1$d Fehler`, in
  `error`-Rolle (Pre-Read: der Fehler ist am Kopf sichtbar, **ohne** aufzuklappen); **und**
- faltet der Lauf **standardmäßig NICHT** (er kommt **offen** in die Timeline).

> **Warum fail-loud:** Ein fehlgeschlagener Koordinations-Schritt ist die eine Sache, die der Operator
> **nicht** verpassen darf. Ihn in einen zugeklappten „12 Schritte"-Balken zu stecken wäre die
> Pre-Read-Verletzung aus `CYP-738 §4` — zwei Läufe (heiler vs. mit Fehler) sähen eingeklappt
> **identisch** aus, bedeuteten aber Verschiedenes. Der Fehler-Marker + das Nicht-Falten sind der
> Pre-Read-Unterschied.

**Zahn 3 — der streamende Tail wird nie gefaltet.** Solange ein `AssistantText` **inkomplett** streamt
oder der letzte `ToolCall` **RUNNING** ist, bleibt der Tail-Lauf **offen** — man faltet nichts, was
gerade live entsteht (der Operator schaut hin). Gefaltet wird erst, wenn der Lauf **abgeschlossen** ist.
(Respektiert die CYP-393-Tail-Follow-Mechanik: die Zusammenfassungs-Zeile ist **ein** Item, das den
Lauf ersetzt — die Tail-Signatur wächst weiter, das Pinnen bleibt intakt.)

**Nichts wird zerstört.** Aufklappen zeigt die Original-Zeilen **unverändert** (gleiche `ToolCallRow`/
`ResultRow`, gleiche Tags, gleiche Zeit-Gutter). Der Kollaps ist reine Sicht, kein Datenverlust.

---

## 4. a11y

- **Die eingeklappte Kopfzeile** ist ein `expand/collapse`-Control: `Role.Button`,
  `stateDescription` = ausgeklappt/eingeklappt, Tastatur-bedienbar (Enter/Space). Die
  `contentDescription` trägt **Zahl + Fehler-Klausel** als Text (Farbe/Glyph nie alleiniger Träger,
  `COLOR-CODING.md §8`): `a11y_transcript_tool_run_collapsed` = „%1$d Schritte, eingeklappt" (+ bei
  Fehlern „, %2$d davon fehlgeschlagen").
- **Ansage-Dringlichkeit:** Auf-/Zuklappen ist eine **vom Operator ausgelöste** Zustandsänderung an
  einer Fläche, auf die er schaut → **keine** `liveRegion`-Unterbrechung nötig; die `stateDescription`
  am Control genügt (`A11Y-ANNOUNCEMENTS.md`: die Achse ist Aufmerksamkeit — er schaut bereits hin).
  Ein **neu eintreffender Fehler-Lauf** dagegen erscheint unaufgefordert; dessen Ansage folgt der
  bestehenden Transkript-Konvention (kein neuer Live-Region-Kanal hier — der Fehler ist ohnehin
  **offen** sichtbar, Zahn 2).
- **Kein Fokus-Verlust beim Falten:** die Faltung ändert die Item-Zahl der `LazyColumn`; die
  Fokus-/Scroll-Position bleibt an der Kopfzeile verankert (nicht an einer weggefalteten Kindzeile).

---

## 5. Bewusst nachrangig / offene Weichen (keine Alleingänge)

- **Extrem-lange `AssistantText`-Prosa** (ein einzelner riesiger PO-Absatz): optionaler Zeilen-Cap mit
  „mehr anzeigen". **Nachrangig**, weil die Prosa das Signal ist — erst bauen, wenn Prosa real flutet;
  wenn, dann mit sichtbarem „…"+Control (nie stiller Schnitt), streamend nie geklappt.
- **Turn-Header** („PO • HH:mm" als Gruppen-Überschrift pro Turn): **nicht** Kern — der Zeit-Gutter je
  Zeile (CYP-335) leistet die Verortung bereits; ein Turn-Header wäre Redundanz. Erwähnt als spätere
  Option, nicht eingebaut.
- **`RUN_FOLD_THRESHOLD` = 4** ist ein Vorschlag; der genaue Wert ist eine **PO-/Tester-Weiche** nach
  einem Blick auf echte PO-Turn-Längen.
- **Strikt op-po-only vs. schwellen-global:** §2 empfiehlt global-schwellen (reuse); op-po-only ist ein
  Einzeiler-Gate. **PO entscheidet.**

---

## 6. i18n-Keys (bestätigungsreif)

| Key | DE | EN |
|---|---|---|
| `transcript_tool_run_collapsed` | %1$d Schritte | %1$d steps |
| `transcript_tool_run_errors` | %1$d Fehler | %1$d failed |
| `a11y_transcript_tool_run_collapsed` | %1$d Schritte, eingeklappt | %1$d steps, collapsed |
| `a11y_transcript_tool_run_collapsed_with_errors` | %1$d Schritte, eingeklappt, %2$d davon fehlgeschlagen | %1$d steps, collapsed, %2$d failed |
| `a11y_transcript_tool_run_expand` | Schritte anzeigen | Show steps |
| `a11y_transcript_tool_run_collapse` | Schritte einklappen | Collapse steps |

**Wortlaut-Notizen:**
- **„Schritte", nicht „Tool-Calls":** operator-lesbar, werkzeug-agnostisch (dieselbe Haltung wie die
  hub-CLI-Werkzeug-Agnostik der Spec). Der Lauf faltet ≥ 4, also nie „1 Schritt" — Singular-Kante
  entfällt strukturell.
- **„%1$d Fehler" / „failed", nicht „Fehler passiert":** nennt den Zustand, nicht die Schuld
  (dieselbe Disziplin wie `ResultRow` „Turn abgeschlossen" statt „erfolgreich").
- **Verb-Konsistenz** mit dem Bestand: „anzeigen/einklappen" reiht sich in die vorhandene
  Transkript-Sprache (`transcript_*`), kein drittes Vokabular.

---

## 7. testTags

Schema `agent.<agentId>.<element>…` (`AgentViewTags`, `TEST-CONTRACT.md §2`, camelCase, dot-getrennt,
Segment `[A-Za-z0-9-]+`). Der Lauf wird über den **Start-Index** seines ersten Events skopet (stabil,
kollisionsfrei mit den bestehenden `agent.<id>.event.<index>…`-Tags):

| Tag (Vorschlag) | Zweck |
|---|---|
| `agent.<agentId>.toolRun.<startIndex>` | die Zusammenfassungs-/Kopfzeile eines gefalteten Laufs |
| `agent.<agentId>.toolRun.<startIndex>.toggle` | das Auf-/Zuklapp-Control |
| `agent.<agentId>.toolRun.<startIndex>.errors` | der Fehler-Marker am Kopf (nur wenn Lauf ≥1 Fehler) |

**Reuse-geprüft** gegen `AgentViewTags.kt` (real gelesen): `stream/event/…` existieren; `toolRun` ist
net-new und kollidiert mit keinem `element`-Segment. Die Original-Zeilen behalten beim Aufklappen ihre
bestehenden `agent.<id>.event.<index>.toolCall|toolResult`-Tags — der Tester findet sie unverändert.

---

## 8. Aufwand & Empfehlung an PO

| Teil | Aufwand |
|---|---|
| View-Ableitung „Läufe aus aufeinanderfolgenden ToolCall/Result" + `ToolRunGroup`-Composable (Falt-State, Kopfzeile, Fehler-Marker) | **M** |
| Zähne (Zahl / Fehler-fail-loud / Streaming-nie-falten) + a11y + Tags + 6 Keys | in **M** enthalten |
| BE-Naht | **keine** — reine `commonMain`-View |

**Empfehlung:**
- **Tool-Läufe schwellen-gefaltet** (§2), **Antwort-Prosa stehen lassen** — der Flood sitzt im
  Koordinations-Rauschen, nicht in der Antwort.
- **Die drei Zähne sind nicht verhandelbar** (§3): Zahl sichtbar, Fehler fail-loud, Streaming nie
  gefaltet — sonst kappt der Kollaps die Wahrheit.
- **Eigenes Bau-Ticket schneiden** (Lese-Seite, Geschwister zu 787/788) und die zwei Weichen (§5:
  Schwelle-Wert, op-po-only vs. global) mit-entscheiden.
- **§-QA nach Bau** übernehme ich — die drei Zähne + a11y + Tags **sind** die Checkliste.

---

## 9. Self-Validation

- **Flut-Fläche am Code geerdet:** `AgentTranscript` (`AgentWindow.kt:715`) ist eine flache `LazyColumn`
  ohne Gruppierung; die sechs Row-Typen und die Fehler-Signale (`ToolCall.status==ERROR`,
  `Result.isError`) sind real gelesen (`AgentWindow.kt:1015/1049`) — der Fehler-Zahn hängt an
  existierenden Feldern, nicht an erfundenen.
- **„Reines Rendering" verifiziert:** die Gruppierung ist eine View-Ableitung über die vorhandene
  Event-Liste — **kein** `AgentEvent`-Typ, **keine** Reducer-/Protokoll-Änderung, **keine** BE-Naht;
  Scope deckt sich mit dem PL-Rahmen.
- **Streaming-Interaktion bedacht, nicht übersehen:** Zahn 3 respektiert die CYP-393-Tail-Follow-Pin
  (die Summary ist **ein** Item, das den Lauf ersetzt → Tail-Signatur wächst weiter).
- **Pre-Read-Regel angewandt** (`CYP-738 §4`): heiler vs. fehlerhafter Lauf sähen eingeklappt gleich
  aus → Fehler-Marker + Nicht-Falten machen den Unterschied vor dem Lesen.
- **safe-but-silent angewandt** (§4a): der gefaltete Zustand trägt ein Signal (die Zahl) und verbirgt
  keinen Fehler still (Zahn 2) — der Kollaps besteht beide Fragen (produziert Output? / kann ein
  Default Abwesenheit→Anwesenheit drehen?).
- **Scope-Ehrlichkeit:** klar benannt, dass dies **nicht** CYP-787/788 ist (die = Schreib-Pfad),
  sondern die Lese-Seite mit **eigenem** Bau-Ticket — kein Anmaßen fremder Ticket-Scopes.
- **Reuse über Sonderfall:** schwellen-getrieben statt „if po", damit der Kollaps generalisiert;
  op-po-only als PO-Weiche offengelassen, nicht selbst geschnitten.
- **Tags reuse-geprüft** gegen `AgentViewTags.kt`; `toolRun` net-new/kollisionsfrei; Original-Event-Tags
  bleiben beim Aufklappen erhalten.
- **Keine Zeile Bau:** `strings.xml`/Tags-Datei (Dev-Lane) nicht angefasst — dieses Dokument ist die
  Referenz, nicht die Quelle.
