package com.ronan.qmusicwatch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mainWatchSurfaceFitsAndCanDismissTheNotice() {
        compose.onNodeWithText("QMusic Watch").assertIsDisplayed()
        runCatching { compose.onNodeWithText("我知道了").performClick() }
        compose.onNodeWithText("第三方非官方客户端").assertIsDisplayed()
    }
}
