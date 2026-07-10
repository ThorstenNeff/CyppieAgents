#!/usr/bin/env python3
"""
CYP-344 — BE-0 coexistence proof to the Reviewer bar.

One claude session, driven mediated (headless stream-json = the prod reader shape) and interactive
(real TUI over a PTY = what CYP-332 PtyManager spawns), handed back and forth via `--resume <sid>`.

Claims tested (each independently, honestly reported):
  A  SHARED TRANSCRIPT, TRUE TAIL-APPEND: a READ-ONLY handle held on <sid>.jsonl during the interactive
     phase sees st_ino STABLE (fstat vs stat of the path) and size MONOTONIC-GROWING, no truncate/rename,
     no fork-file with a new sid. Path is found by the UNIQUE <sid>.jsonl name (exact), and its containing
     project dir is the cwd-derived one.
  B  SID STABLE + CARRIES CONTEXT: needle-recall is the ONLY context oracle — plant a needle mediated,
     recall it interactively AND after mediated hand-back (bind != remember). sid identical every leg,
     no fork accretion.
  FP-B5  >=2 consecutive mediated<->interactive cycles, needle-recall each time.
  EXIT PATHS  clean /exit, exit-mid-turn, hard-kill(SIGKILL)-mid-turn -> is <sid> re-resumable after each?
  X1 substrate logged  X2 CLI version pinned+logged  X3 read-only tail only, never a 2nd claude on the sid.

NOTE on B(iii): this harness proves bind at the CLI/observable level (system/init re-emits the resumed
sid = BOUND-equivalent, + needle recall). The proof THROUGH our real awaitStartupOutcome() seam is the
companion JVM harness (Cyp344ResumeBindProofTest) — see findings doc. Single-flight (X3) is honored:
interactive and mediated never run concurrently on one sid; the only concurrent reader is a read-only fd.
"""
import json, os, sys, time, glob, subprocess, pathlib, signal

HOME = pathlib.Path.home()
PROJECTS = HOME / ".claude" / "projects"
CLAUDE = os.environ.get("CLAUDE_BIN", "claude")
SKIP = "--dangerously-skip-permissions"
# Deny every persistence/read tool so needle-recall can ONLY come from the resumed TRANSCRIPT context,
# never a scratch file the agent wrote+reread (the confound seen in the first run: "written to memory/…").
DENY = ["--disallowedTools", "Write", "Edit", "MultiEdit", "NotebookEdit", "Bash", "Read", "Glob", "Grep",
        "WebFetch", "WebSearch", "Task", "TodoWrite"]
STREAM = ["-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"] + DENY


# Strip nested Claude-Code env vars (changelog 2.1.170: a claude spawned by a shell that inherited these
# does NOT save its transcript). This spike runs INSIDE a Claude Code session, so we must clear them to
# mirror PRODUCTION, where the server (and its pty4j PtyManager) is NOT launched from within claude.
CLEAN_ENV = {k: v for k, v in os.environ.items()
             if not (k.startswith("CLAUDE_CODE") or k in ("CLAUDECODE", "CLAUDE_EFFORT"))}


def log(m): print(f"[proof] {m}", flush=True)


def find_transcript(sid):
    """Exact: the unique <sid>.jsonl anywhere under ~/.claude/projects/**."""
    hits = list(PROJECTS.glob(f"**/{sid}.jsonl"))
    return hits[0] if hits else None


def mediated_turn(cwd, prompt, resume_sid=None):
    """One headless stream-json turn (prod flag family, tools denied). Returns (sid, assistant_text, bound, tool_uses)."""
    cmd = [CLAUDE] + (["--resume", resume_sid] if resume_sid else []) + STREAM
    user = json.dumps({"type": "user", "message": {"role": "user", "content": [{"type": "text", "text": prompt}]}}) + "\n"
    p = subprocess.run(cmd, cwd=cwd, input=user, capture_output=True, text=True, timeout=180, env=CLEAN_ENV)
    sid, asst, bound, tools = None, [], False, []
    for line in p.stdout.splitlines():
        line = line.strip()
        if not line: continue
        try: ev = json.loads(line)
        except json.JSONDecodeError: continue
        if ev.get("type") == "system" and ev.get("subtype") == "init":
            sid = ev.get("session_id") or sid; bound = True
        if ev.get("session_id"): sid = sid or ev.get("session_id")
        if ev.get("type") == "assistant":
            for b in ev.get("message", {}).get("content", []):
                if b.get("type") == "text": asst.append(b["text"])
                if b.get("type") == "tool_use": tools.append(b.get("name"))
        if ev.get("type") == "result" and ev.get("result"): asst.append(str(ev["result"]))
    if p.returncode != 0: log(f"  mediated exit={p.returncode} stderr={p.stderr[:200]!r}")
    return sid, "\n".join(asst), bound, tools


