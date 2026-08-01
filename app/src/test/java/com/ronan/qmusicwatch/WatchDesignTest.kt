package com.ronan.qmusicwatch

import androidx.compose.ui.unit.dp
import com.ronan.qmusicwatch.ui.WatchUiSize
import com.ronan.qmusicwatch.ui.WatchWindowClass
import com.ronan.qmusicwatch.ui.resolveWatchDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDesignTest {
    @Test fun compact480PixelWatchUses240DpTokens() {
        val dimensions = resolveWatchDimensions(240.dp, WatchUiSize.Compact)

        assertEquals(WatchWindowClass.Compact, dimensions.windowClass)
        assertEquals(8.dp, dimensions.screenPadding)
        assertEquals(46.dp, dimensions.trackRowHeight)
        assertEquals(40.dp, dimensions.searchHeight)
        assertEquals(50.dp, dimensions.miniPlayerHeight)
        assertEquals(13f, dimensions.bodySp)
        assertEquals(23.dp, dimensions.rowCornerRadius)
        assertEquals(20.dp, dimensions.searchCornerRadius)
        assertEquals(36.dp, dimensions.playerActionSize)
    }

    @Test fun largerUiModesScaleWithoutChangingWindowClass() {
        val compact = resolveWatchDimensions(320.dp, WatchUiSize.Compact)
        val large = resolveWatchDimensions(320.dp, WatchUiSize.Large)

        assertEquals(WatchWindowClass.Medium, compact.windowClass)
        assertEquals(WatchWindowClass.Medium, large.windowClass)
        assertTrue(large.trackRowHeight > compact.trackRowHeight)
        assertTrue(large.bodySp > compact.bodySp)
    }

    @Test fun roundWatchUsesInsetAwareTokens() {
        val square = resolveWatchDimensions(240.dp, WatchUiSize.Compact, isRound = false)
        val round = resolveWatchDimensions(240.dp, WatchUiSize.Compact, isRound = true)

        assertTrue(round.isRound)
        assertTrue(round.screenPadding > square.screenPadding)
        assertTrue(round.topSafeInset > square.topSafeInset)
        assertEquals(square.trackRowHeight, round.trackRowHeight)
    }

    @Test fun storedUiSizeFallsBackToCompact() {
        assertEquals(WatchUiSize.Compact, WatchUiSize.fromStored(null))
        assertEquals(WatchUiSize.Compact, WatchUiSize.fromStored("phone"))
        assertEquals(WatchUiSize.Standard, WatchUiSize.fromStored("standard"))
        assertEquals(WatchUiSize.Large, WatchUiSize.fromStored("large"))
    }
}
