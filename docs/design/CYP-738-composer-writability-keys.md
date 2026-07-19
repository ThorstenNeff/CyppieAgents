# CYP-738 — Composer-Schreibbarkeit: Keys, Ton-Mapping & die Pre-Read-Regel

> Owner: UIUX-Designer · Wortlaut bestätigt 2026-07-19 (PO-Anfrage, Dev-Entwurf korrigiert) ·
> Stand develop `2de091b2` · **Copy/Design-Referenz, kein Bau.**
> Feature ist **dormant** — die Strings werden erst nach dem Post-Fenster-Wire-up sichtbar.
> Verwandt: `docs/A11Y-ANNOUNCEMENTS.md` (Ansage-Dringlichkeit) · `COLOR-CODING.md §8` (Glyph statt Ton).

---

## 1. Die drei Zustände

Der Composer kennt **drei** Zustände, nicht zwei. Der dritte ist der, der gern verschwindet:

| Zustand | Bedeutung | Composer |
|---|---|---|
| **schreibbar** | Caller ∈ writable-Set | normal |
| **nicht schreibbar** | Caller ∉ writable-Set — **geprüft und verneint** | read-only + Hint ① |
| **unbestimmt** | Schreibrecht **nicht ermittelbar** (Endpunkt-Fehler / pre-deploy) | disabled + Hint ② |

**„Unbestimmt" ist kein Unterfall von „nicht schreibbar".** Im einen Fall wissen wir, dass der Nutzer
nicht darf; im anderen wissen wir **nichts** und sperren vorsichtshalber. Beides führt zum gesperrten
Composer — und genau deshalb ist die Verwechslungsgefahr strukturell.

---

## 2. Keys (bestätigter Wortlaut)

| Key | DE | EN |
|---|---|---|
| `agent_composer_readonly_hint` | Nur lesend — für diesen Agenten hast du kein Schreibrecht. | Read-only — you don't have write access for this agent. |
| `agent_composer_unknown_hint` | Schreibrecht ließ sich nicht prüfen — Senden ist sicherheitshalber gesperrt. | Write permission couldn't be checked — sending is blocked as a precaution. |

