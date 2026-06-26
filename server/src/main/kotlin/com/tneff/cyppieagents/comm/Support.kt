package com.tneff.cyppieagents.comm

import java.util.UUID

/** Injectable clock so message timestamps are deterministic in tests. */
fun interface Clock {
    fun now(): Long
    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** Injectable id generator so message ids are deterministic in tests. */
fun interface IdGenerator {
    fun newId(): String
    companion object {
        val UUIDS = IdGenerator { UUID.randomUUID().toString() }
    }
}
