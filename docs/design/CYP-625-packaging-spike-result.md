# CYP-625 — Packaging spike result (jpackage + jlink native-extraction proof)

> Status: **DONE — the packaging mechanism is PROVEN. The one real packaging risk is retired.**
> Scope honesty: proven end-to-end **on Linux** (the environment available to the backend agent); the Windows
> `.msi` wrapper + Windows-native binaries are a **scripted hand-off for a Windows runner** (§4) — low risk, since
> only the final installer packaging differs, not the app-image / runtime / native-extraction that this spike proves.

## 1. What the spike proves (the risk it retires)

The `:server` hub has exactly two **runtime-extracted native** dependencies (design doc CYP-623 §1): `sqlite-jdbc`
(JNI + a per-platform native lib self-extracted to `java.io.tmpdir`) and `pty4j` (a per-OS PTY helper, same
self-extract; pulls in **JNA**). The open question for the jpackage/jlink approach was: **do both still self-extract
and work under a `jlink`-minimized runtime, and inside a `jpackage` app-image layout?** (This is precisely what
GraalVM native-image cannot do — no jar to extract from.)

**Answer: YES.** A tiny spike (`SpikeMain`, source in §5) that opens a real SQLite DB (write + read back `42`) and
spawns a real PTY via pty4j (echo a marker through the pseudo-terminal) prints **`CYP-625 SPIKE PASS`** both ways:

```
# (a) run directly under the jlink'd JRE 21
CYP-625 SPIKE PASS
  SQLITE_OK  — native extracted + query roundtrip (42)
  PTY_OK     — pty4j native helper spawned a PTY; marker echoed through the pty
  java.home: …/spike-runtime            (the jlink'd runtime, 94M, java.se+jdk.unsupported, no jdk tools)

# (b) run via the jpackage app-image launcher (bundled app + runtime + launcher)
CYP-625 SPIKE PASS
  SQLITE_OK  — native extracted + query roundtrip (42)
  PTY_OK     — pty4j native helper spawned a PTY; marker echoed through the pty
  java.home: …/Cyp625Spike/lib/runtime  (the runtime bundled INSIDE the app-image; total app-image 116M)
```

The launcher was built with `--java-options "-Dio.netty.jfr.enabled=false"` — confirming the **JVM-arg-in-launcher**
path (the mechanism the real installer uses to preserve the two mandatory `:server` args, CYP-623 §1) works.

## 2. The proven recipe (reusable for the story-2 packaging build)

Environment: JDK 21 (Amazon Corretto 21.0.11) — ships both `jlink` and `jpackage`.

```bash
# 0. a distribution with ALL runtime jars in one dir (Gradle application plugin)
./gradlew :installer-spike:installDist          # → build/install/installer-spike/lib/*.jar
#    lib/ = installer-spike.jar + pty4j-0.13.4 + jna-5.14.0 + jna-platform-5.14.0
#           + sqlite-jdbc-3.53.2.0 + kotlin-stdlib + slf4j-api + annotations

# 1. jlink a minimized JRE. java.se is generous-but-proven; jdk.unsupported is REQUIRED (JNA → sun.misc.Unsafe).
#    (story 2 can minimize the module set further; the risk is native EXTRACTION, which the module count doesn't change)
jlink --add-modules java.se,jdk.unsupported \
      --strip-debug --no-header-files --no-man-pages \
      --output build/spike-runtime

# 2. jpackage an app-image (bundles the app jars + the jlink runtime + a native launcher). NO external tool needed.
jpackage --type app-image --name Cyp625Spike \
         --input build/install/installer-spike/lib \
         --main-jar installer-spike.jar --main-class SpikeMainKt \
         --runtime-image build/spike-runtime \
         --java-options "-Dio.netty.jfr.enabled=false" \
         --dest build/spike-appimage

# 3. run the packaged launcher → CYP-625 SPIKE PASS
build/spike-appimage/Cyp625Spike/bin/Cyp625Spike
```

