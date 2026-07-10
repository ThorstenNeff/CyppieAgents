# CYP-352 — Womit prüft man eine Compose-Canvas-App im Browser?

> QA / Test Engineer (Team2) · 2026-07-10 · Basis: `develop` @ `5c79a79`
> Auftrag: Optionen **mit Preis**. Keine Empfehlung ohne Preis, keine Beschwichtigung.

---

## 0. Der Beweis, der dem Rückbau vorausgeht

Der Auftraggeber sagt: *„Wenn Compose-eigene Browser-Tests funktionieren, brauchen wir Maestro für Web nicht."*
Der Satz beginnt mit **wenn**. Also zuerst der Beleg, dann die Optionen.

**`EventLogPresenceWasmTest`** (`app/shared/src/wasmJsTest/…`) prüft die einzige Aussage des ganzen Web-Flow-Satzes,
die eine **Sicherheitsaussage** ist: *Mit Operator-Token sind die zwei Event-Log-Fenster da, ohne Token nicht.*
Mit **positiv belegter Vorbedingung** nach der Regel aus CYP-340: erst `window.host` **und** ein
Nicht-Operator-Fenster (`window.comm`), dann erst die Abwesenheit.

| Lauf | Kommando | Ergebnis |
|---|---|---|
| unmutiert | `./gradlew :app:shared:wasmJsBrowserTest` | **147 Tests · 0 rot**, Report `EventLogPresenceWasmTest` vorhanden |
| **Gating entfernt** (`isOperator = true` in `AgentShell.kt:234`) | dasselbe | **1 rot: `withoutOperatorToken_omitsEventLogWindows`** |

**Beide Hälften belegt: der Test läuft im echten Headless-Chrome, und er beißt.** Das ist mehr, als der
Maestro-Flow je geleistet hat — er konnte die Aussage nicht einmal erreichen.

### Und der Guard ist nicht Zierde — gemessen

Ich habe eine Wegwerf-Sonde in `wasmJsTest` gesetzt, die **nichts** komponiert (`Box {}`) und dann genau die
beiden Abwesenheits-Assertions der Sicherheitsaussage ausführt — **ohne** Deckung:

```kotlin
setContent { MaterialTheme { Box {} } }          // nichts komponiert
onNodeWithTag(WindowTestTags.window("eventlog")).assertDoesNotExist()
onNodeWithTag(WindowTestTags.window("eventtail")).assertDoesNotExist()
```

**Ergebnis: `tests=1 failures=0` — grün.** Die Sicherheitsaussage ist „erfüllt", ohne dass die App existiert.

Das ist die Vakuum-Klasse aus CYP-340, reproduziert im **neuen** Werkzeug. `runComposeUiTest` schützt nicht vor
ihr — **nur die Regel tut das.** Die Sonde ist wieder entfernt; sie war ein Beweis, kein Artefakt.

---

## 1. Der Befund, der die Frage überhaupt stellt

Maestro treibt Chromium über **Selenium** und liest den **DOM**. Compose Multiplatform malt auf `wasmJs` über
`ComposeViewport` in ein Canvas. Gemessen (CYP-352, sieben Punkte): derselbe Maestro sieht eine **reine
HTML-Seite** (`exit 0`) und von unserer App **nichts** — weder `testTag` noch sichtbaren Text, mit 30 s Geduld.

> **Was ich nicht behaupte.** Ich habe versucht, den DOM der *laufenden* App über das DevTools-Protokoll
> auszulesen, um den Mechanismus zu beweisen. Der Versuch ist **gescheitert**: mein Dev-Server war bei einem der
> Läufe bereits beendet, und ich habe eine Chrome-Fehlerseite vermessen. Ich habe die Zahlen deshalb verworfen.
> **Der Mechanismus bleibt unbewiesen; das Verhalten ist bewiesen.** Der Unterschied ist genau der, um den es in
> diesem Ticket geht.

`Modifier.enableTestTagsAsResourceId()` ist eine Android-Semantik-Property. Die JetBrains-Doku kennt
DOM-Knoten je Composable nur für **Compose HTML** (`org.jetbrains.compose.web.dom`) — eine **andere** Bibliothek
als der Canvas-Renderer, den wir benutzen.

---

