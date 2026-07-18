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
├── run-bridge.sh                 # launch ONE isolated instance (see below)
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

`run-bridge.sh` gives the instance a **per-instance `HOME` and working dir** so its `claude` gets a
private `~/.claude` (config / session state / credential store).

## Run — 6 instances (the M1.6 shape)

All instances run as the **same OS user**. Isolation is by per-instance `HOME`/cwd, which `run-bridge.sh`
sets from `HUB_AGENT_ID`:

```bash
# one HUB_URL, one line per agent — each with its own token
declare -A TOKENS=( [po]=… [frontend]=… [backend]=… [agent4]=… [agent5]=… [agent6]=… )
for id in "${!TOKENS[@]}"; do
  HUB_URL=wss://<hub-host> HUB_AGENT_ID="$id" HUB_TOKEN="${TOKENS[$id]}" \
    ./run-bridge.sh >"logs/$id.log" 2>&1 &
done
```

### ⚠ Parallel instances — the AUTH trade-off (operator decision)

Six concurrent `claude` processes have **never** shared one machine before (until now it was always
exactly one session). The hub side is safe (identity is the token), but the **local** side is not
automatic:

- **Isolated (default):** each instance has its own `HOME`, so its `~/.claude` is private — no collision.
  But each private `~/.claude` needs its **own claude authentication seeded once** (copy an already
  authenticated `~/.claude` into each instance's `HOME`, or run `claude` interactively once per HOME).
- **Shared:** point every instance at one `HOME` (set `BRIDGE_ROOT` to a common path) — one login for
  all six, but they share one config/state/credential store. That is the collision the isolation avoids;
  whether it holds under 6 concurrent sessions is **untested** — verify with two before running six.

Pick deliberately. Do not assume a shared home holds.

## What is proven vs. deferred

Proven locally under the bundled stripped runtime:
- **Cold-boot independence** — boots with `JAVA_HOME` and a fake `java` on `PATH` poisoned; uses the
  bundled JRE regardless.
- **Full classload** — Ktor + `ktor-network-tls` + kotlinx-serialization + coroutines + logback load and
  run under the 8-module runtime, reaching the socket datapath (no module / `NoClassDefFound` errors).
- **EC/TLS crypto** — `XDH`/`ECDH`/`EC`/`SHA256withECDSA` resolve under the runtime (SunEC present).
- **Deterministic bytecode** — the Bridge jar is class 65 (Java 21), pinned via `jvmToolchain(21)`.

Deferred to the on-box check (needs a live EC-TLS hub peer):
- A **real wss handshake** completing against the actual hub, and the **6-concurrent-`claude`** home
  behaviour — both are the field acceptance to run before the window.
