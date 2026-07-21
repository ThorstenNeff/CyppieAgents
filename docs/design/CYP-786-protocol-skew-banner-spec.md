# CYP-786 — `PROTOCOL_SKEW`: der benannte Verbindungs-Zustand (Operator-Banner)

> Owner: UIUX-Designer · Design-only, **kein Bau** (Design-Spike CYP-786) · Stand develop `c9aa1a5f`.
> Scope dieses Dokuments = **die UI-Hälfte** des Spikes: der operator-facing Zustand, sein Wording,
> sein Severity-Ton (distinkt von offline/reconnecting) und a11y. Die Backend-Hälfte (WO die
> `schemaVersion` lebt, Connect-Hello-Verdrahtung, Decode-Klassifikations-Safety-Net) ist im Ticket
> als Opt 1 / Opt 2 vom Backend assessiert und **bleibt Backend-Design** — hier nur die Nahtstelle.
> Verwandt: `A11Y-ANNOUNCEMENTS.md` (Ansage-Dringlichkeit) · `CYP-738 §4` (Pre-Read-Regel) ·
> `COLOR-CODING.md §8` (Glyph statt Ton).

---

## 1. Das Problem, in einem Satz

Ein Schema-Skew (Client-DTOs ≠ Server-DTOs) hat heute **keinen benannten Zustand**. Er tritt als
**opake** `MissingFieldException` auf → `ws:comm` bricht → der Reconnect versucht es **endlos erneut**
(jeder Versuch scheitert am selben Feld) → der Composer fällt auf UNKNOWN → „gesperrt", ohne dass der
Operator je erfährt, **warum**. Das andere schlechte Extrem — den Skew still tolerieren — ließe ihn
unbemerkt weiterlaufen.

> **Der Kern ist ein Ehrlichkeits-Kern, kein Kosmetik-Kern.** Der heutige Zustand *lügt durch
> Schweigen*: Er zeigt „verbinde…/getrennt", während in Wahrheit **gar keine Verbindung mehr zustande
> kommen kann**, bis ein Mensch handelt. `PROTOCOL_SKEW` macht genau diese Wahrheit sichtbar und
> benannt — **nie opak, nie still** (das Prinzip des Tickets, hier ins Sichtbare übersetzt).

---

## 2. Die Nahtstelle zum Backend (was ich konsumiere, was ich NICHT entscheide)

Das Banner ist ein **reiner Konsument** eines neuen, terminalen Verbindungs-Zustands. Ich entscheide
**nicht**, wo die `schemaVersion` lebt oder wie sie ausgetauscht wird — das ist die Backend-Hälfte
(Opt 1 Hello-Austausch / Opt 2 Decode-Klassifikation). Ich brauche vom Backend genau dies:

| Ich brauche | Form | Quelle (Backend-Design) |
|---|---|---|
| ein **terminaler** `ConnectionStatus`-Wert `PROTOCOL_SKEW` | Enum-Erweiterung in `CommReducer.ConnectionStatus` (heute `{CONNECTING, LIVE, DISCONNECTED}`) | Opt 1 **und** Opt 2 münden beide hier hinein |
| `clientVersion` | String, **immer bekannt** (der Client kennt seinen eigenen Build) | Client-lokal |
| `serverVersion` | String **oder `null`** | Opt 1 (Hello) liefert sie; Opt 2 (Decode-Fehler) **kennt sie evtl. nicht** |

**Zwei Erzeuger, EIN Zustand.** Opt 1 erkennt den Skew **vor der ersten Message** (Hello-Mismatch),
Opt 2 als **Decode-Klassifikation** (`MissingField` → `SchemaSkewDetected`). Beide erzeugen denselben
`PROTOCOL_SKEW`. Für die UI ist nur der **Unterschied in der Datenlage** relevant: Opt 2 kann ohne
`serverVersion` ankommen → dafür gibt es die **degradierte Copy** (§3.2). Das Banner erfindet **nie**
eine Versionsnummer, die es nicht hat (`null ≠ fabriziert` — Hausregel).