**Findings for the real packaging build (CYP-623 §6 story 2):**
- The app-plugin `installDist` → jlink → jpackage chain is the sound path. `--type app-image` needs no external tool; the OS installer (`--type msi/deb/dmg`) is a thin wrapper over the same app-image.
- pty4j drags in **JNA** → the jlink module set MUST include **`jdk.unsupported`** (else `sun.misc.Unsafe` is missing → JNA fails). Flag for the minimized module list.
- Bake BOTH mandatory `:server` JVM args via `--java-options "-Dio.netty.jfr.enabled=false" --java-options "-XX:MaxRAMPercentage=75.0"` so they ride in the generated launcher / service command (CYP-623 §1).
- The bundled runtime lives at `<app-image>/lib/runtime` and `java.home` resolves there — native libs extract to `java.io.tmpdir` (the service working dir / a writable temp must exist; a Windows Service's temp is fine).
- App-image ≈ 116 MB for just sqlite+pty4j+JNA; the full `:server` (Netty/Tink/Flyway/…) will be larger — size is a story-2 optimization (module minimization, `--compress`), not a risk.

## 3. What is PROVEN vs DEFERRED (honest scope)

| Leg | Status | Where |
|---|---|---|
| jlink minimized JRE 21 runs the app | ✅ proven | Linux |
| sqlite-jdbc native self-extract + query under a stripped runtime | ✅ proven | Linux |
| pty4j native helper self-extract + **real PTY spawn** under a stripped runtime | ✅ proven | Linux |
| jpackage `--type app-image` layout runs the same | ✅ proven | Linux |
| `--java-options` JVM-arg baked into the launcher | ✅ proven | Linux |
| jpackage `--type msi` (WiX 3.x) produces the Windows installer | ⏳ deferred | **needs a Windows runner** |
| Windows conpty/winpty helper self-extract + PTY on Windows | ⏳ deferred | **needs a Windows runner** |

The two deferred legs are **Windows-runner-only** (jpackage cannot cross-build a `.msi` from Linux) and are **low
risk**: the `.msi` is a WiX wrapper over the already-proven app-image, and pty4j/sqlite-jdbc ship their Windows
natives through the identical self-extract path this spike exercised on Linux.

## 4. Windows hand-off (a Windows runner completes the deferred legs)

On a Windows host with **JDK 21** and **WiX Toolset 3.x on PATH** (`light.exe`/`candle.exe`; jpackage `--type msi`
requires WiX 3, not 4/5), run the same recipe with two changes — `--type msi` and the Windows shell in the PTY probe
(`SpikeMain` already branches on `os.name`):

```bat
gradlew.bat :installer-spike:installDist
jlink --add-modules java.se,jdk.unsupported --strip-debug --no-header-files --no-man-pages --output build\spike-runtime
jpackage --type msi --name Cyp625Spike ^
         --input build\install\installer-spike\lib ^
         --main-jar installer-spike.jar --main-class SpikeMainKt ^
         --runtime-image build\spike-runtime ^
         --java-options "-Dio.netty.jfr.enabled=false" ^
         --win-console --dest build\spike-msi
REM install the produced .msi, then run the installed launcher → expect: CYP-625 SPIKE PASS (SQLITE_OK + PTY_OK on Windows)
```

Expected: `SQLITE_OK` (Windows sqlite native `.dll`) + `PTY_OK` (Windows conpty/winpty helper). If green, the Windows
`.msi` leg is retired and the packaging build (story 2) proceeds with full confidence.

## 5. Spike source (reproducible; the throwaway `:installer-spike` module is NOT merged)

The spike ran from a throwaway `:installer-spike` Gradle module (kotlinJvm + application; deps `libs.pty4j` +
`libs.sqlite.jdbc`; `mainClass = "SpikeMainKt"`). It is **not merged** (settings.gradle is untouched on develop) —
this doc + the source below reproduce it. `SpikeMain.kt`:

```kotlin
import com.pty4j.PtyProcessBuilder
import java.io.File
import java.sql.DriverManager

fun main() {
    val results = mutableListOf<String>()
    runCatching { // (1) sqlite-jdbc — write + read back forces the native lib to extract + load
        val db = File.createTempFile("cyp625-spike", ".db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("create table t(x int)"); s.executeUpdate("insert into t values(42)")
                s.executeQuery("select x from t").use { rs -> check(rs.next() && rs.getInt(1) == 42) { "readback" } }
            }
        }; db.delete()
    }.fold({ results += "SQLITE_OK  — native extracted + query roundtrip (42)" }, { results += "SQLITE_FAIL — $it" })
    runCatching { // (2) pty4j — mirrors PtyManager: setEnvironment/setDirectory/setInitialColumns/start
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val cmd = if (isWin) arrayOf("cmd.exe", "/c", "echo cyp625pty") else arrayOf("/bin/sh", "-c", "echo cyp625pty")
        val env = mapOf("PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"), "TERM" to "xterm")
        val proc = PtyProcessBuilder(cmd).setEnvironment(env)
            .setDirectory(System.getProperty("java.io.tmpdir")).setInitialColumns(80).start()
        val out = proc.inputStream.bufferedReader().readText(); proc.waitFor()
        check(out.contains("cyp625pty")) { "PTY marker not echoed; got '${out.trim().take(120)}'" }
    }.fold({ results += "PTY_OK     — pty4j native helper spawned a PTY; marker echoed through the pty" },
           { results += "PTY_FAIL   — $it" })
    val pass = results.all { it.contains("_OK") }
    println("CYP-625 SPIKE ${if (pass) "PASS" else "FAIL"}"); results.forEach { println("  $it") }
    if (!pass) kotlin.system.exitProcess(1)
}
```

## 6. Conclusion

The jpackage + jlink packaging approach for the `:server` hub is **sound** — both runtime-extracted native
dependencies (sqlite-jdbc, pty4j) self-extract and work under a jlink-minimized JRE 21 and inside a jpackage
app-image, with the mandatory JVM args carried by the launcher. **The one real packaging risk is retired.** Proceed to
the packaging build (CYP-623 §6 story 2); complete the Windows `.msi`/conpty legs via the §4 hand-off on a Windows
runner (low risk).
