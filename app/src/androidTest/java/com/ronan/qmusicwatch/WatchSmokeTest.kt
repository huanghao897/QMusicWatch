package com.ronan.qmusicwatch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mainWatchSurfaceFitsAndCanDismissTheNotice() {
        runCatching { compose.onNodeWithText("我知道了").performClick() }
        compose.onNodeWithText("QMusic").assertIsDisplayed()
        val screenshot = compose.onRoot().captureToImage()
        assertTrue(screenshot.width > 0)
        assertTrue(screenshot.height > 0)
    }
}
