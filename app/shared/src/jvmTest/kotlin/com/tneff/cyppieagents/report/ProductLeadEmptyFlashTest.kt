package com.tneff.cyppieagents.report

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.ReportMeta
import com.tneff.cyppieagents.model.ReportSnapshot
import com.tneff.cyppieagents.model.ReportType
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test

/**
 * CYP-279 (CYP-270/276 class) — the Product-Lead snapshot list must NOT flash "no reports" during the async
 * fetch window. The metas ARE async-loaded ([ProductLeadViewModel.reloadList] in init, `loading = true`) — so a
 * project that DOES have snapshots would otherwise flash empty on cold open / switch before the fetch lands.
 * [GatedReportRepo.list] suspends so the VM stays `loading = true`.
 *
 * Mutation proof: drop the `!state.loading &&` guard at ProductLeadPanel.SnapshotList → `report_empty` renders
 * WHILE loading → the first assertion of [snapshotList_neverFlashesEmptyDuringLoad_thenShowsWhenSettledEmpty] REDs.
 */
@OptIn(ExperimentalTestApi::class)
class ProductLeadEmptyFlashTest {

    private class GatedReportRepo(val gate: CompletableDeferred<Unit>, val metas: List<ReportMeta>) : ReportRepository {
        override suspend fun list(): List<ReportMeta> { gate.await(); return metas }
        override suspend fun get(id: String): ReportSnapshot = throw NotImplementedError()
        override suspend fun generate(type: ReportType): ReportSnapshot = throw NotImplementedError()
    }

    @Test
    fun snapshotList_neverFlashesEmptyDuringLoad_thenShowsWhenSettledEmpty() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setContent {
            MaterialTheme {
                Box(Modifier.width(900.dp).height(700.dp)) {
                    ProductLeadPanel(remember { ProductLeadViewModel(GatedReportRepo(gate, emptyList()), accessible = true) })
                }
            }
        }
        waitForIdle()
        onNodeWithTag(ProductLeadTags.EMPTY).assertDoesNotExist() // async fetch in flight → no "Keine Berichte" flash
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProductLeadTags.EMPTY).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(ProductLeadTags.EMPTY).assertExists() // settled-empty → shows
    }

    @Test
    fun snapshotList_neverShowsEmpty_whenLoadYieldsReports() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setContent {
            MaterialTheme {
                Box(Modifier.width(900.dp).height(700.dp)) {
                    ProductLeadPanel(
                        remember {
                            ProductLeadViewModel(
                                GatedReportRepo(gate, listOf(ReportMeta("r1", ReportType.DEFECTS, 1L, "sum"))),
                                accessible = true,
                            )
                        },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(ProductLeadTags.EMPTY).assertDoesNotExist() // during load
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProductLeadTags.snapshot("r1")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(ProductLeadTags.EMPTY).assertDoesNotExist() // settled with data → never
    }
}