## 2. Die Optionen, mit Preis

### Option A — `runComposeUiTest` unter `wasmJsBrowserTest` *(bereits im Einsatz)*

**Was es ist.** Compose-eigener UI-Test, ausgeführt in echtem Headless-Chrome über Karma. Läuft heute: 147–172
Tests, darunter `AvatarWasmRenderSmokeTest` (CYP-216, echter Skia-Render) und jetzt
`EventLogPresenceWasmTest`.

**Preis:**
* Kein neues Werkzeug, keine neue Abhängigkeit, kein zweiter Test-Vertrag — dieselben `testTag`s.
* Läuft im Gate, das ohnehin gefahren wird. **Grenzkosten ≈ 0.**
* **Aber:** es ist kein End-to-End-Test. Er komponiert die App **im Testprozess**; er lädt nicht das
  ausgelieferte Artefakt über HTTP.

**Was es nicht kann:** siehe §3.

---

### Option B — Screenshot-Vergleich (Golden Images)

**Was es ist.** Die gerenderte Oberfläche als Bild aufnehmen und gegen ein Referenzbild vergleichen.

**Preis:**
* **Hoch und wiederkehrend.** Golden Images sind gegenüber Font-Rendering, GPU/SwiftShader-Versionen,
  Skia-Updates und Zeitstempeln in der UI (!) instabil. Unser Transkript zeigt **Uhrzeiten** — jedes Bild
  veraltet im Minutentakt, wenn man nicht die Uhr injiziert.
* Braucht eine Toleranz-Strategie, eine Ablage für Referenzbilder und ein Review-Ritual für „legitime"
  Abweichungen.
* **Was es dafür kann:** es prüft, was ein Nutzer *sieht* — Layout, Farbe, Überdeckung. Genau das, was
  `onNodeWithTag` **nicht** prüft: ein Fenster kann im Semantics-Baum existieren und hinter einem anderen liegen.
* Ich habe heute Screenshots benutzt (AC 9 in CYP-335) — als **Beleg für den Menschen**, nicht als Assertion.
  Das halte ich für die richtige Rolle.

**Empfehlung:** nicht als Gate. Als manuelle Evidenz bei Abnahmen behalten.

---

### Option C — Semantics-zu-DOM-Brücke bauen

**Was es ist.** Den Compose-Semantics-Baum in versteckte DOM-Knoten (`aria-*`, `data-testid`) spiegeln, damit
DOM-basierte Treiber (Selenium/Maestro/Playwright) ihn lesen.

**Preis:**
* **Der höchste.** Eigener Code im Produktivpfad, der nur für Tests existiert; er muss den Semantics-Baum
  vollständig und aktuell spiegeln, sonst prüft man eine Kopie, die von der Wahrheit abweicht.
* Genau die Fehlerklasse dieses Tickets in neuer Kleidung: **ein Test, der einen Schatten prüft statt der Sache.**
* Zusätzlich: ob CMP so etwas bereits (experimentell) anbietet, ist **ungeklärt** — die Doku, die ich abgefragt
  habe, gibt es nicht her. Das zu klären ist selbst ein Spike.

**Empfehlung:** nur, wenn §3 einen Verlust zeigt, den nichts anderes deckt. Heute zeigt es das nicht.

---

### Option D — Maestro für Web aufgeben

**Was es ist.** Die fünf Web-Flows entfernen. Ihre eine kritische Aussage lebt in Option A weiter, bewiesen.

**Preis:** die Verlustliste in §3. **Sonst nichts** — die Flows laufen nicht, haben nie gelaufen und können
strukturell nicht laufen. Man verliert kein Gate; man verliert die *Illusion* eines Gates.

---

## 3. Ehrliche Verlustliste — was Maestro-Web **prinzipiell** könnte und Option A nicht

Ohne Beschwichtigung. Dass die Flows nie liefen, macht diese Fähigkeiten nicht wertlos — nur ungenutzt.

