#!/usr/bin/env python3
"""token-usage.py - hub-INDEPENDENT token accounting from claude session transcripts.

Claude writes its session transcripts to ~/.claude/projects/<cwd-slug>/*.jsonl WITH and WITHOUT the
Hub, so the token numbers it already emits (per assistant response, in the `usage` object) are the
same measurement in both modes. This tool reads those numbers over a time window and sums them,
split by category. No Hub, no instrumentation, no new measurement point - it reads what is there.

Categories (kept SEPARATE - the with/without-Hub delta lives in the split, never in a bare total):
  input_tokens . output_tokens . cache_creation_input_tokens . cache_read_input_tokens

CORRECTNESS GATE (verified at the object 2026-07-21): claude writes each assistant response TWICE in
the transcript, so a naive sum over all assistant records DOUBLE-COUNTS every token (2x inflation).
This matters even for a with/without-Hub DELTA experiment: both modes double the same way, so the delta
survives, but the ABSOLUTE token counts are 2x too high - and the Auftraggeber may report absolutes.

  CAUSE (measured, not assumed): the two entries share the SAME `message.id` AND the SAME `requestId`
  (`req_011C...`) and carry IDENTICAL `usage` - they are ONE billed API response, not two. It is a
  transcript-threading artifact of a `stop_reason == "tool_use"` turn: the assistant message is logged
  once, then re-logged as a uuid-chained node (the 2nd entry's `parentUuid` == the 1st's `uuid`) while
  claude threads the tool_use -> tool_result continuation. `requestId` proves single billing.

  DEDUP RULE: collapse by `message.id` (one billing event per id). Because the duplicate entries are the
  same API response, their usage is identical BY CONSTRUCTION. We do NOT silently "take the last" - that
  would assume which is canonical. Instead, if two records with the same `message.id` ever carry
  DIFFERENT usage, that violates the single-billing invariant -> it is FLAGGED as an anomaly (surfaced in
  output + stderr), never silently resolved. Normal case: identical -> zero anomalies. Run `--selftest`.

  NAMED LIMIT (not closed): dedup-by-id rests on the assumption `1 requestId : 1 message.id` - i.e. a
  request never bills usage across MULTIPLE distinct message.ids. Verified at the object 2026-07-21:
  24706 usage-bearing requestIds across all transcripts, 0 mapping to >1 message.id (+ the API contract:
  one request -> one assistant message -> one usage; a tool_use continuation is a NEW request -> NEW id).
  Revisit ONLY if the API ever bills per-request across DISTINCT message.ids: dedup-by-id would then
  OVER-count, and the same-id divergence flag would NOT catch it (the collision would be across different
  ids, not a same-id conflict). At 24706:0 a runtime guard is gold-plating; the assumption is named here
  rather than left latent - leaving it latent is the trap.

Usage:
  token-usage.py --agent <id> [--from <iso>] [--to <iso>] [--out file.csv|file.json] [--per-turn]
  token-usage.py --dir <path-to-project-slug-dir> ...
  token-usage.py --selftest
"""
import argparse
import csv
import glob
import json
import os
import sys
from datetime import datetime, timezone

CATEGORIES = (
    "input_tokens",
    "output_tokens",
    "cache_creation_input_tokens",
    "cache_read_input_tokens",
)


def _parse_iso(s):
    """Parse ISO-8601 -> (aware_datetime, was_tz_naive), or None if unparseable.

    Record timestamps are UTC (trailing 'Z' -> aware). A tz-LESS input (a bound "2026-07-21T06:00:00", or the
    space-separated form `fromisoformat` accepts) parses NAIVE -> assume UTC, so aware/naive comparisons never
    crash and a bound aligns with the UTC-stamped records. `was_tz_naive` lets the CLI-bound path SURFACE that
    assumption (PL: name-the-limit at runtime, not only in the docstring) without spamming it per record.
    """
    if not s:
        return None
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None
    was_naive = dt.tzinfo is None
    if was_naive:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt, was_naive


def parse_ts(s):
    """RECORD-timestamp parse: aware datetime or None. A bad/absent record ts SKIPS that one record (a single
    corrupt line must not fail a whole measurement) and is SILENT (per-record notes would spam the data path).
    The tz-coerce note belongs to user INPUT only - see [parse_window_bound]."""
    r = _parse_iso(s)
    return r[0] if r else None


