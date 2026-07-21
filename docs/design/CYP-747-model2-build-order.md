# CYP-747 — Modell-2 Trust-Bau-Order (Backend)

> PO → Backend, 2026-07-21. **Autorisierung: PL-§4b-GO UNBEDINGT erteilt** (2 Adversarial-Runden + PL-Text-Gate am Objekt). Bau frei **nach der CYP-775-Kette** (erledigt, develop `5042ae8c`).
> **Design-Grundlage (verbindlich):** `docs/design/CYP-747-model2-issuer-trust-design.md` @ **`db1f879c`** (Rev.3.5). Diese Order ändert das Design NICHT — sie ist die Bau-Anweisung + die gehärteten Gate-Auflagen.
> **Kein Staging-Deploy.** Abo-Creds für echte Läufe (nie API-Key ohne Ansage). 2-Prinzipal-Architektur (Operator+Agent, keine Fremden) gilt.

## 0. Was zuerst zu tun ist (dein nächster Schritt)

**Design-Read (`db1f879c`) + Slice-Plan-Vorschlag (design-pass-first).** Zerlege den Bau in demonstrierbare Slices und leg mir den Plan vor — ich gate slice-für-slice (mutations-bewiesene Zähne + Reviewer + mein Merge). **Ich micro-decomponiere die Trust-Implementierung nicht für dich; du schlägst den Slice-Schnitt vor, ich gate.** Zwei harte Sequenz-Regeln:
- **Steps 1–2 zuerst; die C3-Widerruf-Fläche (Schritt 3) NICHT anfassen, bis der god-token-Invariante-Zahn (§3 unten) im Slice steht.**
- Jeder Slice: Zähne mutations-bewiesen (jede Mutation rötet GENAU ihr Bein), `:server:test` + `:e2e:test` grün VOR Merge.

## 1. Scope (aus dem Design)

Aussteller-Vertrauen für Cross-Hub-Operator-Identität: Issuer=eigenes-Relay (Q3), Rr3TunnelGate/Device-PoP-Anti-Seizure (steady-state code-belegt), `resolvePrincipal`→OPERATOR-Chokepoint mit **AAL2** (Cookie-Achse), aktiver Widerruf a2 (Q2, schließt CYP-697), Seat-scoped (Q1). Browser-Operator-Pfad HEUTE nur self-hosted/loopback (§9.4/§9.5).

## 2. Loopback-Disable NUR Cookie-Achse (Forward-Note → Zahn)

Off-loopback `config.hub.host` (`InetAddress…isLoopbackAddress`, NICHT String) → **Browser-AAL2-OPERATOR (Cookie) DISABLED**; **Token-Achse (`isOperator`) bleibt AN**. Reviewer-Build-Gate, beide Richtungen pinnen:
- **MUT-a:** „off-loopback → Cookie-OPERATOR trotzdem an" → ROT.
- **MUT-b (Gegenrichtung, Scope):** „off-loopback → Token-OPERATOR AUCH aus (Disable zu breit)" → ROT.
- `host="localhost"` → ENABLED · `host="0.0.0.0"` → DISABLED (beide Fälle, nicht nur `127.0.0.1`).

## 3. ★ God-Token-Invariante (PL-HARTE-Auflage, als ZAHN nicht Prosa)

