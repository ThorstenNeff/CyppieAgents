# CYP-396 — Impl-Spec + Test-Definitionen: `UNKNOWN`-Statuspunkt = Ring statt unsichtbarer Scheibe

> Owner: UIUX-Designer (Spec+Verify) · Ticket **CYP-396** (Bug/A11y) · Stand 2026-07-11 · Basis **`b840e135`** (develop-nah)
> Quelle: mein Design `docs/design/cyp351-unknown-vs-error-spec.md` §2/§5 (`feature/CYP-351-…` `3fb852d1`).
> **Docs-only, dev-ready.** Ich implementiere nicht (Rollen-Grenze) — hier die präzise Impl-Spec + Test-Definitionen
> für den zugewiesenen Dev; ich QA das Ergebnis gegen diese Datei.
> **Scope: NUR der Ring-Fix.** Der `ERROR`-Grund-Teil (CYP-351 §4) bleibt getrennt (Backend-Naht), **nicht** koppeln.
> **0 neue Keys, 0 neue Tags, 0 Breitenänderung** (8 dp bleibt 8 dp).

---

## 1. Der Mangel (verifiziert an `b840e135`)

`StatusIndicator` (`app/shared/src/commonMain/kotlin/com/tneff/cyppieagents/agentview/AgentWindow.kt`,
`private fun StatusIndicator(...)`) malt einen gefüllten 8-dp-Punkt:

```kotlin
Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
```

`dotColor` für `UNKNOWN` = `MaterialTheme.colorScheme.outlineVariant` → **1,41:1 hell / 1,52:1 dunkel** auf
`surface`: praktisch unsichtbar (WCAG 1.4.11 verlangt 3:1 für grafische Objekte). Heute nur „erlaubt", weil das
Wort daneben die Bedeutung trägt (Redundanz-Ausnahme, CYP-337) — aber inhaltlich falsch: `UNKNOWN` unterscheidet
sich von `STOPPED` nur durch **Blässe derselben Farbe** (`outline` 3,55 vs `outlineVariant` 1,41). Unwissen ist
keine „schwächere Gewissheit", sondern eine **andere Achse**.

---

## 2. Der Fix — eine **Form**, kein neuer Farbton

`UNKNOWN` = **Ring** (`outline`, 2-dp-Strich, offene Mitte), alle anderen Zustände = gefüllte Scheibe. Größe
bleibt 8 dp (der Header kämpft bei 320 dp um jeden dp, CYP-369) → **null Breitenänderung**.

| Zustand | Form | Farbrolle | Kontrast hell/dunkel |
|---|---|---|---|
| `RUNNING` | Scheibe | `primary` | 7,04 / 9,22 |
| `ERROR` | Scheibe | `error` | 6,54 / 11,09 |
| `STOPPED` | Scheibe | `outline` | 3,55 / 3,63 |
| **`UNKNOWN`** | **Ring** | **`outline`** | **3,55 / 3,63** ✅ (statt 1,41 ❌) |
| `Startet…`/`Neustart…` (pending) | Scheibe | `onSurfaceVariant` | 8,69 / 9,80 |

**Semantischer Reuse (kein neuer Mechanismus):** der Ring = „unbekannt / nicht gemeldet" ist dieselbe Bedeutung,
die der `○`-Glyph im Fidelity-Badge schon trägt (`ConnectorCapabilityViews.kt:84`, `CapabilityStatus.UNAVAILABLE
-> "○"`). Wir übernehmen die **Bedeutung**, rendern aber in der **gezeichneten Form-Familie** des Statuspunkts
(keine Text-Glyphe in den Punkt-Slot — das bräche die 8-dp-Metrik/Baseline).

### 2.1 Empfohlene Struktur — Form/Rolle als **reine, testbare** Entscheidung

Damit die Teeth diskriminieren (Form UND Farbe prüfbar, ohne Pixel-Vergleich), die Punkt-Entscheidung aus der
`@Composable` in eine **reine Funktion** heben (Muster wie `Cyp392ScrollbarStyleTest`, das den Stil als Wert
pinnt statt zu rendern):

```kotlin
// commonMain, neben StatusIndicator (internal → jvmTest sichtbar)
internal enum class StatusDotShape { FILL, RING }
internal enum class StatusDotRole  { PRIMARY, OUTLINE, ERROR, NEUTRAL }   // NEUTRAL = onSurfaceVariant (pending)

/** CYP-396: UNKNOWN ist ein RING (andere Achse als STOPPED), nie eine blasse Scheibe. Rein & testbar. */
internal fun statusDotSpec(state: AgentLifecycleState, pending: Boolean): Pair<StatusDotShape, StatusDotRole> = when {
    pending                              -> StatusDotShape.FILL to StatusDotRole.NEUTRAL
    state == AgentLifecycleState.RUNNING -> StatusDotShape.FILL to StatusDotRole.PRIMARY
    state == AgentLifecycleState.STOPPED -> StatusDotShape.FILL to StatusDotRole.OUTLINE
    state == AgentLifecycleState.ERROR   -> StatusDotShape.FILL to StatusDotRole.ERROR
    state == AgentLifecycleState.UNKNOWN -> StatusDotShape.RING to StatusDotRole.OUTLINE   // ← der Fix
}
```

