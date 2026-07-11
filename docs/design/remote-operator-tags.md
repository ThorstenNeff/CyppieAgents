# Remote-Modus Operator-UX — testTag-Vertrag (CYP-429, Epic CYP-427 Phase 2)

> Owner: UIUX-Designer · Story CYP-429 (Epic CYP-427) · Stand 2026-07-11 · Status: **Spec-Closure (Q1–Q5 geruled)**;
> eingefroren als UI-Vorlage. Begleit-Spec: `remote-operator-ux-spec.md`.
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): **prefixless** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`,
> camelCase-Segmente, keine Punkte im Wert. **Geteilte API mit QA (CYP-7) — über den PO koordinieren.**
> **Neue Area `remote`** (aktive Remote-Session-Chrome; 0 Kollision @ develop `4466ca20`, kein `RemoteTags` bestehend) +
> **Aktivierung** des bestehenden `hubConnect.mode.remote` (Phase-1 disabled → Remote aktiviert). Vorschlag: `RemoteTags`-Objekt.

## Auth-Schritt (§5, Passkey/WebAuthn-PoP)
| Tag | Wert | Zweck |
|---|---|---|
| `popPrompt` | `remote.authStep.popPrompt` | Passkey/WebAuthn-PoP-Aufforderung (nativ, non-optimistisch). |
| `enroll` | `remote.authStep.enroll` | Passkey-Registrierung (Erst-Setup, davor). |
| `authError` | `remote.authStep.error` | PoP-Fehler/Abbruch → **fail-closed** (kein Durchreichen zur Hub-Liste). |

## Verbindungszustand (§7)
| Tag | Wert | Zweck |
|---|---|---|
| `connectRelay` | `remote.connect.relayDialing` | Relay-Vermittlung (neutral). |
| `connectE2e` | `remote.connect.e2eHandshake` | Noise-Handshake (CP=Ciphertext, neutral). |
| `connectTrust` | `remote.connect.trustCheck` | TOFU-Prüfung (§8.1). |
| `connectLive` | `remote.connect.connected` | LIVE `●`+primary — **nie** vor echtem LIVE. |
| `connectError(cause)` | `remote.connect.error.<cause>` | typisierte Ursache (`relay_unreachable`/`hub_offline`/`handshake_failed`/`trust_changed`), Backend-geliefert. |
| `relayDrop` | `remote.relayDrop` | **EIN globales** Relay-Drop-Surface (H4), nicht N per-Agent-Chips. |

## Trust-Affordances (§8)
| Tag | Wert | Zweck |
|---|---|---|
| `trustFingerprint` | `remote.trust.fingerprint` | Container der Fingerprint-Hilfe (§8.1, Q4). |
| `trustWordlist` | `remote.trust.wordlist` | menschen-vergleichbare Wort-/Emoji-Sequenz (primär, PGP-Wordlist-Stil). |
| `trustHex` | `remote.trust.hex` | volle Hex-Fingerprint (kopierbar). |
| `trustQr` | `remote.trust.qr` | QR für starken Scan-Pfad. |
| `trustPinPrompt` | `remote.trust.pinPrompt` | Erstverbindungs-Pin-Bestätigung (TOFU/OOB). |
| `trustChangedAlarm` | `remote.trust.changedAlarm` | **Schlüssel-Änderung = harter Block** (WARN-Amber, nie still, OOB-Re-Pin, H2/Q2). |
| `e2eIndicator` | `remote.trust.e2eIndicator` | „E2E via Relay" — **nur Transport** (H3), nie grün/nie als Hub-Vertrauen. |

## Wechsel + Kontext (§6/§9)
| Tag | Wert | Zweck |
|---|---|---|
| `switchTransition` | `remote.switch.transition` | „Trenne von X … verbinde mit Y" — expliziter Teardown, ein Hub (Q5). |
| `contextBanner` | `remote.context.banner` | „Fern-Betrieb: Hub X"-Kontext-Banner (§9); **absent im Lokal-Modus**. |
| `contextHub` | `remote.context.hub` | Hub-Name im Banner. |
| `contextLatency` | `remote.context.latency` | subtiler Latenz-Hinweis (Q3, neutral, kein Flackern). |
| `contextDegraded` | `remote.context.degraded` | „langsam/instabil" — **nur bei echter Degradation** (Q3, neutral, kein Alarm). |
| `contextReconnecting` | `remote.context.reconnecting` | Reconnect-Zustand im Banner (§7). |

## Fail-closed-Anker (für §-QA)
- `remote.authStep.error` blockt fail-closed (kein Durchreichen bei PoP-Fehler).
- `remote.context.banner` **absent** im Lokal-Modus; `remote.connect.connected` **nie** vor echtem LIVE.
- `remote.trust.changedAlarm` bei Schlüssel-Änderung (nie still); `remote.trust.e2eIndicator` **nie** grün / nie als
  Hub-Vertrauen; `remote.relayDrop` = **ein** globales Surface.
- `remote.context.degraded` **nur** bei echter Degradation (kein Dauer-Alarm). Kein Tag trägt Erfolgs-Grün.

## Reuse (bestehende Tags/Areas — NICHT neu anlegen; verifiziert @ `4466ca20`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| Area `hubConnect` (`mode.remote`, HubList, Connect-Idiom) | CYP-395/419 | Login→Hub-Liste→Modus; `mode.remote` **aktiviert** |
| Area `auth` (`AuthTags`) | CYP-176 | zentraler Login vor dem PoP-Schritt |
| `WorkspaceTags.ROLE_INDICATOR` | Workspace | Host des `remote.context.banner` (Geschwister der Rollen-Zeile) |
| `ProjectViewModel.switchTo`-Muster | Project | Vorbild für non-optimistischen Hub-Wechsel (§6) |

## Self-Validation
- **19 neue Tags** in Area `remote` (3 Auth + 6 Connect inkl. `error.<cause>`/`relayDrop` + 7 Trust + 3 Wechsel/Kontext-Kern
  + Kontext-Sub) + Aktivierung `hubConnect.mode.remote`. `connectError` trägt Qualifier `<cause>`.
- **0 Kollision:** Area `remote` neu, greenfield gg. bestehende `*Tags.kt`.
- **Geteilte API mit QA (CYP-7):** Area + Werte über den PO mit dem Tester abstimmen (Frozen-Contract).
- Jeder Tag ist in `remote-operator-ux-spec.md` (§12) verankert und trägt einen Copy-/a11y-Key aus `remote-operator-keys.md`.
