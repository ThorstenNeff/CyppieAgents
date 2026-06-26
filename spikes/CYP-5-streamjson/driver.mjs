// stream-json spike driver — verifies whether ONE long-lived `claude` process
// accepts MULTIPLE user turns over piped stdin (the core MVP question).
// Read-only: prompts need no tools, so no permissions are exercised.
import { spawn } from "node:child_process";

const args = [
  "-p",
  "--input-format", "stream-json",
  "--output-format", "stream-json",
  "--verbose",
  "--replay-user-messages",          // echoes our stdin back, proves it was parsed
  "--allowedTools", "",              // minimal rights per PO
];

const child = spawn("claude", args, { stdio: ["pipe", "pipe", "pipe"], cwd: process.cwd() });

const seen = [];                     // (type/subtype) sequence
let buf = "";
let turn1ResultSeen = false;
let turn2Sent = false;
let turn2EventAfterResult = false;

function userMsg(text) {
  // candidate stdin shape (reverse-engineered) — we'll see if --replay confirms it
  return JSON.stringify({
    type: "user",
    message: { role: "user", content: [{ type: "text", text }] },
  }) + "\n";
}

child.stdout.on("data", (d) => {
  buf += d.toString();
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (!line) continue;
    let ev;
    try { ev = JSON.parse(line); } catch { console.log("NONJSON>", line.slice(0, 120)); continue; }
    const tag = ev.type + (ev.subtype ? "/" + ev.subtype : "");
    seen.push(tag);
    // compact log
    let extra = "";
    if (ev.type === "assistant" && ev.message?.content) {
      extra = " text=" + JSON.stringify(ev.message.content.map(c => c.type + (c.text ? `:${c.text.slice(0,40)}` : "")));
    }
    if (ev.type === "result") extra = ` is_error=${ev.is_error} result=${JSON.stringify((ev.result||"").slice(0,40))} session=${ev.session_id}`;
    if (ev.type === "user") extra = " (replayed stdin)";
    console.log("EVT>", tag, extra);

    if (ev.type === "result") {
      if (!turn1ResultSeen) {
        turn1ResultSeen = true;
        // === THE TEST: send a 2nd turn into the SAME process ===
        setTimeout(() => {
          if (!child.stdin.writable) { console.log("!! stdin not writable after turn1 result"); return; }
          console.log("--- sending TURN 2 into same process ---");
          turn2Sent = true;
          child.stdin.write(userMsg("Reply with exactly the word: SECOND"));
        }, 200);
      } else {
        // a second result => multi-turn over one process WORKS
        turn2EventAfterResult = true;
        console.log("=== TURN 2 produced a result on the SAME process — multi-turn OK ===");
        child.stdin.end();
      }
    }
  }
});

child.stderr.on("data", (d) => process.stderr.write("ERR> " + d.toString()));

child.on("exit", (code, sig) => {
  console.log(`\n[exit] code=${code} sig=${sig}`);
  console.log("[seq]", seen.join(" → "));
  console.log("[verdict] turn2Sent=" + turn2Sent + " multiTurnOnOneProcess=" + turn2EventAfterResult);
  process.exit(0);
});

// kick off turn 1
child.stdin.write(userMsg("Reply with exactly the word: FIRST"));

// safety timeout
setTimeout(() => { console.log("\n[timeout 90s] killing"); child.kill("SIGKILL"); }, 90000);
