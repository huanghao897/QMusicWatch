package com.ronan.qmusicwatch

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.ronan.qmusicwatch.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real layout/scroll semantics, without booting an emulator or accessing an account. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [24, 35], qualifiers = "w240dp-h240dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLayoutRobolectricTest {
    @get:Rule val compose = createComposeRule()

    private fun assertInsideCircle(bounds: Rect, screen: Rect, label: String) {
        val radiusSquared = minOf(screen.width, screen.height).let { it * it / 4f }
        listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight).forEach { corner ->
            assertTrue("$label clipped by round display: $bounds within $screen",
                (corner - screen.center).getDistanceSquared() <= radiusSquared + 1f)
        }
    }

    private fun checkMenu(round: Boolean, size: String = "compact", footer: Boolean = false) {
        var selected = -1
        var closed = false
        var submitted = false
        compose.setContent {
            QMusicWatchTheme(size, true) {
                WatchSelectionDialog(
                    title = "歌曲与播放列表操作",
                    onDismissRequest = { closed = true },
                    footer = if (footer) ({
                        WatchPrimaryButton("添加所选歌曲", Modifier.fillMaxWidth()) { submitted = true }
                    }) else null,
                ) {
                    items(10) { index ->
                        WatchActionRow(Icons.Default.MoreVert, "操作 $index") { selected = index }
                    }
                }
            }
        }
        val screen = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        if (round) assertInsideCircle(back.fetchSemanticsNode().boundsInRoot, screen, "返回")
        // Last items are not initially composed; use the actual lazy-list scroll action.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("操作 9"))
        val last = compose.onNodeWithText("操作 9").assertIsDisplayed()
        assertTrue("Menu row has collapsed height", last.fetchSemanticsNode().boundsInRoot.height >= 40f)
        if (round) assertInsideCircle(last.fetchSemanticsNode().boundsInRoot, screen, "最后一项")
        last.performClick()
        assertEquals(9, selected)
        if (footer) {
            // The footer scrolls with the list only when a fixed footer would squeeze it.
            if (size == "large") {
                compose.onNode(hasScrollAction()).performScrollToNode(hasText("添加所选歌曲"))
            }
            val action = compose.onNodeWithText("添加所选歌曲").assertIsDisplayed()
            if (round) assertInsideCircle(action.fetchSemanticsNode().boundsInRoot, screen, "添加按钮")
            action.performClick()
            assertTrue(submitted)
        }
        back.performClick()
        assertTrue(closed)
    }

    @Test fun squareMenusKeepTheLastActionReachable() = checkMenu(round = false)

    @Test
    @Config(qualifiers = "w240dp-h240dp-round-xhdpi")
    fun roundMenusKeepBackAndLastActionInsideTheCircle() = checkMenu(round = true)

    @Test
    @Config(qualifiers = "w192dp-h192dp-round-xhdpi")
    fun smallestRoundWatchCanSelectSongsAndReachTheFooterInLargeMode() =
        checkMenu(round = true, size = "large", footer = true)

    @Test fun squareWatchSelectionKeepsTheFixedFooterClickable() = checkMenu(round = false, footer = true)

    private fun checkMiniPlayer(round: Boolean) {
        val size = mutableStateOf("compact")
        var opened = 0
        var paused = 0
        var density = 1f
        compose.setContent {
            QMusicWatchTheme(size.value, true) {
                density = LocalDensity.current.density
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    WatchMiniPlayerContent("歌曲标题", "歌词预览", null, true, { paused++ }, { opened++ })
                }
            }
        }
        listOf("compact", "standard", "large").forEach { value ->
            compose.runOnIdle { size.value = value }
            val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val play = compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
            val artwork = compose.onNodeWithContentDescription("当前歌曲封面", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(play.fetchSemanticsNode().boundsInRoot.width >= 39f * density)
            if (round) {
                assertInsideCircle(play.fetchSemanticsNode().boundsInRoot, screen, "$value 播放按钮")
                assertInsideCircle(artwork.fetchSemanticsNode().boundsInRoot, screen, "$value 封面")
            }
            play.performClick()
        }
        assertEquals(3, paused)
        assertEquals("Playback tap must not open the player", 0, opened)
        compose.onNodeWithText("歌曲标题").performClick()
        assertEquals(1, opened)
    }

    @Test fun squareMiniPlayerPreservesSeparatePlaybackAndOpenActions() = checkMiniPlayer(round = false)

    @Test
    @Config(qualifiers = "w240dp-h240dp-round-xhdpi")
    fun roundMiniPlayerKeepsArtworkAndFullPlaybackTargetVisibleAtAllUiSizes() = checkMiniPlayer(round = true)

    @Test
    @Config(qualifiers = "w192dp-h192dp-round-xhdpi")
    fun smallerRoundMiniPlayerKeepsItsControlsVisible() = checkMiniPlayer(round = true)

    @Test
    @Config(qualifiers = "w320dp-h320dp-round-xhdpi")
    fun largerRoundMiniPlayerKeepsItsControlsVisible() = checkMiniPlayer(round = true)

    @Test
    @Config(qualifiers = "w240dp-h240dp-round-xhdpi")
    fun dailyHeaderReservesEnoughRealWidthForFourChineseGlyphs() {
        val size = mutableStateOf("compact")
        var requiredWidth = 0f
        compose.setContent {
            QMusicWatchTheme(size.value, true) {
                val dimensions = LocalWatchDimensions.current
                requiredWidth = dimensions.titleSp * LocalDensity.current.density * LocalDensity.current.fontScale * 4
                Box(Modifier.fillMaxWidth().padding(horizontal = dimensions.screenPadding)) {
                    WatchSectionHeader("每日推荐", action = "换一换")
                }
            }
        }
        listOf("compact", "standard", "large").forEach { value ->
            compose.runOnIdle { size.value = value }
            // Legacy Robolectric cannot measure CJK glyphs faithfully. Check allocated
            // width against four ems instead of accepting its fake narrow glyph widths.
            val bounds = compose.onNodeWithText("每日推荐").fetchSemanticsNode().boundsInRoot
            assertTrue("$value leaves too little title width: $bounds", bounds.width >= requiredWidth)
        }
    }

    @Test
    @Config(qualifiers = "w240dp-h240dp-round-xhdpi")
    fun longConfirmationBodyScrollsWithoutHidingItsButtons() {
        var confirmed = false
        compose.setContent {
            QMusicWatchTheme("compact", true) {
                WatchDialog(
                    onDismissRequest = {},
                    title = { Text("更新说明") },
                    text = { Column { repeat(30) { Text("更新内容 $it") } } },
                    confirmButton = { TextButton({ confirmed = true }) { Text("知道了") } },
                )
            }
        }
        compose.onNodeWithText("知道了").assertIsDisplayed()
        compose.onNodeWithText("更新内容 29").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        assertTrue(confirmed)
    }
}
