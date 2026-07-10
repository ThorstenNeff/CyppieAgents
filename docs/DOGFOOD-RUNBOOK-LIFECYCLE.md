# Dogfood-Runbook — Lifecycle-Kern (CYP-371 · CYP-247 · CYP-368 · Reader-Fix)

> QA / Test Engineer (Team2) · 2026-07-10 · **Manuell, Browser-only. Kein CI.**
> Für Auftraggeber + Dogfood-Team beim Staging-Livegang. **QA-Artefakt — nicht gelandet, nicht am Deploy-Branch.**
>
> Deckt genau die vier Fixe ab: **371** (Stop kehrt zurück, kein Deadlock), **247** (letzte Zeile bleibt,
> kein erfundenes „died"), **368** (zwei Starts = ein Prozess), **Reader-try/catch** (kein IOException-Spam beim
> Teardown).

---

## 0. Voraussetzung & wie man „still" herstellt

- Ein Agentenfenster ist offen, der Agent ist mindestens einmal gestartet.
- **„Stilles `claude`"** = der Agent hat seinen letzten Turn beendet und **wartet auf Eingabe** — er schreibt
  gerade nichts. Das ist der Normalzustand nach einer Antwort. **Genau dieser Zustand** löste den 371-Deadlock
  aus. Wer sichergehen will: eine Nachricht schicken, die Antwort abwarten, **dann** die Stop-Schritte fahren.
- Die Zeitangaben unten sind Beobachtungshilfen, keine harten SLAs. **„Zügig" = ein, zwei Sekunden, kein
  Spinner, der stehen bleibt.**

---

## 1. Start → RUNNING  *(Grundlinie, nicht der Fix — aber die Vorbedingung für alles andere)*

