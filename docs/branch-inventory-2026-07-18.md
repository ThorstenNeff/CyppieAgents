# Branch-Inventar — 2026-07-18 (Prune-Vorbereitung, KEIN Löschen)

> Erstellt vom PO-Assistent/Reviewer auf PL-Auflage. **Zweck: Rückholbarkeit.** Ein gelöschter Remote-Branch ist
> über seine **SHA** jederzeit wiederherstellbar (`git branch <name> <sha>` / `git push origin <sha>:refs/heads/<name>`),
> solange die SHA notiert ist — dieses Dokument ist genau diese Notiz. **"Erst nachsehen, dann löschen."**
>
> **Es wurde NICHTS gelöscht.** Löschung nur auf explizites GO des PO.
>
> Stand: `origin/develop` = `543326e0726300bd0c69e7f24d6f58fa20dfcf18` · Gesamt nicht-gemergte Remote-Branches: **184**

## Wiederherstellung

```bash
# Branch aus der notierten SHA wiederherstellen (lokal + remote):
git branch <branch-name> <sha>
git push origin <branch-name>
# oder direkt remote:
git push origin <sha>:refs/heads/<branch-name>
```

## Einordnung der Buckets

| Bucket | Anzahl | Bedeutung |
|---|---:|---|
| **C — Docs/Specs/Deploy** | 114 | **Prune-Kandidaten.** Design-/UX-Specs, die im Team per *Lesen* konsumiert werden, nicht per Merge. Kein Code-Verlust — sie machen `--no-merged` unlesbar. |
| A — berührt Haupt-Source | 26 | **NICHT prunen ohne Einzelprüfung** — hier saß der CYP-608-Fund. |
| B — nur Tests | 42 | meist tote Gate-/QA-Artefakte, aber Einzelprüfung vor Löschung. |
| D — sonstiges | 2 | — |

---

## C — Docs/Spec/Deploy-Branches (Prune-Kandidaten)

