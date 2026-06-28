# i18n-Keys — ACL-Matrix-UI (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-19** · Status: **Entwurf** · Stand: 2026-06-28
> Begleitend zu `docs/ACL-MATRIX.md`. `compose.resources` → **Underscore-Real-Keys** (gepunktete Form = menschlicher Namespace). Platzhalter positional (`%1$s`).
> **Reuse (nicht dupliziert):** `comm_back` (Single-Pane Zurück) und `comm_status_offline` (Stale-Banner, no-arg „evtl. nicht aktuell") aus CYP-17 (`comm-panel-keys.md`); Rollen-/Kanal-Typ-Labels aus CYP-14 (`color-coding-keys.md`); Status-Wortregeln aus CYP-12.

## 1. Rahmen / Titel / Sicht

| Namespace (human) | Real-Key | DE (Quelle) | EN |
|---|---|---|---|
| `acl.title` | `acl_title` | Zugriffsrechte (Kanal × Teilnehmer) | Access rights (channel × participant) |
| `acl.empty` | `acl_empty` | Keine Kanäle oder Teilnehmer | No channels or participants |
| `acl.partial.view` | `acl_partial_view` | Teilansicht – nur deine Kanäle. Vollständige Matrix nur als Operator. | Partial view – only your channels. Full matrix requires operator. |
| `acl.operator.required` | `acl_operator_required` | Nur der Operator darf Zugriffsrechte ändern | Only the operator can change access rights |
| `acl.unauthorized` | `acl_unauthorized` | Nicht angemeldet | Not authenticated |

## 2. Rechte / Schalter (R/W)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `acl.read` | `acl_read` | Lesen | Read |
| `acl.write` | `acl_write` | Antworten | Reply |
| `acl.granted` | `acl_granted` | gewährt | granted |
| `acl.denied` | `acl_denied` | gesperrt | denied |
| `acl.non.member` | `acl_non_member` | kein Mitglied | not a member |
| `acl.write.only.hint` | `acl_write_only_hint` | Antworten ohne Lesen – ungewöhnlich | Reply without read – unusual |
| `acl.conflict` | `acl_conflict` | Mehrfacheintrag – strengster Wert gilt | Duplicate entry – strictest value applies |

> `acl_write` = **„Antworten"** (nicht „Schreiben") — bewusst, weil `canWrite` im Hub „in den Kanal antworten" meint (02 §5.3/§6.3); konsistent mit der Hub-and-Spoke-Sprache.

## 3. Editier-Zustände (Enforced vs. Optimistic — Disclosure)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `acl.pending` | `acl_pending` | wird übernommen… | applying… |
| `acl.enforced` | `acl_enforced` | durchgesetzt | enforced |
| `acl.change.failed` | `acl_change_failed` | Änderung nicht bestätigt – erneut versuchen | Change not confirmed – retry |

## 4. PO-Aussperr-Leitplanke (advisory; Durchsetzung = Server CYP-49)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `acl.po.critical` | `acl_po_critical` | PO-kritisch – trägt Hub-and-Spoke | PO-critical – carries hub-and-spoke |
| `acl.po.lockout.warning` | `acl_po_lockout_warning` | Damit verliert der PO Lese-/Antwortrecht in %1$s – Hub-and-Spoke bricht. Wirklich? | This removes the PO's read/reply right in %1$s – hub-and-spoke breaks. Continue? |
| `acl.self.blind.warning` | `acl_self_blind_warning` | Damit erblindet dein Live-Feed für %1$s; du kannst weiter editieren, siehst aber keine Live-Änderungen mehr. | This blinds your live feed for %1$s; you can still edit but won't see live changes. |
| `acl.po.protected` | `acl_po_protected` | Geschützt: würde den PO aussperren – Änderung abgelehnt | Protected: would lock out the PO – change rejected |

> `acl_po_protected` = die **ehrliche** Server-Ablehnung (CYP-49): **HTTP 409 + `ApiError.code = "po_lockout_protected"`** (PO-festgelegt 2026-06-28). Wortwahl trennt sie klar von `acl_operator_required` (403, fehlende Berechtigung) — hier ist die Berechtigung da, aber die Änderung selbst ist unzulässig.

## 5. Preset „Hub-and-Spoke wiederherstellen" (N per-Entry-PUTs, nicht-atomar)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `acl.preset.restore` | `acl_preset_restore` | Hub-and-Spoke wiederherstellen | Restore hub-and-spoke |
| `acl.preset.preview` | `acl_preset_preview` | %1$s Zellen ändern sich | %1$s cells will change |
| `acl.preset.applying` | `acl_preset_applying` | Wird wiederhergestellt… %1$s/%2$s | Restoring… %1$s/%2$s |
| `acl.preset.restored` | `acl_preset_restored` | Hub-and-Spoke wiederhergestellt | Hub-and-spoke restored |
| `acl.preset.partial` | `acl_preset_partial` | %1$s/%2$s wiederhergestellt – %3$s fehlgeschlagen, erneut versuchen | %1$s/%2$s restored – %3$s failed, retry |

> `acl_preset_partial`/`acl_preset_applying` existieren **gerade**, weil das Preset nicht-atomar ist (§7) — Teilausfall nie als „wiederhergestellt" verschweigen.

## 6. Accessibility-Keys (Farbe nie alleiniger Träger)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `a11y.acl.cell` | `a11y_acl_cell` | %1$s in Kanal %2$s: Lesen %3$s, Antworten %4$s | %1$s in channel %2$s: read %3$s, reply %4$s |
| `a11y.acl.cell.nonmember` | `a11y_acl_cell_nonmember` | %1$s ist kein Mitglied von %2$s | %1$s is not a member of %2$s |
| `a11y.acl.toggle.read` | `a11y_acl_toggle_read` | Leserecht für %1$s in %2$s umschalten | Toggle read for %1$s in %2$s |
| `a11y.acl.toggle.write` | `a11y_acl_toggle_write` | Antwortrecht für %1$s in %2$s umschalten | Toggle reply for %1$s in %2$s |
| `a11y.acl.pending` | `a11y_acl_pending` | Änderung wird übernommen, noch nicht bestätigt | Change applying, not yet confirmed |
| `a11y.acl.po.critical` | `a11y_acl_po_critical` | PO-kritische Zelle | PO-critical cell |

### Disclosure-kritische Wortwahl
- `acl_pending` = **„wird übernommen"**, nicht „geändert/gesetzt", bis `AclEvent`/200 bestätigt — die Zelle ist erst dann `acl_enforced`.
- `acl_po_protected` ≠ `acl_operator_required`: ehrliche Trennung „Änderung unzulässig (Server-Guard CYP-49)" vs. „dir fehlt die Berechtigung".
- `acl_non_member` benennt die **Membership-Achse** ehrlich — kein totes Grau-Toggle, das Editierbarkeit vorspiegelt.
- `acl_preset_partial` macht den **nicht-atomaren** Reset transparent (kein „erledigt" bei Teilerfolg).
- `acl_denied` ist **neutral** („gesperrt"), kein Fehler — ein entzogenes Recht ist ein gültiger Zustand.

> **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → konsumierendes Modul (CYP-48-Impl + Test-Modul CYP-7) muss re-syncen. **Lieferung mit der CYP-48-Umsetzung timen.**
