# QA-Abnahme CYP-335 — Endstand `d1bcf24`

> Prüfling: `feature/CYP-335-agent-transcript-timestamps` @ **`d1bcf24`** (Branch-Spitze; Mutationsbefunde gegen `a52bb4d`)
> Prüfer: QA / Test Engineer (Team2) · 2026-07-10
> Vorgänger-Bericht: `docs/QA-REPORT-CYP-335.md` (gegen `6ab6e08`) — inhaltlich weiterhin gültig.

## Urteil

**Abnahme empfohlen.** Alle Gate-Läufe grün, alle vier geforderten Mutationen rot, jede an genau der Stelle,
an der sie rot werden soll. Der Produktivcode ist nach meiner Prüfung korrekt. Meine beiden offenen Befunde
(CYP-342, CYP-343) sind verifiziert geschlossen; der dritte (CYP-346) ist durch den Ausbau behoben.

**Ein Buchführungs-Befund bleibt offen** und ist keine Kleinigkeit — siehe §5.

---

## 1. Gate — die Kommandos, nicht „Tests grün"

Das Gate, abschließend, **nackt gefahren**: ohne `TZ=`-Präfix, ohne `--rerun-tasks`. Zahlen gegen **`d1bcf24`**
(Branch-Spitze inkl. `jsTest`-Quellsatz und meiner zwei übernommenen Zähne).

| Kommando | Ergebnis |
|---|---|
| `./gradlew :core:jvmTest` | **119 Tests · 0 rot** |
| `./gradlew :app:shared:jvmTest` | **765 Tests · 0 rot** |
| `./gradlew :app:shared:wasmJsBrowserTest` | **172 Tests · 0 rot** |
| `./gradlew :app:shared:jsBrowserTest` | **164 Tests · 0 rot** |
| | **1101 Tests · 0 rot** |

**Der nackte Befehl belegt, was er behauptet** — kein `--rerun-tasks`, keine Umgebungsvariable.

*(Zusätzlich, nicht Teil des Gates: `compileKotlinIosSimulatorArm64` grün — das Target darf nicht brechen,
ausgeführt wird es nicht, siehe §6.1.)*

> Die Mutations- und Ausbau-Befunde in §2 wurden gegen **`a52bb4d`** erhoben; `d1bcf24` fügt nur Tests und
> Kommentare hinzu, keinen Produktivcode außer dem `jsTest`-Quellsatz und dem iOS-KDoc. Die Zahlen für
> `jvmTest`/`wasmJsBrowserTest` sind auf beiden Ständen identisch.

### Zone als Build-Input (CYP-342)

| Aktion | Ergebnis |
|---|---|
| unveränderter erneuter Lauf | `UP-TO-DATE` — korrekt, die Zone lebt im Build |
| Zone in `karma.config.d/timezone.js` geändert | **Task lief erneut** — der Input greift |

`process.env.TZ` wird **im Karma-Prozess** gesetzt, nicht als Task-Environment. Damit schlägt der Pin die
Aufrufumgebung: ein vorangestelltes `TZ=UTC` kann ihn nicht aushebeln (gegen `f41d296` gemessen). Das ist mehr,
als CYP-342 forderte.

---

## 2. Mutationen — welche rot wurde

### CYP-343 (wasm, nacktes Kommando)

| Mutation in `TranscriptTime.wasmJs.kt` | rot |
|---|---|
| Vorzeichen entfernt | **1** — `browserOffset_matchesTheBrowsersOwnWallClock_toTheMinute` |
| 30-Minuten-Komponente gestrichen | **1** — derselbe Test |

Die zweite ist die Gegenprobe, die zählt: **dieselbe Mutation entwischte vor dem Fix unter `America/St_Johns`
vollständig** (167 Tests, 0 rot). Der Test hängt nicht mehr an der Tageszeit.

### CYP-343-Analogon am `js`-`actual` (gegen `d1bcf24`)

Die Lücke, die ich in meiner **eigenen** Abnahme übersehen hatte (§7): `jsBrowserTest` war grün, ohne das
js-`actual` je auszuführen. Developer5 hat einen `jsTest`-Quellsatz angelegt; ich habe ihn nicht gelesen,
sondern mutiert.

