# Phase-2 — Desktop (CMP) ↔ web-ts Feature-Parität, Remote-Hub-Fokus · UX-Spec (Vorarbeit)

**Für:** PO + Dev5 (großer Phase-2-Strang) · **Von:** UIUX2 (Team-2) · **Baseline:** develop `8a5a7745` (am Objekt gemessen 2026-07-19)
**Modus:** Vorarbeit/Gap-Map — noch kein Screen-für-Screen-Spec, sondern die **ehrliche Parität-Klassifikation**, damit Dev5s Phase-2 nicht Unmögliches portiert.
**Tooling-Grenze:** Gap gemessen via Dir-/Header-Read (statisch); Runtime/Pixel = guided-human.

---

## §0 Kernaussage (eine Zeile)
web-ts hat vom Remote-Hub **fast nichts** — **1 Datei** (der CYP-676 Tier-Disclosure-Badge, und der ist **un-wired**) gegen **~48** CMP-Dateien (29 `connect/` + 11 `operator/ui` + 8 `trust`). **ABER: der native Remote-Hub-Stack (Noise-E2E + TOFU-Pinning) ist strukturell Desktop-only** — web-ts-Parität ist der **Browser-Gateway-Pfad** (dokumentiert schwächer, CYP-676), **NICHT ein 1:1-Port**. Die Optik nicht über eine strukturelle Grenze portieren ([[migration-ports-tokens-not-optics]]).

## §1 Die strukturelle Grenze (prägt die GANZE Parität — zuerst lesen)
- **Native remote (Desktop):** Noise-E2E-Transport + **TOFU-Pinning** (client-Pin des Hub-DH-PubKey, `TofuHubTrust` CYP-478) + **OOB-Fingerprint-Bestätigung** (PGP-Wörter, `HubFingerprintDisplay`/`PgpWordList`) + `RemoteHubSession` + Operator-Auth + Relay/Mux/Pool. `buildRemoteHubTransport` ist `expect/actual`; der native Noise-Pfad läuft auf JVM/Desktop.
- **★ Der Browser kann KEIN Noise-Endpunkt sein** (kein client-Pin, RR6 — cyp638/CYP-676). Browser-remote = **Gateway-Pfad** (Browser —TLS→ Gateway —Klartext→ Hub): Gateway sieht den Verkehr im Klartext, die SPA/JS ist server-serviert ohne unabhängigen Pin.
- **Konsequenz:** die native **Pinning-/Fingerprint-/Noise-UIs haben KEINEN Browser-Port.** Ihr web-ts-„Äquivalent" ist die **ehrliche Tier-Disclosure** (CYP-676 `RemoteSecurityTierBadge`, existiert). **Dev5 soll NICHT versuchen, die Pinning-UI in den Browser zu portieren** — das wäre eine Optik ohne die dahinterliegende Garantie (die gefährlichste Art Un-Ehrlichkeit).
- **Gating (CYP-676 Option A):** Browser-Gateway-remote **nur für Self-Hosted** (Operator besitzt gateway+CP+hub). Cyppie-gehosteter-Browser-Gateway = separater späterer §4b, durch Phase-2 **NICHT** aktiviert.

## §2 Parität-Gap-Map (Remote-Hub · da / halb / fehlt · Klasse)
| CMP-Feature | web-ts heute | Klasse |
|---|---|---|
| Connect-Mode-Chooser (local / remote) | **fehlt** | Gateway-Parität |
| Remote-Hub-Ziel eingeben (Gateway-URL / Rendezvous) | **fehlt** | Gateway-Parität |
| Noise-E2E-Transport | — | **native-only, kein Port** → Gateway-TLS |
| TOFU-Pinning + OOB-Fingerprint (PGP-Wörter, `PinnedHubStore`) | **fehlt** | **native-only, kein Port** → Tier-Disclosure |
| Trust-Delta-Disclosure (native vs gateway) | **DA** (`RemoteSecurityTierBadge`, CYP-676) — **aber un-wired** | **wire-needed** |
| Operator-Auth (UV/PIN, `OperatorAuthDialog`) | **fehlt** | Gateway-Parität *(falls Gateway-Pfad Operator-Auth braucht — §5)* |
| Remote-Session-State (verbinden / verbunden) | **fehlt** | Gateway-Parität |
| Remote-Revoke-Control | **fehlt** | Gateway-Parität |
| Recovery-Codes (reveal / verify, `RemoteRecovery*`) | **fehlt** | Gateway-Parität *(falls im Gateway-Modell relevant — §5)* |
| Tunnel-Pool-Status | — | **native-only** (Noise-Tunnel) → im Gateway n/a |
| Hub-Discovery (`online`/`lastSeen`, advisory H1) | **fehlt** | Gateway-Parität (advisory) |

