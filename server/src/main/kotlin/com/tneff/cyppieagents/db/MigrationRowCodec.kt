package com.tneff.cyppieagents.db

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * CYP-220 Phase 4 — the store-agnostic row encoding used by [MigrationSource.exportRows] / [MigrationTarget.importRows].
 * A row is a list of string fields encoded **length-prefixed** (field count, then per field a 4-byte big-endian
 * length + its UTF-8 bytes) — **injective**, so a store's rows are a faithful, order-preserving, delimiter-safe
 * representation that the generic [StoreMigrator] can count + checksum without knowing the store's shape.
 */
object MigrationRowCodec {

    fun encode(fields: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt(out, fields.size)
        for (f in fields) {
            val b = f.encodeToByteArray()
            writeInt(out, b.size)
            out.write(b)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): List<String> {
        val ins = ByteArrayInputStream(bytes)
        val n = readInt(ins)
        return (0 until n).map {
            val len = readInt(ins)
            val b = ByteArray(len)
            var read = 0
            while (read < len) {
                val r = ins.read(b, read, len - read)
                require(r >= 0) { "truncated migration row" }
                read += r
            }
            b.decodeToString()
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) =
        out.write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))

    private fun readInt(ins: ByteArrayInputStream): Int {
        val b = ByteArray(4)
        require(ins.read(b) == 4) { "truncated migration row header" }
        return (b[0].toInt() and 0xFF shl 24) or (b[1].toInt() and 0xFF shl 16) or (b[2].toInt() and 0xFF shl 8) or (b[3].toInt() and 0xFF)
    }
}
