# CYP-340 — Audit: „darf nicht sichtbar sein" ohne positiv belegte Vorbedingung

> Prüfer: QA / Test Engineer (Team2) · Stand: 2026-07-10 · Basis: `develop` @ `5c79a79`
> Betrifft **AC 1** (Regel über alle Flows) und **AC 5** (Handlauf-Doku). AC 2–4 sind hier nicht behandelt.

---

## 0. Erst eine Korrektur an meiner eigenen Zählung

Ich habe dem PO „17 `assertNotVisible` über 9 Flows" gemeldet. Es sind **16**. Der 17. `grep`-Treffer ist eine
**Kommentarzeile** in `eventlog-presence-ios.yaml:12`, die das Anti-Muster beschreibt, statt es zu benutzen.

Das ist keine Nebensächlichkeit für ein Ticket, dessen Kern „ein grünes Häkchen aus dem falschen Grund" ist:
Wenn schon meine Zählung eine Kommentarzeile für eine Assertion hält, taugt `grep` nicht als Audit-Werkzeug.
Alle Aussagen unten sind an den Flows **gelesen**, nicht gezählt.

---

## 1. Die Regel (Vorschlag für AC 1)

> **Jede `assertNotVisible`, die eine Eigenschaft der Anwendung behauptet, muss in derselben Flow-Datei von
> mindestens einer vorangehenden `assertVisible` gedeckt sein, die**
>
> 1. **auf einem Knoten steht, der genau in dem Zustand existiert, dessen Abwesenheits-Eigenschaft behauptet
>    wird — und**
> 2. **fehlschlägt, wenn die App in irgendeinem anderen Zustand ist** (nicht gestartet, Login-Screen, falsches
>    Layout-Regime, falsche Seite).
>
> Fehlt diese Deckung, ist die Assertion **vakuum-fähig**: sie besteht auch dann, wenn der behauptete Mechanismus
> gar nicht existiert.

**Zwei Ergänzungen, die ich aus den Flows selbst gelernt habe:**

* **Die Deckung muss als solche gekennzeichnet sein.** In `eventlog-presence-web.yaml` trägt `window.host` die
  Beweislast des ganzen Flows — aber nur ein Kommentar sagt das. Wer die Zeile beim Aufräumen entfernt, nimmt
  dem Flow seine Sicherheitsaussage, ohne dass etwas rot wird.
* **Es gibt eine zweite Vakuumklasse, die die Regel nicht abdeckt:** das *Regime*. `eventlog-presence-ios.yaml`
  behandelt sie vorbildlich — ein `assertNotVisible: phonePager.page.<id>` besteht auf einem Lazy-Pager
  **trivial** für jede nicht-aktuelle Seite. Der Flow beweist deshalb zuerst, dass er im **Dots-Regime** ist
  (`dot.comm` vorhanden **und** `indicator.position` abwesend), und erst dann ist die Dot-Abwesenheit ein Beweis.
  Diese Datei ist der Maßstab, an dem die anderen zu messen sind, nicht der Sünder.

---

## 2. Klassifikation aller 16 Assertions

