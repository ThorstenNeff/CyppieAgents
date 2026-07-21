package com.tneff.cyppieagents.tier

/**
 * CYP-772a — a store declares its own canonical residency key, WITH itself. This is the independent source of
 * truth that breaks the CYP-770 inventory tautology: the old guard compared [StoreResidencies.inventory] against
 * a hand-copied literal of the SAME keys (classification vs. a copy of itself), so it could never DISCOVER a real
 * store nobody classified. Here the key lives on the store type; the discovery tooth
 * ([StoreKeyRegistry.scan]) reads the annotations off the compiled classes — a source that is NOT the
 * classification — and asserts every declared key is classified, so a store whose key drifted out of the
 * inventory (or was never added) reddens.
 *
 * Annotation (not an interface `val`) deliberately: it is readable by reflection WITHOUT instantiating the store
 * (constructor args vary per impl), and one declaration on the logical-store INTERFACE is inherited-in-spirit by
 * every File/Sqlite/Pg backend (they share one residency key).
 *
 * Not every `*Store`/`*Sink` type is a residency store (e.g. an in-memory audit fan-out). Only annotate types
 * whose data actually has a residence decision; the completeness half (every residency store IS annotated) is
 * guarded separately against a documented, shrinking pending-list (CYP-772b classifies the rest).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class StoreKey(val value: String)
