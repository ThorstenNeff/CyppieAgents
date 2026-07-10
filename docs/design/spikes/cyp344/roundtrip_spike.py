#!/usr/bin/env python3
"""
CYP-344 — BE-0 coexistence round-trip spike (mediated -> interactive -> mediated on ONE claude session).

Proves (or refutes) the CYP-331 Option-D / D10 obligation empirically on the installed CLI, with
subscription creds (NO api-key). Answers the three ratified questions:

  Q1  Does interactive `claude --resume <sid>` write to the SAME transcript JSONL, so a later
      mediated reader sees the interactive turns?
  Q2  After a clean interactive TUI exit, is the session still mediated-`--resume`-able?
  Q3  Is the session id STABLE across --resume, or does --resume fork a new sid?

Method — a codeword chain, so each leg must *see the prior leg's context* (a sharp continuity signal,
not merely "a file exists"):

  Leg 1  mediated create   (headless stream-json)         plant  ALPHA-11 , capture S1
  Leg 2  interactive resume (real PTY via pexpect)         ask for ALPHA-11, plant BETA-22, /exit
  Leg 3  mediated hand-back (headless stream-json --resume) ask for BOTH codewords

  PROOF A (leg2): interactive answer contains ALPHA-11  => resume loaded the mediated context.
  PROOF B (disk): after leg2 the SAME <S1>.jsonl grew and NO new sid file appeared => same transcript, stable sid.
  PROOF C (leg3): mediated stdout contains BOTH ALPHA-11 AND BETA-22 => the mediated reader sees the
                  interactive turns after hand-back  => Q1+Q2 proven end-to-end.

The mediated legs use the SAME flag family as production ConnectorDefaults.BASE_STREAM_JSON_FLAGS
(`-p --input-format stream-json --output-format stream-json --verbose --dangerously-skip-permissions`),
so this exercises the real mediated reader shape. Tiny token spend (3 short turns); reversible.
"""
import json, os, sys, time, glob, subprocess, pathlib

ALPHA = "ALPHA-11"
BETA = "BETA-22"
CLAUDE = os.environ.get("CLAUDE_BIN", "claude")
PROJECTS = pathlib.Path.home() / ".claude" / "projects"
SKIP = "--dangerously-skip-permissions"


def log(msg):
    print(f"[spike] {msg}", flush=True)


def project_dir_for(cwd: str) -> pathlib.Path:
    """Claude mangles the cwd path into the transcript project dir name; find it robustly by matching."""
    # newest project dir whose name ends with the mangled tail of cwd
    tail = cwd.replace("/", "-")
    cands = sorted(PROJECTS.glob("*"), key=lambda p: p.stat().st_mtime, reverse=True)
    for p in cands:
        if p.name.endswith(tail) or tail.endswith(p.name.lstrip("-")):
            return p
    return None


def jsonl_snapshot(pdir: pathlib.Path):
    """Map sid -> (size, mtime) for every transcript in the project dir."""
    out = {}
    if pdir and pdir.exists():
        for f in pdir.glob("*.jsonl"):
            st = f.stat()
            out[f.stem] = (st.st_size, st.st_mtime)
    return out


def mediated_turn(cwd: str, prompt: str, resume_sid: str | None):
    """Run ONE headless stream-json turn (production flag family). Returns (session_id, assistant_text, raw_lines)."""
    cmd = [CLAUDE]
    if resume_sid:
        cmd += ["--resume", resume_sid]
    cmd += ["-p", "--input-format", "stream-json", "--output-format", "stream-json",
            "--verbose", SKIP]
    user_msg = json.dumps({
        "type": "user",
        "message": {"role": "user", "content": [{"type": "text", "text": prompt}]},
    }) + "\n"
    log(f"mediated turn (resume={resume_sid or '-'}): {prompt[:70]!r}")
    proc = subprocess.run(cmd, cwd=cwd, input=user_msg, capture_output=True, text=True, timeout=180)
    sid, assistant = None, []
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            ev = json.loads(line)
        except json.JSONDecodeError:
            continue
        if ev.get("type") == "system" and ev.get("subtype") == "init":
            sid = ev.get("session_id") or sid
        if ev.get("session_id"):
            sid = sid or ev.get("session_id")
        if ev.get("type") == "assistant":
            for blk in ev.get("message", {}).get("content", []):
                if blk.get("type") == "text":
                    assistant.append(blk["text"])
        if ev.get("type") == "result" and ev.get("result"):
            assistant.append(str(ev["result"]))
    if proc.returncode != 0:
        log(f"  !! mediated exit={proc.returncode} stderr={proc.stderr[:300]!r}")
    text = "\n".join(assistant)
    log(f"  sid={sid} assistant~={text[:120]!r}")
    return sid, text, proc.stdout


