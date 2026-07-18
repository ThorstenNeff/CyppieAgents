# CYP-234 — web-ts Client-Cross-Check des Protokoll-Vertrags

> **Status:** Beitrag von Dev5 (web-ts) zu der von **Backend2 geführten** sprachneutralen Protokoll-Spec.
> **Spec-not-implement:** dieses Dokument beschreibt ausschließlich, **was am Objekt gilt**. Es ändert kein
> Protokoll, keinen Codegen und keinen Client. Jede Diskrepanz ist als **Befund** markiert, nicht als Fix.
> **Gemessen gegen:** `contract/asyncapi.json` + `contract/openapi.json` (:core-Build-Export) und den realen
> web-ts-Client auf develop `a3ed3962`, 2026-07-18.
> **Revision 2 (2026-07-18):** **F7 korrigiert** nach Assist2s Recompute — meine erste Fassung behauptete
> fälschlich, `security` sei nirgends angewandt (ich hatte nur die globale Ebene gemessen). Alle 65 Operationen
> deklarieren `security`; der überlebende, geschärfte Kern ist die nicht ausdrückbare **Tier**-Grenze. Die
> Korrektur steht sichtbar in F7, nicht stillschweigend ersetzt.

## 1. Warum dieser Cross-Check aussagekräftig ist

web-ts ist heute der **einzige nicht-Kotlin-Client** am Vertrag. Er hat den Weg genommen, den ein BYO-Frontend
(Go, Godot, CLI …) nehmen müsste: **aus dem exportierten Schema Typen generieren und damit gegen den echten
Server sprechen**. Alles, worüber web-ts dabei gestolpert ist, stolpert ein fremdes Frontend genauso — mit dem
Unterschied, dass web-ts die Lücken durch **Kotlin-Kontext und Rückfragen im Team** schließen konnte. Ein
fremder Implementierer hat diesen Kanal nicht. **Die Lücken sind deshalb der eigentliche Ertrag dieses Tickets.**

## 2. Baustein: die vorhandene Extraktions-Maschinerie

Bereits gebaut und in `develop`, verwendbar als Fundament der sprachneutralen Spec:

| Artefakt | Zweck |
|---|---|
| `contract/asyncapi.json`, `contract/openapi.json` | :core-Build-Export (Backend2), Quelle beider Generatoren |
| `web-ts/scripts/contractSchema.mjs` | geteilte Pipeline: laden, REST+WS mergen, **Diskriminanten injizieren**, `$ref`→`definitions` |
| `web-ts/scripts/generate-contract-types.mjs` | → TypeScript-Typen (`json-schema-to-typescript`) |
| `web-ts/scripts/generate-contract-zod.mjs` | → zod-**Runtime**-Validatoren (CYP-420) |

**Wichtig für die Spec:** die Pipeline ist *nicht* nur „Schema rein, Typen raus". Sie enthält **einen
Transformationsschritt, ohne den der Vertrag nicht implementierbar ist** (§3, F2). Genau dieser Schritt gehört in
eine sprachneutrale Spec — sonst muss ihn jeder fremde Client neu erfinden.

### 2.1 Erkenntnisgrenze der Maschinerie: **Vorlage, nicht Wahrheit**

Die Generatoren lesen `:core` — **dieselbe Quelle**, aus der auch der Server abgeleitet ist. Sie sind damit
*accurate-by-construction* für das, was `:core` **deklariert**, und **strukturell blind** dafür, wo der **Server
zur Laufzeit etwas anderes tut**. Ein `:core`↔Server-Auseinanderlaufen kann diese Pipeline **nicht** entdecken —
sie würde die Abweichung stillschweigend mitgenerieren und dabei „verifiziert" aussehen.

Deshalb ist der Vertrag entlang **drei** Achsen zu prüfen, und nur zwei davon kann ich abdecken:

| Achse | Wer kann es messen | Status |
|---|---|---|
| `:core` ↔ **Client** (was web-ts real sendet/erwartet) | **ich** | ✅ dieses Dokument, §3 |
| `:core` ↔ **exportiertes Schema** (Vollständigkeit/Implementierbarkeit) | **ich** | ✅ dieses Dokument, §3 |
| `:core` ↔ **Server-Laufzeit** (was der Server real sendet/annimmt) | **Backend2** (Laufzeit-Messung) | ⛔ nicht von mir prüfbar |

