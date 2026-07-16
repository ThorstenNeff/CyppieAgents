package com.tneff.cyppieagents.firstrun

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_clone_failed
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_clone_failed_auth
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_clone_failed_url
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test

/**
 * CYP-629 (Inc3, 3a) — the four repo clone states are each reachable and DISTINGUISHABLE, `CLONE_FAILED` carries
 * the reason-specific actionable copy, and it is fail-closed (never "ok" while unsure). The pure state collapse is
 * covered by [Cyp629FirstRunGateModelTest.cloneDisplay_mapsStatesFailClosed]; this pins the render.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp629CloneStatusDisplayTest {

    private fun status(clone: CloneStatus, reason: CloneFailReason? = null) =
        FirstRunConfigStatus(loaded = true, apiKeySet = true, cloneStatus = clone, cloneReason = reason)

    @Test
    fun cloning_showsCloningState_notOkNotFailed() = runComposeUiTest {
        setContent { MaterialTheme { CloneStatusDisplay(status(CloneStatus.CLONING)) } }
        onNodeWithTag(FirstRunTags.REPO_CLONING).assertExists()
        onNodeWithTag(FirstRunTags.REPO_CLONE_OK).assertDoesNotExist()
        onNodeWithTag(FirstRunTags.REPO_CLONE_FAILED).assertDoesNotExist()
    }

    @Test
    fun neverCloned_failClosedToCloning_neverOk() = runComposeUiTest {
        setContent { MaterialTheme { CloneStatusDisplay(status(CloneStatus.CONFIGURED_NEVER_CLONED)) } }
        onNodeWithTag(FirstRunTags.REPO_CLONING).assertExists()
        onNodeWithTag(FirstRunTags.REPO_CLONE_OK).assertDoesNotExist()
    }

    @Test
    fun clonedOk_showsOk() = runComposeUiTest {
        setContent { MaterialTheme { CloneStatusDisplay(status(CloneStatus.CLONED_OK)) } }
        onNodeWithTag(FirstRunTags.REPO_CLONE_OK).assertExists()
        onNodeWithTag(FirstRunTags.REPO_CLONING).assertDoesNotExist()
        onNodeWithTag(FirstRunTags.REPO_CLONE_FAILED).assertDoesNotExist()
    }

    @Test
    fun failedUrl_showsFailed_withUrlReasonCopy() = runComposeUiTest {
        lateinit var urlCopy: String
        setContent {
            MaterialTheme {
                urlCopy = stringResource(Res.string.first_run_repo_clone_failed_url)
                CloneStatusDisplay(status(CloneStatus.CLONE_FAILED, CloneFailReason.URL_UNREACHABLE))
            }
        }
        onNodeWithTag(FirstRunTags.REPO_CLONE_FAILED).assertExists()
        // The reason selects the actionable copy — pinned via the KEY's resolved value (i18n-robust).
        onNodeWithText(urlCopy).assertExists()
    }

    @Test
    fun failedAuth_showsAuthReasonCopy() = runComposeUiTest {
        lateinit var authCopy: String
        setContent {
            MaterialTheme {
                authCopy = stringResource(Res.string.first_run_repo_clone_failed_auth)
                CloneStatusDisplay(status(CloneStatus.CLONE_FAILED, CloneFailReason.AUTH))
            }
        }
        onNodeWithText(authCopy).assertExists()
    }

    @Test
    fun failedUnknown_showsGenericCopy() = runComposeUiTest {
        lateinit var genericCopy: String
        setContent {
            MaterialTheme {
                genericCopy = stringResource(Res.string.first_run_repo_clone_failed)
                CloneStatusDisplay(status(CloneStatus.CLONE_FAILED, CloneFailReason.UNKNOWN))
            }
        }
        onNodeWithText(genericCopy).assertExists()
    }

    @Test
    fun notConfigured_showsNoCloneState() = runComposeUiTest {
        setContent { MaterialTheme { CloneStatusDisplay(status(CloneStatus.NOT_CONFIGURED)) } }
        onNodeWithTag(FirstRunTags.REPO_CLONING).assertDoesNotExist()
        onNodeWithTag(FirstRunTags.REPO_CLONE_OK).assertDoesNotExist()
        onNodeWithTag(FirstRunTags.REPO_CLONE_FAILED).assertDoesNotExist()
    }
}