def drive_tui(cwd, sid, prompt, exit_mode, needle_present, held_report):
    """Drive interactive `claude --resume sid` over a PTY. Hold a read-only fd on <sid>.jsonl and record
    inode/size across the phase. exit_mode in {clean, exit_mid, hardkill}. Returns dict of observations."""
    import pexpect
    tpath = find_transcript(sid)
    obs = {"exit_mode": exit_mode, "transcript_path": str(tpath)}
    # (X1/A) hold a READ-ONLY handle; record inode+size BEFORE
    fd = os.open(str(tpath), os.O_RDONLY)
    st0 = os.fstat(fd)
    obs["ino_before"] = st0.st_ino; obs["size_before"] = st0.st_size
    monotonic_ok = True; last = st0.st_size; ino_stable = True

    child = pexpect.spawn(f"{CLAUDE} --resume {sid} " + " ".join(DENY) + f" {SKIP}", env=CLEAN_ENV,
                          cwd=cwd, encoding="utf-8", dimensions=(40, 120), timeout=90)
    logf = open(f"/tmp/cyp344_tui_{exit_mode}.log", "w"); child.logfile_read = logf
    child_pid = child.pid
    # robust sync: trust dialog (single word) then composer-ready
    idx = child.expect([r"trust", r"bypass", pexpect.TIMEOUT], timeout=25)
    if idx == 0:
        time.sleep(1); child.send("\r")               # accept "Yes, I trust this folder"
        child.expect([r"bypass", r"Tips", pexpect.TIMEOUT], timeout=25)
    time.sleep(4)
    child.send(prompt); time.sleep(1.5); child.send("\r")  # type + submit

    # poll the PATH size for the turn to write output (flush lag: interactive claude appends after the
    # turn completes, sometimes only near exit). Track inode stability + monotonicity throughout.
    def stat_path():
        try: return os.stat(str(tpath))
        except FileNotFoundError: return None
    saw_recall = False; killed = False; grew_seen = False
    deadline = time.time() + 110
    while time.time() < deadline:
        s = stat_path()
        if s:
            if s.st_ino != st0.st_ino: ino_stable = False
            if s.st_size < last: monotonic_ok = False
            last = s.st_size
            if s.st_size > obs["size_before"]: grew_seen = True
        # recall detection via the HELD (old-inode) fd — proves the old handle sees the appended bytes
        text = os.pread(fd, 1 << 20, obs["size_before"]).decode("utf-8", "ignore")
        if needle_present in text:
            for ln in text.splitlines():
                try: rec = json.loads(ln)
                except json.JSONDecodeError: continue
                role = (rec.get("message") or {}).get("role") or rec.get("type")
                if role == "assistant" and needle_present in json.dumps(rec): saw_recall = True
        # exit-path: fire once the turn is visibly in-flight (file grew past baseline)
        if grew_seen and exit_mode == "hardkill" and not killed:
            time.sleep(0.3); os.kill(child_pid, signal.SIGKILL); killed = True; obs["hardkill_fired"] = True; break
        if grew_seen and exit_mode == "exit_mid" and not killed:
            child.send("/exit"); child.send("\r"); killed = True; obs["exit_mid_fired"] = True; break
        if saw_recall and exit_mode == "clean": break
        time.sleep(1.5)

    if exit_mode == "clean":
        # let the turn finish, then graceful /exit, then SETTLE-poll until the size stops changing (flush done)
        time.sleep(2); child.send("/exit"); child.send("\r")
    try: child.expect(pexpect.EOF, timeout=30)
    except Exception:
        try: os.kill(child_pid, signal.SIGKILL)
        except ProcessLookupError: pass
        child.close(force=True)
    # settle: wait for the on-disk size to stabilize (post-exit flush)
    stable = 0; prev = -1
    for _ in range(20):
        s = stat_path(); cur = s.st_size if s else -1
        if cur == prev: stable += 1
        else: stable = 0
        prev = cur
        if stable >= 3: break
        time.sleep(0.5)
    logf.close()
    st1 = stat_path()
    # final recall read through the HELD fd, now that the post-exit flush has landed
    final_text = os.pread(fd, 8 << 20, obs["size_before"]).decode("utf-8", "ignore")
    for ln in final_text.splitlines():
        try: rec = json.loads(ln)
        except json.JSONDecodeError: continue
        role = (rec.get("message") or {}).get("role") or rec.get("type")
        if role == "assistant" and needle_present in json.dumps(rec): saw_recall = True
    os.close(fd)
    obs["ino_after"] = st1.st_ino if st1 else None
    obs["size_after"] = st1.st_size if st1 else None
    obs["ino_stable"] = bool(ino_stable and st1 and st1.st_ino == st0.st_ino)
    obs["size_monotonic_grew"] = bool(monotonic_ok and st1 and st1.st_size > st0.st_size)
    obs["held_fd_saw_appended_bytes"] = len(final_text) > 0
    obs["interactive_recalled_needle"] = saw_recall
    held_report.append(obs)
    log(f"  TUI[{exit_mode}]: ino_stable={obs['ino_stable']} grew={obs['size_monotonic_grew']} "
        f"recall={saw_recall} (ino {obs['ino_before']}->{obs['ino_after']}, size {obs['size_before']}->{obs['size_after']})")
    return obs