**Konsequenz für die Spec:** die sprachneutrale Spec darf **nicht** allein aus dem Generator-Output abgeleitet
werden — sonst zementiert sie eine etwaige `:core`↔Server-Abweichung als „Vertrag". Der Generator-Output ist die
**Vorlage**; die Wahrheit entsteht erst, wenn Backend2s Laufzeit-Messung dagegen gehalten wird. Meine Befunde
unten sind entsprechend **`:core`/Client-Befunde**, keine Aussagen über Server-Verhalten.

## 3. Befunde

Severity = Auswirkung auf ein **fremdes** Frontend, nicht auf web-ts.

### F1 (HOCH) — Der Vertrag beschreibt, *was* fließt, aber nicht, *wie man sich verbindet*

`asyncapi.json` deklariert `channels` + Payload-Schemas — aber **kein `servers`, keine channel-`parameters`,
keine `bindings`**. Der reale Client muss jedoch senden:

| Query-Parameter | Kanäle | Quelle im Client |
|---|---|---|
| `?token=<opaque>` | die 7 Kanäle über `BidiFeed`/`OneWayFeed` | `bidiFeed.ts:33`, `oneWayFeed.ts:27` |
| `?agentId=<id>` | `/ws/terminal`, `/ws/agent` | `channels.ts` (terminal), `agentSocket.ts:33` |
| `?since=<seq>` | `/ws/agent` **beim Reconnect** (Replay-Cursor) | `agentSocket.ts:34` |

**Konsequenz:** ein fremdes Frontend kann aus dem Export **perfekte Payload-Typen generieren und trotzdem keinen
einzigen Socket öffnen.** `?since=` ist zusätzlich semantisch tragend (Wiederaufnahme ohne Lücke/Dublette) und
nirgends beschrieben.
**Spec-Vorschlag:** `servers` + pro Kanal `parameters` (Name, Pflicht/optional, Semantik) aufnehmen; `?since=`
als Replay-Contract eigenständig beschreiben (inkl. „Server sendet ggf. das Cursor-Event erneut" — der Client
dedupliziert, s. `agentSocket.ts`).

### F2 (HOCH) — Der Diskriminant `type` steht auf der Leitung, aber nicht im Schema

Die Unions deklarieren `discriminator: {propertyName: "type", mapping: {...}}`, **die Subtyp-Schemata deklarieren
`type` aber nicht als Property.** Gemessen an `CommWsServerEvent` → Mapping `acl`: Subtyp-Properties `["entry"]`,
`required: ["entry"]` — **kein `type`**. Ursache ist kotlinx.serialization: der Subtyp-Deskriptor kennt nur die
eigenen Felder, `type` ist auf der Leitung synthetisch.

**Konsequenz:** ein naiver Codegen erzeugt eine **nicht-diskriminierte** Union. Der fremde Client kann eingehende
Frames nicht dispatchen — oder schlimmer, er akzeptiert jeden Member für jeden `type`. web-ts löst das, indem der
Generator die Literale **vor** dem Codegen injiziert (`contractSchema.mjs`) und fail-closed prüft, dass sie den
Codegen überleben. **Das ist der teuerste undokumentierte Schritt im ganzen Vertrag.**
**Spec-Vorschlag:** entweder (a) den Export so ändern, dass Subtypen das `type`-Literal deklarieren (dann ist der
Vertrag ohne Sonderwissen implementierbar — bevorzugt), oder (b) die Injektionsregel normativ in die Spec
schreiben. **(a) ist ein Server-/Export-Change und damit Backend2s Entscheidung, nicht meine.**

### F3 (HOCH) — WS-Auth ist undeklariert und asymmetrisch zu REST

- `openapi.json` deklariert `securitySchemes`: `bearerAuth` (http/bearer) **und** `sessionCookie`
  (`ory_kratos_session`). ✅
- `asyncapi.json` deklariert **`securitySchemes: NONE`** — obwohl die WS-Seite den **ungewöhnlicheren**
  Mechanismus benutzt.

Real verwendet der Client **drei verschiedene Träger auf einer Fläche**:

| Fläche | Auth-Träger | Quelle |
|---|---|---|
| REST | `Authorization: Bearer` **oder** First-Party-Cookie (`credentials:"include"`) | `rest.ts:60,66` |
| 7 WS-Kanäle | **`?token=` im URL-Query** | `bidiFeed.ts:33`, `oneWayFeed.ts:27` |
| `/ws/agent` | **nur** Same-Origin-Session-Cookie, **kein** Token | `agentSocket.ts:32` (CYP-454) |

