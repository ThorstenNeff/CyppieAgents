package com.tneff.cyppieagents.net

/**
 * CYP-115 catch-hardening: surface a **real** WS failure instead of swallowing it as a silent "Disconnected".
 * The churn bug hid here — a cross-context `emit` [IllegalStateException] ("Flow invariant is violated", thrown
 * on Darwin because the `client.webSocket{}` body runs on the engine dispatcher) was caught and dropped, so a
 * structural bug looked like a graceful reconnect. A **normal** socket close does NOT throw (the `incoming`
 * loop just ends), so this only ever fires on genuine failures — no per-disconnect noise. Minimal `println`
 * until a shared multiplatform logger lands; centralized so it swaps in one place.
 */
fun logWsError(source: String, error: Throwable) {
    println("[ws:$source] error: ${error::class.simpleName}: ${error.message}")
}
