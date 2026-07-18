# CYP-407 / W9 — Comm-Panel + ACL-Matrix: UX-QA-Conformance-Pass (Spec 14 §7.2/§7.3) — kein Neubau

> Owner: UIUX-Designer (Team-2) · **Ungated High-Fill** · Stand 2026-07-18 · **Design-QA, kein Code.** Gegroundet
> READ-ONLY @ develop `025b17ae`. Companion: `14-Web-HTML-Rewrite.md` §5/§7, `09-UI-Funktionskatalog`.
>
> **Kernbefund (am Objekt gemessen): W9 ist bereits GEBAUT + getestet** — `web-ts/src/comm/{CommPanel,AclPanel,
> AclMatrix}.tsx` (+ `.render.test.tsx`, `aclModel`, `aclPreset`, `commDisclosure`, `senderAccent`), Header
> „CYP-407 (W9)". Dies ist daher **kein Neubau-Spec, sondern ein UX-QA-Conformance-Pass** (meine Lane: gebaute
> Screens gegen die load-bearing Invarianten prüfen) — **die vom PO verlangte Eigenschaft (ACL display-only,
> Server = Autorität, §7.3) im Bau verifiziert + evidenzbelegt**, plus **1 echt offener UIUX2-Design-Input (§7.2
> Markdown/Sanitizer)** und eine Scope-Frage.

---

## 1. Conformance-Matrix — die load-bearing Invarianten (alle ✅ HELD, Evidenz file:line)