def parse_window_bound(flag, value):
    """CLI `--from`/`--to`: None when NOT given (no filter); FAIL LOUD when GIVEN but unparseable; and EMIT a
    note when a given bound is tz-naive (assumed UTC).

    Distinctions (PL): a not-given bound is a legitimate "no filter" (None); a given-but-invalid bound (a typo)
    must NOT silently become None, or the tool - whose whole purpose is a time WINDOW - measures the FULL range
    and reports a plausible, wrong number. And a given-but-tz-naive bound is coerced to UTC - which is an
    ASSUMPTION about the user's input, so it is surfaced at runtime (scoped to bounds, never to the record data).
    """
    if value is None:
        return None
    r = _parse_iso(value)
    if r is None:
        raise SystemExit(
            f"error: {flag} value {value!r} is not a valid ISO-8601 timestamp (e.g. 2026-07-21T06:12:00Z) "
            f"- refusing to silently measure the FULL range"
        )
    dt, was_naive = r
    if was_naive:
        print(f"note: {flag} bound {value!r} is tz-naive -> assuming UTC "
              f"(records are UTC-stamped)", file=sys.stderr)
    return dt


def resolve_dir(agent, explicit_dir, projects_root):
    """Map an agent id (or explicit dir) to its ~/.claude/projects/<slug> transcript directory.

    The slug is the launch-cwd path with '/' -> '-', so a bridge/agent for id X lands in a dir whose
    name ends with '-<X>'. Fail LOUD on 0 or >1 matches rather than guessing the wrong agent.
    """
    if explicit_dir:
        if not os.path.isdir(explicit_dir):
            raise SystemExit(f"error: --dir '{explicit_dir}' is not a directory")
        return explicit_dir
    matches = [d for d in glob.glob(os.path.join(projects_root, f"*-{agent}")) if os.path.isdir(d)]
    if not matches:
        raise SystemExit(
            f"error: no transcript dir for agent '{agent}' under {projects_root} "
            f"(expected a dir ending in '-{agent}'); pass --dir explicitly"
        )
    if len(matches) > 1:
        raise SystemExit(
            f"error: agent '{agent}' is ambiguous - {len(matches)} matching dirs: "
            f"{[os.path.basename(m) for m in matches]}; pass --dir explicitly"
        )
    return matches[0]


def collect(files, frm=None, to=None):
    """Return (by_id, anomalies) over the window, DEDUPED by message.id.

    by_id: {message_id: (timestamp, {category: int})} - one billing event per id.
    anomalies: list of {message_id, kept, conflict} for ids whose duplicate entries carried DIFFERENT
    usage. That violates the single-billing invariant (same id + requestId => same API response => same
    usage), so it is FLAGGED rather than silently resolved. The first-seen usage is kept as the
    representative; the divergence is surfaced so no silently-wrong number is ever reported.

    Only `type=='assistant'` records with a `message.usage` and a parseable in-window `timestamp` count.
    """
    by_id = {}
    anomalies = []
    for path in files:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except (ValueError, TypeError):
                    continue
                if rec.get("type") != "assistant":
                    continue
                msg = rec.get("message") or {}
                usage = msg.get("usage")
                mid = msg.get("id")
                ts = parse_ts(rec.get("timestamp"))
                if usage is None or mid is None or ts is None:
                    continue
                if frm is not None and ts < frm:
                    continue
                if to is not None and ts > to:
                    continue
                vals = {c: int(usage.get(c) or 0) for c in CATEGORIES}
                if mid in by_id:
                    prev = by_id[mid][1]
                    if prev != vals:
                        # VERIFY, don't assume: a duplicate id whose usage differs is NOT the expected
                        # tool_use double-log (which is byte-identical) -> flag it, keep first-seen.
                        anomalies.append({"message_id": mid, "kept": prev, "conflict": vals})
                    continue  # identical duplicate -> the expected artifact, already counted once
                by_id[mid] = (ts, vals)
    return by_id, anomalies


def summarize(by_id, anomalies=()):
    """Sum the 4 categories separately over the deduped turns; turns = distinct message ids.

    `anomalies` (divergent same-id usage, from [collect]) is surfaced as a count so a run that hit the
    single-billing violation is never reported as clean.
    """
    totals = {c: 0 for c in CATEGORIES}
    for _ts, vals in by_id.values():
        for c in CATEGORIES:
            totals[c] += vals[c]
    totals["turns"] = len(by_id)
    totals["total_tokens"] = sum(totals[c] for c in CATEGORIES)
    totals["anomalies"] = len(anomalies)
    return totals