**Wortlaut-Begründung:**
- **① nennt die Ursache statt sie zu wiederholen.** Der Erstentwurf („Nur lesend — du darfst diesem
  Agenten keine Nachricht senden.") sagte dieselbe Tatsache zweimal und den **Grund** nicht. Jetzt: Folge
  einmal, dann die Ursache (**kein Schreibrecht**). **Keine Handlungszeile** — der Nutzer kann sich selbst
  kein Recht geben, und eine Handlung zu erfinden wäre unehrlich.
  *Optionale Erweiterung (Dev-Call, PO durchgereicht): „… — ein Operator kann es freischalten." Wahr
  (ACL-Matrix ist operator-gated) und nennt den realen Weg; kostet Zeilenlänge.*
- **② behält „nicht geprüft" und behauptet keine Ablehnung.** Das ist die schwierige Hälfte und stand im
  Entwurf bereits richtig.
- ⚠ **Verworfen: „vorübergehend deaktiviert" / „temporarily disabled".** Eine der beiden Ursachen ist
  **pre-deploy** — dort hält die Sperre, bis jemand deployt, also **potenziell unbegrenzt**. „Vorübergehend"
  sagt „warte kurz", und der Nutzer wartet auf ein Ereignis, das von selbst nie eintritt. **Dieselbe Klasse
  wie `store_migrating`** („temporarily rejected — retry after the switch") bei Alt-Bindungen, CYP-730.
  **Regel daraus: eine Auflösungs-Zusage nur geben, wenn die Auflösung garantiert eintritt.**
- **„sicherheitshalber gesperrt"** rahmt fail-closed als **Schutz-Voreinstellung**, nicht als Urteil über
  den Nutzer — sonst liest sich die Sperre wie Misstrauen. Bewusst **dieselbe Wendung** wie
  `migration_legacy_action` („Vorsorglich gesperrt", CYP-730): gleiche Situationsklasse, gleiches
  Vokabular, kein drittes Wort für dieselbe Sache.

**Ansage-Dringlichkeit:** beide **`Polite`** — Anfangszustand einer gerade geöffneten Fläche, nicht das
Ergebnis einer abgeschickten Aktion (`A11Y-ANNOUNCEMENTS.md §1`).

---

## 3. Ton-Mapping — und warum es nicht Geschmack ist

| Hint | Ton | Glyph | Farbrolle |
|---|---|---|---|
| ① nicht schreibbar | **`HintTone.GATED`** | `·` | `onSurfaceVariant` |
| ② unbestimmt | **`HintTone.ERROR`** | `✕` | `error` |

**② ist `ERROR`, obwohl der Nutzer nichts falsch gemacht hat.** Der Ton beschreibt **den Zustand des
Systems**, nicht die Schuld des Nutzers: bei ② **funktioniert tatsächlich etwas nicht** (der Endpunkt
antwortet nicht / existiert noch nicht). `GATED` hieße „du darfst nicht" — das ist die Aussage von ①, und
sie wäre bei ② **unwahr**, weil wir es gar nicht wissen.

> Verfügbares Vokabular ist `HintTone = { EFFECT_DEFERRED, GATED, INFO, ERROR }` (`ui/TonedHint.kt:32`) —
> **kein `WARN`.** ② könnte inhaltlich ein Warnzustand sein; da es den Ton nicht gibt, ist `ERROR` die
> nächstliegende **wahre** Wahl (etwas ist kaputt), und sie erfüllt zugleich die Unterscheidungs-Anforderung
> aus §4. `INFO` wäre zu ruhig für „wir konnten es nicht prüfen".

---

## 4. Die Pre-Read-Regel (verallgemeinerbar)

> **Wenn zwei Zustände nicht verwechselt werden dürfen, muss der Unterschied in der Schicht liegen, die
> **vor** dem Lesen wahrgenommen wird — Glyph, Farbe, Form. Ein Unterschied, der nur im Fließtext steht,
> existiert für den überfliegenden Blick nicht.**

**Warum das hier trägt:** ① und ② führen zum **optisch identischen** Ergebnis — gesperrter Composer plus
eine Hint-Zeile. Tragen beide denselben Ton, unterscheidet sie **ausschließlich** der Satzinhalt. Wer die
Fläche überfliegt (der Normalfall bei einem Zustand, den man schon zu kennen glaubt), sieht zweimal
dasselbe Muster und liest den zweiten Fall als den ersten: **„unbestimmt" wird zu „abgelehnt".**

Das ist die **sichtbare Hälfte** der ratifizierten safe-but-silent-Regel:
- Die safe-but-silent-Regel fragt: *hat der degradierte Zustand überhaupt ein Signal?*
- Die Pre-Read-Regel fragt: *ist dieses Signal von seinem harmlosen Nachbarn **unterscheidbar, bevor man
  liest**?*

Ein Zustand kann die erste Frage bestehen und an der zweiten scheitern — genau das wäre hier passiert:
korrekte, ehrliche Copy in einem Ton, der sie mit dem Nachbarzustand verschmelzen lässt.

**Anwendung (zwei Fragen, wie bei §4a):**
1. **Gibt es einen zweiten Zustand, der zur selben sichtbaren Fläche führt?** (Hier: beide sperren den
   Composer.) Wenn ja →
2. **Unterscheiden sie sich in Glyph oder Farbrolle — nicht nur im Text?** Wenn nein, ist die
   Unterscheidung nur nominell vorhanden.

**Grenze, damit die Regel nicht überdehnt wird:** sie verlangt **nicht**, dass jeder Zustand eine eigene
Farbe bekommt. Sie greift **nur**, wenn zwei Zustände **dieselbe sichtbare Konsequenz** haben und
**verschiedene Wahrheiten** bedeuten. Zustände, die ohnehin verschieden aussehen (z.B. Composer aktiv vs
gesperrt), brauchen nichts.

**Präzedenzfälle im Produkt** (dieselbe Form, verschieden gelöst):
- `CYP-730` — `READ_ONLY` aus Migrationsfenster vs Alt-Bindung: gelöst über **Farbe** (erwarteter Vorgang
  ohne Amber, Handlungsfall mit `▲`) plus getrennte Labels.
- `CYP-727` — Ladefehler vs leeres Inventar: gelöst über eine **eigene Fläche** (`LoadErrorRetry` statt
  Nichts), nicht über einen Textzusatz.
- `CYP-738` (hier) — nicht schreibbar vs unbestimmt: gelöst über den **Ton** (`·` vs `✕`).

> Drei verschiedene Mittel, eine Anforderung: **der Unterschied muss vor dem Lesen ankommen.**

---

## 5. Status & Grenze

- **Wortlaut + Ton-Mapping sind bestätigt**; der optionale Operator-Zusatz für ① ist **Dev-Call**
  (vom PO durchgereicht).
- **Ich fasse `strings.xml` nicht an** (geteilte Datei, Dev-Lane) — dieses Dokument ist die Referenz,
  nicht die Quelle.
- **Die Pre-Read-Regel steht hier bewusst in einem Feature-Dokument**, nicht in der Design-System-Ebene:
  sie ist an **drei** Fällen belegt (§4), aber neu. **Vorschlag, kein Alleingang:** bewährt sie sich über
  die vom PO genannten Folgefälle hinaus, gehört sie in die Design-System-Doku — Nachbarschaft
  `A11Y-ANNOUNCEMENTS.md` / `COLOR-CODING.md §8`. **Entscheid PO/PL, ich schneide das nicht selbst.**

---

## 6. Self-Validation

- **Ton-Vokabular am Code geprüft**, nicht angenommen: `HintTone = {EFFECT_DEFERRED, GATED, INFO, ERROR}`
  (`ui/TonedHint.kt:32`), Glyphen `!` / `·` / `i` / `✕` (`:80-85`) — **kein `WARN`**; die Wahl von `ERROR`
  für ② ist deshalb explizit als *nächstliegende wahre* Option begründet, nicht als Idealfall.
- **Der verworfene Entwurf ist mit Grund dokumentiert** („vorübergehend"), damit die Kürzung nicht später
  als Stilfrage zurückgedreht wird — dieselbe Vorsorge wie bei `migration_legacy_action` (CYP-730).
- **Vokabular-Reuse belegt:** „sicherheitshalber/Vorsorglich gesperrt" ist bewusst aus `CYP-730`
  übernommen; kein drittes Wort für dieselbe Situationsklasse.
- **Die neue Regel ist an drei bestehenden Fällen belegt** (§4), nicht an einem — und mit **drei
  verschiedenen** Lösungsmitteln, was zeigt, dass sie eine Anforderung beschreibt und keine Technik
  vorschreibt.
- **Grenze der Regel ausdrücklich benannt** (§4), damit sie nicht zu „jeder Zustand braucht eine eigene
  Farbe" verkommt — dieselbe Verengungs-Disziplin wie bei der safe-but-silent-Grenze.
- **Kein Alleingang bei der Beförderung:** §5 schlägt die Design-System-Ebene vor und überlässt den
  Entscheid dem PO/PL.