def interactive_resume(cwd: str, resume_sid: str, prompt: str, pdir: pathlib.Path, baseline_bytes: int):
    """Drive a REAL interactive claude TUI (no --print) over a PTY, resuming resume_sid; then /exit cleanly.
    Handles the first-run "trust this folder" safety dialog (which otherwise eats the first keystrokes).
    Returns (saw_alpha_in_assistant_turn, transcript_sid_that_grew)."""
    import pexpect
    cmd = f"{CLAUDE} --resume {resume_sid} {SKIP}"
    log(f"interactive TUI: {cmd}")
    child = pexpect.spawn(cmd, cwd=cwd, encoding="utf-8", dimensions=(40, 120), timeout=60)
    child.logfile_read = sys.stdout  # stream the TUI so the run is auditable

    # (1) answer the trust dialog if it appears (Enter = default "Yes, I trust this folder").
    try:
        i = child.expect([r"trust this folder", r"Do you trust", r"bypass permissions", pexpect.TIMEOUT], timeout=20)
        if i in (0, 1):
            log("  trust dialog present -> Enter to accept")
            time.sleep(1); child.send("\r"); time.sleep(3)
        else:
            log("  no trust dialog (folder already trusted / straight to composer)")
    except Exception as e:
        log(f"  trust-dialog expect note: {e}")

    # (2) wait for the composer to be ready, then type + submit the prompt.
    time.sleep(4)
    child.send(prompt)
    time.sleep(1.5)
    child.send("\r")

    # (3) poll the on-disk transcript: the turn is truly submitted only when the file GROWS past baseline
    #     and contains BETA (our typed user text); context is proven when an assistant turn quotes ALPHA.
    saw_alpha = False; submitted = False; target = None
    deadline = time.time() + 120
    while time.time() < deadline:
        for f in sorted(pdir.glob("*.jsonl"), key=lambda p: p.stat().st_mtime, reverse=True):
            try:
                content = f.read_text(errors="ignore")
            except OSError:
                continue
            if f.stem == resume_sid and f.stat().st_size > baseline_bytes and BETA in content:
                submitted = True; target = f.stem
            if BETA in content and f.stem != resume_sid:  # a forked transcript carrying the interactive turn
                submitted = True; target = f.stem
            for ln in content.splitlines():
                try:
                    rec = json.loads(ln)
                except json.JSONDecodeError:
                    continue
                role = (rec.get("message") or {}).get("role") or rec.get("type")
                if role == "assistant" and ALPHA in json.dumps(rec) and BETA in content:
                    saw_alpha, target = True, f.stem
        if saw_alpha:
            break
        time.sleep(2)
    log(f"  interactive: submitted={submitted} saw_ALPHA_in_assistant={saw_alpha} (transcript sid={target})")

    # (4) graceful exit — the hand-off's "clean stop".
    time.sleep(2)
    child.send("/exit"); child.send("\r")
    try:
        child.expect(pexpect.EOF, timeout=30)
    except pexpect.TIMEOUT:
        log("  interactive did not EOF on /exit; sending Ctrl-C")
        child.sendcontrol("c"); child.sendcontrol("c")
        try:
            child.expect(pexpect.EOF, timeout=15)
        except pexpect.TIMEOUT:
            child.close(force=True)
    log(f"  interactive exit status={child.exitstatus} signal={child.signalstatus}")
    return saw_alpha, target