| Branch | SHA | letzter Commit | Betreff |
|---|---|---|---|
| `audit/CYP-370-min-constants` | `adb51456b3d8908464dcd176caf16c7523a45f3c` | 2026-07-10 | CYP-370: vier von fuenf MIN-Konstanten binden — die Breite der Inhalts |
| `backlog/register-owner-notice-copy` | `9551ad4fb86624526170b9919711375ab7549a08` | 2026-07-14 | Backlog #64: dedizierte Register-Owner-Notice-Copy (statt Recovery-Mail- |
| `deploy/cyp179-loopback-evidence` | `1163f8c52fde4e12d844af507802b25aca1fb8ee` | 2026-07-04 | CYP-207: docs-refresh republish (56567a1) - remote-agents section live,  |
| `docs/CR3-2-minimal-single-conn-spec` | `952e7568e28d3e41f0841aa6ddab90920fa7dde9` | 2026-07-13 | CR3-② (Path A): build-ready minimal single-connection datapath design  |
| `docs/CYP-197-byoa-datapath` | `fe4399d454ad77f0ceff207c32c0833e16ca0149` | 2026-07-12 | CYP-197: BYOA backend-datapath design-spec-delta (for Auftraggeber ratif |
| `docs/CYP-220-asbuilt-ratification` | `d7a5d107656733afae81c50ed99ff7ff51cf2434` | 2026-07-07 | CYP-220: reconcile the ratification doc to AS-BUILT (Option A, docs-only |
| `docs/CYP-220-backend-readiness` | `e86a669ee95c79c549b4d18661ce654c45bc4e4b` | 2026-07-12 | CYP-220: Backend Phase-7 activation-readiness delta (for Auftraggeber ra |
| `docs/CYP-234-2a-document-assembly-plan` | `b123e7d67d185c4c5a317df2b1e9878a5e691e11` | 2026-07-06 | CYP-234a-2a: durable document-assembly build plan (docs-only) |
| `docs/CYP-234-2b-api-versioning-plan` | `01457cc630bc9d0d2d1b033c0fe091e7d4ee8cd5` | 2026-07-06 | CYP-234a-2b: fold in the CYP-272 tier-tooth approach (§3.5) |
| `docs/CYP-234-3-hosted-docs-plan` | `240c25a073686a09f2705f0538fe1f093516fc70` | 2026-07-06 | CYP-234a-3: durable hosted-docs scope (docs-only) |
| `docs/CYP-234-b2-fix-i-design` | `bd9507a4699d8f2b07a9a66a48cfd012a3e4cf96` | 2026-07-07 | CYP-234b-2 ①-fix: design pass — resolvePrincipal bearer validation ( |
| `docs/CYP-234-c-narrative-guide-outline` | `80e3841ebaa2f8a52961d189f6f0d10c8fbc4804` | 2026-07-07 | CYP-234c: durable narrative-guide outline (docs-only) |
| `docs/CYP-234-frontend-agnostic-contract-design` | `92b1123d844480b97693d20b9b75cf3db4c20248` | 2026-07-06 | CYP-234: 234c trim — no generated reference client (Auftraggeber); doc |
| `docs/CYP-255-4b-monolith-plan` | `3cd04e428caaed6f44006529b7aff8e60f3e089e` | 2026-07-06 | CYP-255: durable .4b monolith build plan (docs-only, survives compact) |
| `docs/CYP-256-per-project-persistence-design` | `b9e95a669093ea8432c060ba5518bb38d6cca2bc` | 2026-07-06 | CYP-256: correct §3 EAGER→LAZY + fold the 4 .5a change-requests as-bu |
| `docs/CYP-525-enroll-completion-semantics` | `79b6a89c37ad574501c9f27b24c2433bfefeb471` | 2026-07-13 | CYP-525 docs: REVISION 3 — H1 precision (atomic-rename finalize record |
| `docs/CYP-525-operator-device-enroll-design` | `9b554475a257bfabbca05a04f263ee98d8039521` | 2026-07-13 | CYP-525: operator device-key enroll model — design pass (analysis only |
| `docs/CYP-620-transport-mux-design` | `6cd172e93e791183537a612d53861b639ab61b8e` | 2026-07-15 | CYP-620: transport-replacement design — library-A (yamux) over one Noi |
| `docs/CYP-623-installer-design` | `58dc7a173e2077bc9913f23bd63f808d7d70cb2a` | 2026-07-16 | CYP-623: design pass — Deployable Server-Hub-Installer (self-hosted, W |
| `docs/CYP-623-macos-pkg-design` | `f428e3c1268cb8d67856c14ec39445abc18c9f42` | 2026-07-18 | CYP-623: macOS .pkg installer — design pass (Intel + ARM64) |
| `docs/CYP-629-first-run-uxqa` | `11108cbad82caf12c0aad2c11a97733eeb1abc71` | 2026-07-17 | docs: CYP-629 First-Run gate UX-QA result (Inc1-3) |
| `docs/CYP-687-abnahme` | `dcd8c249e1b2846c9b81933a994e3e625d97672f` | 2026-07-18 | CYP-687: BYOA-M1 Abnahme-Kriterien (M1.1 A-E + M1.7/CYP-688 inkl. 2b) -  |
| `docs/CYP-dogfood-runbook` | `58debdfb9351cab39792dc22b575aa887ff19052` | 2026-07-14 | CYP-550: M1+M2 dogfood-readiness runbook (server/remote-hub side) — pr |
| `docs/byoa-enrollment-reuse-audit` | `6bb3f7c8b585ed25f9d48110c6925e2297ee9d5d` | 2026-07-18 | docs: BYOA self-service enrollment — reuse audit (design-pass groundwo |
| `docs/gateway-cleartext-operator-posture` | `e0a0c5f1cb1e9afae3168e31e67b93a535002255` | 2026-07-17 | docs(security): core-dumps -> §2A (our systemd unit) with named §2B co |
| `docs/observation-vs-derivation-audit` | `14bdc5d4d3d27e73dc6dbe29c52a62f4f94fbaac` | 2026-07-10 | CYP-346: vierte Instanz (B2) + Wurzelanalyse — zwei Wurzeln, nicht ein |
| `docs/residual-exposure-disclosure-audit` | `d6115a98f8cadcf5d63f9b3f58425117d158ca96` | 2026-07-16 | docs: residual-exposure disclosure-coherence audit (①②③④ as a se |
| `feature/CYP-158-eventlog-compact-density` | `ae79f6e25364a75aa8dc15078b55dade3b62a8bf` | 2026-06-30 | CYP-158: Design-Entscheidung 2-Zeilen-Row statt Karten-Liste (begründet |
| `feature/CYP-159-project-switcher-hint-width` | `06db12f422b71a0b4274a2907e68dc89a50f8777` | 2026-06-30 | CYP-159: Tester-Overlay (Surface edge-to-edge x[33..1080]) — widthIn ( |
| `feature/CYP-176-register-collision-notice` | `ff8c821a86d639cb1631c4683a438ce17080fcc4` | 2026-07-07 | CYP-278: adopt canonical to<Target> tag names (toLogin/toForgot) |
| `feature/CYP-212-avatar-picker-design` | `2aa304a5a770771b7f65a570f971f3384697c86c` | 2026-07-04 | CYP-212: Per-Agent-Avatar-Picker — UX/UI-Design-Spec (docs-only, DESIG |
| `feature/CYP-220-p6w2-secret-consumers` | `0e7b061c97de958c69054c9290596b7ad5981cb4` | 2026-07-05 | CYP-220 W2 (docs): secret-store consumer rewiring plan/checkpoint |
| `feature/CYP-228-agent-add-onboarding` | `8a72a43399b24cd513e39e82c868cd4f4b33904f` | 2026-07-05 | CYP-228: + Projekt-Scope-Note (Fold aus Multi-Projekt-Support-Gap, docs- |
| `feature/CYP-233-project-bar-discoverability` | `eb02ab4acf3830a40d314756575d25ba40739dad` | 2026-07-05 | CYP-233: Projekt-Leiste Discoverability — Design-Spec (docs-only) |
| `feature/CYP-234-dev-api-docs-experience` | `a8602b79ca6109e964678b5cef607668b9579072` | 2026-07-06 | CYP-234: author maritime shell-template for hosted API docs (234a-3) |
| `feature/CYP-239-home-restart-hint` | `da8951ce85c85ed21ca31446ca80f72c05b79f98` | 2026-07-06 | CYP-239: design spec — restart-pending hint on the Home agent window |
| `feature/CYP-241-titlebar-expand-center` | `dd97d1539fa90f717e695b3d20db4e6305a107fb` | 2026-07-05 | CYP-241: design spec — titlebar double-click Expand+Center / Restore t |
| `feature/CYP-245-window-expand-keyboard` | `b7b6c41fcc163c6cf5dda41f251b9be98a84f648` | 2026-07-06 | CYP-248: fold Escape=Restore-only into the CYP-245 keyboard spec (one co |
| `feature/CYP-247-lifecycle-design` | `f46ebb2416162f05e8b55cd68609b2b9b7c3dc83` | 2026-07-06 | CYP-247: per-project agent lifecycle (L) design note — ratification ar |
| `feature/CYP-247-per-project-isolation-design` | `559003217b8bd02f85da09b05bafbb1f01b91eeb` | 2026-07-08 | CYP-247: design r4 — drain must cancelAndJoin the reader (verified) |
| `feature/CYP-250-empty-project-empty-state` | `547d67e584e07a5c6eaa16669b563e9b55233c8e` | 2026-07-06 | CYP-250: spec v1.1 — §D2 background -> foreground overlay (UX-QA corr |
| `feature/CYP-262-l-client-ux-spec` | `ade1e55fde45d5c40679c007042f39df446da03e` | 2026-07-06 | CYP-262: fold Teil 2 onto the real seam — ProjectsView.runtimeState (C |
| `feature/CYP-268-maritime-redesign` | `8c73a385feef502533e2f89d06cc1b0c946ea61d` | 2026-07-07 | CYP-281: compact icon-only theme-toggle on narrow widths (design-pass) |
| `feature/CYP-288-load-error-retry-pattern` | `488c96f0ba9cd488ebb54e69f7040953398aba7f` | 2026-07-07 | CYP-288: systemic load-error + retry pattern (design-pass) |
| `feature/CYP-314-add-success-copy-spec` | `60db68df29afb39c857963d136624b7f23614ecc` | 2026-07-08 | CYP-314: Erfolgs-Copy fürs Agent-Anlegen — Design-Spec (INFO-Ton, age |
| `feature/CYP-315-worktree-path-spec` | `2faadf7dd1d8e7843095286687c810c38c472e54` | 2026-07-07 | CYP-315: design-spec — read-only Worktree-Pfad im Agenten-Settings-Pan |
| `feature/CYP-316-context-tokens-spec` | `0e3deecdcf6879f64105f28cc3c37ea87a2f846e` | 2026-07-07 | CYP-316: design-spec — live context-token count in the agent window ti |
| `feature/CYP-317-acl-flexible-spec` | `8703d975b30a4ef4688126c2e1ed6ea5581be2f9` | 2026-07-08 | CYP-317: design-spec — flexible ACL matrix (every agent×channel cell  |
| `feature/CYP-319-acl-scaling-spec` | `abbe5f4d6ea9b5a354cc93afb7ae7dd1e5c2845f` | 2026-07-08 | CYP-319: design-spec — ACL-matrix scaling (sticky headers + channel fi |
| `feature/CYP-326-compact-orchestration-spec` | `2ff81b2439069c979a2a81b7a14301e6d7983184` | 2026-07-09 | CYP-326: §7 Dogfood-Follow-ups — eingehende System-Nachricht im Trans |
| `feature/CYP-327-compact-enhancements-spec` | `7f667d2e9dcc7cc4992aaf46f66d652dc703f12c` | 2026-07-09 | CYP-327: Q4 geschlossen — Filter = nur die 4 Orchestrierungs-Events, k |
| `feature/CYP-333-terminal-toggle-spec` | `a6ccd0b95f695281bf3d7f96d1d0d36bd6d60388` | 2026-07-10 | CYP-333: §5.1 WCAG rendering note — label in barContent (AA), glyph c |
| `feature/CYP-337-outline-usage-audit` | `b4a8010fa173341d5950d4b31e670ebc516f798d` | 2026-07-10 | CYP-337: Pill-Satz woertlich + die Regel soll erfassen, nicht benennen |
| `feature/CYP-350-agent-header-no-wrap` | `ec661cc1bb54369c77f5a9a4d1946bc145700421` | 2026-07-10 | CYP-350: T ist gemessen (84, nicht 44) — und meine Chrome-Regel war zu |
| `feature/CYP-351-unknown-vs-error-spec` | `3fb852d1b14d6cc2b24e8dcb958c66673500f0d6` | 2026-07-11 | CYP-351: Design-Item adoptiert — Kopplungs-Auflage korrigiert (350/369 |
| `feature/CYP-363-chrome-height-remeasure` | `446abe2621d5dd77f70881e8b1e10a7d277188f2` | 2026-07-11 | CYP-363/369/370: Skizze der drei offenen Entscheidungen fuer den PO (doc |
| `feature/CYP-381-shell-handoff-spec` | `4d53bd3e824a9b3cf01ab642a4c7e7fc845887f8` | 2026-07-11 | CYP-381: add §7.1 CONTEXT_LOST transcript chrome (#3) — Dev-ready, gr |
| `feature/CYP-383-ready-line-spec` | `f3ea85b0dc953c82b4f40d8a84ba4dd70231e54a` | 2026-07-11 | CYP-383: die "bereit"-Zeile — Reuse einer Notice, mit einer harten Dis |
| `feature/CYP-385-notice-error-tone` | `92212b07e5cc53448e156297e60f6037e1b67820` | 2026-07-11 | CYP-385: Fehler-Notices bekommen einen Ton — und Verbindungsverlust NI |
| `feature/CYP-387-composer-history-spec` | `ab16c41314cc37c06f1a6520e4a08b4fe8f9a1a1` | 2026-07-11 | CYP-387: Eingabe-Historie im Agenten-Composer — Interaktions-Spec (sin |
| `feature/CYP-392-transcript-scrollbar-spec` | `bc57e6e2364f5af994ad45e0de6c3383a8438ba6` | 2026-07-11 | CYP-392: §0 korrigiert — es GIBT ein Android-Target, expect/actual-Se |
| `feature/CYP-393-scroll-retention-note` | `6cf56c532e310d0ed81e1053883a8ee3d3190327` | 2026-07-11 | CYP-393: Scroll-Retention — kurze Semantik-Note fuer Developer5 (kein  |
| `feature/CYP-395-arch-spine-design` | `e9e35f3d3031ef3318d0c223d23e1f093ed46bb9` | 2026-07-11 | CYP-395: design-awareness — fold moving seams CYP-394 (TerminalAccess/ |
| `feature/CYP-395-client-connection-design` | `46592083aee596d00dc44c2374be5ab8f5497856` | 2026-07-11 | CYP-395: client connection/session architecture design pass (docs, no co |
| `feature/CYP-395-connection-ux-spec` | `1e2ac2240763325c5e91062aee74725467d24e7f` | 2026-07-11 | CYP-395: 3 LOW Doc-Notizen re-freeze (Nav-Konsistenz + register-Klarstel |
| `feature/CYP-396-ring-fix-spec` | `ebedc19b8ea798bbb26170ac3b8b7a57c3f07fbd` | 2026-07-11 | CYP-396: Impl-Spec + Test-Definitionen fuer den UNKNOWN-Ring-Fix (docs-o |
| `feature/CYP-407-w9-comm-acl-uxqa` | `95de4b3f6f6858446b8377539873f94f2dba5f21` | 2026-07-18 | CYP-407/W9: UX-QA-Conformance-Pass — Comm/ACL DOM gebaut+conformant ( |
| `feature/CYP-417-resource-capacity-ux-spec` | `437af8c1fab6b21374f11baaf70e2f0a079e089d` | 2026-07-11 | CYP-417: Reconcile a11y_hubcap_readout_nomax auf Impl-Wortlaut (Option b |
| `feature/CYP-427-operating-chrome-spec` | `7e544e681a172520c6a1da3ff9282878c1ed18cb` | 2026-07-13 | CYP-427: M2 remote-operating-surface chrome specs (context-banner gradua |
| `feature/CYP-431-lifecycle-header-spec` | `8fff4191c5860a6f3715eca053076691c96645bd` | 2026-07-11 | CYP-431 (P2-a): Lifecycle-Header DOM-Spec — Status + start/stop/restar |
| `feature/CYP-432-event-log-warden-spec` | `a168501032dcf4efa2cb89a2917811f6aaa9f2d1` | 2026-07-12 | CYP-432 §8: Omission-Zahn an die korrigierte Rahmung angeglichen (defen |
| `feature/CYP-433-apikey-screen-spec` | `39864a57d76b618c533acb1efdf9b1cc5321d90c` | 2026-07-11 | CYP-433 (P2-e): API-Key-Screen DOM-Spec — die leak-sensibelste Flaeche |
| `feature/CYP-440-client-remote-design` | `743b54331490d3805e28004f2dd94acb4c935427` | 2026-07-11 | CYP-440: record CR1 Noise-lib spike result (Signal noise-java, h exposed |
| `feature/CYP-449-desktop-shell-ux-spec` | `7241db5925e3e0ae8dc5fe18ed957066ffdae858` | 2026-07-11 | CYP-449: Spec-Closure Desktop-App-Shell (Q1-Q4 gefolded) + eingefrorene  |
| `feature/CYP-450-agent-management-spec` | `e30067889d213cde7287b27eb6b43c4bcabac852` | 2026-07-11 | CYP-450 (P2-b): Agenten-Verwaltung DOM-Spec (add/remove/change, Port) |
| `feature/CYP-452-event-log-browse-spec` | `b2bfe7b2cdce3cd0e6fb34add6b4f4a2d59eeb16` | 2026-07-12 | CYP-452 §0: Rahmung praezisiert (CYP-432-Erbe) — Metadata + Server-Gr |
| `feature/CYP-453-settings-level-spec` | `6fc5485df48c26a987ae98b3e06da98e958e5a0c` | 2026-07-11 | CYP-453 (P2-f): Effect-Hint Muster-Ownership ≠ Render-Ownership schär |
| `feature/CYP-460-desktop-remote-operator-ux-spec` | `8d36bc715de3be1ef37ad96f904457c74e6ef5c4` | 2026-07-12 | CYP-460: §-QA-Checkliste fuer den Desktop-Dialog vorbereitet (Vorlauf) |
| `feature/CYP-461-connector-selection-spec` | `72d1c7187f2a31500be02b0740c61ae2c45b88db` | 2026-07-12 | CYP-461 (P2-g): Connector-Auswahl DOM-Spec (Port CYP-119 §3 + Vorschau- |
| `feature/CYP-464-product-lead-spec` | `4251031936edeacd383151743477bda336460c3e` | 2026-07-12 | CYP-464 (P2-d): Glyph final signiert — report-lokaler Satz (Finding != |
| `feature/CYP-465-repo-reprovision-workguard-spec` | `995334dbce042761776f27efaec04215143da426` | 2026-07-12 | CYP-465 (P2-h): honest-end aktiv (CYP-466 AtRiskAgent live), advisory de |
| `feature/CYP-470-auth-redirect-gate-spec` | `a0ade5698e67187e1fe58816868974cc9e7d6b78` | 2026-07-12 | CYP-470 §2.1: Flow-Return-Guard (CYP-515) — ?flow=-Return darf nicht  |
| `feature/CYP-5-streamjson-spike` | `910040ebe9b87a0df04e629713c84e742f8f1d7d` | 2026-06-26 | CYP-5: stream-json connector spike — verify protocol, flag-set, event  |
| `feature/CYP-515-auth-redirect-loop-ux-spec` | `62f125e67c11831d2e816747b1fe81d0d6cf1869` | 2026-07-18 | CYP-515: refokussiert auf honest Fehlerpfad (Proxy-JSON-Contract-Bruch)  |
| `feature/CYP-525-device-enroll-spec` | `550ddb00d5b53acbdebdadef6ef59f77fc1fddfa` | 2026-07-13 | CYP-525: EnrollCodesUnavailable (① H3-fix) net-new AC + HF-retire weic |
| `feature/CYP-540-tunnel-pool-status-spec` | `0104eb6b63ef9427f21e928b0218464ca44a736f` | 2026-07-14 | CYP-540: Pool-State-UX-Spec + frozen testTag-Contract (WS5, against C3) |
| `feature/CYP-542-uv-ui-spec` | `2aafc95442a3a75c51d30fc4ee6687df681613d1` | 2026-07-14 | CYP-542: #1-Reconcile Tag-Namespace enrollError.* -> error.<cause> (PO-G |
| `feature/CYP-576-auth-oidc-legibility-spec` | `aeccd9154aeb4eb2af218bd54dc6c6b00cf9d120` | 2026-07-15 | CYP-576: Visual/UX-Completeness-Critic — Login Fehler-/Edge-Sichtzusta |
| `feature/CYP-584-enroll-legibility-spec` | `66f437fbe947d9be0eda8d41a5b4b56fb0b392d7` | 2026-07-15 | CYP-584: strip stray NUL byte in spec Addendum (fill() literal → prope |
| `feature/CYP-587-vault-corrupt-legibility` | `6b966cd17f484676c157353c50c1d99c7cd26a2c` | 2026-07-15 | CYP-587: Reconcile mit CYP-584 §3b (PO-Relay) — CTA-Cede + dreiwertig |
| `feature/CYP-597-end-session-placement-spec` | `588b6091c2c1e16b140bb4252517b897b614d59e` | 2026-07-15 | CYP-597: End-Session oben-rechts — Platzierungs-Spec (Reuse-Move, kein |
| `feature/CYP-597-end-session-placement` | `e3466ad20cb9c5976ec41e9c4f7f4120df095624` | 2026-07-15 | CYP-597: End-remote-session sicher platzieren (Live-Safety-Fund) — Pla |
| `feature/CYP-629-first-run-setup-spec` | `4a01d3624c4b4861bacc333d61268f581bfcca68` | 2026-07-16 | CYP-629: a11y/Interaktions-Spec (PO-zugewiesen) — Prio #3 GATED-Grund  |
| `feature/CYP-656-titlebar-token-staleness-spec` | `7b6fa2688942f4efdf13b93b061f3f5ca2a0152f` | 2026-07-16 | CYP-656: Titelleisten-Token-Frische — gemessener Befund + Marker-Empfe |
| `feature/CYP-676-trust-delta-indicator-spec` | `eadc054b85c9616a958bc9dc817c5bdccad9baf8` | 2026-07-18 | CYP-676: User-Doku (a) — Trust-Delta user-facing Copy DE+EN, gg. cyp63 |
| `feature/CYP-69-ios-maestro` | `017acd83cbb5381a7243a8bf1d3fbe131e46e264` | 2026-06-28 | CYP-69: iOS Maestro flow drafts (ungated prep) — 5 flows + README |
| `feature/CYP-79-cross-project-design` | `407baecb63dc9011eac5a66ca3f62c8f8831f1a6` | 2026-06-29 | CYP-93: Doc-Sync — crossproject_dialog_scope auf die refined Pre-Share |
| `feature/CYP-80-member-operator-ux-spec` | `d5de379056d30f2b5cd30075da0e28a8a6dc9ebd` | 2026-07-02 | CYP-80: fold Backends BE1-Read-Ceiling-Matrix — Spec final |
| `feature/acl-self-blind-advisory-spec` | `4e766ec954165435b2060f01e08fb1147da58d0e` | 2026-07-17 | docs: ACL self-blind advisory — B1 decided, outcome teeth, PO-coordina |
| `feature/error-reason-disclosure-spec` | `0d34c30f64b905ebe58f364af32de1f9996907d5` | 2026-07-11 | ERROR-Grund: auf CYP-421-Contract aktualisiert — Freitext-Branch raus, |
| `feature/maritime-design-system` | `c1b62b473f8513c2904a38f087135c2fecfe28c5` | 2026-07-07 | maritime a0: tertiary-deoverload per-site remap template (Dev-Vorlage, C |
| `feature/p2-impl-qa-recipes` | `9c68c236ead8b73299e9bacc294c44d1f802b496` | 2026-07-12 | p2-impl-qa-recipes: CYP-515 Flow-Return-Guard-Zahn (?flow=-Return darf n |
| `feature/recovery-codes-save-ux-fix` | `0977a02a8c4996f76d9201172c93787bfea1586d` | 2026-07-15 | recovery-codes-save Build-Pack: RE-GROUND @ develop 025b17ae + 2 Reuse-R |
| `feature/web-ts-cutover-ux-parity-map` | `484da8ad57a0540f5f38e199624d8d9ae2ee0c8c` | 2026-07-13 | parity-map: §10 Auth Posture-Wechsel (a) — CYP-470 redirect-only loop |
| `feature/web-ts-w5-w6-qa-checklist` | `aa5689bcac9e6db8e4b585deaeee8fdc4ee60a50` | 2026-07-11 | Web-TS W5/W6: UX-QA-Checkliste (Composer- + Scrollbar/Retention-Paritaet |
| `feature/web-ts-w8-w9-dom-ux-spec` | `980073c85531b01ad3018798b10e0c3a50d70068` | 2026-07-11 | Web-TS W8+W9: DOM-UX-Spec (Toggle Orchestrierung<->Shell + Comm-Panel/AC |
| `feature/webts-inapp-login-a-spec` | `de04c8f2161b184bb998c7995140d8ea0c9bf467` | 2026-07-13 | webts-inapp-login §2.3: RECONCILE — gemergter Impl nutzt Browser-Flow |
| `qa/B1-passphrase-floor-testplan` | `2d68a0aec3875261c6021cbc8cf6dc66e7e02def` | 2026-07-14 | B1-QA (CYP-542): anchor the plan to the real Diceware-enroll seam (UIUX2 |
| `qa/CYP-330-analysis` | `0f454762bcb905e8fb3d68514699b8847837c65f` | 2026-07-10 | CYP-330 Analyse: restartWithLiveResume ist vakuoes und verfehlt CYP-371 |
| `qa/composition-sweep` | `988189b443c940a0b9073fa65f368bb0eeed21df` | 2026-07-10 | QA: Laufzeit-Sweep — Zahl in 1 auf 43 gezogen (39/28 waren Artefakte e |
| `qa/cutover-parity-plan` | `e32050c709fe02fea367bc8ee9701cfc013330f7` | 2026-07-12 | CYP-422: close A6 live-indicator (CYP-499 verified) — out of deferred, |
| `qa/dogfood-runbook-lifecycle` | `7a44123873c5ae8ad91dd4885b40f317d7e38e8a` | 2026-07-10 | QA: Dogfood-Runbook Lifecycle-Kern (371/247/368/Reader) — Browser + St |
| `qa/post-deploy-smoke` | `d826148f8dee4ed3fd50b0c68553f8c6c65ede65` | 2026-07-12 | CYP-422: §1.1c — Playwright JS-redirect-loop detector (the real CYP-5 |
| `test/CYP-556-accept-loop-concurrency-fidelity` | `90fc11602f94d8e73be90d322c3613f2c72eb7d0` | 2026-07-14 | CYP-556: pre-stage the accept-loop concurrency-fidelity adversarial QA p |

---

## A — Source-berührende Branches (NICHT prunen ohne Einzelprüfung)

| Branch | SHA | letzter Commit | Betreff |
|---|---|---|---|
| `bugfix/CYP-478-oob-reject-failclosed` | `f77eed97b4f96de91c834efde3500db755d05e2e` | 2026-07-13 | CYP-478: fail-closed terminal LOST on an OOB-fingerprint reject (no sile |
| `bugfix/CYP-573-dot-connection-gate` | `faa725681606cfa4b48084b7c3d8c641e3fda1e9` | 2026-07-14 | CYP-573: connection-gate status dot → UNKNOWN on sustained disconnect |
| `bugfix/CYP-606-handshake-fail-backoff-escalation` | `13bb81cd504f82318d4a421cf2ba36c69b2d9951` | 2026-07-15 | CYP-606: reset relay re-dial backoff only after a served tunnel |
| `docs/CYP-618-comment-ref` | `60c41d7d5f0342b339d0d390e64302d8870e1275` | 2026-07-15 | CYP-618: fix comment ticket-ref CYP-617 -> CYP-618 (comment-only) |
| `feature/CYP-10-clamp-position` | `bde3e5eb201706111064bfde7270b0aa5e59b90b` | 2026-06-26 | CYP-10: Clamp window position to host bounds + align resizeHandle tag |
| `feature/CYP-326-compact-orchestration` | `24f5f11e0958d0f8b2df1665c6eb308e9c15e1f0` | 2026-07-09 | CYP-327 (contract): CompactRunSummary.correlationId — the authoritativ |
| `feature/CYP-354-terminal-control-state` | `9b348e336773955c573c7b1c7a3f932ff19bd749` | 2026-07-10 | CYP-354: BE-1 server — TerminalControlState tracker + /ws/terminal-sta |
| `feature/CYP-363-chrome-floor` | `bf4e68d1d938750b5a5fa8c97eec763703fcd3b0` | 2026-07-10 | CYP-363: LifecycleErrorRow ist Chrome — Boden 405/495, und der Guard k |
| `feature/CYP-366-declare-oidc-jsonnet-test-input` | `f59c73904eddc32267fad7fde9fc7d74968387d5` | 2026-07-10 | CYP-366: declare oidc.github.jsonnet as a test input so its guard can't  |
| `feature/CYP-377-comm-floor` | `f3262c9a34e09ae690c13f342269a40980c024a8` | 2026-07-10 | CYP-377: Der geteilte Inhaltsfenster-Boden traegt auch das Comm-Fenster, |
| `feature/CYP-542T-vault-migration-qa` | `c529c4e9054cab0b0c187f9847a1d21e34f3c667` | 2026-07-14 | CYP-542: QA vault/migration regression teeth (corrupt-Vault + Migration- |
| `feature/CYP-547-uv-wiring-guard-final` | `698bd04f2a6fb94c37aa31fe7f956bfeca3bd193` | 2026-07-14 | CYP-547: 1-UV-for-N guard — behavioral (prompts==1) vs final B1 tree ( |
| `feature/CYP-583-devicekey-fail-closed` | `9a4e8081318472fec2ce99e5dbd148eb30c70571` | 2026-07-15 | CYP-583: copy-swap — UIUX2 terminology reuse ('recovery code', not 'ba |
| `feature/CYP-584-enroll-recovery-honesty` | `df17727be8aab59bd9719bda217746defe009995` | 2026-07-15 | CYP-584 F1a-render (T1): Cancel stays visible during ENROLLING (no dead- |
| `feature/CYP-597-2a-end-session-affordance` | `6210d45b3c1c59fcc49c3a300a3ab0c2f1078d73` | 2026-07-15 | CYP-597: End-remote-session-Affordanz heben — neutraler OutlinedButton |
| `feature/CYP-629-firstrun-wiring` | `60cfea8df137065e21cfe1a0d09140432fee1bdc` | 2026-07-18 | CYP-629 live-wiring (3a): firstRunGateEnabled() opt-in flag — live-by- |
| `feature/CYP-636-comment-nit` | `e345450eab9cdb2d639206ca0622ea20c2e4a69b` | 2026-07-16 | CYP-636: fix comment nit — --app-content lands the unit at /opt/cyppie |
| `feature/CYP-638-S7-gateway-launch-hardening` | `438235993d928d7f31185cc21712df78f2b7ea11` | 2026-07-17 | CYP-638: S7a gateway launch hardening — heap/core-dump-off as a FLAG,  |
| `test/CYP-310-integration-wire` | `872822d7e77dea010066fb4149fdea495766a8e3` | 2026-07-07 | CYP-310: adapt integration tooth to nullable version (Dev fix 124fa4f) |
| `test/CYP-324-realseam-teeth` | `b585201608834f8ecad65897623ba1e9c7d71f8f` | 2026-07-08 | CYP-324: QA real-seam + coexistence teeth (on the b5ba761+467b407 merge) |
| `test/CYP-326-realseam-teeth` | `d97ccd0ea8d7bf10317f3885255f35a6f88ddc06` | 2026-07-09 | CYP-326: QA real-seam tooth — real CompactHttpRepository <-> real comp |
| `test/CYP-335-final-teeth` | `f26164887c0632e69e026d7ad619a0eca88bfe5e` | 2026-07-10 | CYP-335: Ankerliste korrigiert — 22 Fundstellen, nicht drei |
| `test/CYP-335-skew-teeth` | `c87c3ad0e310503b16cfe7d7c905ed8976f4a9d1` | 2026-07-10 | CYP-335: Zahn — Skew-Schaetzer verwechselt Uhrenversatz mit Alter der  |
| `test/CYP-335-time-column-teeth` | `719f67fd9fdaf36a39644d40e8c3b7cadba23422` | 2026-07-10 | CYP-335: QA-Abnahme — End-to-End-Zahn fuer die Zeitspalte + Abnahmeber |
| `test/CYP-542-b1-qa-passphrase-policy` | `14ebfeb67ba755b703ebd936dc414b05aab223ce` | 2026-07-14 | CYP-542: B1-QA passphrase input-policy — full 8-item matrix vs the fin |
| `test/CYP-542-b1-qa-pin-input-audit` | `e43f0f6205d490cc7b91059082d5735de23c32ac` | 2026-07-14 | CYP-542: re-audit results @ 54f840ba — F1 GO+locked, F2 GO+residual, F |

---

## B — Test-only-Branches

| Branch | SHA | letzter Commit | Betreff |
|---|---|---|---|
| `bugfix/CYP-341-restart-collector-race` | `1b78db38a9051c74769baec2e33f401d824ac4ca` | 2026-07-10 | CYP-341: fix Cyp330RestartRobustnessTest collector-attach-race (determin |
| `bugfix/CYP-560-cyp355-pty-flake` | `3bd55244bed931dabefeb4ffd348fa7c67f742ca` | 2026-07-14 | CYP-560: de-flake restartDuringInteractive — assert the observed onExi |
| `docs/CYP-542-b1-review-checklist` | `bf040455a24284153bdcbda2feb78a50fa000501` | 2026-07-14 | CYP-542: pre-review of newer B1 logic commits (74ac52f4 + fd0ee01f) |
| `feature/CYP-110-real-agent-harness` | `4c92b388b41bbb0ad26317d90c5da014c22dda7f` | 2026-06-29 | CYP-110: RB1_AUTH fail-closed guard — direction steers, not key presen |
| `feature/CYP-344-coexistence-spike` | `c9f34a277c326465ac001072d9168fa1548c2a08` | 2026-07-10 | CYP-344: confirm coexistence through the REAL PtyManager (pty4j) seam |
| `feature/CYP-362-bridge-lazy-init-hang` | `538e4506a3620ec6d73526f68695722e6e5058bd` | 2026-07-10 | CYP-362: BridgeRelay.close() beendet das Kind, bevor es auf den Reader w |
| `feature/CYP-407-seed-second-channel` | `4c27323bcd08d311a38c1d9110b7187916b847b2` | 2026-07-18 | CYP-407: opt-in zweiter Worker im web-e2e-Seed — macht U5 + §4 ueberh |
| `feature/CYP-420-ws-boundary-zod` | `50bd7d0a3a617015d36f4592bf65527598327b06` | 2026-07-18 | CYP-420: fold Assist2 F1-F4 — fail-closed defaults, parse inside the g |
| `feature/CYP-427-activation-e2e` | `a294be23d23867a076cd91e7ef092f4ddf174cfc` | 2026-07-13 | CYP-427: transport proof PROVEN after CYP-526 — AEAD TunnelAuthGrant r |
| `feature/CYP-44-needle-harness` | `5b2be7b05d878062bd506191d957a13f13177c04` | 2026-06-27 | CYP-44: two-class needle-injection harness for §4 (ahead of CYP-37) |
| `feature/CYP-515-flow-return-guard` | `724f3273609bd1ec995f296d500de7e88992a8c4` | 2026-07-12 | CYP-515: AuthGate guards Kratos ?flow= return → no re-redirect loop (f |
| `feature/CYP-515-login-unavailable` | `695f7f78cacf1c07d95f5e89d59b32cbeca0a396` | 2026-07-18 | CYP-515: honest loud error — a system failure is not a credential verd |
| `feature/CYP-525T-encoding-e2e` | `74d43e9e162de99e42f070727e04cbbf56102b97` | 2026-07-13 | CYP-525: cross-stack SPKI→raw encoding-e2e regression guard (Axis-1) |
| `feature/CYP-525T-nolockout-e2e` | `8dbe601c572d85c75ba3d81ea7db3192ed4f5a2a` | 2026-07-13 | CYP-525: enroll-completion no-lockout regression guard (Axis-4) |
| `feature/CYP-525T-persist-e2e` | `6493ed99ee4f3cd3380fb1adc70ff634ab6bb51d` | 2026-07-13 | CYP-525: persist-over-restart signing-continuity regression guard (Axis- |
| `feature/CYP-529T-live-negatives-scaffold` | `7c99943c569985122a6642c69465e44fe34f9935` | 2026-07-13 | CYP-529: live-negatives scaffold (TrustChanged / wrong-sub / revoked-mid |
| `feature/CYP-536-joint-e2e` | `d11b3cff730e07bfb5ae298095461f96d441e48b` | 2026-07-14 | CYP-536: responder-side joint auth e2e — 1-UV-for-N + cross-tunnel ant |
| `feature/CYP-542R-render-qa` | `db015c4046c22b4787000630a05e0236a12122fc` | 2026-07-14 | CYP-542R: render-QA strength-meter glyph-grammar teeth (R1/R2/R3/R8/R5)  |
| `feature/CYP-571-s2-client-render-proof` | `962bcfccecf57d29c2e80a9697b0c6c9e290237a` | 2026-07-14 | CYP-571: S2 client-render KERN proof — replayed backfill renders non-b |
| `feature/CYP-692-window-first-click` | `81213aa0a39e4a9bcc58a6c30d17b8066025280b` | 2026-07-18 | CYP-692 + CYP-693: window first-click swallow (root cause) + ACL partial |
| `feature/CYP-M2T-e2e-over-tunnel-harness` | `d8658270acc35c764e2034a3e5870d9cbda5c057` | 2026-07-13 | CYP-M2: E2E-over-tunnel test harness (Tier A datapath) |
| `feature/CYP-M2T-tier-b-real-transport` | `5fd0306efead237c96b3d16cb62deec9497c72e9` | 2026-07-13 | CYP-M2: Tier B — E2E over Dev's real RemoteTunnelHubTransport (+ 2-cha |
| `qa/CYP-351-testplan` | `acf67d85f95f53cc3d8c27062bfe9f667613fddc` | 2026-07-10 | CYP-351: T4b — der harte Tod muss gemeldet werden (Gegenstueck zu T7) |
| `qa/CYP-353-testplan` | `254028d8b6e8f13f9e4c30725e5f663d545fd302` | 2026-07-10 | CYP-353: Trennversuch — der Report zaehlt Meldungen, nicht Ereignisse |
| `qa/CYP-363-guard` | `d4e14a289d197e61c8c12e863286fdb6065d943c` | 2026-07-10 | CYP-363: Bericht — die Zahlen aus 2.3 sind obere Schranken, nicht der  |
| `qa/CYP-364-investigation` | `9c7579526fef7b0386a85f97ef5c6b2b60d69afc` | 2026-07-10 | CYP-364: Messung — log.dropped IST ein Plattform-Ereignis |
| `qa/CYP-364-testplan` | `2c534949aea5e49f8dbacd2bce42e2de5a71262e` | 2026-07-10 | CYP-364: Trennversuch Leseseite — Sichtbarkeit, Bezugsrahmen, ganzer P |
| `qa/CYP-371-testplan` | `d4ec35c0ce134b5a3b73f4ca882ed7bacb56e7e6` | 2026-07-10 | CYP-371: Plan praezisiert — T-Term trifft happy-path, NICHT den Timeou |
| `qa/CYP-372-churn` | `194ee1ca4be10b6240dfcfabfef10a0ac1a6fab0` | 2026-07-10 | CYP-372 (b): doesNotChurn war vakuoes — Lebendigkeit am Ende des Fenst |
| `qa/CYP-382-repro` | `54b6c2b7c377ac5f09d5e1e45e5b6a1b16809f73` | 2026-07-11 | CYP-382 E2E: throw-treu — erste Nachricht wirft, WS reisst ab, v2 leer |
| `qa/CYP-422-phase1-parity` | `9a7a044f539d4712c50fbf662ab8576832a18ceb` | 2026-07-12 | CYP-422: verify CYP-499 fix — restore the A6 live-indicator + live-in- |
| `qa/CYP-588-reverify` | `c6917b92e6751ed0efa1ece2d491d892821c5929` | 2026-07-15 | CYP-588 §3: independent client-side read-only OBSERVE harness (gated, m |
| `qa/e2e-hunt-b1-secondhalf` | `3c4786474b76bc97f65a5b9918f13d34ce28b0fa` | 2026-07-15 | HUNT(H2)+prep: concurrent-turns exactly-once (verified-good) + live-stac |
| `test/CYP-305-switch-bug-repro` | `34eef168a325fe1612c82fb77fb8b88ceec412eb` | 2026-07-07 | CYP-305: independent repro — seed-vs-active mismatch strands bootstrap |
| `test/CYP-308-agent-ownership-repro` | `36fc80f2e10dafe61fb96cf09c599b51c935f940` | 2026-07-07 | CYP-308: independent restart-stability repro of the agent-ownership mode |
| `test/CYP-312-add-wire` | `44fa7e613dbc67e67de85762ed662e7fee62d82c` | 2026-07-07 | CYP-312: real repo<->server add wire tooth (CreatedAgent decode) |
| `test/CYP-313-role-preserve-wire` | `d9f39c5d0f0cd39f6ae233605f8f1f74b99331b1` | 2026-07-07 | CYP-313: real client-repo<->server role-preserve wire tooth |
| `test/CYP-325-persist-bandguard` | `2b17fe8b82b71bc9727303aeb20ce2ea85cba3f0` | 2026-07-09 | CYP-325-persist: load-bearing band-guard tooth (dev's shipped one is vac |
| `test/CYP-325-sequence-tooth` | `2a37f10c43538bfb9dfd7a10724bfa5938b0d423` | 2026-07-08 | CYP-325: QA sequence tooth + prod-wired bander-consistency (re-verify @  |
| `test/CYP-327-server-mirror-tooth` | `b5f7e2f2d3273c921046611fc6660ed5e77a9f75` | 2026-07-09 | CYP-327: QA divergent-server tooth — never-optimistic confirm-on-SERVE |
| `test/CYP-341-collector-race-repro` | `dcbd120f1a88c284c79ea397f99eec0b01884886` | 2026-07-10 | CYP-341: deterministic repro of the Cyp330 collector-attach race (test-o |
| `test/CYP-571-reconnect-honesty-verify` | `a54db0b7c04808bd71f9e4ffc36212aa17dc6ee3` | 2026-07-14 | CYP-571 V7: backfill-render honesty — persisted-shell landing (scroll- |

---

## D — Sonstige

| Branch | SHA | letzter Commit | Betreff |
|---|---|---|---|
| `chore/dogfood` | `f68ea80e7117fdb5eca9da3a1468c51b5ef6c3dc` | 2026-07-16 | Exchange runscripts |
| `feature/CYP-455-csp-nonce-seam-guard` | `25f1358486b3c79d57235e4257279ca55c0e64a6` | 2026-07-18 | CYP-455: enforce the CSP nonce seam on the built artifact (+ correct a m |
