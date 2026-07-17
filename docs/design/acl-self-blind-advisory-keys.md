# ACL Operator-Selbst-Erblindung — Advisory-Copy (i18n-Keys)

> Owner: UIUX-Designer · **kein Ticket** (④ aus `residual-exposure-disclosure-audit.md`, Auftraggeber-Entscheid
> 2026-07-17 „add an advisory, not a block") · Companion zu `acl-self-blind-advisory-spec.md` / `-tags.md` ·
> Stand 2026-07-17 · **Design/Copy-Pass, kein Bau.**
> Konvention gg. `app/shared/src/commonMain/composeResources/values/strings.xml` (DE-Default) +
> `values-en/strings.xml` (EN), snake_case Realkeys, positional `%1$s`, verifiziert @ develop `1da14371`.
> **DE → `values/strings.xml` · EN → `values-en/strings.xml`.**

## Kontext (eine Zeile)

Der Operator kann sich in der ACL-Matrix **sein eigenes** Lese-/Antwortrecht in einem Kanal entziehen
(`AclViewModel.kt:197`, `agentId == OPERATOR_ID`). Heute: READ öffnet einen Halte-Dialog (`acl_self_blind_warning`),
**WRITE ist komplett stumm**. Auftraggeber-Entscheid: **eine INFO-Advisory, kein Block, kein Dialog** — post-commit,
die Handlung + der Rückweg. Diese Copy liefert genau das, **symmetrisch für beide Dimensionen**.

## Neue Keys — Ton `INFO` (nichts kaputt; reversible, selbst hergestellt)

| Key | DE | EN |
|---|---|---|
| `acl_self_blind_read` | Du hast dir das Leserecht für %1$s entzogen — du siehst diesen Kanal nicht mehr live. Wieder einschalten, um ihn zurückzuholen. | You removed your own read access for %1$s — you no longer see this channel live. Switch it back on to restore it. |
| `acl_self_blind_write` | Du hast dir das Antwortrecht für %1$s entzogen — du kannst hier nicht mehr senden. Wieder einschalten, um es zurückzuholen. | You removed your own reply access for %1$s — you can no longer send here. Switch it back on to restore it. |

### a11y (Polite-Announce beim Erscheinen + Klausel der Zell-`stateDescription`, solange der Zustand hält)
| Key | DE | EN |
|---|---|---|
| `a11y_acl_self_blind_read` | Eigenes Leserecht für %1$s entzogen — wieder einschalten, um es zurückzuholen. | Your own read access for %1$s removed — switch it back on to restore. |
| `a11y_acl_self_blind_write` | Eigenes Antwortrecht für %1$s entzogen — wieder einschalten, um es zurückzuholen. | Your own reply access for %1$s removed — switch it back on to restore. |

## Warum zwei Keys statt einem (Honesty-Fund)

„Self-blind" ist **READ-zentrisch**: eigenes **Lesen** aus → du *erblindest* (siehst nicht mehr).
Eigenes **Antworten** aus → du *verstummst* (kannst nicht mehr senden), **siehst aber weiter**. „Erblinden" ist für
WRITE schlicht **falsch** — und genau dieses READ-zentrische mentale Modell ist vermutlich der Grund, **warum
WRITE übersehen wurde** (`AclViewModel.kt:197` ist `dimension == READ`-gated). Eine parametrisierte Ein-Satz-Copy
müsste entweder lügen („erblindet" bei WRITE) oder verwaschen. Zwei dimensions-spezifische Sätze sind die ehrliche
Form — je die **reale** Konsequenz („nicht mehr live" vs „nicht mehr senden").

## Ton-Begründung (`INFO`, kein Alarm — Auftraggeber-Constraint ③/④)

- **Kein `ERROR`/kein Rot:** nichts ist fehlgeschlagen; der Operator hat eine **legitime, reversible** Aktion
  ausgeführt. Alarm-Rot wäre „eine Lüge in die andere Richtung" (Auftraggeber) und würde die Advisory faktisch zum
  Guard machen (soziale Reibung statt technischer Block).
- **Kein `EFFECT_DEFERRED`:** es ist nichts „gespeichert, greift später" — die Aktion ist **sofort** wirksam
  (post-commit). Die Advisory beschreibt einen **bereits eingetretenen, stehenden** Zustand, nicht einen latenten.
- **Kein Scare-Wortlaut:** kein „Achtung"/„Wirklich?"/„Gefahr". Die Sätze **benennen** (Handlung), **erklären**
  (Konsequenz) und **weisen den Rückweg** (Wieder einschalten). Fällt die Copy in Warn-Duktus, ist sie falsch — sie
  beschreibt einen reversiblen Zustand, den der Nutzer selbst herstellte.

## Handlung + Rückweg (Auftraggeber-Constraint ②, Vorgabe-Formulierung)

Jede Copy trägt beide Teile, in dieser Reihenfolge:
1. **Handlung** — „Du hast dir das X-Recht für %1$s **entzogen**" (der Nutzer erkennt, was er gerade tat; Aktiv,
   nicht „dieser Kanal ist leer").
2. **Konsequenz** — „du siehst … nicht mehr live" / „du kannst hier nicht mehr senden".
3. **Rückweg** — „**Wieder einschalten**, um … zurückzuholen" (der Undo **ist** derselbe Schalter; kein separater
   CTA, keine Reibung).

## Reuse / Retire

- **Reuse Domänen-Wörter:** „Leserecht"/„read access" ↔ `acl_read` (Lesen/Read); „Antwortrecht"/„reply access" ↔
  `acl_write` (Antworten/Reply). Kein drittes Vokabular.
- **Reuse Marker-Fläche:** dieselbe per-Zelle-`cellNotice`→`StateMarker`-Bahn wie `acl_po_protected`
  (`AclPanel.kt:310,366`) — neutraler Inline-Marker, **kein** Error-Banner (`state.notice` ist errorContainer,
  `AclPanel.kt:133-134` — **nicht** verwenden).
- **RETIRE `acl_self_blind_warning`** („Damit erblindet dein Live-Feed …") — **B1 vom Auftraggeber ENTSCHIEDEN**
  (2026-07-17): READ zieht auf die Inline-Advisory um, der selfBlind-Ast in den Halte-Dialog fällt weg. Die alte
  Dialog-Copy ist „_warning"-getönt (Alarm) + READ-zentrisch + im Halte-Dialog — genau das, was der Entscheid
  ablöst. **Ablösung `acl_self_blind_warning` → `acl_self_blind_read` + `acl_self_blind_write`.**
  ⚠ **PO-KOORDINIERT, nicht einseitig:** der Key hängt am geteilten CYP-7-Contract (Team-2 liest mit) — der **PO**
  fährt die Ablösung mit po2; ich benenne nur exakt (spec §5.1). **Nicht** retiren: `acl_po_lockout_warning` /
  `acl_po_protected` (der PO-Lockout-Dialog lebt weiter).

## Self-Validation

- **4 neue Keys** (2 sichtbar + 2 a11y), alle DE+EN befüllt.
- **Argument-Anzahl:** alle **1 Arg** (`%1$s` = Kanalname); DE==EN je Zeile (1 == 1).
- **Kein sensibler/content-tragender Klartext:** kein Token/Recht-Rohwert; nur Kanalname interpoliert (schon
  überall sichtbar).
- **Kollision:** `acl_self_blind_read`/`_write` + `a11y_*` gg. `name="acl_self_blind"` @ `1da14371` = nur der
  bestehende `acl_self_blind_warning` (den B1 retired) — keine Kollision mit den neuen Real-Keys.
- **DE/EN-Parität:** jede Zeile beidseitig; Domänen-Wörter (`acl_read`/`acl_write`) konsistent gespiegelt.
- **Ton konsistent:** beide Sätze INFO, kein Alarm-Duktus, Handlung→Konsequenz→Rückweg (Constraint ②③④).
- Jeder Key ist in `acl-self-blind-advisory-spec.md` verankert und in `-tags.md` einem Tag/Ton/Politeness zugeordnet.
