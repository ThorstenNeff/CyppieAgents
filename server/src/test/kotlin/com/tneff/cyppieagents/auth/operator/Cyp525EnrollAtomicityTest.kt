package com.tneff.cyppieagents.auth.operator

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-525 Reviewer F2 — [OperatorDeviceEnrollment.enrollFirstDevice] must make the empty-check → save ATOMIC over the
 * shared store. Two simultaneous first-connects on an empty store (each a valid operator CpJwt, live-tunnel-reachable
 * via the CYP-525 TOFU enroll) must yield exactly ONE enrolled device, not a last-write-wins double-grant.
 */
class Cyp525EnrollAtomicityTest {

    /** A store whose save() sleeps, WIDENING the check-then-act window so a non-atomic impl deterministically races. */
    private class SlowSaveStore : OperatorDeviceStore {
        private val ref = AtomicReference<EnrolledOperatorDevice?>(null)
        override fun enrolled(): EnrolledOperatorDevice? = ref.get()
        override fun save(device: EnrolledOperatorDevice) { Thread.sleep(60); ref.set(device) }
    }

    private fun device(i: Int) = EnrolledOperatorDevice("dev-$i", DeviceKeyAlg.ED25519, ByteArray(32) { (it + i).toByte() })

    @Test
    fun concurrentFirstEnroll_exactlyOneWins_atomicCheckThenSave() {
        val store = SlowSaveStore()
        val enroll = OperatorDeviceEnrollment(store)
        val results = CopyOnWriteArrayList<EnrollResult>()
        val barrier = CyclicBarrier(2) // release both threads into enrollFirstDevice simultaneously
        val threads = (0..1).map { i ->
            Thread {
                barrier.await()
                results.add(enroll.enrollFirstDevice(device(i)))
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // With the atomic (synchronized-on-store) impl: one thread takes the store monitor, checks empty, saves (holds
        // through the slow save); the other blocks, then checks → sees the anchor → rejected. Exactly one wins.
        // Without it (MUT): both pass the empty check before either save() sets the ref → BOTH enroll = RED.
        assertEquals(1, results.count { it is EnrollResult.Enrolled }, "exactly ONE concurrent first-enroll wins (atomic check-then-save)")
        assertEquals(1, results.count { it is EnrollResult.Rejected }, "the loser is rejected (already_enrolled), not a second grant")
    }
}
