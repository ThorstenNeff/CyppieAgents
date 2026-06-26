// Capture FULL raw event structures incl. a tool-call turn.
// Scratch-dir only; --dangerously-skip-permissions used ONLY here to observe
// tool_use / tool_result event shapes (per PO sign-off).
import { spawn } from "node:child_process";
import { writeFileSync } from "node:fs";

const args = [
  "-p",
  "--input-format", "stream-json",
  "--output-format", "stream-json",
  "--verbose",
  "--dangerously-skip-permissions",   // scratch-dir ONLY, to see tool events
];

const child = spawn("claude", args, { stdio: ["pipe", "pipe", "pipe"], cwd: process.cwd() });
const firstOfType = {};   // type/subtype -> full object (first occurrence)
const rawLines = [];
let buf = "";
let resultCount = 0;

const userMsg = (text) => JSON.stringify({ type: "user", message: { role: "user", content: [{ type: "text", text }] } }) + "\n";

child.stdout.on("data", (d) => {
  buf += d.toString();
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
    if (!line) continue;
    rawLines.push(line);
    let ev; try { ev = JSON.parse(line); } catch { continue; }
    const tag = ev.type + (ev.subtype ? "/" + ev.subtype : "");
    if (!(tag in firstOfType)) firstOfType[tag] = ev;
    // also capture assistant tool_use separately (content may vary)
    if (ev.type === "assistant" && ev.message?.content?.some(c => c.type === "tool_use") && !firstOfType["assistant#tool_use"]) {
      firstOfType["assistant#tool_use"] = ev;
    }
    if (ev.type === "user" && Array.isArray(ev.message?.content) && ev.message.content.some(c => c.type === "tool_result") && !firstOfType["user#tool_result"]) {
      firstOfType["user#tool_result"] = ev;
    }
    console.log("EVT>", tag, ev.type === "assistant" ? JSON.stringify(ev.message?.content?.map(c=>c.type)) : "");
    if (ev.type === "result") {
      resultCount++;
      child.stdin.end();   // tool turn done
    }
  }
});
child.stderr.on("data", (d) => process.stderr.write("ERR> " + d.toString()));
child.on("exit", (code) => {
  writeFileSync("events.raw.ndjson", rawLines.join("\n") + "\n");
  writeFileSync("events.bytype.json", JSON.stringify(firstOfType, null, 2));
  console.log(`\n[exit ${code}] captured types: ${Object.keys(firstOfType).join(", ")}`);
  process.exit(0);
});

// one turn that forces a tool_use: ask it to run a trivial bash command
child.stdin.write(userMsg("Run the bash command `echo spike-tool-ok` and tell me its output."));
setTimeout(() => { console.log("[timeout]"); child.kill("SIGKILL"); }, 120000);