Der statische Machine-Operator-Token („god") umgeht TTL/Widerruf by design, ist aber by-design-safe, WEIL `TunnelGodTokenGuard` ihn auf jedem Tunnel-Connector refüsiert (port-diskriminiert, server-seitig). **Diese Sicherheit ist konditional darauf, dass JEDES Remote-Ingress im Guard-Set liegt.** Modell-2/CYP-702 fügt Remote-Ingress hinzu (eigenes Relay) →

> **ZAHN (Closure, gefunden==gepinnt — wie 8(a)-① eine Ebene tiefer, gebunden an §9.5-kein-3.-Ingress):** jeder neue Relay-/Browser-/Tunnel-Connector ∈ `guard.tunnelPorts`. Ein operator-fähiges Remote-Ingress **außerhalb** des Guard-Sets → god-Token remote-präsentierbar → **ROT**. Non-vakuos: neuer Ingress-Connector ohne `tunnelPorts`-Eintrag rötet die Closure.

**Diesen Zahn bauen, BEVOR die C3-Widerruf-Fläche angefasst wird.**

## 4. B1/B2 — Build-Gate-Verify (kein Re-Design, am Bau prüfen)

- **B1:** das AAL/`amr`/`acr`, das `resolvePrincipal` liest, MUSS aus **server-seitiger Kratos-Session-Introspektion** kommen — NIE aus einem client-lieferbaren Feld/Header/Cookie-Claim (sonst ist das AAL2-Gate selbst fälschbar).
- **B2:** statischer `operatorToken` nie im Browser-Kontext (kein Endpoint liefert ihn, nicht in web-served Config) — sonst bricht die Cookie-vs-Token-Achsentrennung (§9.2).

## 5. §11-Zähne (der Bau MUSS sie tragen — Reviewer-Build-Gate-Checkliste)

Alle §11-Zähne aus `db1f879c` mutations-bewiesen, plus explizit:
- **8(a) drei Beine als ECHTE Zähne** (nicht Prosa): ① Typ-Konstruktions-Closure (`gefunden==gepinnt`; MUT „neue `Human(id,OPERATOR)`/`TerminalPrincipal.Operator`-Mint ohne Registry-Eintrag" → ROT; Anti-Vakuität: `:121` raus → bleibt grün ⟹ Literal-Variante muss AUSGESCHLOSSEN sein) · ② `isOperator`-Call-Site-Pin (MUT „neue Call-Site ungepinnt" → ROT) · ③ Single-Cookie-Consumer (MUT „`routing/` liest `ory_kratos_session` direkt" → ③a ROT; „`routing/` ruft `whoami()` direkt statt via `resolvePrincipal`" → ③b ROT; positive Detektor-Vorbedingung: „Detektor flaggt bekannte `auth/`-Consumer NICHT" → ROT). Jede Mutation rötet GENAU ihr Bein (keine Konflation).
- **Per-Achse:** MUT „`CpJwtVerifier:36` AAL2 aufgezwungen" → sein device-PoP-Zahn ROT.
- **7(b):** Registry-Partition-Floor via passiver `NOT_EXPIRED`, registry-unabhängig, kein Re-Mint.
- Zähne 1–6, 9–11 wie im Design (Anti-Seizure-Tripel · Wrong-Audience-Reject · Per-Hub-Isolation · owned-but-issuer-not-trusted distinkt · DeviceNotEnrolled≠rejected · Nebenläufigkeit · per-Hub-Origin-Zukunft · Cap · Wahrheitstabellen-Vollständigkeit).

## 6. CYP-702-Kopplung (PO-getragen, dich betreffend)

Der eigene-Relay-Bau (CYP-702) MUSS **Relay=Noise-Rendezvous** bleiben — **KEIN HTTP-Reverse-Proxy des Browsers** (sonst §9.4-Bruch: shared-origin → Cookie-Replay → Seizure, verlangt §9.6 zuerst) — **plus** der god-token-`tunnelPorts`-Closure-Zahn (§3). CYP-702-Scope fahre ICH; du baust Modell-2 gegen diese Grenze.

## 7. Benannte Konsequenzen (PL → Auftraggeber-Bündel, kein Build-Item)

(a) off-loopback-Hub → Browser-Operator-Pfad deaktiviert (CMP/native nötig). (b) neues Hub-Warming braucht OOB-Konsolen-Fingerprint (kein Bulk-Auto-Enroll). Nur zur Kenntnis — der PL legt sie dem Auftraggeber vor.

## 8. Gate-Fluss je Slice

Branch von aktuellem develop · Zähne mut-bewiesen · `:server:test`+`:e2e:test` grün · Reviewer-Second-Opinion · ICH merge (kein Selbst-Merge, kein Staging-Deploy). C3 erst nach §3-Zahn.
