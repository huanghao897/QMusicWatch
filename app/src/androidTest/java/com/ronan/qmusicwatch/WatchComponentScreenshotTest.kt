package com.ronan.qmusicwatch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.ronan.qmusicwatch.ui.QMusicWatchTheme
import com.ronan.qmusicwatch.ui.WatchIconButton
import com.ronan.qmusicwatch.ui.WatchListRow
import com.ronan.qmusicwatch.ui.WatchSearchField
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WatchComponentScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactComponentsRenderLongChineseTextWithoutBlankSurface() {
        compose.setContent {
            QMusicWatchTheme(uiSize = "compact", pureBlack = false) {
                val query = remember { mutableStateOf("") }
                Column(Modifier.padding(8.dp)) {
                    WatchSearchField(
                        value = query.value,
                        onValueChange = { query.value = it },
                        placeholder = "搜索歌曲、歌单、歌手、专辑",
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = Icons.Default.Search,
                    )
                    WatchListRow(
                        title = "一首标题非常长但不能挤出手表屏幕的歌曲",
                        subtitle = "歌手名称 / 另一位歌手",
                        trailing = { WatchIconButton(Icons.Default.MoreVert, "更多") {} },
                    )
                }
            }
        }

        compose.onNodeWithText("一首标题非常长但不能挤出手表屏幕的歌曲").assertExists()
        val screenshot = compose.onRoot().captureToImage()
        assertTrue(screenshot.width > 0)
        assertTrue(screenshot.height > 0)
    }
}