def per_turn_rows(by_id):
    """One row per distinct message id, ordered by timestamp (stable, reproducible output)."""
    rows = []
    for mid, (ts, vals) in by_id.items():
        row = {"message_id": mid, "timestamp": ts.isoformat()}
        row.update(vals)
        rows.append(row)
    rows.sort(key=lambda r: (r["timestamp"], r["message_id"]))
    return rows


def write_output(out_path, totals, rows):
    if out_path is None:
        print(json.dumps({"summary": totals, "per_turn": rows} if rows else {"summary": totals}, indent=2))
        return
    ext = os.path.splitext(out_path)[1].lower()
    if ext == ".json":
        with open(out_path, "w", encoding="utf-8") as fh:
            json.dump({"summary": totals, "per_turn": rows}, fh, indent=2)
    elif ext == ".csv":
        with open(out_path, "w", encoding="utf-8", newline="") as fh:
            w = csv.writer(fh)
            # `anomalies` in the CSV too (not just JSON/stderr): a CSV-only consumer must still see a
            # single-billing violation - stderr scrolls away, the file is what gets reported.
            w.writerow(["scope"] + list(CATEGORIES) + ["turns", "total_tokens", "anomalies"])
            w.writerow(["SUMMARY"] + [totals[c] for c in CATEGORIES]
                       + [totals["turns"], totals["total_tokens"], totals["anomalies"]])
            if rows:
                w.writerow([])
                w.writerow(["message_id", "timestamp"] + list(CATEGORIES))
                for r in rows:
                    w.writerow([r["message_id"], r["timestamp"]] + [r[c] for c in CATEGORIES])
    else:
        raise SystemExit(f"error: --out must end in .json or .csv (got '{out_path}')")
    print(f"wrote {out_path}: {totals['turns']} turns, {totals['total_tokens']} total tokens", file=sys.stderr)


# --------------------------------------------------------------------------- teeth (--selftest)

def _rec(mid, ts, inp=0, out=0, cc=0, cr=0, extra=None, rtype="assistant"):
    """Build one transcript line. `extra` injects hub-specific fields to prove they are ignored."""
    usage = {"input_tokens": inp, "output_tokens": out,
             "cache_creation_input_tokens": cc, "cache_read_input_tokens": cr}
    if extra:
        usage.update(extra)
    return json.dumps({"type": rtype, "timestamp": ts, "message": {"id": mid, "usage": usage}})


