package com.tneff.cyppieagents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.auth.StubAuthRepository
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.project.ProjectSwitcherBar
import com.tneff.cyppieagents.project.ProjectTags
import com.tneff.cyppieagents.project.ProjectViewModel
import com.tneff.cyppieagents.project.StubProjectRepository
import com.tneff.cyppieagents.ui.InMemoryThemePreferences
import com.tneff.cyppieagents.ui.MaritimeDark
import com.tneff.cyppieagents.ui.MaritimeLight
import com.tneff.cyppieagents.ui.ThemeMode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-322 — the Dark-mode-surface + active-project-indicator fix, proven at the pixel (the only faithful proof for
 * a *rendering* regression; asserting a token value can't catch "the code forgot to paint / forgot the colour").
 *
 * Root cause: nothing under the `App.kt` `MaterialTheme` painted a background, so (a) the platform window's WHITE
 * showed through in Dark mode, and (b) unstyled `Text` inherited M3's default `LocalContentColor = Black`. The fix is
 * one theme-bound root `Surface(color = colorScheme.background)` (heals both) plus explicit `onSurface` on the two
 * indicator `Text`s.
 *
 * Mutation proof:
 *  - drop the root `Surface` wrapper in App.kt → the backdrop is unpainted → the dark corner captures WHITE →
 *    [rootBackdrop_dark_paintsMaritimeNight_notWhite] REDs (and the light one stays green — a true dark-only guard).
 *  - drop the explicit `color = onSurface` on the indicator `Text`s → they inherit `LocalContentColor = Black` over
 *    the dark surface → no light text pixels → [activeIndicator_dark_rendersLightText_notInheritedBlack] REDs.
 */
@OptIn(ExperimentalTestApi::class)
class CYP322SurfaceThemeTest {

    /** Corner pixel of the app backdrop — well outside any centred login card, so it is the root Surface fill. */
    private fun backdropCorner(img: ImageBitmap): Color = img.toPixelMap()[2, 2]

    /** Channel-wise proximity (eps) — a flat opaque fill renders exact, but stay tolerant of Skia rounding. */
    private fun assertColorApprox(expected: Color, actual: Color, msg: String, eps: Float = 0.02f) {
        assertTrue(
            kotlin.math.abs(expected.red - actual.red) <= eps &&
                kotlin.math.abs(expected.green - actual.green) <= eps &&
                kotlin.math.abs(expected.blue - actual.blue) <= eps,
            "$msg — expected≈$expected, was $actual",
        )
    }

    @Test
    fun rootBackdrop_dark_paintsMaritimeNight_notWhite() = runComposeUiTest {
        setContent {
            App(
                authRepository = StubAuthRepository(), // boot = no session → the login gate renders ON the backdrop
                themePreferences = InMemoryThemePreferences(ThemeMode.DARK),
            )
        }
        waitForIdle()
        val corner = backdropCorner(onRoot().captureToImage())
        assertColorApprox(MaritimeDark.background, corner, "dark backdrop must be maritime Night #06121A")
        assertTrue(
            corner.red < 0.5f && corner.green < 0.5f && corner.blue < 0.5f,
            "dark backdrop must not be the white platform-window default — was $corner",
        )
    }

    @Test
    fun rootBackdrop_light_paintsWhite_noRegression() = runComposeUiTest {
        setContent {
            App(
                authRepository = StubAuthRepository(),
                themePreferences = InMemoryThemePreferences(ThemeMode.LIGHT),
            )
        }
        waitForIdle()
        assertColorApprox(
            MaritimeLight.background,
            backdropCorner(onRoot().captureToImage()),
            "light backdrop stays white (#FFFFFF) — no Light-mode regression",
        )
    }

    @Test
    fun activeIndicator_dark_rendersLightText_notInheritedBlack() = runComposeUiTest {
        // Bare MaterialTheme + a dark Box (NOT a Surface) → LocalContentColor stays M3's default Black, so ONLY the
        // Text's explicit onSurface can make the indicator legible. This isolates the indicator fix from the root
        // Surface fix (which would otherwise also drive the content colour).
        setContent {
            MaterialTheme(colorScheme = MaritimeDark) {
                Box(Modifier.background(MaritimeDark.background)) {
                    val vm = remember {
                        ProjectViewModel(
                            StubProjectRepository(listOf(Project("default", "Default")), "default"),
                            editable = true,
                        )
                    }
                    ProjectSwitcherBar(vm)
                }
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.ACTIVE).fetchSemanticsNodes().isNotEmpty()
        }
        val pm = onNodeWithTag(ProjectTags.ACTIVE, useUnmergedTree = true).captureToImage().toPixelMap()
        var lightPixels = 0
        for (y in 0 until pm.height) {
            for (x in 0 until pm.width) {
                val c = pm[x, y]
                // onSurface #DCE7ED → all channels ≳0.86; the dark Box bg (#06121A) and inherited-black text are ~0.
                if (c.red > 0.75f && c.green > 0.75f && c.blue > 0.75f) lightPixels++
            }
        }
        assertTrue(
            lightPixels > 0,
            "active-project indicator must render in light onSurface over the dark surface, not inherited Black",
        )
    }
}
