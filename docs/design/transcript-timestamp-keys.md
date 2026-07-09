# Zeitstempel im Agenten-Transkript — Resource-Keys (CYP-335)

> Owner: UIUX-Designer · Spec: `transcript-timestamp-spec.md` · Basis `develop f8063c1`
> **1 neuer Key. 0 geänderte bestehende Keys.**

---

## 1. Neuer Key

| Key | `values/` (DE, Default) | `values-en/` (EN) | Verwendung |
|---|---|---|---|
| `a11y_transcript_time` | `um %1$s Uhr` | `at %1$s` | `contentDescription` der Zeitzelle jeder Transkriptzeile. `%1$s` = die formatierte Uhrzeit (`HH:mm`, 24 h, führende Null, **lokale Zone**). |

**Ansage bei `09:14`:** DE „um 09:14 Uhr" · EN „at 09:14".

### Warum gelabelt

Nackte Ziffern sind für einen Screenreader wertlos (PO-Frage 5). Die Zelle folgt dem Idiom der Datei —
*sichtbarer Text ≠ gesprochener Text* (`ResultRow:419`, `UserTurnRow:479`): der sichtbare String bleibt
`09:14`, gesprochen wird die gelabelte Fassung.

### Warum abweichend von `a11y_event_row`

`a11y_event_row` (`„%1$s, %2$s, von %3$s, %4$s"`) hängt die Zeit **ungelabelt** als vierte Position an einen
festen Komma-Satz. Im Transkript endete die Beschreibung nach **beliebigem Nutzertext** auf einer nackten
Zahl — „Deine Nachricht: treffen wir uns um 3, 09:14". Das Label trennt Inhalt von Metadatum.
**Bewusste, begründete Abweichung** (Spec §6.1), keine Wording-Drift.

---

## 2. Platzierung

| Datei | Zeile einfügen bei | Nachbarschaft |
|---|---|---|
| `app/shared/src/commonMain/composeResources/values/strings.xml` | bei den `a11y_transcript_*` / `transcript_*`-Keys (dort steht `transcript_system_label`, `a11y_transcript_system`) | Namensfamilie `transcript` |
| `app/shared/src/commonMain/composeResources/values-en/strings.xml` | analog | — |

Namenswahl `a11y_transcript_time` reiht sich exakt in die bestehende Familie ein: `a11y_transcript_system`,
`transcript_system_label`. **Kein Cross-Surface-Reuse** von `event_*` (das gehört dem Event-Log — Key-Drift,
vgl. CYP-51).

---

## 3. Formatierung des Arguments — **nicht** `formatTs`

`%1$s` wird von einem **neuen** `formatClock(ts): String` geliefert (Spec §7.2), **nicht** von
`eventlog/EventVisuals.kt:145 formatTs`.

> `formatTs` ist laut eigenem KDoc **UTC** („UTC wall-clock … No timezone lib in commonMain") und liefert
> `HH:MM:SS.mmm`. Wiederverwendung würde in Deutschland im Sommer **jede Uhrzeit zwei Stunden falsch**
> anzeigen — unsichtbar. `kotlinx-datetime` steht heute nicht im Versionskatalog; die Fähigkeit muss erst
> entstehen (§9-Ask 1).

**Format-Vertrag für `%1$s`:** `HH:mm` · 24 h · führende Null (`09:14`, nicht `9:14`) · **lokale
Browser-Zone** · keine Sekunden · kein Datum.

**Fail-closed:** Ist `ts` unbekannt (`null`), wird die Zelle **leer** gerendert und `a11y_transcript_time`
**gar nicht** formatiert — die Zeile bekommt keine Zeitklausel. Kein `00:00`, kein `--:--`, kein
„Uhrzeit unbekannt"-Rauschen (Spec §6.3).

---

## 4. Locale-Parität & Shared-Key-Sync — **Flag an Dev5**

- Beide Dateien halten heute **466 = 466** `<string>`-Einträge. Nach diesem Key: **467 = 467**.
- Der Key landet **gleichzeitig** in `values/` **und** `values-en/`. Ein DE-only-Merge lässt die EN-App zur
  Laufzeit auf einen fehlenden Key laufen.
- **Das konsumierende Modul (`:app:shared`) muss nach dem Key-Landen re-syncen, sonst bricht ein
  Shared-Check.** Ich liefere den Key deshalb **getimed mit der CYP-335-Impl** auf Zuruf des Koordinators —
  nicht vorab nach `develop`.
- **Lücke im Schutz (§9-Ask 5):** Es existiert **kein** automatischer DE/EN-Paritäts-Guard.
  `CommI18nDisclosureTest` prüft nur das Disclosure-Trio `comm_readonly_hint`/`comm_send_denied`/
  `comm_send_failed`. Ein Key, der nur in einer Locale landet, fällt heute durch **kein** Netz.
  Empfehlung an Tester2: Paritäts-Test (gleiche Key-Menge in beiden `strings.xml`).

---

## 5. Self-Validation

- **1** Key, **2** Locales, **0** geänderte bestehende Keys, **0** neue Wordings außerhalb der
  `transcript`-Familie.
- Positionsbasiertes Argument `%1$s` (Konvention der Datei).
- Disclosure: kein erfundener Zeitwert bei `ts == null`; kein UTC-als-Ortszeit; keine Sekunden/Datum
  entgegen dem Scope.