> **Terminal heißt: der Reconnect-Churn hört auf.** Damit das Banner ehrlich ist, muss der Zustand
> den automatischen Wiederverbindungs-Loop **anhalten** (sonst widerspräche „so kommt keine Verbindung
> zustande" dem sichtbar weiterlaufenden Spinner). Das Anhalten ist Backend-Verhalten; das Banner
> **stellt es dar** (§4: statisch, kein Spinner). Aufheben tut den Zustand nur ein **erneut
> erfolgreiches Handshake** nach App-Update **oder** Server-Redeploy — nicht bloßes Warten.

---

## 3. Wording

### 3.1 Vollform (beide Versionen bekannt — Opt-1-Pfad)

| Key | DE | EN |
|---|---|---|
| `comm_status_protocol_skew` | Client-Protokoll %1$s ≠ Server-Protokoll %2$s — App aktualisieren oder Server neu ausrollen. | Client protocol %1$s ≠ server protocol %2$s — update the app or redeploy the server. |

### 3.2 Degradierte Form (Server-Version unbekannt — Opt-2-Decode-Pfad)

| Key | DE | EN |
|---|---|---|
| `comm_status_protocol_skew_server_unknown` | Client-Protokoll %1$s passt nicht zur Server-Version — App aktualisieren oder Server neu ausrollen. | Client protocol %1$s doesn't match the server version — update the app or redeploy the server. |

### 3.3 a11y-Beschreibung (Screenreader — trägt den terminalen Fakt, den das Auge am fehlenden Spinner sieht)

| Key | DE | EN |
|---|---|---|
| `a11y_comm_status_protocol_skew` | Verbindung angehalten: Client-Protokoll %1$s ≠ Server-Protokoll %2$s. Es wird nicht automatisch neu verbunden — App aktualisieren oder Server neu ausrollen. | Connection halted: client protocol %1$s ≠ server protocol %2$s. It will not reconnect on its own — update the app or redeploy the server. |
| `a11y_comm_status_protocol_skew_server_unknown` | Verbindung angehalten: Client-Protokoll %1$s passt nicht zur Server-Version. Es wird nicht automatisch neu verbunden — App aktualisieren oder Server neu ausrollen. | Connection halted: client protocol %1$s doesn't match the server version. It will not reconnect on its own — update the app or redeploy the server. |

**Wortlaut-Begründung:**
- **Ursache + Weg, nicht bloß „Fehler".** Das Banner nennt die **Tatsache** (welche Versionen
  auseinanderlaufen) und **beide realen Wege** (App aktualisieren **oder** Server neu ausrollen). Es
  gibt keinen dritten Weg, und keinen, den der Operator sich „erwarten" soll — deshalb keine
  Auflösungs-Zusage à la „gleich wieder da". (Dieselbe Disziplin wie `CYP-738`: eine Zusage nur, wenn
  die Auflösung garantiert eintritt — hier tritt sie **nie von selbst** ein.)
- **„angehalten" nur in der a11y-Beschreibung, nicht im sichtbaren Text.** Sichtbar trägt die
  *statische, spinnerlose Form* (§4) den terminalen Fakt vor dem Lesen; der Screenreader kann die
  fehlende Bewegung nicht sehen, darum steht „wird nicht automatisch neu verbunden" **explizit** in
  `a11y_*`. Gleiche Live-State-Ehrlichkeit wie bei CONTEXT_LOST (CYP-381 §7.1): das Nicht-Sichtbare
  wird verbalisiert.
- **Anglizismen des Ticket-Entwurfs geglättet** („updaten/redeployen" → „aktualisieren/neu
  ausrollen") — Hausstimme (maritim/M3, DE-default). Inhalt = PO-Entwurf, Ton = Haus.
- **„Protokoll" statt „v" / „Schema".** Es ist das **App-Schema**, nicht der Transport (der hat
  bereits `MuxHello.VERSION`), aber „Schema" ist Jargon; der Zustand heißt `PROTOCOL_SKEW`, also ist
  „Protokoll" das operator-lesbare, zum Zustandsnamen passende Wort. Die Versionsnummer selbst ist ein
  opaker `%1$s`/`%2$s`-Token — das Banner formatiert sie nicht (kann `2025-06-18` **oder** `7` sein,
  je nach Backend-Wahl).

---

## 4. Severity-Ton — distinkt von offline **und** von reconnecting

Heute rendert `ConnectionBanner` (CommPanel.kt:312) **jeden** Nicht-LIVE-Zustand auf `errorContainer`
(weiches Rot) — inkl. `CONNECTING`. `RECONNECTING` (Remote-Pfad, `RemoteRelayDropBanner`) ist
**amber/WARN**. `PROTOCOL_SKEW` muss von **beiden** vor dem Lesen unterscheidbar sein:

| Zustand | Was er bedeutet | Ton (Quelle) | Bewegung |
|---|---|---|---|
| CONNECTING / RECONNECTING | transient, **erholt sich** | amber/WARN (bzw. heute rot-Container) | Spinner / in Bewegung |
| DISCONNECTED (offline) | unten, **kann sich erholen** | `errorContainer` (weiches Rot) | ggf. Reconnect-Spinner |
| **`PROTOCOL_SKEW`** | **terminal, erholt sich NIE von selbst — Mensch muss handeln** | **`severityContainer(Severity.ERROR)`** = gefülltes `error`/`onError` (lautes Rot) **+ Glyph** | **statisch, kein Spinner** |

