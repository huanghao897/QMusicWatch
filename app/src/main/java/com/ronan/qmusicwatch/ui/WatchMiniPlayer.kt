package com.ronan.qmusicwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
internal fun WatchMiniPlayerContent(
    title: String,
    preview: String,
    artwork: Any?,
    playing: Boolean,
    onPlayPause: () -> Unit,
    onOpen: () -> Unit,
) {
    val dimensions = LocalWatchDimensions.current
    val screen = LocalWatchScreenBounds.current
    val round = dimensions.isRound
    val height = dimensions.miniPlayerHeight
    val rowHeight = dimensions.touchTarget
    val roundWidth = roundWatchContentWidth(screen, screen.height - height, screen.height - height + rowHeight)
    BoxWithConstraints(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.BottomCenter) {
        Surface(
            modifier = if (round) Modifier.width(maxWidth * dimensions.miniPlayerWidthFraction).fillMaxHeight()
                else Modifier.fillMaxSize().padding(horizontal = 6.dp),
            shape = if (round) RoundMiniPlayerShape else RoundedCornerShape(50),
            color = WatchSurfaceRaised,
            tonalElevation = 0.dp,
        ) {
            Box(Modifier.fillMaxSize().clickable(onClick = onOpen)) {
                Row(
                    modifier = if (round) Modifier.align(Alignment.TopCenter).width(roundWidth).height(rowHeight)
                        else Modifier.align(Alignment.Center).fillMaxWidth().height(rowHeight).padding(start = 6.dp, end = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = "当前歌曲封面",
                        modifier = Modifier.size(if (round) rowHeight * .6f else dimensions.artworkSize)
                            .clip(CircleShape).background(WatchSurface),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(if (round) 4.dp else 7.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = if (round) 10.sp else dimensions.bodySp.sp, fontWeight = FontWeight.SemiBold)
                        Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = if (round) 8.sp else dimensions.secondarySp.sp, color = WatchTextSecondary)
                    }
                    WatchIconButton(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "暂停" else "播放",
                        buttonSize = rowHeight,
                        containerColor = Color.Transparent,
                        onClick = onPlayPause,
                    )
                }
            }
        }
    }
}
