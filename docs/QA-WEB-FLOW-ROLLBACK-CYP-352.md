# CYP-352 — Rückbau der Web-Maestro-Flows

> QA / Test Engineer (Team2) · 2026-07-10 · Freigabe durch PO nach belegter Bedingung
> Begleitpapiere: `QA-WEB-TESTING-OPTIONS-CYP-352.md` (Optionen mit Preis) · `QA-MAESTRO-FLOW-AUDIT-CYP-340.md`
> (Regel + Klassifikation)

Dieses Dokument ist der **Anhang**, den man in einem Jahr liest, wenn jemand fragt: *Was haben wir aufgegeben,
und was hatten wir überhaupt?* Es steht hier, nicht in einer Commit-Message.

---

## 1. Was entfernt wurde

Fünf Dateien:

| Datei | Was sie zu prüfen behauptete |
|---|---|
| `maestro/smoke-web.yaml` | testTag auf dem Wasm-Canvas adressierbar; Off-Screen-Scroll |
| `maestro/eventlog-presence-web.yaml` | **Sicherheitsaussage:** ohne Operator-Token keine Event-Log-Fenster |
| `maestro/operator-token-web.yaml` | die andere Hälfte: mit Token sind sie da |
| `maestro/eventlog-browse-web.yaml` | Browse-Tabelle, Filter, Drilldown |
| `maestro/eventlog-tail-web.yaml` | Live-Tail: Pause ≠ Live, Buffered-Count, Resume |

## 2. Warum — der Befund, nicht die Bequemlichkeit

**Kein einziger dieser Flows konnte je etwas adressieren.** Maestro treibt Chromium über Selenium und liest den
**DOM**; Compose Multiplatform malt auf `wasmJs` in ein Canvas und legt keinen DOM-Knoten je Composable an.

Gemessen (CYP-352, sieben Punkte). Der Schnitt sind Punkt 4 gegen Punkt 7:

| # | Versuch | Ergebnis |
|---|---|---|
| 1 | `eventlog-tail-web` gegen den korrekten Demo-Build | exit 1 · `eventTail.stream` nicht sichtbar |
| 2 | `eventlog-browse-web`, derselbe Build | exit 1 · `eventBrowse.table` nicht sichtbar |
| 3 | Sonde auf den Tag, 30 s Geduld | nicht sichtbar |
| 4 | Sonde auf den **Text** „Live-Tail", 30 s | **nicht sichtbar** |
| 5 | Maestros Fehler-Screenshot | 2942 Bytes, weiß |
| 6 | Derselbe URL in headless Chrome | 38090 Bytes, App gerendert, WebGL OK |
| 7 | Derselbe Maestro gegen eine **reine HTML-Seite** | **exit 0**, Text sichtbar |

**Maestro-Web funktioniert. Es sieht nur Compose nicht.**

> **Was nicht bewiesen ist:** der *Mechanismus*. Mein Versuch, den DOM der laufenden App per DevTools-Protokoll
> auszulesen, ist gescheitert — mein Dev-Server war bei einem Lauf bereits beendet, ich habe eine
> Chrome-Fehlerseite vermessen und die Zahlen verworfen. Das **Verhalten** ist bewiesen, der **Mechanismus** ist
> es nicht. Der Unterschied ist der Gegenstand dieses Tickets.

## 3. Die falsche Zusicherung, die alles trug

Im Kopf von `smoke-web.yaml` stand, bis zu seiner Löschung:

> *„(1) a testTag is addressable on the Wasm canvas, and (2) `scrollUntilVisible` reaches an OFF-SCREEN list row
> … Wasm mechanism verified in the tester's spike."*

**Diese Zusicherung war in dieser Umgebung nie reproduzierbar.** Sie verweist auf einen Spike, den niemand
nachfahren kann. Auf ihr ruhten fünf Flows, zwei Tickets und die Überzeugung, das Web-Gate sei abgedeckt.

Sie hat monatelang so ausgesehen, als sei etwas geprüft worden.

**Und der Widerspruch lag die ganze Zeit im Repo, zwei Dateien nebeneinander.** `maestro/README.md` schrieb, im
selben Verzeichnis:

> *„testTags exposed as resource-ids … This is platform-specific … **the Wasm mechanism is still to be confirmed
> with the frontend**, matching Test-Contract v0.5 §0 ‚Wasm-Gating unter Vorbehalt'. A flagged CYP-11 follow-up
> … **not** wired yet."*