| Zustand von `TranscriptTime.js.kt` | js-Tests | rot |
|---|---|---|
| unmutiert | 164 | **0** |
| Vorzeichen entfernt | 164 | **1** — `browserOffset_matchesTheBrowsersOwnWallClock_toTheMinute` |
| Halbstunde abgeschnitten | 164 | **1** — derselbe Test |

**Ein Test, der eine Mutation im `actual` fängt, führt es aus.** Und nebenbei belegt: die Zone ist auch im
js-Karma gepinnt — unter UTC hätte die Vorzeichen-Mutation `-0 == 0` gerechnet und wäre grün geblieben.

### Ausbau der Skew-Schätzung (jvm)

**M-SKEW** — event-gespeiste Basis wieder eingebaut (`clientStampMs()` gibt die Zeit der Zeile darüber zurück,
statt `maxOf(now, previous)`): **4 rot**

```
ROT  replayedOldHistory_doesNotBackdateANewTurn          ← der von dir geforderte Zahn
ROT  reloadOnStaleHistory_userTurnIsStampedNow_…         ← mein Zahn aus CYP-346
ROT  reloadOnStaleHistory_turnAndItsReplyAreNotAnHourApart
ROT  cyp346_fastBrowser_invertsTheColumn_replyRendersAboveTheQuestion
```

**M-KLEMME** — Klemme entfernt (roher Client-Stempel, `return now`): **1 rot**

```
ROT  clientClockJumpsBackwards_clientRowsNeverFall
```

Saubere Isolation: genau der Mechanismus, genau sein Test. Beide Mechanismen sind belegt, keiner ist
„vorhanden, aber unbewacht".

### Eine Beobachtung zu M-SKEW, die man kennen muss

`cyp346_fastBrowser_…` wird bei M-SKEW **mit** rot — nicht weil die Mutation gefangen wurde, sondern weil sie
den charakterisierten Dip **beseitigt**. Der Test sagt selbst: „If this now fails, CYP-346 landed."

**Er kann also nicht unterscheiden, ob CYP-346 sauber gelandet ist oder ob jemand heimlich einen Skew
zurückgebaut hat.** Genau dafür existiert `replayedOldHistory_doesNotBackdateANewTurn`, und der KDoc sagt es
auch so. Das Paar funktioniert — aber es funktioniert **nur als Paar**. Wer den einen löscht, macht den anderen
mehrdeutig. Das gehört in den Kommentar an beiden Stellen, nicht nur an einer.

---

## 3. Meine Kontrolltests