def main():
    stamp = os.environ.get("SPIKE_STAMP", "run")
    wt = pathlib.Path(os.environ["SPIKE_WT"]) if "SPIKE_WT" in os.environ else \
        pathlib.Path("/tmp/cyp344-spike-" + stamp)
    wt.mkdir(parents=True, exist_ok=True)
    (wt / "README.md").write_text("CYP-344 coexistence spike scratch working dir.\n")
    cwd = str(wt)
    log(f"working dir (cwd/transcript key): {cwd}")

    result = {"cwd": cwd, "claude_version": subprocess.getoutput(f"{CLAUDE} --version")}

    # LEG 1 — mediated create
    s1, a1, _ = mediated_turn(
        cwd,
        f"Remember this fact for later in our conversation: the mediated codeword is {ALPHA}. "
        f"Reply with only the word OK.",
        resume_sid=None)
    result["leg1_mediated_create_sid"] = s1
    pdir = project_dir_for(cwd)
    result["project_dir"] = str(pdir)
    snap_after_1 = jsonl_snapshot(pdir)
    result["snapshot_after_leg1"] = {k: v[0] for k, v in snap_after_1.items()}
    log(f"after leg1 transcripts: { {k: v[0] for k,v in snap_after_1.items()} }")
    if not s1:
        result["VERDICT"] = "ABORT: leg1 produced no session_id"
        print(json.dumps(result, indent=2)); return

    # LEG 2 — interactive resume (real PTY)
    baseline_bytes = snap_after_1.get(s1, (0, 0))[0]
    saw_alpha, interactive_sid = interactive_resume(
        cwd, s1,
        f"Two things: (1) What was the mediated codeword I asked you to remember? "
        f"(2) Also remember a second fact: the interactive codeword is {BETA}. Keep your reply short.",
        pdir, baseline_bytes)
    result["leg2_interactive_saw_ALPHA_with_context"] = saw_alpha
    result["leg2_interactive_transcript_sid"] = interactive_sid
    snap_after_2 = jsonl_snapshot(pdir)
    result["snapshot_after_leg2"] = {k: v[0] for k, v in snap_after_2.items()}
    log(f"after leg2 transcripts: { {k: v[0] for k,v in snap_after_2.items()} }")

    # Q3 — sid stability + Q1 same-transcript (disk evidence)
    new_sids = set(snap_after_2) - set(snap_after_1)
    grew = {k for k in snap_after_1 if k in snap_after_2 and snap_after_2[k][0] > snap_after_1[k][0]}
    result["Q3_new_sid_files_after_interactive"] = sorted(new_sids)
    result["Q3_existing_transcripts_that_grew"] = sorted(grew)
    sid_stable = (s1 in grew) and (not new_sids)
    result["Q3_sid_stable_same_transcript_appended"] = sid_stable

    # which sid does the hand-back resume? the interactive transcript sid if known, else s1
    handback_sid = interactive_sid or s1

    # LEG 3 — mediated hand-back
    s3, a3, _ = mediated_turn(
        cwd,
        "Without any preamble, list every codeword you have been told so far in this conversation, "
        "separated by spaces.",
        resume_sid=handback_sid)
    result["leg3_handback_resumed_sid"] = handback_sid
    result["leg3_mediated_reports_sid"] = s3
    result["leg3_assistant_text"] = a3
    sees_alpha = ALPHA in a3
    sees_beta = BETA in a3
    result["PROOF_C_mediated_sees_ALPHA"] = sees_alpha
    result["PROOF_C_mediated_sees_BETA"] = sees_beta

    # VERDICT
    coexist = saw_alpha and sees_alpha and sees_beta
    result["Q1_interactive_shares_transcript_readable_by_mediated"] = bool(sees_beta and sid_stable)
    result["Q2_reresumable_after_clean_tui_exit"] = bool(s3 is not None and (sees_alpha or sees_beta))
    result["VERDICT_coexistence_via_resume_holds"] = bool(coexist)
    result["RECOMMENDATION"] = (
        "BE-2 TRIVIAL (no transcript-bridge): --resume shares one transcript, sid stable, "
        "mediated reader sees interactive turns after hand-back."
        if coexist and sid_stable else
        "ESCALATE to Auftraggeber BEFORE BE-2: coexistence NOT clean via --resume "
        f"(saw_alpha={saw_alpha} sees_alpha={sees_alpha} sees_beta={sees_beta} sid_stable={sid_stable}). "
        "A transcript-bridge / sid-chase is a materially larger scope than 'Option D via --resume'.")

    out = pathlib.Path(os.environ.get("SPIKE_OUT", cwd + "/result.json"))
    out.write_text(json.dumps(result, indent=2))
    print("\n==== RESULT ====")
    print(json.dumps(result, indent=2))
    print(f"\n(result written to {out})")


if __name__ == "__main__":
    main()