| # | Flow : Zeile | Behauptung | Deckung | Urteil |
|---|---|---|---|---|
| 1 | `comm-pane-collapse-android:42` | `comm.channelList` nicht neben der Konversation | `assertVisible comm.timeline` + `comm.back` nach `tapOn demo.tab.comm` | ✅ gedeckt |
| 2 | `comm-pane-collapse-android:52` | `comm.timeline` nicht mehr komponiert | `tapOn comm.back` + `assertVisible comm.channelList` | ✅ gedeckt |
| 3 | `eventlog-tail-android:47` | `liveIndicator` weg, während pausiert | `tapOn pauseToggle` + `assertVisible pausedIndicator` | ✅ gedeckt |
| 4 | `eventlog-tail-ios:48` | dito | dito | ✅ gedeckt |
| 5 | `eventlog-tail-web:43` | dito | dito | ✅ gedeckt |
| 6 | `phone-pager-android:38` | `window.host` abwesend im Pager-Modus | `assertVisible phonePager.pager` (echtes XOR) | ✅ gedeckt |
| 7 | `phone-pager-ios:40` | dito | dito | ✅ gedeckt |
| 8 | `eventlog-presence-ios:53` | `indicator.position` abwesend → Dots-Regime | `assertVisible pager` + `indicator` + `dot.comm` | ✅ gedeckt (ist selbst eine Deckung) |
| 9 | `eventlog-presence-ios:58` | `dot.eventlog` abwesend | die Regime-Deckung aus #8 | ✅ gedeckt — **aber Flow läuft nicht** (§3) |
| 10 | `eventlog-presence-ios:61` | `dot.eventtail` abwesend | dito | ✅ gedeckt — **Flow läuft nicht** |
| 11 | `eventlog-presence-ios:73` | `page.eventlog` abwesend | ausdrücklich als *sekundäres Indiz* gekennzeichnet | ✅ ehrlich deklariert |
| 12 | `eventlog-presence-ios:76` | `page.eventtail` abwesend | dito | ✅ ehrlich deklariert |
| 13 | `eventlog-presence-web:33` | `window.eventlog` abwesend ohne Operator-Token | `assertVisible window.host` + `window.comm` | ⚠️ gedeckt, **Deckung unerreichbar** (§3) |
| 14 | `eventlog-presence-web:36` | `window.eventtail` abwesend | dito | ⚠️ dito |
| 15 | `eventlog-presence-android:45` | `window.eventlog` abwesend | dito | ⚠️ dito |
| 16 | `eventlog-presence-android:48` | `window.eventtail` abwesend | dito | ⚠️ dito |

**Ergebnis der Klassifikation:** **Keine einzige** der 16 Assertions ist strukturell ungedeckt. Es gibt in
diesem Repo kein `assertNotVisible` ohne vorangehende positive Assertion. Die Autoren haben die Regel bereits
befolgt — sie ist nur nirgends aufgeschrieben und wird von nichts erzwungen.

**Damit ist der Defekt nicht der, den man erwartet.** Die Sicherheitsaussage besteht heute nicht „vakuum grün".
Sie wird **überhaupt nicht ausgewertet**, weil der Flow vorher an seiner eigenen Deckung scheitert.

---

## 3. Der eigentliche Defekt — und er ist größer als das Ticket sagt

### 3.1 Belegt, nicht erschlossen

Ich habe den Prod-Web-Build serviert und angesehen:

```
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun     # liefert webApp.js, <title>KMPCyppieAgents</title>
```

Der Prod-Build bootet in den **Login-Screen** (Screenshot: `assets/cyp340-prod-boot-login.png`).

Dann habe ich den Flow **gefahren**, statt seinen Ausgang zu erschließen:

```
$ maestro test maestro/eventlog-presence-web.yaml

 > Flow CYP-41/42 Event-Log presence — windows omitted without operator token
   Open ${APP_URL_NOOP}... COMPLETED
   Window host rendered... FAILED
   Assertion is false: id: ^window\.host$ is visible

$ echo $?
1
```

**Damit ist belegt:** Der Flow scheitert an **Zeile 25** — also **vor** den beiden `assertNotVisible`. Die
Sicherheitsaussage wird nie ausgewertet. Der Flow ist **rot**, nicht still grün.

Ursache im Code: `App()` wickelt `AgentShell` in `AuthGate` (`App.kt:67`); ohne Session rendert `AuthGate` den
`LoginScreen` (`AuthGate.kt:109`).