| Schritt | Erwartet (grün) | Rot |
|---|---|---|
| **Start** klicken | Status-Anzeige wird **RUNNING** (blau/„läuft"); das Transkript beginnt zu füllen | bleibt STOPPED/UNKNOWN, oder Spinner hängt |

> Wenn Start schon hängt, **hier stoppen und melden** — die Stop-Tests darunter wären dann nicht aussagekräftig.

---

## 2. Stop gegen ein stilles `claude` → **kehrt zügig zurück**  *(CYP-371, der Kern)*

| Schritt | Erwartet (grün) | Rot |
|---|---|---|
| Agent hat geantwortet und wartet (§0). **Stop** klicken | Der **Stop-Knopf reagiert zügig**: Status wechselt binnen ~1–2 s auf **STOPPED**, kein hängender Spinner | Der Knopf bleibt „busy", der Status wechselt **nicht**, das Fenster wirkt eingefroren — **das ist der 371-Deadlock**, sofort melden |

**Was hier gemessen wird:** vor dem Fix kehrte `POST /stop` gegen einen stillen Prozess **nie** zurück. Der
sichtbare Beweis ist banal — *der Status wird STOPPED und du wartest nicht.* Ein Hänger hier ist der ganze Grund
für den Fix.

---

## 3. Nach dem Stop: **kein erfundenes „died"**, letzte Zeile bleibt  *(CYP-247 + closing-Guard)*

| Schritt | Erwartet (grün) | Rot |
|---|---|---|
| Direkt nach dem Stop die **Timeline / den Event-Log** ansehen | Kein `process.exit`-Ereignis, das den Stop als **Absturz/„agent died"** ausweist. Ein gewollter Stop ist **STOPPED**, nicht ERROR | Ein **WARN/ERROR „process exit"** oder „agent died" erscheint, obwohl **du** gestoppt hast |
| Die **letzte Transkript-Zeile** vor dem Stop ansehen | Sie ist **noch da** — die letzte Ausgabe des Agenten geht durch den Teardown nicht verloren | Die letzte Zeile fehlt / ist abgeschnitten |
| Die **Status-Anzeige** ansehen | **STOPPED**, klar von **ERROR** unterscheidbar (nicht rot) | STOPPED sieht aus wie ERROR, oder zeigt ERROR |

**Zwei Fixe, ein Blick:** Der closing-Guard verhindert, dass ein absichtlicher Stop als Tod gemeldet wird
(371er-Nachbarschaft); der 247-Flush sorgt dafür, dass die **letzte Zeile** vor dem Exit noch ankommt. Beide
sind an der Timeline ablesbar.

---

## 4. Stop → Restart sauber  *(Lifecycle-Rundlauf)*

| Schritt | Erwartet (grün) | Rot |
|---|---|---|
| Nach dem Stop **Start** (oder **Restart**) klicken | Status wird wieder **RUNNING**; ein **neues** Transkript beginnt; der Agent antwortet auf eine frische Nachricht | bleibt STOPPED, oder das alte Transkript „klebt" ohne neue Reaktion |

---

## 5. Zwei schnelle Starts = **genau ein** Prozess  *(CYP-368)*

| Schritt | Erwartet (grün) | Rot |
|---|---|---|
| Bei gestopptem Agenten **zweimal schnell hintereinander Start** klicken (Doppelklick-Tempo) | Es entsteht **ein** laufender Agent, **ein** Status-Wechsel auf RUNNING, **ein** Transkript. Der zweite Klick wird abgewiesen/ignoriert (evtl. „already running") | **zwei** Sessions, doppelte Ausgaben im Transkript, oder zwei RUNNING-Wechsel — das ist der Doppelspawn |

> Wenn die UI Start nach dem ersten Klick sofort ausgraut, ist das **auch grün** — es macht den Doppelspawn
> unmöglich. Beobachtbar ist die **Anzahl** der resultierenden Prozesse (ein Transkript, eine Antwort).

---

## 6. Logs: kein IOException-Spam beim Teardown  *(neuer Reader-try/catch)*

> **Nicht im Browser sichtbar, aber sehr wohl beobachtbar — in der STAGING-Server-Konsole**, in die Team1
> (Staging-Betreiber) und das Dogfood-Team ohnehin schauen. **Genau dieser Log-Spam war der Deploy-Blocker**,
> den Team1s PO gemeldet hat. Ein reiner Browser-Tester **ohne** Konsolen-Zugang überspringt §6 und **vermerkt
> das** — er winkt es nicht grün durch. Team1 **hat** den Zugang; für sie ist §6 ein Pflicht-Check.

| Schritt | Erwartet (grün) | Rot |
|---|---|---|
| Während/nach **Stop** und **Restart** die **Staging-Server-Logs** ansehen | Sauberer Teardown, sauberes STOPPED. **Keine** uncaught Exception beim Reader-Abbau | **`IOException: Stream closed`** (Ursprung `AgentProcess.stdoutLines` — der `useLines`-Reader) bei **jedem** Teardown, ggf. als Stacktrace-Schwall |

**Konkrete rote Signatur:** `java.io.IOException: Stream closed`, geworfen aus dem `stdoutLines`-Reader
(`bufferedReader().useLines`), wenn `destroy()` die Pipe schließt, während der Reader noch liest. Der neue
try/catch fängt genau das ab. **Eine solche Zeile pro Stop = rot.**

> **Der Reader-Fix ist nicht „unbeobachtet".** Er ist **vor** dem Deploy gegatet — Fix-SHA-Read durch
> Team1-PO-Assistent + grünes `:server:test` — und auf Staging nur noch **bestätigt**. §6 ist die
> Staging-Bestätigung, nicht die einzige Absicherung.

---

## 7. Melden

Für jeden roten Punkt an den Koordinator: **Schritt-Nummer · was du gesehen hast · Browser + OS · (bei §6) der
Log-Ausschnitt ohne Secrets.** Ein Screenshot der Timeline/Status-Anzeige reicht für §2–§5.

**Reihenfolge der Wichtigkeit**, falls die Zeit knapp ist:
1. **§2** (Stop kehrt zurück) — der eigentliche 371-Beweis, das Gating-Kriterium.
2. **§3** (kein erfundenes „died", letzte Zeile) — die 247/closing-Zusage.
3. **§5** (ein Prozess) — 368.
4. **§6** (keine IOException) — der Reader-Fix; nur mit Log-Zugang.
5. §4, §1 — Rundlauf/Grundlinie.

---

## 8. Was dieses Runbook **nicht** abdeckt (ehrlich benannt)

- **SIGTERM-taubes `claude`** (ein Prozess, der `destroy()` ignoriert): das ist der **Timeout-Fallback**,
  ein **separates Ticket (CYP-374), nicht Teil dieses Deploys** (dieser Batch = 371 + 368 + Reader-Naht). Ein
  normales `claude` reagiert auf Stop; wer den tauben Fall provozieren will, braucht einen Sonderaufbau — nicht
  Dogfood-Alltag. Nicht in diesem Runbook, nicht in dieser Abnahme.
- **Genaue Latenzzahlen**: „zügig" ist ein Mensch-Urteil. Ein hartes Millisekunden-SLA gehört in einen
  automatisierten Test, nicht in ein manuelles Runbook.
- **Der IOException-Check** ist nur mit Server-Log-Zugang aussagekräftig (§6). Ohne ihn bleibt der Reader-Fix
  im Dogfood **unbeobachtet** — das ist eine Lücke des Browser-only-Rahmens, keine grüne Zusage.
