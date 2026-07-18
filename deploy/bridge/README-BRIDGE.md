# Cyppie Bridge — self-contained distribution (CYP-142)

The Bridge is the **user-side deployable**: it wraps a local `claude` (Claude-Code) stream-json session
and relays it to the hub over the Hub-Wire-Protocol (`/ws/hub`). It carries **no server secrets** — only
the per-agent bearer token (`HUB_TOKEN`) and `HUB_URL`.

## What this artifact is

A jpackage **app-image** — a self-contained directory, **no installer, no sudo, no systemd**:

```
cyppie-bridge-1.0.0/
├── CyppieBridge/
│   ├── bin/CyppieBridge          # native launcher — execs the BUNDLED runtime by absolute path
│   └── lib/
│       ├── app/                  # the Bridge jars
│       └── runtime/              # a bundled jlink JRE 21 (the Bridge does NOT use any host java)
├── run-bridge.sh                 # launch ONE instance (see below)
└── README-BRIDGE.md
```

### Bundled runtime (why the Bridge ships its own JRE)

Like the Hub, the Bridge **bundles its own jlink JRE 21** and its launcher execs it by absolute path —
it never touches `JAVA_HOME` or a `java` on `PATH`. A BYOA box may carry no JRE 21, a module-stripped
one, or one unreadable to the run user; a Bridge that assumed a host JRE would not be a deployable.

The runtime is jlinked to the **Bridge's own minimal module set** (not the Hub's `java.se` aggregate):

```
java.base  java.instrument  java.logging  java.management  java.naming  java.xml
jdk.unsupported            (kotlinx-coroutines → sun.misc.Unsafe)
jdk.crypto.ec              (SunEC — the wss/TLS handshake: XDH/ECDH/EC/ECDSA)
+ java.security.sasl       (jlink transitive of java.naming/management)
```

## Run — one instance

```bash
HUB_URL=wss://<hub-host> HUB_AGENT_ID=<id> HUB_TOKEN=<token> ./run-bridge.sh
```

## Run — 6 instances (the M1.6 shape)

All instances run as the **same OS user** and **share the real `HOME`** (`~/.claude` preserved). Per-agent
separation is by a **per-instance working directory**, not by HOME — see the model below.

```bash
# agent ids map 1:1 to their existing working dirs /home/thorsten/cyppie-agents/<id>
AGENTS=( po frontend backend agent4 agent5 agent6 )
declare -A TOKENS=( [po]=… [frontend]=… [backend]=… [agent4]=… [agent5]=… [agent6]=… )
STAGGER="${STAGGER:-5}"            # seconds between starts — see the RMW note
for id in "${AGENTS[@]}"; do
  HUB_URL=wss://<hub-host> HUB_AGENT_ID="$id" HUB_TOKEN="${TOKENS[$id]}" \
    ./run-bridge.sh >"logs/$id.log" 2>&1 &   # BRIDGE_CWD defaults to /home/thorsten/cyppie-agents/$id
  sleep "$STAGGER"                 # stagger — do NOT launch all six at the same instant
done
```

### Parallel instances — SHARED HOME, per-instance cwd, staggered starts

Six concurrent `claude` processes on one machine is new (until now it was always exactly one session).
The correct model, measured on the box (po2), separates two things:

- **`HOME` — UNTOUCHED (shared, real `~`).** The agent's **memory** lives under `~/.claude/projects/<cwd-slug>/`
  and the single OAuth login lives in `~/.claude`. Pointing `HOME` at a fresh per-instance dir would make
  every agent boot **without its memory, silently** (no error), and split one login into six (the
  Auftraggeber's credentials/cost). `run-bridge.sh` therefore does **not** touch `HOME`. This preserves the
  existing memory slugs (e.g. the backend agent's ~43 files) and the one login; credentials are hash-stable
  across the shared store.
- **Per-instance working directory = the isolation, and it is MANDATORY.** `claude` keys per-project memory
  by the **cwd slug**, so each agent must run in **`/home/thorsten/cyppie-agents/<agent>`** — the path whose
  slug already holds that agent's memory (NOT a worktree subfolder, NOT a fresh dir). `run-bridge.sh`
  defaults `BRIDGE_CWD` to exactly that and **fails loud if the dir is missing** — a fresh cwd would mint a
  new, memory-less slug, the silent-blank-boot this guards against.
- **Stagger the starts.** `claude` does a read-modify-write on `~/.claude.json`; launching all six at the
  same instant races that file. Space the starts a few seconds apart (the `sleep "$STAGGER"` above). po2's
  N=2/N=4 runs confirmed the cwd-slug already separates memory+role and creds stay hash-stable; staggering
  covers the `~/.claude.json` write race.

## What is proven vs. deferred

Proven locally under the bundled stripped runtime:
- **Cold-boot independence** — boots with `JAVA_HOME` and a fake `java` on `PATH` poisoned; uses the
  bundled JRE regardless.
- **Full classload** — Ktor + `ktor-network-tls` + kotlinx-serialization + coroutines + logback load and
  run under the 8-module runtime, reaching the socket datapath (no module / `NoClassDefFound` errors).
- **EC/TLS crypto** — `XDH`/`ECDH`/`EC`/`SHA256withECDSA` resolve under the runtime (SunEC present).
- **Deterministic bytecode** — the Bridge jar is class 65 (Java 21), pinned via `jvmToolchain(21)`.

Deferred to the on-box check (needs a live EC-TLS hub peer):
- A **real wss handshake** completing against the actual hub, and the **6-concurrent-`claude`** shared-home
  behaviour (per-cwd-slug memory separation + the staggered `~/.claude.json` writes) — the field acceptance
  to run before the window (po2 verifies with two instances first).