Die README sagte „unbestätigt, nicht verdrahtet". Der Flow-Kopf sagte „verifiziert". **Niemand hat die beiden
nebeneinandergelegt** — und der Flow, der es entschieden hätte, konnte nicht laufen.

> **Ein Verweis auf einen Beleg ist kein Beleg.**

## 4. Was den Rückbau überhaupt erlaubt hat

Der Auftraggeber: *„Wenn Compose-eigene Browser-Tests funktionieren, brauchen wir Maestro für Web nicht."* Der
Satz beginnt mit **wenn**. Belegt, bevor eine Datei verschwand:

* **`EventLogPresenceWasmTest`** (`wasmJsTest`) prüft die **einzige sicherheitsrelevante** Aussage des Satzes —
  Operator-Gating — mit positiv belegter Vorbedingung.
  * unmutiert: **147 Tests · 0 rot**, Report vorhanden → er läuft im echten Headless-Chrome.
  * `isOperator = true` (Gating entfernt): **1 rot** — `withoutOperatorToken_omitsEventLogWindows`.
  **Er läuft, und er beißt.** Der Maestro-Flow konnte diese Aussage nicht einmal erreichen.
* **`scripts/web-boot-smoke.sh`** deckt, was `runComposeUiTest` strukturell nicht kann: das **ausgelieferte**
  Artefakt über HTTP. Geprüft an vier Mutationen (§6).

---

## 5. Die Verlustliste — was Maestro-Web **prinzipiell** konnte

Ohne Beschwichtigung. Dass die Flows nie liefen, macht diese Fähigkeiten nicht wertlos — nur ungenutzt.

| # | Fähigkeit | Ersetzt? |
|---|---|---|
| 1 | **Das ausgelieferte Artefakt über HTTP laden** | **ja**, durch `web-boot-smoke.sh` (§6) |
| 2 | Der echte Boot-Pfad (`main()`, `ComposeViewport`, `onWasmReady`) | **teilweise** — der Smoke fährt ihn bis zum ersten gemalten Bild |
| 3 | **Navigation, URL, Reload, Deep-Links, OIDC-Redirect** | **nein** |
| 4 | **Der Auth-Gate über ein echtes Login-Formular** | **nein** (war ohnehin nie geprüft) |
| 5 | Echtes Browser-Chrome: Tabs, Zurück, Zoom, echter Viewport | **nein** |
| 6 | Sicht auf das, was der Nutzer **sieht** (Überdeckung, Z-Order) | **nein** — Semantics ≠ Pixel |

**Wichtig, und beides ist wahr:** Von diesen sechs hat Maestro-Web hier **keine einzige jemals ausgeübt.** Der
Verlust ist ein Verlust an **Möglichkeit**, nicht an Deckung.

### 5.1 Die Inhalte der zwei „unbeschädigten" Flows

`eventlog-browse-web` und `eventlog-tail-web` prüften Browse-Tabelle, Filter, Drilldown und die
Live-Tail-Mechanik (Pause ≠ Live, Buffered-Count, Resume). **Diese Aussagen existieren weiterhin** als
Compose-Tests: `EventBrowsePanelRenderTest`, `EventBrowseA11yTest`, `EventTailPanelRenderTest`.

**Aber sie laufen auf der JVM, nicht im Browser.** Das ist ein echter, kleiner Verlust an Ort — nicht an Aussage.
Wer ihn schließen will, verschiebt diese Tests nach `wasmJsTest`; sie sind reines `runComposeUiTest`.

### 5.2 Backend2s Wächter — doppelt wahr

`smoke-web.yaml` galt als der einzige Wächter dafür, dass die Demo **backendlos** läuft. Gemessen beim Boot des
intakten Demo-Artefakts: **51 fehlgeschlagene Backend-Aufrufe** (`/api/agents`, `/api/channels`, `/api/acl` und
drei WebSockets gegen `:8787`), die App degradiert sichtbar zu „Couldn't load".

1. Der Flow **konnte** es nie prüfen — er sah die Oberfläche nicht.
2. **Die Eigenschaft existiert so nicht** — die Demo *kommt ohne Backend aus*, sie *läuft nicht ohne*.

