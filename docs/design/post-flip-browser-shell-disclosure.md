# Nach `CYP-333-flip`: der Browser bekommt einen Schalter, der lügt

> Owner: UIUX-Designer · Stand 2026-07-10 · Basis **`origin/develop` = `bc38cbe`** · Scope **WASM-App**
> Docs-only. **Severity: hoch** — verletzt CYP-317 („no fake switch") auf dem **einzigen ausgelieferten Target**.
> Gefunden beim Nachmessen der Chrome-Zeilen post-flip (CYP-370), nicht gesucht.

---

## 1. Was der Flip getan hat

```kotlin
AgentShell.kt:174   private const val WORKTREE_SHELL_LIVE_ENABLED = true      // vorher false
AgentShell.kt:848   terminalContent = if (WORKTREE_SHELL_LIVE_ENABLED) { { id, m -> TerminalView(session, m) } } else null
AgentShell.kt:856   terminalGatedNote = !WORKTREE_SHELL_LIVE_ENABLED          // ⇒ jetzt IMMER false
```

`terminalContent` ist **`commonMain`** und ruft das `expect fun TerminalView` auf. Es ist damit auf **jedem**
Target `!= null`. Die `ModeToggleRow` leitet daraus ab:

```kotlin
terminalAvailable = terminalContent != null      // "jemand hat ein Composable übergeben"
```

Und das WASM-`actual`:

```kotlin
// wasmJsMain/terminal/TerminalView.wasmJs.kt — CYP-334, compile-only stub
Box(modifier.padding(8.dp)) { Text("Interactive terminal is available on Desktop only.") }
```

---

## 2. Vier Konsequenzen im Browser, in aufsteigender Schwere

**(1) Der Shell-Knopf ist entsperrt und liefert nichts.** Für einen Operator ist das Segment `enabled`, weil
`terminalAvailable = true`. Ein Klick tauscht das Inhaltsrechteck gegen den Platzhalter. **Genau der Zustand,
den CYP-317 ausschließt:** *„a non-operator sees the live mode read-only … never a switch that lies."* Der
Operator sieht jetzt einen Schalter, der lügt.

**(2) Der Hinweistext behauptet die Shell, die nicht da ist.** Im Shell-Modus rendert die Toggle-Zeile

> `terminal_shell_note` = **„bash-Worktree-Shell"**

als *ehrlichen Deskriptor* — direkt über einem Rechteck, in dem steht, dass es kein Terminal gibt. **Der
Deskriptor beschreibt den Desktop und wird im Browser gezeigt.** Eine Offenlegung, die das Gegenteil offenlegt.

**(3) Die einzige ehrliche Auskunft ist unerreichbar geworden.** Der Zweig

```kotlin
terminalGatedNote && !terminalAvailable -> Text(terminal_gated_pending)   // "Shell verfügbar, sobald …"
```

kann **nie mehr** feuern: `terminalGatedNote` ist konstant `false`, und `terminalAvailable` ist konstant `true`.
Der Flip hat den Satz, der für den Browser stimmt, aus dem Baum genommen — und den, der für den Desktop stimmt,
hineingestellt. **Der Kommentar darüber sagt noch, wozu er da war:** *„say WHY the Shell segment is off, rather
than a silently-disabled control."*

**(4) Der Composer verschwindet dabei.** `AgentWindow` rendert die Eingabezeile nur in der
Orchestrierungs-Ansicht (gemessen: `composerVorhanden = 0` im Shell-Modus). Im Browser tauscht der Operator
also seine **einzige** Möglichkeit, mit dem Agenten zu sprechen, gegen einen englischen Platzhaltersatz. Er
kommt zurück — aber der Weg dorthin sieht aus wie ein Defekt.

**Nebenbei:** `„Interactive terminal is available on Desktop only."` ist ein **hartkodiertes englisches
Literal** in `commonMain`-nahem Code. Der deutsche Build zeigt Englisch — dieselbe Lücke wie im
`MessageComposer` (CYP-350 §8).

---

## 3. Die Wurzel, und sie ist eine alte Bekannte

```kotlin
terminalAvailable = terminalContent != null
```

> **`terminalContent != null` heißt „wir haben einen Slot verdrahtet", nicht „es gibt ein Terminal".**
> Ein Stellvertreter, aus dem eigenen Handeln abgeleitet, gelesen als Beobachtung der Welt.

Das ist **Wurzel B** aus dem Beobachtungs-vs-Ableitungs-Audit, wörtlich: dieselbe Form wie
`status[agentId] = RUNNING` („wir haben einen Start befohlen") und `count { LOG_DROPPED }` („wir haben einen
Verlust gemeldet"). *Ein Stellvertreter, der aus dem eigenen Handeln abgeleitet ist, irrt immer zugunsten der
Beruhigung.*

**Der `expect`/`actual`-Mechanismus macht den Stellvertreter systematisch blind:** ein Stub erfüllt den Vertrag
`@Composable (String, Modifier) -> Unit` genauso gut wie ein echtes Terminal. **Der Typ kann nicht sagen, ob
etwas funktioniert.**

---

## 4. Der Fix

**Die Fähigkeit muss deklariert werden, nicht erraten.** Neben `expect fun TerminalView` gehört

```kotlin
expect val TERMINAL_INTERACTIVE: Boolean      // jvm = true · wasmJs = false · ios = false
```

und die Verdrahtung liest **sie**, nicht die Nicht-Nullheit eines Slots:

```kotlin
terminalContent  = if (WORKTREE_SHELL_LIVE_ENABLED && TERMINAL_INTERACTIVE) { … } else null
terminalGatedNote = !TERMINAL_INTERACTIVE || !WORKTREE_SHELL_LIVE_ENABLED
```

Damit gilt im Browser wieder: **Segment deaktiviert, mit Begründung.** Die Begründung ist vorhanden und
lokalisiert (`terminal_gated_pending`), sie ist nur unerreichbar geworden. **0 neue Keys.**

> Falls der Wortlaut nicht mehr passt („sobald das Worktree-Shell-Backend steht" — es steht ja jetzt, nur nicht
> hier), liefere ich einen neuen Schlüssel. Der ehrliche Satz für den Browser lautet sinngemäß: **„Shell nur in
> der Desktop-App"** — eine Eigenschaft des Ziels, keine Verzögerung. Sag Bescheid, dann spezifiziere ich ihn.

**Und der Platzhaltersatz gehört in `strings.xml`**, wenn er überhaupt bleibt. Nach diesem Fix ist er im
Browser unerreichbar — dann kann er ersatzlos weg.

---

## 5. Abnahme

1. **`wasmJsBrowserTest`:** Der Shell-Segment-Knopf ist für einen Operator **`assertIsNotEnabled`**, und
   `modeToggleTerminalGated` ist **vorhanden**. **Mutation:** `TERMINAL_INTERACTIVE = true` auf wasm ⇒ **rot**.
2. **`jvmTest`:** derselbe Knopf ist **enabled**, kein gated-Hinweis. *(Beweist, dass der Guard nicht einfach
   überall „deaktiviert" fordert.)*
3. **Kein Deskriptor ohne Sache:** `modeToggleShellNote` existiert **nur**, wenn ein interaktives Terminal
   gerendert wird. **Mutation:** Note auch im Stub-Fall ⇒ **rot**.
4. **Keine hartkodierten Literale** in `TerminalView.*.kt` — bestehender Quell-Guard-Stil (CYP-303).

**Test 1 ist der einzige, der auf dem ausgelieferten Target läuft.** Die anderen drei schützen ihn davor,
trivial zu werden.

---

## 6. Self-Validation

- **Nicht gesucht, sondern gestolpert** — beim Messen der Toggle-Zeile post-flip. Gemeldet, weil der Auftrag
  „Browser" lautet und der Flip genau dort wirkt.
- **Quellenbeleg statt Vermutung:** `terminalContent` liegt in `commonMain`, das WASM-`actual` ist ein Stub,
  `terminalGatedNote` ist nach dem Flip konstant `false`. Drei Zeilen, kein Ermessen.
- **Ich habe es *nicht* im Browser gerendert.** Der Beleg ist Quelltext + die Zustandsmessung der Toggle-Zeile
  auf der JVM (`shellNote = 1` im Terminal-Modus, `composerVorhanden = 0`). **Die Bestätigung im Browser gehört
  in `wasmJsBrowserTest`** — sie ist Abnahme (§5.1), nicht Beweis dieses Dokuments. *Wer keine Messung hat,
  nennt keine.*
- **Severity ehrlich:** kein Datenverlust, kein Absturz. Aber ein **Schalter, der lügt**, auf dem einzigen
  Target, das wir ausliefern — und die Regel, die das verbietet, ist bereits geschrieben (CYP-317).
- **Docs-only.**