Die `@Composable` löst nur noch auf (Rolle → `Color`, Form → Modifier):

```kotlin
val (shape, role) = statusDotSpec(state, pending)
val dotColor = when (role) {
    StatusDotRole.PRIMARY -> MaterialTheme.colorScheme.primary
    StatusDotRole.OUTLINE -> MaterialTheme.colorScheme.outline      // war für UNKNOWN: outlineVariant
    StatusDotRole.ERROR   -> MaterialTheme.colorScheme.error
    StatusDotRole.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}
val dotModifier = when (shape) {
    StatusDotShape.FILL -> Modifier.size(8.dp).clip(CircleShape).background(dotColor)
    StatusDotShape.RING -> Modifier.size(8.dp).border(2.dp, dotColor, CircleShape)   // offene Mitte, 8 dp bleibt
}
Box(dotModifier)
```

`import androidx.compose.foundation.border` ergänzen; `CircleShape`/`background`/`clip`/`size` sind bereits im
File. Label/`contentDescription`/Tag/Row bleiben **unverändert** (die `label`- und `a11y_agent_status`-Logik
nicht anfassen).

> Inline-Äquivalente sind ok, **solange `statusDotSpec` als reine Funktion existiert** — die Tests rufen sie auf.
> **Abgelehnt:** Form über `semantics {}` am Punkt testbar machen — das verschmutzt die A11y-Ausgabe mit
> Test-Metadaten. Wertfunktion ist sauberer und folgt dem CYP-392-Präzedenzfall.

---

## 3. Kontrast — schon gerechnet, größtenteils schon gepinnt

Alle Werte **gerechnet** (kein Bild-Vergleich), gegen `MaritimeLight` **und** `MaritimeDark`. `outline` ↔ `surface`
= **3,55 / 3,63** (≥ 3:1 aus eigener Kraft, nicht mehr über Redundanz). Punkt-zu-Punkt `UNKNOWN`↔`ERROR` (jetzt
`outline`↔`error`) = **4,65 / 7,31** — klar trennscharf. Das Paar `outline`↔`surface` ist bereits durch
`ContrastPairGuardTest` (CYP-359) / `Cyp392ScrollbarStyleTest` gepinnt; **kein** neuer Kontrast-Test nötig — die
Kontrast-Zusage entsteht dadurch, dass Test T-A (unten) die **Rolle = OUTLINE** festnagelt und das bestehende
Paar-Gate `outline↔surface ≥ 3:1` garantiert.

---

## 4. Test-Definitionen (jede mit der Mutation, die sie **rot** macht)

Diskriminierend im Sinne von „welche falsche Implementierung ließe der Test durch?" — nicht „rendert es?".

**T-A — Form & Rolle (rein, der Kern-Zahn).** *Neu:* `Cyp396StatusDotSpecTest` (jvmTest, ruft `statusDotSpec`).
- `statusDotSpec(UNKNOWN, pending=false) == RING to OUTLINE`.
- `statusDotSpec(STOPPED, false) == FILL to OUTLINE`; `RUNNING → FILL to PRIMARY`; `ERROR → FILL to ERROR`;
  `pending=true → FILL to NEUTRAL` (für jeden Zustand).
- **Assertion der Trennschärfe:** `spec(UNKNOWN) != spec(STOPPED)` **und** `spec(UNKNOWN) != spec(RUNNING)` —
  in **Form** (Ring vs Scheibe). Das ist die **Nie-Auflösung** auf der Render-Achse: `UNKNOWN` fällt nie auf das
  Aussehen von `STOPPED`/`RUNNING` zusammen.
- **Reddening-Mutationen:** (a) `UNKNOWN → FILL to OUTLINE` ⇒ rot (sähe aus wie `STOPPED`). (b) `UNKNOWN → RING
  to <irgendwas anderes als OUTLINE>` ⇒ rot. (c) Rolle-Auflösung `OUTLINE → outlineVariant` ⇒ rot **zusätzlich**
  über `OutlineTextColorGuardTest`/Paar-Gate (unkatalogisierte `outlineVariant`-Nutzung / 1,41 < 3:1).

**T-B — Render zeigt `UNKNOWN` als `UNKNOWN` (Ehrlichkeit, gerendert).** *Erweitern:* `AgentLifecycleHeaderTest`
(bestehendes Muster: `runComposeUiTest { setContent { MaterialTheme { AgentWindow(…) } } }` mit
`StubAgentLifecycle(mapOf("backend" to AgentLifecycleState.UNKNOWN))`).
- `onNodeWithTag(AgentViewTags.status("backend")).assertExists()` (Status ist **ungated** — auch ohne Operator).
- Der Zustand rendert das **eigene** Label `agent_status_unknown`, **nicht** `_stopped`/`_running`
  (`onNodeWithText` gegen das aufgelöste Label; die Fremd-Labels dürfen **nicht** existieren).
