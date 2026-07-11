package com.tneff.cyppieagents.connect

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * CYP-419 (S-L) — a scriptable [LocalConnectFeed] stub. Default = the honest happy path
 * `attempting → handshake → connected`; [failing] scripts `attempting → Failed(cause)` for a typed error.
 * Emits its [script] verbatim so a test can pin the exact sequence (and that `connected` never precedes LIVE).
 */
class StubLocalConnectFeed(
    private val script: List<ConnectProgress> = listOf(
        ConnectProgress.Attempting,
        ConnectProgress.Handshake,
        ConnectProgress.Connected,
    ),
) : LocalConnectFeed {

    override fun connect(hub: HubDescriptor): Flow<ConnectProgress> = flow {
        script.forEach { emit(it) }
    }

    companion object {
        /** A feed that attempts, then fails with the given typed [cause] (never reaching `connected`). */
        fun failing(cause: ConnectCause) = StubLocalConnectFeed(
            listOf(ConnectProgress.Attempting, ConnectProgress.Failed(cause)),
        )
    }
}
