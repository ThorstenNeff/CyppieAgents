# QA-Analyse — CYP-330 `restartWithLiveResume_preservesContext_noClear`

> QA / Test Engineer (Team2) · 2026-07-10 · Basis `origin/develop` @ `bc38cbe`
> Auftrag: Zusicherung oder Bequemlichkeit? Und trifft der `delay(1_500)`-Test die CYP-371-Naht?
> **Kein Fix — der Resume-Pfad gehört Backend2, bis CYP-371 steht.**

## Antwort in einem Satz

**Es ist eine Zusicherung ohne Beobachtung — und sie trifft CYP-371 nicht, sie berührt es nicht einmal.**
Der Test ist grün, ohne dass ein Prozess läuft.

## 1. Was er misst — gemessen, nicht gelesen

Instrumentiert (Wegwerf-Sonde), Zwischenstände über das ganze Fenster:

```
t=100ms   entry=live-1   events=0
t=500ms   entry=live-1   events=0
t=1000ms  entry=live-1   events=0
t=1400ms  entry=live-1   events=0
session.close() dauerte 1ms
```

**`events = 0` über die vollen 1,5 s.** Die Session liefert nichts. Es gibt **keine positive Beobachtung**,
dass in diesem Fenster überhaupt etwas lief, das hätte fehlgehen können. Der Eintrag ist von der ersten
Millisekunde `live-1` und bleibt es.

## 2. Die Trennprobe — der Test ist grün OHNE lebenden Prozess

Mutation: `connector.open(...)` ganz entfernt — **kein Prozess wird je gestartet.**

```
restartWithLiveResume OHNE open():   1.502s   GRÜN
  PROBE-330b: entry=live-1 -> der Test ist GRUEN OHNE lebenden Prozess
```

**Er prüft nur, dass eine `InMemorySessionStore`-Map sich nicht selbst leert** — den Wert `live-1`, den der
Test in Zeile *„upsert(...live-1...)"* **selbst hineingeschrieben** hat. Der Erwartungswert läuft durch dieselbe
Naht, die geprüft wird: die Map bestätigt ihren eigenen Inhalt. Das ist die `contrastRatio`-Zwilling-Klasse
(CYP-358), auf der Zeitachse.

Der `delay(1_500)` misst hier nichts. Er ist reine Wartezeit: **Bequemlichkeit im Gewand einer Zusicherung.**

## 3. CYP-371: nicht ausgesessen, sondern verfehlt

`ClaudeCodeSession`:
```kotlin
override fun close()          { readerJob?.cancel();        process.destroy(); ... }   // <- der Test
override suspend fun closeAndAwait() { readerJob?.cancelAndJoin(); process.destroy(); process.awaitTerminated(); ... }
```

Der Deadlock aus CYP-371 sitzt in **`closeAndAwait()`** (`cancelAndJoin` + `awaitTerminated` gegen einen stillen
`readLine()`). **`restartWithLiveResume` ruft `close()`** — `cancel()`, kein Join. Gemessen: **1 ms.**

> Die 1,505 s bedeuten **nicht**, dass der Test den Deadlock aussitzt. Sie sind der nackte `delay(1_500)`.
> Der Test läuft an der Naht **vorbei**, nicht durch sie.

Also **kein** zweiter Zeuge für CYP-371 an dieser Stelle. Der Zeuge ist woanders — §5.

## 4. Der Kontrast: der Nachbar-Test macht es richtig

`restartWithStaleResume_...` (dieselbe Datei) ist die Gegenprobe:

```kotlin
withTimeout(15_000) { while (store.find("default", "backend") != null) delay(50) }   // wartet AUF das Ereignis
assertNull(...)
withTimeout(15_000) { session.sendTurn(UserTurn("hello after restart")) }
assertTrue(seen.any { it is SystemEvent }, "the fresh session bound on the first real turn")   // BEOBACHTET
```

Er **wartet aktiv, bis der Eintrag verschwindet** (kein fixes Fenster, ein `waitUntil`), und belegt mit einem
echten Turn, dass eine frische Session gebunden hat. **Das ist eine Beobachtung.** Der Live-Test daneben ist ihr
vakuöser Spiegel: dieselbe Story, aber „nichts passiert" statt „das Richtige passiert".

## 5. Wo CYP-371 wirklich einen Test bräuchte (Hinweis, nicht Fix)

`closeAndAwait()` wird im Produktivcode gerufen — `ConnectorSession.kt:126`. **Kein Test fährt es gegen einen
stillen, lebenden Prozess.** Genau dort läge der Deadlock. Der `FakeProcess` in `SessionReadonlyWsTest` liefert
seit CYP-372 eine Zeile und blockiert dann — **das ist die Prozessform, die CYP-371 braucht**, an die
`closeAndAwait()` aber noch niemand hält. Das gehört Backend2; ich benenne es, ich baue es nicht.

## 6. Was ein Fix bräuchte (für Backend2, wenn CYP-371 steht)

- Eine **positive Beobachtung im Fenster**: die Session muss ein `init`/`SystemEvent` liefern (der Prozess lebt
  wirklich), *bevor* geprüft wird, dass der Eintrag überlebt. Ohne das ist „nicht gelöscht" bedeutungslos.
- Den `delay(1_500)` durch ein `waitUntil` auf ein **beobachtbares** Signal ersetzen — wie der STALE-Nachbar.
- Der Erwartungswert darf nicht der vom Test selbst gesetzte `live-1` sein, der durch dieselbe Map zurückkommt.

## 7. Severity

| Befund | Grad |
|---|---|
| Test grün ohne lebenden Prozess (Erwartungswert durch die geprüfte Naht) | **Fragilität** — er schützt den Resume-Pfad nicht, den sein Name verspricht |
| `delay(1_500)` ist reine Wartezeit | Fragilität (1,5 s Laufzeit ohne Messwert) |
| CYP-371 ungetestet an `closeAndAwait()` | **Live-Lücke** — aber nicht dieser Tests Schuld; hier nur benannt |

**Der Produktionscode ist nicht das Problem** (der Live-Resume-Pfad mag korrekt sein) — der Test beweist es nur
nicht.