**Konsequenz:** nicht ableitbar. Ein fremdes Frontend rät. Zusätzlich ist `?token=` im **URL-Query** eine
Hygiene-Entscheidung mit Folgen (Proxy-/Access-Logs, Referrer, Browser-History) — vertretbar, aber sie sollte
**bewusst dokumentiert** sein statt implizit. (Für Browser-Clients ist die WS-Handshake-Header-Beschränkung der
übliche Grund; für Nicht-Browser-Clients gilt sie nicht — ein Header-Pfad wäre dort sauberer.)
**Spec-Vorschlag:** `securitySchemes` auch in AsyncAPI; pro Kanal den zulässigen Träger benennen; die
Cookie-vs-Token-Asymmetrie von `/ws/agent` explizit machen (oder angleichen — Backend2s Call).

### F4 (MITTEL) — Unbekannte Felder: Policy undefiniert

**0 von 39** WS-Schemata deklarieren `additionalProperties`. Damit ist unbestimmt, ob ein Client unbekannte
Felder **tolerieren muss** (Forward-Compat) oder **ablehnen darf** (Strictness).
**Konsequenz:** ein streng generierter Go-Client lehnt ab, wo web-ts toleriert. Ein harmloses neues Serverfeld
wird damit für den einen Client ein No-Op und für den anderen ein Ausfall. web-ts hat sich (CYP-420) bewusst für
**tolerant** entschieden — fail-closed bei Verletzung der bekannten Felder, aber nicht fail-brittle bei neuen.
**Spec-Vorschlag:** die Erweiterungs-Policy normativ festschreiben („Clients MÜSSEN unbekannte Felder ignorieren;
Feld-Additionen sind nicht-brechend") — das ist die Voraussetzung dafür, dass Versionierung überhaupt greift.

### F5 (MITTEL) — Free-form-Payloads sind vertraglich formlos

Innerhalb von `StreamJsonEvent` sind `content`, `input`, `usage`, `tools`, `rate_limit_info` **untypisiert**
(kotlinx `JsonElement`, Claude-Code-stream-json-Nutzlast). Gemessen: 8 Stellen ohne Typ im generierten
zod-Schema — das ist **korrekt**, nicht defekt.
**Konsequenz:** ein fremder Client muss dort **beliebiges JSON** erwarten. Wer aus dem Schema „validiert" ableitet,
überschätzt die Garantie. **Explizit festhalten:** der Vertrag garantiert den **Envelope**, nicht diese Nutzlasten;
Render-/Ausgabesicherheit (Sanitizing, kein `innerHTML`, CSP) liegt beim Client und ist **nicht** durch
Schema-Validierung abgedeckt.

### F6 (NIEDRIG) — Uneinheitliche Benennung der Client→Server-Payloads

`CommWsClientEvent`, `EventsWsClientEvent`, `TerminalClientFrame`, **`UserTurn`**. Die Kanal-Zuordnung ist über
`channels[].publish` **vollständig und korrekt** auflösbar (s. §4) — die Namen allein tragen sie aber nicht.
**Spec-Vorschlag:** entweder normalisieren oder in der Spec-Tabelle explizit zuordnen (Letzteres genügt).

### F7 (MITTEL) — REST: die **Tier**-Grenze (Operator vs. Member) ist nicht maschinen-ausdrückbar

> **KORRIGIERT nach Assist2s Recompute (2026-07-18).** Meine ursprüngliche Fassung behauptete, `openapi.json`
> setze `security` „weder global noch pro Operation". **Das war falsch und hat der Messung nicht standgehalten.**
> Ich hatte nur den *globalen* Schlüssel `o.security` geprüft (= `NONE`) und daraus auf „nirgends" geschlossen,
> **ohne die Operations-Ebene zu messen** — exakt der Kurzschluss von der Abwesenheit auf einer Ebene auf die
> Abwesenheit überhaupt. Nachgemessen: **alle 65 Operationen deklarieren `security`.**
> Der Fehler steht hier sichtbar statt stillschweigend ersetzt — dieselbe Behandlung wie die widerlegte
> Hypothese in §4; ein Spec-Input, der seine eigenen Korrekturen versteckt, ist als Quelle weniger wert.

**Gemessener Ist-Stand:** 65 Operationen, **65 mit `security`** — davon **62** mit
`[{bearerAuth},{sessionCookie}]` (= „authentifiziert, per Token **oder** Session") und **3** explizit öffentlich
(`[]`): `GET /api/health`, `GET /api/auth/me`, `POST /api/auth/register`. Die Grenze **öffentlich ↔
authentifiziert ist damit sauber und maschinenlesbar ausgedrückt.** ✅

**Was NICHT ausgedrückt ist — der überlebende Kern:** die **Autoritäts-*Tier*-Grenze**. Gemessen:

| Operation | Tier real | deklariertes `security` |
|---|---|---|
| `PUT /api/acl` | **Operator-only** | `[{bearerAuth},{sessionCookie}]` |
| `GET /api/channels` | member-lesbar | `[{bearerAuth},{sessionCookie}]` |
| `GET /api/agents` | member-lesbar | `[{bearerAuth},{sessionCookie}]` |

**Byte-identisch.** OpenAPI-`security` beantwortet „**ob** authentifiziert", nicht „**als was**". Ein fremdes
Frontend kann aus dem Vertrag also nicht ableiten, welche Operationen Operator-Autorität verlangen (ACL-`PUT`,
Projekt-Mutationen, API-Key-Wechsel) — **es lernt die Grenze erst am 403.**
**Konsequenz für BYO:** genau diese Grenze ist das Sicherheitsrelevante. Ein Client, der sie nicht kennt, baut
UI-Affordanzen für Aktionen, die er nie ausführen darf (und muss den 403 als Normalfall behandeln statt als
Fehler). **Spec-Vorschlag:** das Tier maschinenlesbar machen — z. B. Scopes/Rollen am Scheme
(`bearerAuth: [operator]` vs `[]`) oder ein normatives `x-tier`-Feld pro Operation; mindestens aber eine
normative Tabelle „Operation → geforderte Tier" in der Spec.

**Zusätzlich (unverändert):** `openapi.json` deklariert **kein `servers`** (Basis-URL/Origin) — analog zu F1 auf
der WS-Seite.

## 4. Widerlegte Hypothese (dokumentiert, damit sie niemand erneut aufwirft)

**Vermutung:** `/ws/agent` habe keinen deklarierten Client→Server-Frame (der Client sendet `{ text }`).
**Falsch.** Nachgemessen: `channels['/ws/agent'].publish` → **`UserTurn`**, und `UserTurn` ist
`{type:"object", properties:{text:{type:"string"}}, required:["text"]}` — **exakt** was der Client sendet
(`useAgentTranscript.ts:53`). Mein erster Filter (`/Client/i` auf Schemanamen) hat den Typ nur wegen seines
Namens nicht gefunden — genau der Fall aus **F6**.
**Die Kanal-Richtungs-Abdeckung ist vollständig:** alle 8 Kanäle deklariert, 4 davon bidirektional
(`/ws/comm`, `/ws/events`, `/ws/agent`, `/ws/terminal`), 4 nur server→client.

## 5. Was ich geprüft habe — und was ausdrücklich nicht

**Geprüft (am Objekt):** Kanal-Inventar + Richtungen + Payload-`$ref`s; Diskriminanten-Deklaration in den
Subtyp-Schemata; `additionalProperties` über alle 39 WS-Schemata; `securitySchemes`/`security`/`servers` in
beiden Exporten; die realen Query-Parameter und Auth-Träger im web-ts-Client; `UserTurn` gegen den tatsächlichen
Sende-Aufruf.

**Nicht geprüft (bewusst, außerhalb meiner Kenntnis):** ob der **Server** die dokumentierten Frames auch so
**sendet/annimmt** wie deklariert (das ist Backend2s Seite — ich habe nur Client-Erwartung gegen Contract
gehalten, nicht gegen Server-Verhalten); Verhalten unter Protokoll-Verletzung serverseitig; Versionierungs-/
Deprecation-Politik (existiert noch nicht); CORS/Origin-Policy für fremde Origins; die Kratos-Flows.
**Ein Live-Cross-Check gegen einen laufenden Server würde F1/F3 endgültig festnageln** — den kann ich lokal nicht
fahren.

## 6. Vorschlag für die gemeinsame Spec (Priorität aus Client-Sicht)

1. **F1 + F3 zuerst** — ohne Verbindungs- und Auth-Beschreibung ist der Rest akademisch: man kommt nicht rein.
2. **F2** — entscheidet, ob der Vertrag *ohne Sonderwissen* generierbar ist. Bevorzugt am Export lösen.
3. **F7 (korrigiert)** — nicht „Auth fehlt", sondern: die **Tier**-Grenze (Operator vs. Member) ist nicht
   maschinen-ausdrückbar. Für BYO ist genau sie das Sicherheitsrelevante.
4. **F4** — Voraussetzung dafür, dass Versionierung/Kompatibilität überhaupt funktioniert.
5. **F5, F6** — Klarstellungen; billig, verhindern Fehlannahmen.