**Nebenbefund zu AC 2 („meldet es laut"):** Maestro beendet sich mit **Exit-Code 1**. Ein fehlgeschlagener Flow
ist also technisch nicht still — er ist nur dann still, wenn ihn **niemand fährt**. Ohne CI ist genau das der
Normalzustand. AC 2 ist damit weniger ein Maestro-Problem als ein Prozess-Problem, und die Doku aus AC 5 ist die
einzige Gegenmaßnahme, die greift.

> **Eine Warnung aus eigener Erfahrung, weil sie exakt das Ticket-Muster ist:** Mein erster Screenshot zeigte
> einen fertigen Desktop **mit** Event-Log-Fenstern — scheinbar der Beweis, dass die Ticket-Behauptung falsch
> ist. Tatsächlich lief auf `:8080` noch ein **`webAppDemo`**-Dev-Server aus einem früheren Schritt; mein
> Prod-Task konnte den Port nicht binden. Erst `curl … | grep src=` (`webAppDemo.js` statt `webApp.js`) hat es
> aufgedeckt. Ein grünes Bild aus dem falschen Grund — genau der Fehler, den dieses Ticket bekämpft, in der
> Arbeit dessen, der es bearbeitet. **Die Handlauf-Doku (§5) muss deshalb sagen, wie man prüft, *was* serviert
> wird.**

### 3.2 Das Ticket nennt drei Flows. Es sind sechs.

`App()` — und damit `AuthGate` — ist der Einstiegspunkt **aller drei** Prod-Builds:

| Einstiegspunkt | Datei | Gegatet? |
|---|---|---|
| Web prod | `app/webApp/…/main.kt` → `App()` | **ja** |
| Android prod | `app/androidApp/…/MainActivity.kt` → `App()` | **ja** |
| iOS prod | `app/shared/src/iosMain/…/MainViewController.kt` → `App()` | **ja** |
| Demo (alle Targets) | `EventLogDemoApp()` → `AgentShell()` direkt | nein |

Damit sind **sechs** Flows betroffen, nicht drei:

| Flow | Ziel | Status |
|---|---|---|
| `eventlog-presence-web.yaml` | prod `:app:webApp` | im Ticket |
| `operator-token-web.yaml` | prod `:app:webApp` | im Ticket |
| `smoke-web.yaml` | prod-Build laut Kopf | im Ticket |
| **`eventlog-presence-android.yaml`** | prod `com.tneff.cyppieagents` | **nicht im Ticket** |
| **`operator-token-android.yaml`** | prod `com.tneff.cyppieagents` | **nicht im Ticket** |
| **`eventlog-presence-ios.yaml`** | prod `com.tneff.cyppieagents.KMPCyppieAgents` | **nicht im Ticket** |

Die drei zusätzlichen sind **dieselbe Sicherheitsaussage auf anderen Targets** — Operator-Gating der
Event-Log-Fenster. Sie scheitern aus demselben Grund an derselben Stelle (`window.host` bzw.
`phonePager.pager`). Das Ticket beschreibt die Web-Flows; der Befund ist plattformübergreifend.

Ich habe die Android-/iOS-Flows **nicht ausgeführt** (kein Emulator, kein Simulator auf diesem Host). Die
Aussage folgt aus dem Einstiegspunkt, nicht aus einem Lauf — sie ist **erschlossen, nicht belegt**, und ich
kennzeichne sie so.

---

## 3.3 Der Befund, der alles darüber neu rahmt: **kein Web-Flow kann irgendetwas adressieren**

Beim Bearbeiten von AC 2/AC 3 habe ich die zwei Flows gefahren, die das Ticket als **„unbeschädigt"** führt.
Beide sind **rot — an ihrer ersten Assertion**.

| # | Versuch | Ergebnis |
|---|---|---|
| 1 | `eventlog-tail-web.yaml` gegen `:app:webAppDemo` (Build per `curl` verifiziert) | **exit 1** · `eventTail.stream` nicht sichtbar |
| 2 | `eventlog-browse-web.yaml`, derselbe Build | **exit 1** · `eventBrowse.table` nicht sichtbar |
| 3 | Sonde: `extendedWaitUntil` auf den Tag, **30 s** Geduld | nicht sichtbar |
| 4 | Sonde: `extendedWaitUntil` auf den **Text** „Live-Tail", 30 s | nicht sichtbar |
| 5 | Maestros Fehler-Screenshot | **2942 Bytes, weiß** |
| 6 | Derselbe URL in `google-chrome --headless=new` **und** `--headless` (alt) | **38090 Bytes**, App gerendert, WebGL OK |
| 7 | Derselbe Maestro gegen eine **reine HTML-Seite** | **exit 0**, Text sichtbar |

**Punkt 7 gegen Punkt 4 ist der Schnitt: Maestro-Web funktioniert — es sieht nur Compose nicht.** Maestro treibt
Chromium über Selenium und liest den **DOM**; Compose malt auf wasmJs in ein `<canvas>` und legt keine DOM-Knoten
je Composable an. `enableTestTagsAsResourceId()` ist eine Android-Semantik-Property.

**Konsequenzen:**

* Die Rahmung „drei Flows tot, zwei unbeschädigt" trifft **nicht** zu. **Alle fünf Web-Flows sind unlauffähig.**
* Die Sicherheitsaussage in `eventlog-presence-web` wird nicht deshalb nicht ausgewertet, weil Prod in den Login
  bootet — **auch ihre Deckung** (`window.host`) kann nie sichtbar werden. Der Auth-Gate ist die **zweite**
  Hürde, nicht die erste. §3.1 bleibt richtig und war unvollständig.
* `smoke-web.yaml` behauptet im Kopf: *„a testTag is addressable on the Wasm canvas … Wasm mechanism verified in
  the tester's spike."* **In dieser Umgebung nicht reproduzierbar** — und das ist die Grundannahme des ganzen
  Satzes.

**Was ich nicht behaupte:** dass es *keine* Konfiguration gibt, die es doch ermöglicht (Accessibility-DOM in CMP,
anderer Treiber, andere Maestro-Version). Punkt 5 zeigt zusätzlich, dass in Maestros Browser **gar nichts gemalt**
wurde — vertäglich mit der DOM-Erklärung, aber nicht identisch mit ihr. Das sauber zu trennen braucht einen
Spike. Angelegt als **CYP-352** (blockiert CYP-340).

**Der Ersatz existiert und ist bewiesen:** `runComposeUiTest` unter `wasmJsBrowserTest` (`AvatarWasmRenderSmokeTest`,
CYP-216) läuft in echtem Headless-Chrome mit echter Compose-Semantik; `onNodeWithTag` funktioniert dort. Die
Präsenz-/Omissions-Aussage lässt sich dort **stärker** ausdrücken als per Maestro.

---

## 4. Empfehlung zu AC 1–3

1. **Regel dokumentieren** (Text aus §1) — in `maestro/README.md` und im Test-Contract, damit sie eine API ist
   und keine Gewohnheit.
2. **Deckung kennzeichnen, nicht nur kommentieren.** Ein `label:` reicht nicht; ein eigener Kommentar-Marker
   (`# GUARD (load-bearing): …`) direkt über der deckenden `assertVisible` macht den Zusammenhang beim Löschen
   sichtbar. Billig, und es ist das Einzige, was ohne CI überhaupt wirkt.
3. **Prod-Flows den Auth-Gate explizit behandeln** (AC 3): entweder einloggen (der Stub-Repository-Pfad erlaubt
   das) oder den Flow mit einem Tag `needs-login` versehen und aus dem Standardlauf ausschließen. **Still rot ist
   die schlechteste Variante:** ein Flow, den nie jemand grün gesehen hat, wird irgendwann als „bekannt rot"
   ignoriert — und dann ist er kein Gate mehr, sondern Dekoration.
4. **AC 2 („meldet es laut") ist zur Hälfte schon erfüllt** — Maestro liefert Exit-Code 1 (§3.1 gemessen). Was
   fehlt, ist die **Diagnose**: ein gescheitertes `assertVisible: window.host` sieht aus wie jede andere
   fehlende UI. Der `label:`-Text der deckenden Assertion sollte sie tragen — nicht „Window host rendered",
   sondern „VORBEDINGUNG: Shell gerendert (sonst: Login-Screen → Flow braucht Login, siehe README)". Ein Flow,
   den niemand fährt, ist auch mit Exit-Code 1 still; das ist ein Prozess-, kein Werkzeugproblem.

---

## 5. AC 5 — Handlauf: Wie die Web-Flows von Hand gefahren werden

**Auf diesem Host verifiziert** (Chrome 150, Maestro aus `~/.maestro/bin`).

### 5.1 Voraussetzungen
* `maestro` im `PATH` (`export PATH="$HOME/.maestro/bin:$PATH"`).
* Ein Chromium/Chrome für den Web-Treiber.
* **Kein CI** — das ist Absicht (Auftraggeber, 2026-07-10). Diese Doku ist die einzige Instanz, die den
  Flow-Satz am Leben hält.

### 5.2 Welchen Build servieren?

| Flow | Build | Gradle-Task |
|---|---|---|
| `eventlog-browse-web`, `eventlog-tail-web` | **Demo** | `./gradlew :app:webAppDemo:wasmJsBrowserDevelopmentRun` |
| `eventlog-presence-web`, `operator-token-web`, `smoke-web` | **Prod** | `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun` |

Beide binden **denselben Port `8080`**. Sie können nicht gleichzeitig laufen — der zweite Task bindet den Port
nicht und **schlägt nicht laut fehl**.

### 5.3 Prüfen, *was* serviert wird (Pflicht, nicht Kür)

```bash
curl -s http://localhost:8080/ | grep -oE 'src="webApp[A-Za-z]*\.js"'
#  src="webApp.js"      → PROD
#  src="webAppDemo.js"  → DEMO
```

Ohne diesen Schritt fährt man den Flow gegen den falschen Build und liest ein Ergebnis, das nichts bedeutet.
(Mir genau so passiert, §3.1.)

> **Nachtrag:** `scripts/web-boot-smoke.sh` **erzwingt** diesen Check inzwischen — das erwartete Bundle ist ein
> Pflichtargument, fail-closed. Ein Handlauf, der auf Disziplin baut, ist der schwächste Teil eines Wächters.

### 5.4 Flow fahren

```bash
export PATH="$HOME/.maestro/bin:$PATH"
maestro test maestro/eventlog-tail-web.yaml                       # Demo-Build muss laufen
maestro test maestro/smoke-web.yaml -e APP_URL=http://localhost:8080
maestro test maestro/ --include-tags smoke                        # Tag-Auswahl (ohne @)
```

### 5.5 Aufräumen
Der Dev-Server läuft als Gradle-Task im Vordergrund. Beenden über die PID des Tasks —
**nicht** `pkill -f wasmJsBrowserDevelopmentRun`: das Muster trifft auch die eigene Shell-Kommandozeile,
wenn sie den Namen enthält (zweimal passiert, exit 144).

```bash
ps -eo pid,args | grep '[w]asmJsBrowserDevelopmentRun' | awk '{print $1}' | xargs -r kill
```

---

## 6. Was ich **nicht** getan habe

* Die Android- und iOS-Flows **nicht gefahren** — kein Emulator/Simulator auf diesem Host. Ihre Betroffenheit ist
  aus dem Einstiegspunkt erschlossen.
* `eventlog-presence-web.yaml` habe ich **gefahren**; das Ergebnis steht in §3.1 bzw. im Lauf-Protokoll.
* **Nichts repariert.** Dieses Dokument ist eine Analyse, kein Fix. AC 4 hängt an CYP-338/CYP-339.
