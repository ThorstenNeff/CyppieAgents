package com.tneff.cyppieagents.logging

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * CYP-188 / BG-WS-5 — redacts bearer credentials from log MESSAGES. The WS routes accept the token as a
 * `?token=` query param (a browser WebSocket can't set an `Authorization` header), so at the app's `trace`
 * log level Ktor logs the handshake URI **including the token** into `hub.out.log`. The edge (Caddy/proxy)
 * is scrubbed, but a token in the hub's own server log is a defense-hygiene leak — now materially relevant
 * with the public `/ws/` WebSocket surface. This masks the value wherever it appears, independent of log level.
 *
 * Pure + hermetically testable ([TokenRedactorTest]); [TokenRedactingMessageConverter] wires it into logback.
 */
object TokenRedactor {
    // `?token=…` / `&token=…` (also access_token) up to the next `&` or whitespace — keep the key, drop the value.
    private val QUERY_TOKEN = Regex("""([?&](?:token|access_token)=)[^&\s]*""")
    // `Bearer <token>` up to the next whitespace.
    private val BEARER = Regex("""(Bearer )\S+""")

    fun redact(message: String): String =
        message.replace(QUERY_TOKEN, "$1[REDACTED]").replace(BEARER, "$1[REDACTED]")
}

/** Logback converter (registered in `logback.xml` as `%redactedMsg`) — redacts the token from every message. */
class TokenRedactingMessageConverter : MessageConverter() {
    override fun convert(event: ILoggingEvent): String = TokenRedactor.redact(super.convert(event))
}