**Warum ERROR und nicht amber:** Amber sagt „läuft, hab Geduld" — bei einem terminalen Zustand wäre
das **unwahr** (Geduld hilft nie). `PROTOCOL_SKEW` gehört semantisch in die **Fehler-Familie**
(etwas ist echt kaputt, es braucht eine Handlung) — deshalb `Severity.ERROR`, **nicht** `WARN`.

**Warum das trotzdem nicht mit offline verschmilzt — die Pre-Read-Regel (`CYP-738 §4`):**
offline und Skew teilen die sichtbare Konsequenz „rotes Banner, nicht live", bedeuten aber
**verschiedene Wahrheiten** (erholt-sich-evtl. vs. terminal-handeln). Der Unterschied **muss vor dem
Lesen ankommen** — nicht nur im Satzinhalt. Drei Pre-Read-Träger, gestaffelt:

1. **Intensität:** offline = weicher `errorContainer`; Skew = **gefülltes `error`** (`severityContainer(Severity.ERROR)`, dieselbe geteilte Quelle wie die Event-Log-/Badge-Severity — kein neuer Farbwert). Gefüllt-laut vs. Container-weich ist ein Vor-dem-Lesen-Unterschied.
2. **Glyph:** offline/connecting tragen **keinen** Glyph; Skew trägt einen **führenden Fehler-Glyph** (`✕`, der Severity-/Hint-ERROR-Glyph). Ein Glyph, wo vorher keiner war, ist per se pre-read.
3. **Form:** Skew ist **statisch** (kein Spinner) und **persistent** (klärt nicht von selbst) — das transiente Vokabular fehlt bewusst.

> **Glyph-Konsistenz-Notiz an Dev:** das Repo führt zwei Fehler-Glyphen — `✕` (Hint/Severity,
> `CYP-738`) und `✗` (ToolCall-Status). Das Banner ist ein **Severity-Banner**, also `✕`. Bitte den am
> Render-Ort ohnehin gebräuchlichen nehmen und **keinen dritten** einführen. (Kein neuer Farbwert, kein
> neuer Glyph — nur die vorhandene ERROR-Severity an einer neuen Stelle.)

