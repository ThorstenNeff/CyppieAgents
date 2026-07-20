# 4a — Does the Claude Code project-slug directory appear at PROCESS START or at FIRST TURN?

**Status:** designed, NOT yet run. Runnable by anyone with box access; takes ~2 minutes.
**Owner:** unassigned — intended to be picked up alongside the remaining four M1.7 bridges.
**Origin:** M1.7 first-bridge window, 2026-07-20. PL had asserted "SLUG-OK proves claude received AND
processed the elicit" and retracted it: that was an inference, never a measurement. This experiment turns
it into one.

## Why it matters

`CHECK1` (slug freshness) is used as evidence that a bridge **processed messages**. If the directory is
created at process start, `CHECK1` only proves **"process launched, cwd correct"** — a *folder* proof, not a
*processing* proof. We would then be trusting it across the remaining four bridges for something it cannot
show. It also decides whether the "CHECK2 before CHECK1" ordering has any evidential basis (see §Fallout).

## Why it could not be answered from source

The directory is created by the **`claude` binary**, not by our code, so its creation condition is not
derivable from this repo. And the 2026-07-20 run could not separate the two hypotheses: the process started
at ~15:40:06 and the first turn landed 15:40:12.110 — **4.3 s apart**. Only an empirical run with *no*
message can separate them.

## Preconditions (both are load-bearing — the 2026-07-20 run violated both)

1. **Exactly ONE bridge process for the agent id.** That day two ran concurrently as `backend2`
   (PIDs 351154 from 15:38 and 352590 from 15:40, identical `HUB_AGENT_ID`/`BRIDGE_CWD`). The server
   registers one session per agentId, so the later connect displaces the earlier, leaving a mute zombie on
   the spoke. Verify first:
   ```bash
   ps aux | grep '[C]yppieBridge'          # expect exactly one line for the agent under test
   tr '\0' '\n' < /proc/<pid>/environ | grep -E '^(HUB_AGENT_ID|BRIDGE_CWD)='
   ```
2. **A THROWAWAY cwd that no running agent shares.** Do **not** use an agent folder. The slug directory is
   keyed on the launch cwd, and an agent working in that folder writes to the same directory continuously —
   on 2026-07-20 `-home-thorsten-cyppie-agents-backend2` had **three** writers (two bridges *and* the
   backend2 agent's own session, updating every few seconds). Directory mtime there measures nothing.

## Procedure

```bash
NEWCWD=$(mktemp -d /tmp/cyp745-slug-XXXX)          # fresh, agent-free
SLUG=$(echo "$NEWCWD" | sed 's|/|-|g')             # dir-name convention: cwd with / -> -
ls -d ~/.claude/projects/"$SLUG" 2>/dev/null && echo "ABORT: slug already exists, pick another dir"

HUB_URL=ws://127.0.0.1:8787 HUB_AGENT_ID=<agent> HUB_TOKEN=<token> BRIDGE_CWD="$NEWCWD" \
  /home/thorsten/byoa-m1-bridge/.../CyppieBridge &
BPID=$!

sleep 60                                            # connect + settle; send NOTHING to the agent
ls -la ~/.claude/projects/"$SLUG"/ 2>/dev/null || echo "NO SLUG DIR"
kill "$BPID"
```

## Reading the result

| Observation after 60 s with **zero** messages sent | Conclusion |
|---|---|
| Slug dir exists (with or without a `.jsonl`) | **Created at START** ⇒ `CHECK1` is a *folder/launch* proof only. Must not be cited as processing evidence. |
| No slug dir | **Created at FIRST TURN** ⇒ `CHECK1` does carry processing meaning — but see the contamination caveat: it only holds in a cwd with no other writer. |

Record the actual output verbatim; do not summarize it to a verdict.

## Fallout to settle once it is answered

- **If created at start:** the "CHECK2 before CHECK1" ordering loses its stated justification ("the slug only
  appears through message processing"). The ordering becomes arbitrary *on that axis*. A weaker but honest
  reason survives — CHECK2 is the stronger test and fails more informatively — but that is economics, not
  necessity, and must not be presented as proof.
- **Either way**, prefer the stronger processing proof found on 2026-07-20 (proposed `CHECK1′`): the session
  transcript, written by `claude` itself, timestamped, independent of our logging, surviving the process:
  ```bash
  jq -r 'select(.type=="user" and .promptId).timestamp' \
    ~/.claude/projects/<slug>/*.jsonl | awk -v t="$CONNECT_TS" '$0>t' | head -1
  ```
  Non-empty ⇒ **provably processed** a turn after connect. This measures what CHECK1 only asserts.
  Caveat: per-file attribution is per-process (one session file = one process), so this stays valid even
  with duplicate bridges — unlike directory mtime.

## Related

- The bridge's success path logs **nothing** (`BridgeRelay`: only the non-`WireDeliver` `else` branch logs),
  so absence of log lines is not absence of processing. A one-line `log.info` on successful `WireDeliver`
  injection would close that blind spot — worth its own ticket.
- `BRIDGE_CWD` = agent folder means the bridge's claude shares the project dir (incl. `memory/`) with the
  agent of the same name. Intended or not, it should be a decision, not a side effect (CYP-707).
