// CYP-342 — pin the browser timezone in the BUILD, not in the environment.
//
// `TZ=… ./gradlew wasmJsBrowserTest` is not reproducible: TZ is not a Gradle task input, so an UP-TO-DATE task
// happily replays a result recorded under a different zone. A gate command that needs `--rerun-tasks` to mean
// what it says is not a gate. This file IS an input to the karma test task, so changing the zone re-runs the
// tests, and the gate command needs no environment variable at all.
//
// Karma launches the browser as a child process, which inherits this `process.env`.
//
// Why `America/St_Johns`: a NEGATIVE offset at a HALF-HOUR boundary (−03:30 in winter, −02:30 in summer). Under
// `TZ=UTC` the offset is 0 and the sign assertion in `TranscriptTimeWasmTest` is vacuous (`-0 == 0`); a
// whole-hour zone never exercises the 30-minute component; a negative zone additionally drives the floor-mod in
// `formatHhMm` across the day boundary. The assertion itself compares the full `HH:mm` (CYP-343) — the zone
// makes the test non-vacuous, the assertion makes it correct. Neither substitutes for the other.
process.env.TZ = "America/St_Johns";