- **Reddening-Mutation:** `UNKNOWN`-Zweig im `label`-`when` auf `agent_status_stopped` zeigen ⇒ rot.

**T-C — a11y-Stimme trennt `UNKNOWN` von `ERROR`.** In T-B oder als Nachbar-Fall (State `UNKNOWN` vs `ERROR`):
`contentDescription` (`a11y_agent_status` mit Zustandslabel) ist für beide **verschieden**.
- **Reddening-Mutation:** beide auf denselben String ⇒ rot. *(Bereits heute grün — als Regressions-Zahn halten,
  damit der Fix die Trennung nicht versehentlich einebnet.)*

> **Scope-Ehrlichkeit — was CYP-396 NICHT prüft:** die *Upstream*-Nie-Auflösung („ein vom **Server** gemeintes
> `UNKNOWN` wird nie zu `STOPPED`/`RUNNING` gemappt, bevor es die UI erreicht", CYP-351 §3) lebt in der
> Lifecycle-State-Zuordnung, **nicht** im Punkt-Renderer. Sie gehört zum CYP-351/Backend-Strang. T-A prüft die
> Nie-Auflösung auf der **Render-Achse** (Aussehen kollabiert nie), nicht den Server-Vertrag. Nicht überschreiben.

---

## 5. Pflicht-Nebenänderung: `OutlineTextColorGuardTest` (sonst bricht das Gate)

`app/shared/src/jvmTest/kotlin/com/tneff/cyppieagents/ui/OutlineTextColorGuardTest.kt` katalogisiert jede
`outline`/`outlineVariant`-Nutzung. Nach dem Fix ändert sich die Quelle → der Katalog **muss** mitziehen, sonst
findet der Guard eine unkatalogisierte Zeile und wird rot:

- **Entfernen** (existiert nicht mehr): der `AgentWindow.kt`-Eintrag
  `"AgentLifecycleState.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant"` (der „1.41:1 … Decorative,
  redundant"-Text).
- **Hinzufügen** (neue Nutzung): der `OUTLINE`-Auflösungspfad des Punkts trägt jetzt auch `UNKNOWN`. Falls der
  Guard die Zeile `StatusDotRole.OUTLINE -> MaterialTheme.colorScheme.outline` (oder das inline-Äquivalent)
  erfasst, Eintrag: *„8dp Status-Punkt/RING (STOPPED=Scheibe, UNKNOWN=Ring); grafisches Objekt WCAG 1.4.11 3:1,
  besteht auf der ZAHL (3,55/3,63 ≥ 3:1), nicht auf Redundanz. Kein Alpha (outline sitzt nahe dem 3:1-Boden —
  CYP-337-Lektion)."*

Der Dev richtet die Katalog-Schlüssel exakt an den finalen Quellzeilen aus (der Guard matcht Strings).

---

## 6. Gate & Abnahme (was ich beim QA prüfe)

**Gate grün:** `:core` + `:app:shared` (`./gradlew :app:shared:jvmTest :core:test` bzw. `check` je nach
Modul-Cut). Kein Web-Bündel nötig — backend-unabhängig.

**Abnahme-Checkliste (QA gegen diese Datei):**
1. `statusDotSpec(UNKNOWN,false) = RING/OUTLINE`; Form ≠ STOPPED/RUNNING (T-A). ⭐ Kern-Zahn.
2. Kontrast: Rolle = `outline` (3,55/3,63), **nie** `outlineVariant`; Paar-Gate grün (T-A + ContrastPairGuard).
3. Gerendert: `UNKNOWN` zeigt eigenes Label, nicht STOPPED/RUNNING (T-B).
4. a11y: `UNKNOWN` ≠ `ERROR` in der Stimme (T-C).
5. `OutlineTextColorGuardTest` aktualisiert, grün (§5).
6. 8 dp unverändert; 0 neue Keys/Tags; Label/Row/`contentDescription`-Logik unangetastet.
7. `RUNNING`/`ERROR`/`STOPPED` optisch unverändert (nur `UNKNOWN` wechselt die Form).

**Nicht in CYP-396:** `ERROR`-Grund-Offenlegung (§CYP-351 §4, Backend-Naht) — getrennt, fail-closed
„Fehler — Grund nicht gemeldet", bis Backend2 die Vertragsfrage beantwortet.

---

## 7. Übergabe

Impl-Spec docs-only auf `feature/CYP-396-ring-fix-spec` (Basis `b840e135`). Der zugewiesene Dev implementiert
auf eigenem Branch gegen diese Datei, Gate grün, SHA an den PO → Review Assist → PO1. Ich stehe für Rückfragen
und den QA-Pass bereit. **Kein Code hier — Spec + Test-Definitionen.**