| Fähigkeit | `runComposeUiTest` | Bemerkung |
|---|---|---|
| **Das ausgelieferte Artefakt über HTTP laden** | **nein** | Option A komponiert im Testprozess. Ein kaputtes `index.html`, ein fehlendes Asset, ein Wasm-Ladefehler bleiben unbemerkt. **Realer Verlust.** |
| **Der echte Boot-Pfad** (`main()`, `ComposeViewport`, `onWasmReady`) | **nein** | Option A ruft Composables direkt auf. CYP-216 war genau so ein Laufzeitfehler — er wurde nur gefangen, weil `runComposeUiTest` in *echtem* Chrome läuft, nicht weil es den Boot fährt. **Teilverlust.** |
| **Navigation, URL, Reload, Deep-Links** | **nein** | Für den OIDC-Redirect (`Sign in with GitHub`) relevant: er verlässt die Seite. **Realer Verlust.** |
| **Der Auth-Gate über einen echten Login** | **nein** | Option A injiziert `ShellConfig`/Tier direkt. Der Login-Formular-Pfad im Browser bleibt ungetestet. **Realer Verlust** — heute allerdings ohnehin ungetestet. |
| **Browser-Chrome:** Tabs, Zurück-Button, Zoom, Fenstergröße des echten Fensters | **nein** | Fensterbreiten-Logik (Pager/Canvas) wird per `WindowSizeClass` getestet, nicht per echtem Viewport. **Teilverlust.** |
| **Sicht auf das, was der Nutzer sieht** (Überdeckung, Z-Order) | **nein** | Semantics ≠ Pixel. Deckt Option B ab, wenn man sie will. **Realer Verlust.** |

**Wichtig:** Von diesen sechs Fähigkeiten hat Maestro-Web hier **keine einzige jemals ausgeübt.** Der Verlust ist
ein Verlust an *Möglichkeit*, nicht an Deckung. Wer sie zurückwill, braucht ein DOM-fähiges Werkzeug **und**
Option C — oder einen Smoke-Test, der nur den Boot prüft (siehe unten).

**Der billigste Ersatz für den größten Posten:** ein einziger, nicht-Compose-abhängiger Boot-Smoke gegen das
servierte Artefakt — „lade `/`, warte, bis `document.title` steht und kein Konsolenfehler auftrat". Das prüft
Artefakt, Assets und Wasm-Ladepfad, braucht **keinen** Zugriff auf Compose-Interna und ist mit dem vorhandenen
Chrome + einem 20-Zeilen-Skript machbar. Es ersetzt Zeile 1 und 2 der Tabelle, nicht mehr.

---

## 4. Empfehlung

1. **Option A als Gate** — sie trägt die einzige sicherheitsrelevante Aussage, und der Beweis liegt vor (§0).
2. **Option D vollziehen:** die fünf Web-Flows entfernen. Sie sind kein Gate, sie sind ein Versprechen.
3. **Den Boot-Smoke aus §3 nachziehen** (klein), damit Zeile 1 der Verlustliste nicht offenbleibt.
4. **Option B** nur als manuelle Abnahme-Evidenz, nie als Assertion.
5. **Option C nicht bauen**, solange kein Verlust sie erzwingt.

**Und die Regel aus CYP-340 wandert mit:** *Keine Abwesenheits-Assertion ohne positiv belegte Vorbedingung.*
Sie ist nicht an Maestro gebunden. Der bestehende JVM-Test `EventLogPresenceTest.withoutOperatorToken_omitsEventLogWindows`
verletzt sie **heute** — er prüft die Abwesenheit ohne Deckung. Gleiche Vakuum-Klasse, anderes Werkzeug.

---

## 5. Was mit `smoke-web.yaml` verschwindet, und was bleiben muss

Der Kopf der Datei behauptet:

> *„(1) a testTag is addressable on the Wasm canvas … Wasm mechanism verified in the tester's spike."*

**Diese Zusicherung war in dieser Umgebung nie reproduzierbar.** Sie verweist auf einen Spike, den niemand
nachfahren kann, und sie ist die Grundannahme, auf der fünf Flows und zwei Tickets ruhten. Sie verschwindet mit
der Datei — **aber sie gehört in den Bericht**, denn sie ist der Ursprung des Irrtums, nicht seine Fußnote.

Die Lehre ist dieselbe wie in CYP-335, zum fünften Mal:
**Eine grüne Zusicherung ist so viel wert wie die Frage, die sie beantwortet — und ein Verweis auf einen Beleg
ist kein Beleg.**
