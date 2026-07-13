# Remote-Operating-Surface-Chrome — i18n-Keys (M2, Epic CYP-427, CYP-449 §4-Seams)

> Owner: UIUX-Designer · **Dev-AC (copy-paste-fertig)** · Stand 2026-07-13 · Auftraggeber-autorisiert (M2-Slice, PO
> `1526364905…`). **Rein Doku/Copy, kein Code.** Gegroundet READ-ONLY gg. develop `5fbd9e9c` (nichts angefasst).
> Begleit: `remote-operating-chrome-tags.md` (testTag-Vertrag) + `remote-operating-chrome-ux-spec.md` (Verhalten/Seams).
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml` @ `5fbd9e9c`): **Underscore-Realkeys**,
> positionsbasierte Args `%1$s`. **DE = Default** + **EN** (`values-en/`). Parität Pflicht.

---

## 0. Kontext + Grounding-Korrektur (Pflicht-Lesung vor Bau)

M2 macht das **Operieren über den Tunnel real**. Diese Slice finalisiert die drei In-Betrieb-Chrome-Nähte aus
`remote-operating-surface-ux-spec.md` §4 (CYP-449 §10-Seams #4/#6/#8). **Grounding-Korrektur ggü. der CYP-449-Frozen-Spec:**
jene nannte als Reuse-Ziele `remote_context_operating` / `remote_e2e_indicator` / `remote_trust_pinned` und Tags
`remote.context.*` / `shell.hubContext` — **diese existieren NICHT im Build** (CYP-429 §8/§9 wurde nie gebaut; Trust
konsolidierte in `RemoteConnectTags`, das Kontext-Banner in **CYP-527** `workspace.remoteContext`). Diese Slice groundet
daher auf das **tatsächlich Gebaute** und ist **anti-duplikat**: sie **erweitert die eine gebaute Banner-Naht**
(`RemoteContextBanner`/`WorkspaceTags.REMOTE_CONTEXT`), sie steht **kein zweites Banner** daneben.

| Seam | Gebaut heute | M2-Delta |
|---|---|---|
| **#1 Kontext-Banner** | `RemoteContextBanner` gemountet in `AgentShell`, WARN-partial „Daten noch nicht über den Tunnel" (`workspace_remote_context_partial`) | **Graduierung** derselben Node in die **affirmative** „über verschlüsselten Tunnel"-Form + E2E-gepinnt-Indikator, **capability-gegated** |
| **#2 Relay-Drop** | `RemoteConnState.RECONNECTING` + `inFlightUncertain` existieren, **kein In-Betrieb-Konsument** | In-Betrieb-**Relay-Drop-Fläche** (Reconnect-Banner) + **„Aktionen unbestätigt"-Warnung** (`inFlightUncertain`) |
| **#3 Revoke-Mount** | `RemoteRevokeControl` gebaut+wired, aber im **Connect-Flow** gemountet, nicht im Operating-Shell | **Live-Mount** in der Kontext-Zeile — **0 net-new** (Copy/Tags gebaut), reine Mount-Naht |

**Net-new gesamt: 4 Realkeys** (+2 empfohlene a11y) · **Seam #3 = 0 net-new** (alles gebaut).

---

## Seam #1 — Kontext-Banner: affirmative Tunnel-Form (Graduierung, capability-gegated)

**Baut 1 Realkey** (+1 a11y). Der Key-Name `workspace_remote_context_tunnel` ist von **CYP-527 reserviert**
(dortiger „Deferred"-Eintrag, `●`-Vollform „wenn CR3 vollständig"). Diese Slice liefert ihn — **reused dieselbe Node**
(`workspace.remoteContext`, Zustandsvariante), **kein** neuer Banner.

| Key | DE | EN |
|---|---|---|
| `workspace_remote_context_tunnel` **(NET-NEW, 1 Arg)** | Remote verbunden mit %1$s — über verschlüsselten Tunnel. | Connected remotely to %1$s — over an encrypted tunnel. |

`%1$s` = **`hub.name`** (editierbarer Anzeigename, **nie** die opake `hubId` — konsistent mit `HubRow`/`_partial`).

**E2E-gepinnt-Indikator (Fingerprint-verifiziert) = REUSE, kein net-new:**
| Reuse-Key | DE | EN | Rolle |
|---|---|---|---|
| `remote_connect_trust_pinned` | Identität gepinnt | Identity pinned | Gepinnt-Indikator im Banner — **present-iff echt gepinnt** (realer Fingerprint-Pin via `HubTrust`), **absent bei provisorischem Trust**. Trägt Sub-Tag `workspace.remoteContext.pinned` (`-tags.md`). |

> **Warum kein net-new E2E-Indikator-Key:** die „verschlüsselter Tunnel"-Wahrheit steht bereits **im Hauptsatz** des
> affirmativen Keys; „Fingerprint-verifiziert" = **gepinnt** = `remote_connect_trust_pinned` (bereits gebaut, CYP-480).
> CYP-429s nie-gebautes `remote_e2e_indicator` wird **NICHT** wiederbelebt (anti-duplikat). Ein separater Lock-Glyph ist
> Dekoration (kein Copy-String).

### a11y (empfohlen — 1 Key)
| Key | DE | EN |
|---|---|---|
| `a11y_workspace_remote_context_tunnel` **(NET-NEW, 1 Arg)** | Remote verbunden mit %1$s über einen verschlüsselten Tunnel. | Connected remotely to %1$s over an encrypted tunnel. |

> Der gepinnt-Sub-Node trägt seine eigene `contentDescription` aus `remote_connect_trust_pinned` (nur wenn present) —
> die Banner-a11y hardcodet „gepinnt" **nicht** (sonst behauptet der Screenreader Pin, wo evtl. provisorisch).

### Ton (Pflicht — der ehrliche Kern von Seam #1)
- **Affirmative Form = neutral/informational**, `●`-Glyph + `primary`/`onSurface`-Ton — **NICHT** `tertiary`/Erfolgs-Grün
  (es ist kein Erfolgs-Event, sondern eine Zustands-Tatsache) und **NICHT** WARN-Amber (es ist keine Warnung mehr).
  CYP-527 reservierte exakt „`●`+`primary` statt `▲`+WARN". Der Glyph `●` separat gerendert (WCAG 1.4.1).
- **Der Gepinnt-Indikator ist NUR Transport-/Identitäts-Tatsache, nie grün, nie als „alles sicher"** (H3): E2E =
  Transport-Verschlüsselung; Pin = Hub-Authentizität (TOFU). Zwei getrennte Wahrheiten, keine als Erfolgs-Grün.

---

## Seam #2 — In-Betrieb-Relay-Drop-Fläche (RECONNECTING + inFlightUncertain)

**Baut 1 Realkey** (+1 a11y) · **1 Key reused**. Das Reconnect-Banner reused die gebaute Connect-Copy; die
**„Aktionen unbestätigt"-Warnung** (der `inFlightUncertain`-Konsum) ist net-new — **das ist der Ehrlichkeits-Kern**.

| Key | DE | EN | Rolle |
|---|---|---|---|
| `remote_connect_relay_dropped` **(REUSE, 0 Arg)** | Verbindung unterbrochen — verbinde neu … | Connection dropped — reconnecting… | Reconnect-Banner-Text (gebaut, CYP-471). Trägt **im Betrieb** den neuen Tag `workspace.relayDrop` (andere Fläche als der Connect-Flow-Row → eigener Tag). |
| `workspace_relay_uncertain` **(NET-NEW, 0 Arg)** | Laufende Aktionen sind unbestätigt — beim Wiederverbinden wird geprüft, was ankam. | In-flight actions are unconfirmed — we'll check what went through once reconnected. | Der `inFlightUncertain==true`-Konsum: laufende Aktionen ehrlich **ungewiss**, **nie still als erledigt**. Sub-Zeile unter dem Reconnect-Banner. |

### a11y (empfohlen — 1 Key)
| Key | DE | EN |
|---|---|---|
| `a11y_workspace_relay_uncertain` **(NET-NEW, 0 Arg)** | Warnung: laufende Aktionen sind unbestätigt — beim Wiederverbinden wird geprüft, was ankam. | Warning: in-flight actions are unconfirmed — we'll check what went through once reconnected. |

> Das Reconnect-Banner nutzt für a11y den aufgelösten `remote_connect_relay_dropped`-String (keine eigene a11y nötig —
> 0-Arg, selbsterklärend). Die **Ungewissheits-Warnung** kriegt ihren eigenen „Warnung:"-a11y-Key (WARN-Natur announced).

### Ton (Pflicht)
- **Relay-Drop-Fläche = WARN-Amber** (`severityColor(Severity.WARN)`, `▲`-Glyph separat) — es ist ein Betriebs-Downgrade,
  aufmerksamkeits-würdig, aber **NICHT** `errorContainer`/Rot: die Sitzung ist **nicht** tot, sie verbindet neu. (Terminaler
  Verlust = `RemoteConnState.LOST` → Sitzung-beendet-Pfad, **out-of-scope** dieser Slice.)
- **Kein** „RR5"/„Seam"/„Tunnel-Internals"-Jargon. Kein Erfolgs-Grün auf irgendeinem Zustand.

---

## Seam #3 — Revoke-Control-Mount — **0 net-new**

Alle Copy-/a11y-Keys **gebaut** (CYP-480, verifiziert @ `5fbd9e9c`): `remote_revoke_end_action` · `remote_revoke_confirm_title`
· `remote_revoke_confirm_body` · `remote_revoke_scope_note` · `remote_revoke_ttl_hint` (1 Arg, seam-gated) · `remote_revoke_ended`
· `a11y_remote_revoke_ended`. Diese Slice **liefert keine neue Copy** — nur die **Mount-Naht** (siehe `-ux-spec.md` §Seam-3 +
`-tags.md`). Reuse `agent_cancel` für den Dismiss (wie gebaut).

---

## Honesty-Anker (für §-QA)

- **HA — Graduierung capability-gegated, nie optimistisch (Seam #1):** die affirmative Form erscheint **nur**, wenn die
  Workspace-Daten **echt** über den Tunnel laufen (reales CR3-Capability-Signal), **nie** allein auf `conn==CONNECTED`.
  Fehlt das Signal, **bleibt korrekt die WARN-partial-Form** (CYP-527 G5). „Über verschlüsselten Tunnel" wird nie
  behauptet, solange die Daten es nicht tun.
- **HB — Gepinnt nur wenn echt gepinnt (Seam #1):** `remote_connect_trust_pinned` **present-iff** realer Fingerprint-Pin;
  bei provisorischem Trust **absent** (nie „Identität gepinnt" vortäuschen).
- **HC — In-flight ehrlich ungewiss, nie still erledigt (Seam #2, H4):** `workspace_relay_uncertain` erscheint **iff**
  `inFlightUncertain==true`; laufende Aktionen werden **nie** still als abgeschlossen dargestellt.
- **HD — Drop ≠ kaputt (Seam #2):** Reconnect = WARN-Amber, **nie** Rot; terminaler Verlust ist ein anderer Pfad.
- **HE — Revoke: garantiert vs. advisory (Seam #3):** „diese Verbindung sofort trennen" = **garantiert** (lokaler Teardown);
  Scope-Note „kein globales Revoke" = **advisory** — nie überzeichnet (Copy gebaut, Mount-Naht wahrt die Trennung).
- **HF — Neutral ≠ Grün:** kein Zustand dieser drei Nähte trägt Erfolgs-Grün. Affirmativ = neutral `●`; Drop/Ungewiss = WARN `▲`.

## Reuse (bestehende Keys — NICHT neu anlegen; verifiziert @ `5fbd9e9c`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `workspace_remote_context_partial` / `a11y_workspace_remote_context` | CYP-527 | die **andere Zustandsvariante** derselben Node (CONNECTED, Daten noch nicht über Tunnel) — unverändert |
| `remote_connect_trust_pinned` | CYP-480 | Gepinnt-Indikator im affirmativen Banner (Seam #1) |
| `remote_connect_relay_dropped` | CYP-471 | Reconnect-Banner-Text (Seam #2) |
| `remote_revoke_*` / `a11y_remote_revoke_ended` / `agent_cancel` | CYP-480 | Revoke-Control (Seam #3), 0 net-new |
| `severityColor(Severity.WARN)` | `eventlog/severityColor` | WARN-Ton (Drop/Ungewiss), Glyph + Text |

## Deferred / Koordination (NICHT in dieser Slice bauen)
- **CR3-① Copy-Swap** von `workspace_remote_context_partial` → „…Live-Daten laufen noch nicht über den Tunnel." (wenn
  nur Roster über Tunnel, Live-I/O noch nicht) — **CYP-527-Deferred, dort verankert**, reiner Copy-Update **desselben** Keys,
  kein neuer Key/Tag. Diese M2-Slice liefert die **Voll**-Form (`_tunnel`); die Zwischenstufe bleibt CYP-527s Deferred.
- **Latenz-/Degraded-Chip** im Banner (CYP-449 §4-Skizze): **nicht** Teil der drei PO-Nähte → **nicht** angelegt
  (`remote_conn_degraded` nie gebaut; bleibt out-of-scope bis eigenes Ticket).

## Self-Validation
- **Net-new gebaut: 4 Realkeys** — `workspace_remote_context_tunnel` [1 Arg] · `a11y_workspace_remote_context_tunnel` [1 Arg]
  · `workspace_relay_uncertain` [0 Arg] · `a11y_workspace_relay_uncertain` [0 Arg]. Alle DE+EN, Argument-Anzahl je Sprache identisch.
- **Argument-Keys:** 1-Arg (`%1$s`=`hub.name`): `workspace_remote_context_tunnel` + `a11y_workspace_remote_context_tunnel` = 2;
  0-Arg: die zwei `*_uncertain`-Keys. DE=EN Arg-Anzahl.
- **Kollision: 0** @ `5fbd9e9c` — `workspace_remote_context_tunnel` (CYP-527-reserviert, greenfield im Build) · `workspace_relay_uncertain`
  (+`a11y_`) greenfield gg. `strings.xml` (grep `workspace_relay`/`_context_tunnel` liefert nichts).
- **Kein content-tragender/sensibler Klartext** — `%1$s` = Hub-Anzeigename; **kein** Secret/Token/Fingerprint-Rohwert in Copy.
- **DE/EN-Parität:** jede gebaute Zeile beidseitig.
- **Seam #3 = 0 net-new** (Copy/a11y gebaut, verifiziert).
- Jeder gebaute Key ist im `-tags.md` (Binding & Guards) verankert; Ton-/Honesty-Anker im `-ux-spec.md`.