**Angrenzender Befund (nicht Teil dieses Tickets, nur im Vorbeigehen):** `CONNECTING`
(„Verbinde…", `comm_status_connecting`) rendert heute ebenfalls auf `errorContainer` — ein transienter
Zustand in Fehlerrot. Das ist ein eigener kleiner Ehrlichkeits-Geruch (transient sieht aus wie Fehler)
und **verschärft** den Bedarf, dass Skew sich stark absetzt. Ob `CONNECTING` einen ruhigeren Ton
bekommt, ist eine **separate PO-Weiche** — ich fasse es hier nicht an, flagge es nur.

---

## 5. a11y — Ansage-Dringlichkeit

`PROTOCOL_SKEW` trifft **unaufgefordert** ein (der Operator hat nichts abgeschickt; die App hat die
Inkompatibilität erkannt) und ist **terminal** (sonst wartet er auf ein Reconnect, das nie kommt).
Nach `A11Y-ANNOUNCEMENTS.md §1` ist das die **`Assertive`**-Klasse („unaufgefordert eintrifft"):

```
Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
```

Es ist der berechtigte Fall zum Unterbrechen der laufenden Screenreader-Ausgabe: ohne die Ansage
verpasst der Operator, dass er **handeln** muss. Farbe/Glyph sind **nie** alleiniger Träger
(`COLOR-CODING.md §8`): die `a11y_*`-Beschreibung (§3.3) trägt Ursache, Versionen, den terminalen Fakt
und den Weg als Text.

---

## 6. testTags

| Tag | Zweck |
|---|---|
| `comm.protocolSkew` | das ProtocolSkew-Banner (eigener Tag, **nicht** der geteilte `comm.connection`) — damit der Tester den terminalen Zustand gezielt von offline/connecting trennen kann |

**Warum ein eigener Tag statt eines Qualifiers auf `comm.connection`:** die drei Zustände sind für den
Tester **verschiedene Wahrheiten** mit verschiedenen Erwartungen (offline erholt sich evtl.; Skew nie).
Ein eigener Tag macht die Zahn-Assertion „Skew-Banner sichtbar **und** Reconnect gestoppt" sauber
adressierbar, ohne den Text zu parsen. Segment-Charset `[A-Za-z0-9-]+`, camelCase, dot-getrennt —
konform `TEST-CONTRACT.md §2`. (Reuse-geprüft gegen `CommTags.kt`: `CONNECTION = "comm.connection"`
existiert; `protocolSkew` ist net-new, kollidiert nicht.)

---

## 7. Aufwand (je Teil — grobe UIUX-Sicht, Dev bestätigt)

| Teil | Wo | Aufwand |
|---|---|---|
| `ConnectionStatus.PROTOCOL_SKEW` + Banner-Zweig (Ton/Glyph/Copy/a11y/Tag) | `CommReducer.kt`, `CommPanel.kt:312` | **S** — ein Enum-Wert, ein `when`-Zweig, 3 Key-Paare, 1 Tag |
| Reconnect-Loop bei Skew **anhalten** | Client-WS-Reconnect-Logik | **S–M** (Backend/Client-Transport) — Verhalten, nicht UI |
| Opt 1: `schemaVersion`-Hello-Austausch | `ws:comm`-Connect, `:core` + Server | **M** — Backend |
| Opt 2: Decode-Failure → `SchemaSkewDetected(frameType, field)` | ws-Deserialisierung wrappen | **S–M** — Backend |

Meine Lane = Zeile 1 (Banner). Zeilen 2–4 = Backend-Design (Ticket Opt 1/Opt 2); dieses Dokument
liefert die **Ziel-Darstellung**, auf die beide Erzeuger hinlaufen.

---

## 8. Empfehlung an PO

- **UI-Zustand `PROTOCOL_SKEW` wie hier spezifiziert** — ein eigener, terminaler, ERROR-getönter,
  pre-read-distinkter Banner-Zustand mit Vollform + degradierter Form + Assertive-a11y.
- **Beide Backend-Erzeuger auf diesen einen Zustand mappen** (Opt 1 primär, Opt 2 als
  Defense-in-depth — genau die Backend-Empfehlung). Die UI ist für beide Datenlagen vorbereitet
  (degradierte Copy für den serverVersion-losen Decode-Pfad).
- **Reconnect-Anhalten ist Voraussetzung der Ehrlichkeit**, nicht Kosmetik: ohne es widerspricht der
  weiterlaufende Spinner dem „so kommt keine Verbindung zustande". Als ⟂-Abhängigkeit am Bau führen.
- Der `CONNECTING`-auf-Fehlerrot-Geruch (§4) als **separate** kleine PO-Weiche — nicht in dieses
  Ticket ziehen.

---

## 9. Self-Validation

- **Ton-Vokabular am Code geprüft:** `Severity = {DEBUG, INFO, WARN, ERROR}`
  (`core/.../EventModel.kt:59`); `severityContainer(Severity.ERROR)` = `scheme.error/onError`,
  `WARN` = amber (`eventlog/EventVisuals.kt:82`) — **kein neuer Farbwert**, Skew hängt an der
  vorhandenen ERROR-Severity.
- **Bestehendes Banner am Code geprüft:** `ConnectionBanner` (`CommPanel.kt:312`) rendert heute jeden
  Nicht-LIVE-Zustand auf `errorContainer`; `ConnectionStatus` (`CommReducer.kt:13`) = 3 Werte — die
  Enum-Erweiterung ist der reale Eingriffspunkt. Präzedenz für einen **terminalen** Enum-Wert:
  `AccessRevoked → DISCONNECTED` („honest terminal, never live", CYP-291).
- **Pre-Read-Kollision aktiv gelöst, nicht ignoriert:** offline **und** Skew sind rot-Familie → drei
  gestaffelte Pre-Read-Träger (Intensität/Glyph/Form) statt Verlass auf den Satzinhalt (`CYP-738 §4`
  angewandt, nicht nur zitiert).
- **`null ≠ fabriziert` durchgezogen:** degradierte Copy für den serverVersion-losen Opt-2-Pfad; keine
  erfundene „v?"-Nummer.
- **Ansage-Dringlichkeit aus der Regel abgeleitet**, nicht geraten: unaufgefordert + terminal →
  `Assertive` (`A11Y-ANNOUNCEMENTS.md §1`), mit Begründung warum das Unterbrechen hier berechtigt ist.
- **Lane-Grenze benannt:** WO die `schemaVersion` lebt / Hello-Verdrahtung / Decode-Klassifikation =
  Backend-Design (Ticket Opt 1/Opt 2); dieses Dokument liefert nur die konsumierte Nahtstelle + die
  Ziel-Darstellung. Kein Alleingang über die Grenze.
- **testTag reuse-geprüft** gegen `CommTags.kt` (real gelesen): `comm.connection` existiert,
  `comm.protocolSkew` ist net-new/kollisionsfrei; Begründung für eigenen Tag statt Qualifier gegeben.
- **Keine Zeile Bau:** `strings.xml` (geteilte Datei, Dev-Lane) nicht angefasst — dieses Dokument ist
  die Referenz, nicht die Quelle.