`Cyp335SkewReplayTest` (aus `test/CYP-335-skew-teeth`) war gegen `dcbcaf9` der **Nachweis des Defekts** — beide
Tests rot. Gegen `a52bb4d` ist er **grün**. Er ist jetzt der **Nachweis des Fixes**, und er bleibt als
Regressionsklammer sinnvoll: er beschreibt die Eigenschaft („eine jetzt getippte Nachricht trägt die jetzige
Zeit"), nicht den Mechanismus.

`Cyp335TranscriptTimeColumnTest` (Fan-out + Monotonie über die ganze Spalte) ist unverändert grün.

**Empfehlung:** Beide in den Feature-Branch übernehmen. Sie überschneiden sich mit Developer5s Tests, decken
aber jeweils eine Achse, die dessen Tests nicht messen — die gerenderte Spalte bzw. den Stempel bei nachgespielter
Historie.

---

## 4. Gelöschte Tests — geht Deckung verloren?

Developer5 hat zwei Tests gelöscht statt charakterisiert. Ich habe geprüft, ob dadurch eine Eigenschaft
unbewacht bleibt, die nirgends sonst existiert.

| Gelöschter Test | Prüfte | Deckung heute |
|---|---|---|
| `skewIsReEstimated_onEachFreshServerEvent` | den **Mechanismus** Skew-Schätzung | Mechanismus existiert nicht mehr → **kein Verlust** |
| `resolvedToolCall_carryingItsStartTime_doesNotRegressTheSkew` | dass ein re-emittierter `ToolCall` den Skew nicht zurückzieht | Skew weg. Die *darunterliegende* Eigenschaft — „der aufgelöste `ToolCall` trägt die Startzeit" — ist **dreifach** gedeckt: `TranscriptFoldingTest.toolCall_runningToOk/Error_keepsStartTimestamp`, `StreamJsonMapperTest.toolResultFanOut_datesTheTwoRowsDifferently`, `Cyp335TranscriptTimeColumnTest` → **kein Verlust** |

**Beide Löschungen sind korrekt.** Sie entfernen Mechanismus-Tests zusammen mit dem Mechanismus. Hätte man sie
charakterisiert, hätten sie CYP-346 bekämpft — genau die Sorte Test, die den nächsten richtigen Patch rot macht.

---

## 5. Offener Befund: der Ticket-Anker verrottet

`CYP-347` („serverNowMs beim Attach") ist als **Duplikat von CYP-346** geschlossen. Damit ist **CYP-346** der
Tracker für den Server-seitigen Fix.

Aber CYP-346 ist der Bug, den **ich** angelegt habe: „Skew-Schätzer verwechselt Uhrenversatz mit Alter der
Historie". **Dieser Defekt ist mit `a52bb4d` behoben.** Wer CYP-346 daraufhin auf `Fertig` setzt, reißt drei
Anker heraus:

* den KDoc in `AgentViewModel` („Tracked as **CYP-346** (`:core` + server)"),
* `cyp346_fastBrowser_invertsTheColumn_…` („When CYP-346 lands …"),
* `cyp346_bootstrapTurn_…` (dito).

Dann zeigen zwei Charakterisierungstests und ein Klassen-Kommentar auf ein geschlossenes Ticket, und der
**akzeptierte** Defekt (schnelle Browser-Uhr invertiert die Spalte) hat keinen Tracker mehr.

**Konkreter Vorschlag — PO-Entscheidung, ich rate nicht:**

1. **CYP-346 bleibt offen** und wird auf den Server-Fix umgewidmet (Titel: „`serverNowMs` beim Attach"), oder
2. CYP-346 wird geschlossen und ein **neues** Ticket trägt den Server-Fix; dann müssen KDoc und die zwei
   Test-Kommentare auf dessen Key umgezogen werden — im selben Commit, sonst ist der Verweis tot.

Ich habe CYP-346 entsprechend kommentiert, aber **nichts transitioniert**.

---

## 6. Scope und Lücken — getrennt geführt

**Team2 arbeitet für den Browser** (Entscheidung des Auftraggebers, 2026-07-10). Das Gate lautet abschließend:

```
:core:jvmTest · :app:shared:jvmTest · :app:shared:wasmJsBrowserTest · :app:shared:jsBrowserTest
```

Es wird kein Emulator, kein Simulator und nichts KVM-gestütztes gestartet.

### 6.1 Außerhalb des Scopes — per Entscheidung, nicht aus Versäumnis

Das sind **keine Lücken**. Sie stehen hier, damit belegt ist, *was* nicht geprüft wird und *warum es unkritisch
ist*. Einmal genannt, nicht als Schuld geführt.

| Target | Status | Warum unkritisch |
|---|---|---|
| **Android** | nicht getestet, außerhalb des Scopes | `TranscriptTime.android.kt` ist — Kommentare und Objektnamen abgezogen — **zeichengleich** mit `TranscriptTime.jvm.kt` (per `diff` verifiziert): dieselbe `TimeZone.getDefault().getOffset(atEpochMs)`, dasselbe `System.currentTimeMillis()`. Der ausgeführte JVM-`actual` deckt denselben Code. |
| **iOS** | kompiliert, `actual` nie ausgeführt | Der `iosTest`-Quellsatz ist da, das Target kompiliert, ausgeführt wurde das `actual` nie — und auf diesem Host kann es niemand. **Host-Mangel, keine fehlende Infrastruktur.** Der iOS-KDoc benennt die Falle explizit: `getTimezoneOffset()` (js/wasm) zählt **hinter** UTC und muss negiert werden, `secondsFromGMTForDate` ist **ost-positiv** und darf es nicht. Gegenläufige Konventionen, gleich aussehender Code. |

**Drei `actual`s werden ausgeführt: `jvm`, `wasmJs`, `js`.** Jedes ist per **Mutation im `actual`** belegt
(Vorzeichen entfernt → rot; Halbstunde abgeschnitten → rot), nicht per Lektüre.

### 6.2 Echte Lücken — innerhalb des Scopes

| ID | Lücke |
|---|---|
| L1 | Der **zweite** Verbindungsverlust ist live nicht reproduzierbar (`catch` außerhalb der `collect`-Schleife). Belegt nur auf Fold-Ebene. |
| L2 | AC 3 (Reconnect) ist nur auf der **JVM** bewiesen — embedded Ktor ist `jvmTest`-only. Analogie, kein Browser-Beweis. |
| L3 | Der `eventTime`-Tag ist nur auf der JVM per `onNodeWithTag` belegt; der Browser-Render-Test nutzt `onNodeWithText`. |
| L6 | **DST-Wechsel** ist per Ticket außerhalb des Scopes und ungetestet. |
| L7 | **Akzeptierter Defekt:** eine vorgehende Browser-Uhr invertiert die Spalte (Antwort unter der Frage, mit früherer Zeit). Bewusst in Kauf genommen, charakterisiert, getrackt (CYP-346, §5). |

### 6.3 Was hier stand — und warum es weg ist

Ich hatte „Android unkompiliert und ungetestet" und „iOS nur kompiliert" als **Lücken** geführt: als Schulden,
die jemand mit dem richtigen SDK begleichen müsste. Das war zweimal falsch — in der **Gewichtung** (Android
teilt sich den Code mit dem ausgeführten JVM-`actual`) und in der **Rahmung** (beides liegt außerhalb des
Scopes).

**Das `js`-`actual` war die einzige echte Lücke dieser Liste — und sie ist geschlossen.** Geschlossen, weil eine
Mutation *im `actual`* den Test rot macht, nicht weil ein Target grün war. Genau dieser Unterschied ist der
Gegenstand des ganzen Tickets.

---

## 7. Die Lehre, die ich mir selbst schreibe

Ich habe die Monotonie-Invariante („die Zeitspalte fällt nie") als **den Test, der beißt** in den Testplan
geschrieben. Sie hat den schwersten Fehler dieser Story **nicht** gesehen: bei nachgespielter Historie stieg die
Spalte brav, während jede client-geborene Zeile um Stunden zurückdatiert war.

> **Die Invariante ist notwendig, nicht hinreichend.** Sie prüft die *Ordnung*, nicht den *Stempel*.
> Ordnungsaussagen gehören **zusätzlich** zu Wertaussagen, nie an deren Stelle.

Derselbe Fehler in anderer Kleidung: eine `assertNotVisible` prüft Abwesenheit, nicht den Grund der Abwesenheit
(CYP-340). Und ein Test, dessen Erwartungswert durch dieselbe Naht läuft, die er prüft, prüft nichts
(`transcriptRow_rendersItsTimestampInTheBrowser`).

**Und eine vierte, die ich in meiner eigenen Abnahme übersehen habe:** `jsBrowserTest` war grün, ohne das
js-`actual` je auszuführen. `platformTranscriptClock()` wurde in genau *einem* Testquellsatz aufgerufen
(`wasmJsTest`); `commonTest` injiziert überall eine Fake-Uhr. Ich habe „Target grün" gelesen und „`actual`
bewiesen" verstanden — und das Target im Gate geführt, als beweise es das `actual`.

Geschlossen ist die Lücke nicht dadurch, dass ein `jsTest`-Quellsatz existiert, sondern dadurch, dass eine
**Mutation im `actual`** den Test rot macht (§2). Der Unterschied ist der ganze Gegenstand dieses Tickets.

Vier Gestalten einer Sache: **eine grüne Zusicherung ist so viel wert wie die Frage, die sie beantwortet.**
Die vierte fand ich an der Stelle, an der sie am unangenehmsten ist — in der eigenen Abnahme.
