package com.tneff.cyppieagents.net

/**
 * Redact query-param secrets from a string BEFORE it reaches a log sink. The WS clients dial URLs that carry the
 * operator/agent credential as a `?token=…` query param (browsers can't set the `Authorization` header on a WS
 * upgrade, CYP-230), so a connect/handshake exception whose `.message` embeds the request URL would otherwise
 * leak the token to stdout. Pure + unit-tested; the ONE place log messages are sanitized.
 */
internal fun redactUrlSecrets(message: String?): String {
    if (message == null) return ""
    // `?token=…` / `&token=…` (also access_token), case-insensitive; value runs until the next `&`, whitespace, or quote.
    return message.replace(Regex("(?i)([?&](?:token|access_token)=)[^&\\s\"']*"), "$1***")
}

/**
 * CYP-115 catch-hardening: surface a **real** WS failure instead of swallowing it as a silent "Disconnected".
 * The churn bug hid here — a cross-context `emit` [IllegalStateException] ("Flow invariant is violated", thrown
 * on Darwin because the `client.webSocket{}` body runs on the engine dispatcher) was caught and dropped, so a
 * structural bug looked like a graceful reconnect. A **normal** socket close does NOT throw (the `incoming`
 * loop just ends), so this only ever fires on genuine failures — no per-disconnect noise. Minimal `println`
 * until a shared multiplatform logger lands; centralized so it swaps in one place.
 *
 * **Security:** the message is [redactUrlSecrets]-sanitized — a WS-URL-bearing exception message must never leak
 * the `?token=…` operator/agent credential to stdout (the instrumented dogfood harness parses these logs).
 */
fun logWsError(source: String, error: Throwable) {
    println("[ws:$source] error: ${error::class.simpleName}: ${redactUrlSecrets(error.message)}")
}

/**
 * Tunnel-warmth incident instrumentation: log a WS/tunnel **teardown** with its cause so an instrumented dogfood
 * re-test can pin WHAT tears the remote WS tunnels synchronously (a transport close, a per-agent WS end, a pool
 * teardown). [source] = the layer (`transport`/`pool`/`agent-ws:<id>`), [cause] = why it ended. The cause is
 * [redactUrlSecrets]-sanitized (defense-in-depth — no token/secret to stdout). Minimal `println` until a shared
 * multiplatform logger lands; centralized so it swaps in one place (mirrors [logWsError]).
 */
fun logWsTeardown(source: String, cause: String) {
    println("[ws-teardown:$source] ${redactUrlSecrets(cause)}")
}

/**
 * 6-agent-remote incident instrumentation: log a tunnel-**pool** lifecycle event so an instrumented dogfood connect-run
 * can pin the 1-up/6-churn as pool exhaustion — and distinguish its driver: (a) the CP rendezvous set is smaller than the
 * concurrent WS demand vs (b) idle keep-alive REST connections holding rendezvous-ids (the loopback client's `∞`
 * keep-alive). [source] = the site (`resolve`/`reserve`/`acquire`/`close`/`transport`), [msg] = a **secret-safe** census:
 * counts + the CP-derived OPAQUE rendezvous-id only — never a token/handshake value (defence-in-depth via [redactUrlSecrets]).
 * Minimal `println` until a shared multiplatform logger lands; centralized so it swaps in one place (mirrors [logWsTeardown]).
 */
fun logWsPool(source: String, msg: String) {
    println("[ws-pool:$source] ${redactUrlSecrets(msg)}")
}