Verloren geht nicht der Wächter, sondern **die Absicht, einen zu haben**. Der Boot-Smoke ersetzt sie ehrlich: er
meldet die 51 Fehler als **Hinweis** und macht sie nicht rot — sonst wäre er der nächste Test, der aus dem
falschen Grund die Farbe wechselt.

---

## 6. Was der Boot-Smoke deckt — und die vier Restposten

`scripts/web-boot-smoke.sh <URL> <erwartetes-Bundle>`. An Mutationen geprüft, denn ein Skript, das immer grün
ist, deckt nichts:

| Fall | Ergebnis |
|---|---|
| intaktes Artefakt, kein Backend, richtiges Bundle erwartet | **grün** |
| `.wasm`-Asset fehlt | **rot** (`Aborted(both async and sync fetching of the wasm failed)` + 404) |
| `index.html` ohne Bundle | **rot** |
| Seite bootet sauber, **rendert nichts** | **rot** („Bildschirm zeigt nur 1 Farbe") |
| **falsches Bundle serviert** (`webApp.js` erwartet, `webAppDemo.js` da) | **rot** |
| **Bundle-Argument fehlt** | **rot**, fail-closed |

Der vierte Fall ist der Grund für den Screenshot: **Compose malt in ein Canvas, der DOM verrät darüber nichts —
das Bild schon.** Ein Smoke, der nur Konsolenfehler prüft, hätte eine leere Seite grün gemeldet: dieselbe
Krankheit, im Werkzeug, das sie heilen sollte.

Der letzte Fall ist erzwungen, nicht erbeten: **ein Handlauf, der auf Disziplin baut, ist der schwächste Teil
eines Wächters.** Die Disziplin hat bei dem versagt, der die Falle untersucht hat.

### Die vier Restposten, namentlich

1. **Was gemalt wurde, prüft er nicht.** „Mehr als eine Farbe" ist kein Inhalt. **Ein falsches, aber buntes Bild
   besteht.** Das ist die ehrliche Obergrenze eines Pixel-Wächters — **er darf nie für einen Inhaltstest gehalten
   werden.** Inhalt prüft `runComposeUiTest`.
2. **Nur das 25-Sekunden-Fenster.** Ein spät nachgeladenes Asset (Schriften, Bilder eines selten geöffneten
   Panels) fällt heraus.
3. **Nur der Dev-Serve.** Gegen echtes Hosting kämen MIME-Typ für `.wasm`, CSP-Header, Kompression und Caching
   hinzu. Ungeprüft.
4. **Nicht der Prod-Boot hinter dem Auth-Gate.** Ein Login-Screen ist ein gültiger, bunter Boot. Der Smoke sagt
   nichts darüber, ob dahinter etwas ist.

---

## 7. Nicht angefasst, und warum

* **Die Mobile-Flows** (`*-android.yaml`, `*-ios.yaml`) bleiben unverändert. Team2 arbeitet Browser-only; kein
  Emulator, keine KVM. Ihre Betroffenheit durch den Auth-Gate ist **erschlossen, nicht belegt** — drei von ihnen
  zielen auf Prod-Builds, deren Einstiegspunkt `App()` → `AuthGate` ist.
* **Dangling-Verweise:** `eventlog-presence-android.yaml`, `eventlog-browse-android.yaml` und
  `eventlog-tail-android.yaml` nennen in ihren Köpfen die jetzt gelöschten Web-Flows („Native-Android variant
  of …"). Diese Verweise zeigen ins Leere. Ich habe sie **stehen lassen**, weil die Mobile-Flows außerhalb
  meines Auftrags liegen. **Ein Verweis auf eine Datei, die es nicht mehr gibt, ist genau die Sorte Zusicherung,
  von der dieses Ticket handelt** — er gehört bereinigt, von dem, der die Mobile-Flows besitzt.

---

## 8. Die Lehre, in einer Zeile

Sie ist heute in fünf Gestalten aufgetreten — in `foldEvent`, in `assertNotVisible`, in einem Test, dessen
Erwartungswert durch dieselbe Naht lief, die er prüfte, in einem grünen `jsBrowserTest`, der sein `actual` nie
ausführte, und zuletzt in meinem eigenen Boot-Smoke, der eine leere Seite bestanden hätte:

> **Eine grüne Zusicherung ist so viel wert wie die Frage, die sie beantwortet.**