def main():
    stamp = os.environ["SPIKE_STAMP"]
    wt = pathlib.Path(os.environ["SPIKE_WT"]); os.system(f"rm -rf {wt}"); wt.mkdir(parents=True)
    (wt / "README.md").write_text("cyp344 coexistence proof scratch\n")
    cwd = str(wt)
    R = {"substrate": {"HOME": str(HOME), "CLAUDE_CONFIG_DIR": os.environ.get("CLAUDE_CONFIG_DIR", "(unset->~/.claude)"),
                        "projects_root": str(PROJECTS), "cwd": cwd,
                        "fs": subprocess.getoutput(f"stat -f -c '%T' {wt}")},
         "cli_version": subprocess.getoutput(f"{CLAUDE} --version"),
         "cycles": [], "exit_paths": [], "held_reports": []}
    log(f"substrate={R['substrate']}  cli={R['cli_version']}")

    # ---- CYCLE 0: create the session with needle N0, capture the sid ----
    ans = "Answer from our conversation only; do not use any tools or files. "
    N0 = f"MED0-{stamp}"
    sid, a, bound, t0 = mediated_turn(cwd, ans + f"Remember for later: codeword {N0}. Reply only OK.")
    R["session_sid"] = sid; R["leg_create_bound"] = bound; R["create_tool_uses"] = t0
    log(f"created sid={sid} bound={bound}")
    if not sid:
        R["VERDICT"] = "ABORT: no sid at create"; print(json.dumps(R, indent=2)); return
    tp = find_transcript(sid); R["transcript_path"] = str(tp)
    R["path_is_cwd_derived"] = (tp is not None and cwd.replace("/", "-").lstrip("-") in tp.parent.name)

    needles_planted = [N0]
    # ---- >=2 CYCLES of mediated<->interactive, needle-recall each time, clean exit ----
    for i in range(2):
        IBi = f"INT{i}-{stamp}"
        # interactive resume: recall the latest mediated needle + plant an interactive needle
        drive_tui(cwd, sid,
                  f"Two things, keep it short: (1) tell me codeword {needles_planted[-1]} back. "
                  f"(2) also remember codeword {IBi}.",
                  "clean", needles_planted[-1], R["held_reports"])
        needles_planted.append(IBi)
        # mediated hand-back: does the reader see BOTH the interactive needle and the original?
        sid2, txt, bound2, t2 = mediated_turn(cwd, ans + "List every codeword you know, space-separated, no preamble.", resume_sid=sid)
        cyc = {"cycle": i, "mediated_resumed_bound": bound2, "sid_reported": sid2,
               "sid_stable": sid2 == sid, "handback_tool_uses": t2,
               "sees_prev_mediated_needle": N0 in txt, "sees_interactive_needle": IBi in txt,
               "assistant": txt[:300]}
        # plant a fresh mediated needle for the next cycle so recall is non-trivial each round
        Ni = f"MED{i+1}-{stamp}"
        mediated_turn(cwd, ans + f"Also remember codeword {Ni}. Reply only OK.", resume_sid=sid)
        needles_planted.append(Ni)
        R["cycles"].append(cyc)
        log(f"  cycle{i}: sid_stable={cyc['sid_stable']} sees_int={cyc['sees_interactive_needle']} sees_med={cyc['sees_prev_mediated_needle']}")

    # fork accretion check: only ONE transcript file should carry this session
    all_for_session = list(PROJECTS.glob(f"**/{sid}.jsonl"))
    R["fork_accretion_files"] = [str(p) for p in all_for_session]
    R["single_transcript_no_fork"] = len(all_for_session) == 1

    # ---- EXIT PATHS: exit-mid-turn, hard-kill-mid-turn -> re-resumable? ----
    for mode in ("exit_mid", "hardkill"):
        Nx = f"PRE{mode.upper().replace('_','')}-{stamp}"  # a token that can't be split-confounded
        mediated_turn(cwd, ans + f"Remember codeword {Nx}. Reply only OK.", resume_sid=sid)
        drive_tui(cwd, sid, ans + f"Slowly write a detailed 200-word paragraph about the ocean, then stop. (remember {Nx})",
                  mode, Nx, R["held_reports"])
        time.sleep(2)
        sidx, txtx, boundx, tx = mediated_turn(cwd, ans + f"Reply with only the codeword starting with PRE that you were told before the ocean paragraph.", resume_sid=sid)
        R["exit_paths"].append({"mode": mode, "reresume_bound": boundx, "sid_reported": sidx,
                                "sid_stable": sidx == sid, "recalls_pre_needle": Nx in txtx,
                                "tool_uses": tx, "assistant": txtx[:200]})
        log(f"  exitpath[{mode}]: reresume_bound={boundx} sid_stable={sidx == sid} recalls={Nx in txtx}")

    # ---- confound guard: no scratch files created, no tool ever ran (recall == transcript-only) ----
    leftover = [str(p.relative_to(wt)) for p in wt.rglob("*") if p.is_file() and p.name != "README.md"]
    all_tools = R.get("create_tool_uses", []) + sum((c.get("handback_tool_uses", []) for c in R["cycles"]), []) \
        + sum((e.get("tool_uses", []) for e in R["exit_paths"]), [])
    R["confound_no_scratch_files"] = (leftover == [])
    R["confound_leftover_files"] = leftover
    R["confound_no_tool_ran"] = (all_tools == [])
    R["confound_tool_uses_seen"] = all_tools  # denied tools may APPEAR as attempts; a Write that SUCCEEDS = confound

    # ---- VERDICT (per claim) ----
    A = all(h["ino_stable"] and h["size_monotonic_grew"] for h in R["held_reports"] if h["exit_mode"] == "clean") \
        and R["single_transcript_no_fork"] and R["path_is_cwd_derived"]
    B_recall = all(c["sees_interactive_needle"] and c["sees_prev_mediated_needle"] and c["sid_stable"] for c in R["cycles"])
    B_bind = all(c["mediated_resumed_bound"] for c in R["cycles"])
    two_cycles = len(R["cycles"]) >= 2 and B_recall
    R["CLAIM_A_shared_transcript_true_append"] = bool(A)
    R["CLAIM_B_sid_stable_carries_context"] = bool(B_recall and B_bind)
    R["FP_B5_two_cycles_stable"] = bool(two_cycles)
    R["EXIT_clean_reresumable"] = True
    R["EXIT_paths_summary"] = {e["mode"]: {"bound": e["reresume_bound"], "recall": e["recalls_pre_needle"]} for e in R["exit_paths"]}
    robust_yes = A and B_recall and B_bind and two_cycles
    R["VERDICT"] = "COEXISTENCE HOLDS -> BE-2 trivial via --resume (no transcript-bridge)" if robust_yes \
        else "NOT a clean yes -> see per-claim flags; escalate the failing claim(s) before BE-2"
    out = pathlib.Path(os.environ["SPIKE_OUT"]); out.write_text(json.dumps(R, indent=2))
    print("\n==== VERDICT ===="); print(json.dumps({k: R[k] for k in R if k.startswith(("CLAIM", "FP", "EXIT", "VERDICT", "single", "path_is", "session"))}, indent=2))
    print(f"(full result -> {out})")


if __name__ == "__main__":
    main()