### §7.3 — ACL-Durchsetzung serverseitig, UI **nur Anzeige, nie Autorität** (die vom PO betonte Eigenschaft)
| Invariante | Status | Evidenz |
|---|---|---|
| Enforced-Wert **≠ optimistischer Klick** (a11y-Ebene) | ✅ | `AclMatrix.tsx:3-4` + `:61` `aria-checked={s.checked}` = ENFORCED (Kommentar: „never the optimistic click"); `aria-busy={s.pending}` |
| Switch flippt **nur auf den Server-`AclEvent`-Echo** (non-optimistic) | ✅ | `AclPanel.tsx:7-8` „the ENFORCED switch flips only on the AclEvent echo (non-optimistic)"; Compose-Parität `AclViewModel.kt:72` „optimistically but **not enforced** until the hub echoes" |
| Rejected PUT (z. B. 409 Lockout) = **transiente Notice, kein stuck switch** | ✅ | `AclPanel.tsx:24-25`+`:80-84` `role="alert"` transient reject (CYP-435) |
| PO-Lockout = **advisory Confirm** (Server ist der echte Guard, 409 `po_lockout_protected`) | ✅ | `AclPanel.tsx:3-4`+`:103-125` `role="alertdialog"`, WARN-`▲` aria-hidden, Text trägt Bedeutung |
| Preset **non-atomisch** (1 PUT/Zelle) → nie ein stilles „done" | ✅ | `AclPanel.tsx:5-6`+`:68-76` |
| Kanäle **server-gefiltert, nie client-gefiltert** (Client-Filter „would lie about access") | ✅ | `CommPanel.tsx:2-5` |
| Non-Operator = **read-only Chips, keine Switches** | ✅ | `AclMatrix.tsx:52-55` (`readOnly` → `<span>` „Lesen: erlaubt/—"), `AclPanel.tsx:100` `readOnly={!operator}` |
| Non-Member = **„kein Mitglied"-Marker, kein disabled Switch** (grantable, CYP-317) | ✅ | `AclMatrix.tsx:44-47` |
| **No-Roster-Leak-by-Absence** (Humans nur für Operator, strukturell absent) | ✅ | `AclMatrixTags.kt:36-42` (Human-Bänder), `AclPanel.tsx` humanCells operator-gated |

### §7.2 — DOM statt Canvas → XSS-Oberfläche (untrusted Agenten-/Kanalinhalt)
| Invariante | Status | Evidenz |
|---|---|---|
| **Kein `innerHTML`/`dangerouslySetInnerHTML`** auf untrusted Content | ✅ | `CommPanel.tsx:83` `{m.body}` = React-JSX-Text-Escaping (inert); `AgentTranscript.tsx:3` „NO innerHTML anywhere" |
| **Repo-weit regressions-geschützt** | ✅ | `agentview/noInnerHtml.test.ts` (CYP-456) scannt `web-ts/src` auf `dangerouslySetInnerHTML`/`.innerHTML`/DOM-Injection → **fail-closed Guard-Test** |
| Server-Masking **nicht unterlaufen** (kein Roh-Feld gerendert, das Compose maskiert) | ✅ | `CommPanel.tsx:83` rendert nur den server-gelieferten `body`, kein Roh-Alternativfeld |

### Honesty / States / A11y (meine Standard-QA-Achsen)
| Achse | Status | Evidenz |
|---|---|---|
| Farbe nie alleiniger Träger (Sender = Accent **+ Text**) | ✅ | `CommPanel.tsx:80-82` `senderAccent(...)` + `{m.from}` |
| Terminal-Revoke (WS 1008) **schließt den Composer ganz** (fail-closed, kein „nur server-seitig fehlschlagen") | ✅ | `CommPanel.tsx:35-37`+`:90-93` |
| Distinkte Write-Disclosure-States (readonly / denied / failed) — nicht ein generischer | ✅ | `CommPanel.tsx:94-109` (`composerDisclosure`) |
| Empty **≠** Offline (distinkt) | ✅ | `CommPanel.tsx:71-74` (`comm-empty`) vs `:60-68` (`comm-status`, role=status polite) |
| ACL-Matrix a11y: native `<table>` scope-Header, `role="switch"`, `aria-checked/busy` | ✅ | `AclMatrix.tsx:23-79` |

**Verdikt: W9/CYP-407 ist gebaut UND conformant** zu §7.2 (XSS-safe + regressions-geschützt) und §7.3 (ACL
display-only, Server = Autorität) sowie den Honesty/State/A11y-Achsen. **Kein Refinement nötig für diese
Invarianten.**

## 2. Der eine echt offene UIUX2-Design-Input (§7.2) — Markdown/Sanitizer

Spec 14 §7.2 markiert explizit `⟨INPUT: UIUX2/Dev5 → Markdown/Sanitizer-Wahl⟩`. **Ist-Stand:** `CommPanel.tsx:83`
rendert `body` als **Plain-Text** (React-escaped) — **XSS-safe, aber kein Markdown**.
- **Entscheidungspunkt:** ist **Plain-Text** das ratifizierte W9-Design (dann: fertig, sicher, kein Sanitizer
  nötig) — **oder** soll die Comm-Timeline **Markdown** rendern (Fett/Code/Listen, Parität zum evtl. Markdown im
  Compose-`CommPanel`)?
- **Falls Markdown gewünscht:** dann wird der **Allowlist-Sanitizer XSS-kritisch** (§7.2/§9). Meine Empfehlung
  (spec-fertig auf Anfrage): **Allowlist-only** (kein Roh-HTML; erlaubte Nodes = Emphasis/Code/`<a>` mit
  `rel="noopener noreferrer"` + Protokoll-Allowlist; **kein** `dangerouslySetInnerHTML` — ein React-Markdown-
  Renderer, der zu escaped Nodes kompiliert, damit der `noInnerHtml`-Guard weiter grün bleibt) + **XSS-Regression-
  Zahn** (§8.4: `<script>`/`<img onerror>`-Payload rendert inert). **Das ist der einzige Ort, an dem ich echten
  neuen Spec-Wert liefere.**
- **Parität-Check offen:** rendert der Compose-`CommPanel` den Body als Markdown oder Plain? → bestimmt, ob
  Plain-Text hier ein bewusster Parität-Entscheid oder eine Lücke ist. (Kann ich prüfen, wenn gewünscht.)

## 3. Scope-Frage an PO (nicht-blockend)

CYP-407/W9 ist **gebaut + getestet + conformant** (§1). Damit ist „DOM-Design speccen, Dev5 baut" für die
**Kern-Flächen erledigt**. Was ist der gewünschte CYP-407-Rest?
- **(a)** = **Conformance-QA bestätigen** (§7.3 display-only / §7.2 XSS) → **hiermit geliefert** (dieser Pass).
- **(b)** = **Markdown/Sanitizer-Input** (§2) → sag „ja Markdown", dann liefere ich die Allowlist-Sanitizer-Spec.
- **(c)** = ein **spezifisches Refinement**, das dem gebauten Surface fehlt (Feld/State/Interaktion) → nenne es,
  ich spece **den Delta** (nicht das ganze, gebaute Surface neu).

Ich habe **(a)** gemessen + belegt und erfinde **keine** Arbeit am fertigen Surface. **(b)/(c) = deine Wahl.**

## 4. Self-Validation
- **Verify-existence gehalten:** W9 am Objekt als gebaut+getestet erkannt → QA-Pass statt Duplikat-Spec.
- **Gegroundet @ `025b17ae`:** `CommPanel.tsx` · `AclPanel.tsx` · `AclMatrix.tsx` · `AclViewModel.kt:72` (Compose-
  Parität) · `AclMatrixTags.kt` · `noInnerHtml.test.ts`.
- **PO-Load-Bearing gespiegelt:** §7.3 ACL display-only/Server-Autorität ist im Bau **verifiziert HELD** (§1),
  nicht nur behauptet — genau „spiegel das in der Spec, kein Client-State-Vertrauen".
- **Ehrliche Grenze:** Conformance ist **render-test-/code-belegt** (JSDOM, kein Browser); Laufzeit-XSS gegen den
  echten Server = Tester2/E2E (§8.4/§8-„grün≠funktional"). Kein Pixel/Runtime-Claim ohne Bestätigung.
- Kein Bau; docs-only auf `feature/CYP-407-w9-comm-acl-uxqa` (Basis `025b17ae`).