## §3 Was web-ts für Parität braucht (der Gateway-adaptierte Strang, priorisiert)
1. **★ Browser-Gateway-Connect-Flow** — Mode wählen + Gateway-Ziel eingeben + verbinden. **Der Einstieg fehlt komplett**; ohne ihn ist alles andere (inkl. der bereits gebauten Tier-Badge) tot. **Höchste Priorität.**
2. **Tier-Disclosure wiren** — `RemoteSecurityTierBadge` existiert (CYP-676), aber `RemoteSecurityTierBadge.tsx:10`: *„NOT wired into the browser-remote flow — mapping live-connection → tier is the S7-gated Backend/transport seam"*. Sobald der Flow (1) steht: live-connection → tier mappen, **BROWSER_GATEWAY immer-sichtbare INFO-Disclosure**, fail-closed default UNKNOWN (nie optimistic NATIVE-green).
3. **Remote-Session-State + Revoke** — verbunden/trennen, ehrlich (non-optimistisch wie überall).
4. **Operator-Auth (§5-reconcile)** — falls der Gateway-Pfad Operator-UV braucht: den bestehenden `OperatorAuthDialog`-Flow gateway-adaptieren.
5. **Discovery / Recovery** — je nach Gateway-Modell (advisory `online`/`lastSeen`; Recovery falls relevant).

## §4 Honesty-Leitplanken (aus CYP-676, tragen 1:1 in Phase-2)
- **native = starke E2E-Garantie mit Pin; browser-gateway = dokumentiert schwächer** — **immer-sichtbare** INFO-Disclosure (nie tap-to-reveal — einen Downgrade zu verstecken wäre die Lüge), fail-closed default **UNKNOWN**, nie optimistic-NATIVE-green. (Der Badge macht das schon; der Flow muss den Tier ehrlich einspeisen.)
- **Abwesenheit der nativen Features ist die ehrliche Disclosure, kein Fehlen zum Kaschieren** — nie eine Pin-/Fingerprint-Optik faken, die es im Browser nicht gibt (das wäre „stark aussehen ohne stark zu sein"). [[token-contrast-is-role-dependent]]-Schwester: die Optik muss die reale Garantie tragen.
- **Scope strikt Self-Hosted (Option A)** — an operator-owned gateway+CP+hub gebunden; cyppie-hosted-Browser-Gateway bleibt draußen.

## §5 Offene Reconcile-Punkte (für PO / Backend2 — vor Dev5s Fest-Bau)
- **Server-Gateway (CYP-638): existiert er / wie weit?** Das ist die **S7-Naht** (live-connection → tier). Ohne Server-Gateway ist der ganze Strang blockiert (wie 705 auf die Backend-Fläche).
- **Braucht der Gateway-Pfad Operator-Auth/UV**, oder ist Operator-Auth native-only? (Gateway sieht Klartext — Operator-Autorisierung könnte trotzdem nötig sein.)
- **Welche native-Features sind im Gateway-Modell relevant** (Recovery, Discovery) vs. entfallen (Pinning, Tunnel-Pool)?
- **Identitäts-Kopplung:** teilt der Gateway-Connect eine Auth-/Identitäts-Entscheidung mit CYP-704-NOTIFY / 706-Menschen-Präsenz? (content-free `AuthMe`.)

## §6 Sekundäre (nicht-Remote) Paritäts-Gaps — kurz, am Objekt
- **crossproject:** web-ts hat **Teil-Parität** — `ChannelSharePanel` (CYP-659) + der Event-Browse Cross-Project-Axis; CMP hat ein eigenes `crossproject/`-Modul → **prüfen, ob web-ts alle CMP-crossproject-Flächen deckt** (halb).
- **firstrun:** CMP hat ein `firstrun/`-Modul; web-ts hat `workspace_unconfigured*`-Banner, aber **evtl. kein volles First-Run-Onboarding** → verifizieren (mögliches Gap).
- **migration:** CMP `migration/` = Store-Infra, **kein UI-Paritäts-Anteil** (aus dem UX-Scope raus).
- Gemeinsame Flächen (comm, acl, agentmgmt, agentsettings, agentview, eventlog, report, settings, terminal, connector, compact, workspace, window) sind **beidseitig vorhanden** — kein Struktur-Gap (Detail-Fidelity je Fläche separat prüfbar, nicht in diesem Remote-fokussierten Pass).

## §7 Übergabe an den Koordinator
- **Der load-bearing Punkt: Parität ≠ Port.** Die strukturelle Grenze (Noise Desktop-only) macht web-ts-Remote zum **Gateway-Pfad mit ehrlicher Tier-Disclosure**, nicht zur Kopie. Dev5s Phase-2 baut den **Gateway-Connect-Flow (§3.1) + wired die Disclosure (§3.2)**; die Pinning/Noise/Tunnel-UIs **entfallen durch die Grenze**, nicht durch Auslassung.
- **Blockiert auf §5** (v.a. den Server-Gateway CYP-638 / die S7-Naht) — wie 705 auf eine Backend-Fläche.
- **Reuse:** der Tier-Badge (CYP-676) existiert; die CMP-Flows (`HubConnect*`, `RemoteRevokeControl`, `OperatorAuthDialog`) sind **Referenz-Vorlagen** für die Gateway-Adaption (Struktur/Copy übernehmen, native-Krypto weglassen).
- **Nächster Schritt nach §5-Klärung:** je Gateway-Parität-Feature (§3) ein Screen-Spec — dann ist es build-fertig.
