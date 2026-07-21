package com.tneff.cyppieagents.tier

import java.io.File

/**
 * CYP-772a — the INDEPENDENT enumeration behind the discovery tooth. It walks a compiled-classes directory and
 * reads the [StoreKey] annotation off each class **by reflection, without instantiating** it (the annotation is
 * `RUNTIME`-retained). This is the source the residency-completeness check compares against — deliberately NOT
 * [StoreResidencies]'s own sets, so it can find a store whose declared key is missing from the inventory (the
 * CYP-770 tautology could not). No classpath-scanner dependency: the JVM compiles to a real directory tree, so a
 * plain file walk + `Class.forName` is enough, and it works identically for a test-classes dir (used to prove the
 * tooth is non-vacuous with a deliberately-unclassified dummy store).
 */
object StoreKeyRegistry {

    data class DeclaredStore(val className: String, val storeKey: String)

    /** Every `@StoreKey`-annotated class found under [classesRoots], with its declared key. */
    fun scan(classesRoots: List<File>): List<DeclaredStore> {
        val out = ArrayList<DeclaredStore>()
        for (root in classesRoots) {
            if (!root.isDirectory) continue
            root.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
                val binaryName = f.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
                val clazz = runCatching { Class.forName(binaryName, false, javaClass.classLoader) }.getOrNull() ?: return@forEach
                val ann = clazz.getAnnotation(StoreKey::class.java) ?: return@forEach
                out.add(DeclaredStore(binaryName, ann.value))
            }
        }
        return out
    }

    /** Declared stores whose key is NOT in [StoreResidencies.inventory] — the residency violations the tooth reds on. */
    fun unclassified(classesRoots: List<File>): List<DeclaredStore> =
        scan(classesRoots).filter { it.storeKey !in StoreResidencies.inventory }
}
