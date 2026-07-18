# CYP-676 — Trust-Delta: kurze User-Doku (Copy) — Teil (a) des Trust-Komm-Pakets

> Owner: UIUX-Designer (Team-2) · Companion zu `cyp676-trust-delta-indicator-spec.md` (Indikator = Teil b) ·
> Stand 2026-07-18 · **User-facing Copy, kein Code.** **Gleiche ehrliche Stimme wie der Indikator** — die
> Wortwahl **spiegelt §4 der Indikator-Spec** (UI + Doku müssen identisch lesen). Fakten **verbatim gg.
> `cyp638`** (Backend2-server-truth-gecheckt 🟢, 2026-07-18).
>
> **Publish-Home = PO-Entscheidung.** Vorschlag: als kurzer Abschnitt in der Remote-/Getting-Started-Doku
> **oder** `docs/security/` (user-lesbar gehalten). Hier als bau-fertige Copy (DE Default + EN).

---

## Redaktions-Leitplanken (nicht Teil der User-Copy)
- **Ehrlich, nicht alarmierend · Anti-Hype · Anti-Downplay.** Kein „sicher/safe" unqualifiziert, kein
  „unsicher/gefährlich", kein Superlativ auf nativ. Faktischer Komparativ statt Angst.
- **Konsistenz mit dem Indikator:** dieselben Begriffe wie die Badge-Copy (`Ende-zu-Ende` / `Browser-Gateway`
  / „dokumentiert schwächer" / „selbst gehostete Deployments, die dir gehören (Hub und Gateway)").
- **Freigabe-Bedingung ehrlich (cyp638 Präz. i):** die Browser-Gateway-Stufe ist **nur** akzeptabel, wenn
  **das ganze Deployment dir gehört (Hub *und* Gateway)** — self-hosted-Hub **+ fremder/Cyppie-Gateway** ist
  **nicht** die grüne Stufe.

---

## User-Copy — DE (Default)

### Wie sicher ist deine Remote-Verbindung?

Wenn du dich aus der Ferne mit deinem Hub verbindest, zeigt dir die App die **Sicherheits-Stufe** der
aktuellen Verbindung. Es gibt zwei:

**Ende-zu-Ende (die starke Stufe).**
Dein Gerät und der Hub sind durchgehend Ende-zu-Ende verschlüsselt (Noise-E2E), und die Identität des Hubs
ist bei dir gepinnt. Niemand dazwischen kann mitlesen; ein vertauschter Schlüssel wird erkannt. Das ist der
Standard-Weg der nativen App.

**Browser-Gateway (dokumentiert schwächer).**
Ein Browser kann die native Ende-zu-Ende-Verschlüsselung technisch nicht selbst herstellen. Deshalb endet die
Verschlüsselung schon **am Gateway**: die Verbindung Browser→Gateway ist per TLS geschützt, aber **das Gateway
sieht den Datenverkehr mit dem Hub im Klartext**. Außerdem wird die **Browser-App vom Server ausgeliefert —
ohne einen unabhängigen Pin**, mit dem dein Gerät einen bösartigen Server erkennen könnte.

Das ist **dokumentiert schwächer** als die native Ende-zu-Ende-Verbindung. Es ist **kein Fehler und keine
Gefahr** — aber du solltest wissen, worauf du dich verlässt: nämlich auf den Gateway-Server selbst.

### Was das für dich heißt

Die Browser-Gateway-Stufe ist **nur für selbst gehostete Deployments vorgesehen, die dir gehören — Hub *und*
Gateway.** Dann sind beide Punkte oben schlicht „**deinem eigenen Server vertrauen**" — was du ohnehin tust
(er hält deine Agents, dein Repo, deine Schlüssel).

Läuft dagegen der **Gateway bei jemand anderem** (z. B. gehostet), gilt diese grüne Einordnung **nicht** — dann
gibst du einem Dritten Klartext-Einblick und die Kontrolle über die ausgelieferte App. Für den stärksten Schutz
nimm die **native** Verbindung; sie bleibt auch dann Ende-zu-Ende, wenn Teile gehostet sind.

Kurz: **native = stark überall · Browser-Gateway = in Ordnung, wenn das ganze Deployment dir gehört.** Welche
Stufe gerade aktiv ist, steht immer am Verbindungs-Indikator.

---

## User-Copy — EN

### How secure is your remote connection?

When you connect to your hub remotely, the app shows the **security tier** of the current connection. There are
two:

**End-to-end (the strong tier).**
Your device and the hub are end-to-end encrypted throughout (Noise-E2E), and the hub's identity is pinned on
your side. No one in between can read along; a swapped key is detected. This is the native app's default path.

**Browser gateway (documented as weaker).**
A browser can't technically establish the native end-to-end encryption itself. So the encryption ends **at the
gateway**: the browser→gateway link is protected by TLS, but **the gateway sees the traffic with the hub in
cleartext**. On top of that, the **browser app is served by the server — without an independent pin** your
device could use to detect a malicious server.

This is **documented as weaker** than the native end-to-end connection. It is **not a fault and not a danger** —
but you should know what you're relying on: the gateway server itself.

### What this means for you

The browser-gateway tier is **intended only for self-hosted deployments that you own — hub *and* gateway.** In
that case both points above simply mean "**trusting your own server**" — which you already do (it holds your
agents, your repo, your keys).

But if the **gateway runs at someone else's** (e.g. hosted), this green reading does **not** apply — you'd be
giving a third party cleartext visibility and control over the served app. For the strongest protection use the
**native** connection; it stays end-to-end even when parts are hosted.

In short: **native = strong everywhere · browser gateway = fine when the whole deployment is yours.** Which tier
is currently active is always shown on the connection indicator.

---

## Konsistenz-Check (Redaktion — gg. Indikator-Spec §4 + cyp638)
- Begriffe identisch zur Badge-Copy: `Ende-zu-Ende`/`End-to-end` · `Browser-Gateway`/`Browser gateway` ·
  „dokumentiert schwächer"/„documented as weaker".
- Fakten = die B2-🟢-Klauseln: **A2-Hop** („Gateway sieht Datenverkehr mit dem Hub im Klartext") · **RR6**
  („App vom Server ausgeliefert, ohne unabhängigen Pin") · **Präz. i** („selbst gehostete Deployments, die dir
  gehören — Hub und Gateway"; Fall b [fremder Gateway] explizit **nicht** grün).
- Register-Trennung wie der Indikator: nativ = Garantie plain benannt (kein Hype); Browser-Gateway = advisory,
  impliziert **nie** die native Garantie; „kein Fehler/keine Gefahr" entschärft Über-Alarm, ohne zu downplayen.
- **Fluss:** wie §4 — dieselbe cyp638-Quelle; falls PO/B2 an §4 noch etwas justieren, hier spiegeln (ein Text,
  zwei Flächen).