def _write_lines(path, lines):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def selftest():
    import tempfile
    import unittest

    class Teeth(unittest.TestCase):
        def setUp(self):
            self.tmp = tempfile.mkdtemp(prefix="tokusage-selftest-")

        def _file(self, name, lines):
            p = os.path.join(self.tmp, name)
            _write_lines(p, lines)
            return p

        def test_dedup_by_message_id_14_records_7_ids(self):
            # THE correctness gate: claude double-logs each assistant response (tool_use threading artifact,
            # same id+requestId+usage). 14 records / 7 ids. A naive sum-all counts 14 -> 2x inflation.
            # Dedup must yield exactly 7 turns and 1x tokens, with ZERO anomalies (identical duplicates).
            lines = []
            for i in range(7):
                mid = f"msg-{i}"
                lines.append(_rec(mid, f"2026-07-21T06:12:{50+i:02d}.254Z", inp=2, out=100, cr=1000))
                lines.append(_rec(mid, f"2026-07-21T06:12:{50+i:02d}.789Z", inp=2, out=100, cr=1000))
            by_id, anomalies = collect([self._file("hub.jsonl", lines)])
            t = summarize(by_id, anomalies)
            self.assertEqual(t["turns"], 7, "MUST count 7 distinct ids, not 14 records (dedup gate)")
            self.assertEqual(t["output_tokens"], 700, "7*100, not 14*100 - a naive sum double-counts")
            self.assertEqual(t["cache_read_input_tokens"], 7000)
            self.assertEqual(t["anomalies"], 0, "identical duplicates are the expected artifact, not an anomaly")

        def test_divergent_same_id_usage_is_FLAGGED_not_silently_resolved(self):
            # PL requirement 1 (verify, don't assume): if two records share a message.id but carry DIFFERENT
            # usage, the single-billing invariant broke. The tool must NOT silently pick one (neither
            # "take last" nor "take first" as if canonical) - it must FLAG the divergence.
            lines = [
                _rec("m", "2026-07-21T06:00:00.100Z", inp=1, out=5, cr=0),      # first
                _rec("m", "2026-07-21T06:00:00.600Z", inp=1, out=50, cr=900),   # SAME id, DIFFERENT usage
            ]
            by_id, anomalies = collect([self._file("d.jsonl", lines)])
            self.assertEqual(len(anomalies), 1, "a divergent same-id usage MUST be flagged, not silently resolved")
            self.assertEqual(anomalies[0]["message_id"], "m")
            self.assertEqual(summarize(by_id, anomalies)["anomalies"], 1, "the anomaly count surfaces in the summary")
            # and it did NOT double-count: still one turn, not two
            self.assertEqual(summarize(by_id, anomalies)["turns"], 1)

        def test_hub_independence_same_result_raw_and_hub(self):
            # THE review tooth: identical result against a RAW-shaped and a HUB-shaped transcript.
            # The hub transcript carries extra fields (server_tool_use, inference_geo, ...) - the tool must
            # ignore them, keying only on the shared core (message.id + usage categories + timestamp).
            raw = [_rec("a", "2026-07-21T06:00:00.100Z", inp=5541, out=21, cc=34374, cr=18878)]
            hub = [_rec("a", "2026-07-21T06:00:00.100Z", inp=5541, out=21, cc=34374, cr=18878,
                        extra={"server_tool_use": {"web": 0}, "service_tier": "standard",
                               "inference_geo": "not_available", "speed": "standard"})]
            traw = summarize(*collect([self._file("raw.jsonl", raw)]))
            thub = summarize(*collect([self._file("hub.jsonl", hub)]))
            self.assertEqual(traw, thub, "hub-specific extra usage fields must NOT change the accounting")
            self.assertEqual(traw["input_tokens"], 5541)

        def test_window_filter_excludes_out_of_range(self):
            lines = [
                _rec("early", "2026-07-21T05:00:00.000Z", inp=999),
                _rec("in",    "2026-07-21T06:30:00.000Z", inp=10),
                _rec("late",  "2026-07-21T08:00:00.000Z", inp=999),
            ]
            frm = parse_ts("2026-07-21T06:00:00Z")
            to = parse_ts("2026-07-21T07:00:00Z")
            t = summarize(*collect([self._file("w.jsonl", lines)], frm, to))
            self.assertEqual(t["turns"], 1, "only the in-window record counts")
            self.assertEqual(t["input_tokens"], 10, "out-of-window tokens excluded")

        def test_categories_stay_separate(self):
            lines = [_rec("x", "2026-07-21T06:00:00Z", inp=1, out=2, cc=4, cr=8)]
            t = summarize(*collect([self._file("c.jsonl", lines)]))
            self.assertEqual((t["input_tokens"], t["output_tokens"],
                              t["cache_creation_input_tokens"], t["cache_read_input_tokens"]), (1, 2, 4, 8),
                             "the 4 categories are summed independently, never merged")
            self.assertEqual(t["total_tokens"], 15)

        def test_cli_window_bound_given_but_invalid_fails_LOUD(self):
            # PL bug: a specified-but-unparseable --from/--to must NOT silently become None (which would
            # measure the FULL range). not-given -> None (ok); given-but-invalid -> SystemExit (loud).
            self.assertIsNone(parse_window_bound("--from", None), "not-given bound is a legit no-filter (None)")
            self.assertIsNotNone(parse_window_bound("--from", "2026-07-21T06:00:00Z"), "a valid bound parses")
            # a tz-less but VALID stamp is coerced to UTC (aware) - not an error, and comparison-safe:
            b = parse_window_bound("--from", "2026-07-21T06:00:00")
            self.assertIsNotNone(b) and self.assertIsNotNone(b.tzinfo)
            for bad in ("not-a-date", "yesterday", "2026/07/21", "Jul 21 6am"):
                with self.assertRaises(SystemExit, msg=f"invalid bound {bad!r} must FAIL LOUD, not silently None"):
                    parse_window_bound("--from", bad)
            # the RECORD-level parse_ts stays lenient (a corrupt line skips, never crashes the run):
            self.assertIsNone(parse_ts("not-a-date"), "record-ts parsing stays lenient (skip, not crash)")

        def test_tz_naive_bound_emits_note_but_records_and_aware_bounds_do_not(self):
            # PL: the tz-naive->UTC coercion is an assumption about USER INPUT -> surface it at runtime, but
            # ONLY on the bound path. An aware bound emits nothing; a record-stamp parse emits nothing (per-record
            # notes would spam the data path). The note is for the user's window, not the data.
            import contextlib
            import io

            def stderr_of(fn):
                buf = io.StringIO()
                with contextlib.redirect_stderr(buf):
                    fn()
                return buf.getvalue()

            naive_note = stderr_of(lambda: parse_window_bound("--from", "2026-07-21T06:00:00"))  # no Z
            self.assertIn("assuming UTC", naive_note, "a tz-naive BOUND must surface the UTC assumption")
            self.assertEqual("", stderr_of(lambda: parse_window_bound("--from", "2026-07-21T06:00:00Z")),
                             "an aware bound emits NO note")
            self.assertEqual("", stderr_of(lambda: parse_ts("2026-07-21T06:00:00")),
                             "record-stamp parsing must NEVER emit the note (per-record spam)")
            # and the coercion still works (aware result, comparison-safe)
            self.assertIsNotNone(parse_window_bound("--from", "2026-07-21T06:00:00").tzinfo)

        def test_csv_output_carries_anomalies_column(self):
            import tempfile
            lines = [  # a divergent same-id pair -> 1 anomaly that MUST reach the CSV, not just stderr
                _rec("m", "2026-07-21T06:00:00.100Z", inp=1, out=5),
                _rec("m", "2026-07-21T06:00:00.600Z", inp=1, out=99),
            ]
            by_id, anomalies = collect([self._file("d.jsonl", lines)])
            out = os.path.join(self.tmp, "o.csv")
            write_output(out, summarize(by_id, anomalies), None)
            text = open(out, encoding="utf-8").read()
            self.assertIn("anomalies", text.splitlines()[0], "CSV header must include the anomalies column")
            self.assertEqual(text.splitlines()[1].split(",")[-1], "1", "the anomaly count must appear in the CSV row")

        def test_non_assistant_and_usageless_records_ignored(self):
            lines = [
                json.dumps({"type": "user", "timestamp": "2026-07-21T06:00:00Z", "message": {"id": "u"}}),
                json.dumps({"type": "assistant", "timestamp": "2026-07-21T06:00:01Z", "message": {"id": "nousage"}}),
                _rec("real", "2026-07-21T06:00:02Z", inp=7),
            ]
            t = summarize(*collect([self._file("m.jsonl", lines)]))
            self.assertEqual(t["turns"], 1, "user turns and usage-less assistant records do not count")
            self.assertEqual(t["input_tokens"], 7)

    suite = unittest.TestLoader().loadTestsFromTestCase(Teeth)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description="hub-independent token accounting from claude transcripts")
    ap.add_argument("--agent", help="agent id (resolves to ~/.claude/projects/*-<id>)")
    ap.add_argument("--dir", help="explicit transcript dir (overrides --agent)")
    ap.add_argument("--from", dest="frm", help="window start (ISO-8601, inclusive)")
    ap.add_argument("--to", dest="to", help="window end (ISO-8601, inclusive)")
    ap.add_argument("--out", help="output file (.csv or .json); default = JSON to stdout")
    ap.add_argument("--per-turn", action="store_true", help="include one row per turn")
    ap.add_argument("--projects-root", default=os.path.expanduser("~/.claude/projects"),
                    help="transcript root (default ~/.claude/projects)")
    ap.add_argument("--selftest", action="store_true", help="run the correctness teeth and exit")
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()
    if not args.agent and not args.dir:
        ap.error("one of --agent or --dir is required (or --selftest)")

    d = resolve_dir(args.agent, args.dir, args.projects_root)
    files = sorted(glob.glob(os.path.join(d, "*.jsonl")))
    if not files:
        raise SystemExit(f"error: no *.jsonl transcripts in {d}")
    by_id, anomalies = collect(files, parse_window_bound("--from", args.frm), parse_window_bound("--to", args.to))
    totals = summarize(by_id, anomalies)
    rows = per_turn_rows(by_id) if args.per_turn else None
    if anomalies:
        # FLAG loudly (PL: verify, don't assume) - a divergent same-id usage means the single-billing
        # invariant broke; the operator must not report the sum as trustworthy without looking.
        print(f"WARNING: {len(anomalies)} message.id(s) had DIVERGENT usage across duplicate entries "
              f"(single-billing invariant violated - review before trusting absolutes):", file=sys.stderr)
        for a in anomalies[:10]:
            print(f"  {a['message_id']}: kept={a['kept']} conflict={a['conflict']}", file=sys.stderr)
    write_output(args.out, totals, rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
