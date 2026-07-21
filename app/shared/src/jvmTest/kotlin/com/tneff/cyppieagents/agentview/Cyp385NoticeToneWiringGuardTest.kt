package com.tneff.cyppieagents.agentview

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-385 — the committed-token wiring guard for the notice error tone (the CYP-760 pattern, Compose equivalent).
 *
 * A Compose `Text(color = …)` colour is NOT in the semantics tree, so a pure `runComposeUiTest` render is blind to
 * it — it cannot assert "this notice is red". The tone therefore rides a token a render test can't see, exactly the
 * case the CYP-760 committed-token check exists for. So this scans the committed source of `NoticeRow` and pins the
 * two mappings directly:
 *  - `NoticeToneRole.ERROR   -> MaterialTheme.colorScheme.error`            (the failure tone)
 *  - `NoticeToneRole.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant` (the neutral tone stays neutral)
 *
 * Mutation (the PO's acceptance criterion): recolour the ERROR branch back to `onSurfaceVariant` ⇒ the error-wiring
 * assertion goes RED — "Verbindung verloren" would read as "alles ok" again. The pure tone-role distinction is
 * pinned separately by [Cyp385NoticeErrorToneTest]; together they cover flag → role → committed token.
 *
 * Same mechanism as [com.tneff.cyppieagents.ui.OutlineTextColorGuardTest] (source scan + walk-up locate), no second
 * machinery. Comment lines are stripped so a commented-out mapping cannot false-green.
 */
class Cyp385NoticeToneWiringGuardTest {

    private val errorWiring =
        Regex("""NoticeToneRole\.ERROR\s*->\s*MaterialTheme\.colorScheme\.error\b""")
    private val neutralWiring =
        Regex("""NoticeToneRole\.NEUTRAL\s*->\s*MaterialTheme\.colorScheme\.onSurfaceVariant\b""")

    @Test
    fun noticeRow_mapsErrorRoleToTheErrorToken_andKeepsNeutralOnSurfaceVariant() {
        val src = codeOf("agentview/AgentWindow.kt")

        assertTrue(
            errorWiring.containsMatchIn(src),
            "NoticeRow must map NoticeToneRole.ERROR to MaterialTheme.colorScheme.error (CYP-385). If this is red, " +
                "the error notice was recoloured back to a neutral token — 'Verbindung zum Agenten verloren' would " +
                "read as 'alles ok' again (the CYP-760/CYP-643 honesty class).",
        )
        assertTrue(
            neutralWiring.containsMatchIn(src),
            "NoticeRow must keep NoticeToneRole.NEUTRAL on onSurfaceVariant (CYP-385/CYP-337) — the neutral half of " +
                "the distinction; without it the two tones could collapse the other way.",
        )
    }

    /** Read a commonMain source file with `//`-comment and KDoc/block-comment lines stripped (a comment naming a
     *  mapping is not a use of it). */
    private fun codeOf(relPath: String): String =
        locateCommonMain().resolve(relPath).readLines().mapNotNull { raw ->
            val code = raw.substringBefore("//").trim()
            if (code.isEmpty() || code.startsWith("*") || code.startsWith("/*")) null else code
        }.joinToString("\n")

    /** Walk up to the module's commonMain kotlin root (mirrors OutlineTextColorGuardTest's locate). */
    private fun locateCommonMain(): File {
        val rel = "src/commonMain/kotlin/com/tneff/cyppieagents"
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            File(cur, rel).let { if (it.isDirectory) return it }
            File(cur, "app/shared/$rel").let { if (it.isDirectory) return it }
            cur = cur.parentFile
        }
        error("Could not locate $rel from ${File(".").absolutePath}")
    }
}
