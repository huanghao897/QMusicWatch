package com.ronan.qmusicwatch

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ronan.qmusicwatch.data.DownloadEntity
import com.ronan.qmusicwatch.data.AppLog
import com.ronan.qmusicwatch.lyrics.LyricLine
import com.ronan.qmusicwatch.lyrics.activeLyricIndex
import com.ronan.qmusicwatch.lyrics.lyricRenderProgress
import com.ronan.qmusicwatch.model.*
import com.ronan.qmusicwatch.network.*
import com.ronan.qmusicwatch.performance.FramePerformanceMonitor
import com.ronan.qmusicwatch.update.UpdateInstaller
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.io.File

private val Green = Color(0xFF6DFF9E)
private val Surface = Color(0xFF111714)
internal enum class LibrarySection(val routeValue: String) {
    Liked("liked"),
    Created("created"),
    Collected("collected");

    companion object {
        fun fromRoute(value: String?): LibrarySection = entries.firstOrNull { it.routeValue == value } ?: Liked
    }
}

private fun libraryRoute(section: LibrarySection) = "library/${section.routeValue}"
@Composable private fun watchSearchColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF59625E), unfocusedBorderColor = Color.Transparent,
    focusedContainerColor = Surface, unfocusedContainerColor = Surface,
    cursorColor = Color.White,
)
private fun nextPlayMode(mode: String) = when (mode) { "sequential" -> "repeat_one"; "repeat_one" -> "loop_all"; "loop_all" -> "shuffle"; else -> "sequential" }
private fun playModeName(mode: String) = when (mode) { "repeat_one" -> "å•æ›²å¾ªç¯"; "loop_all" -> "åˆ—è¡¨å¾ªç¯"; "shuffle" -> "éšæœºæ’­æ”¾"; else -> "é¡ºåºæ’­æ”¾" }
private fun playModeIcon(mode: String) = when (mode) { "repeat_one" -> Icons.Default.RepeatOne; "loop_all" -> Icons.Default.Repeat; "shuffle" -> Icons.Default.Shuffle; else -> Icons.Default.FormatListNumbered }
internal fun lyricTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    return "${(safe / 60_000).toString().padStart(2, '0')}:${((safe / 1000) % 60).toString().padStart(2, '0')}"
}
internal fun lyricLayers(showOriginal: Boolean, showTranslation: Boolean, hasTranslation: Boolean): Pair<Boolean, Boolean> =
    (showOriginal || !hasTranslation) to (showTranslation && hasTranslation)
internal fun fitSingleLineFontSp(requestedSp: Float, measuredWidthPx: Float, availableWidthPx: Float): Float {
    if (requestedSp <= 0f || measuredWidthPx <= 0f || availableWidthPx <= 0f) return requestedSp.coerceAtLeast(1f)
    if (measuredWidthPx <= availableWidthPx) return requestedSp
    return (requestedSp * availableWidthPx / measuredWidthPx).coerceIn(minOf(10f, requestedSp), requestedSp)
}
internal fun lyricIndexClosestToCenter(
    viewportStart: Int,
    viewportEnd: Int,
    visibleItems: List<Triple<Int, Int, Int>>,
): Int {
    if (viewportEnd <= viewportStart) return -1
    val center = (viewportStart + viewportEnd) / 2f
    return visibleItems.minByOrNull { (_, offset, size) -> abs(offset + size / 2f - center) }?.first ?: -1
}

internal fun lyricCenterScrollOffset(viewportStart: Int, viewportEnd: Int, itemSize: Int): Int {
    val viewportSize = viewportEnd - viewportStart
    if (viewportSize <= 0 || itemSize <= 0) return 0
    val desiredItemStart = viewportStart + (viewportSize - itemSize) / 2
    return -desiredItemStart
}

internal fun showLyricTimePill(index: Int, focusedIndex: Int, manualSelection: Boolean, timeMs: Long): Boolean =
    manualSelection && index == focusedIndex && timeMs >= 0

@Composable private fun SingleLineLyricText(
    text: String,
    modifier: Modifier = Modifier,
    requestedFontSp: Float,
    color: Color,
    fontWeight: FontWeight = FontWeight.Normal,
    renderProgress: Float? = null,
    lowPower: Boolean = false,
    centered: Boolean = false,
    emphasisScale: Float = 1f,
) = BoxWithConstraints(modifier, contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val availableWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
    val measuredWidthPx = remember(text, requestedFontSp, fontWeight, measurer, availableWidthPx) {
        measurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(fontSize = requestedFontSp.sp, fontWeight = fontWeight),
            maxLines = 1,
            softWrap = false,
        ).size.width.toFloat()
    }
    val fontSizeSp = fitSingleLineFontSp(requestedFontSp, measuredWidthPx, availableWidthPx)
    // The player clock is sampled every 100 ms (500 ms in low-power mode).
    // Let the tween span the sampling window so the highlight keeps moving
    // between samples instead of stopping briefly after every update.
    val smoothProgress by animateFloatAsState(
        targetValue = renderProgress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = tween(if (lowPower) 520 else 130, easing = LinearEasing),
        label = "lyricRender",
    )
    // Keep the measured text layout stable while the active line grows. A
    // render-layer transform avoids remeasuring every visible row during the
    // center-scroll animation.
    val smoothScale = emphasisScale.coerceIn(.92f, 1.12f)
    val horizontalScale = if (measuredWidthPx > 0f && measuredWidthPx <= availableWidthPx) {
        minOf(smoothScale, availableWidthPx / measuredWidthPx)
    } else 1f
    Box(
        Modifier.wrapContentWidth().graphicsLayer {
            scaleX = horizontalScale
            scaleY = smoothScale
        },
    ) {
        Text(
            text, color = color, fontSize = fontSizeSp.sp, fontWeight = fontWeight,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
        if (renderProgress != null) Text(
            text, color = Green, fontSize = fontSizeSp.sp, fontWeight = fontWeight,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            modifier = Modifier.drawWithContent {
                clipRect(right = size.width * smoothProgress) { this@drawWithContent.drawContent() }
            },
        )
    }
}

@Composable private fun LyricTimePill(timeMs: Long, onSeek: () -> Unit) {
    Surface(
        onClick = onSeek,
        modifier = Modifier.height(28.dp),
        shape = RoundedCornerShape(9.dp),
        color = Green.copy(alpha = .12f),
        contentColor = Green,
    ) {
        Row(Modifier.padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PlayArrow, "è·³è½¬åˆ° ${lyricTime(timeMs)}", Modifier.size(13.dp))
            Spacer(Modifier.width(2.dp))
            Text(lyricTime(timeMs), color = Color.White.copy(alpha = .86f), fontSize = 10.sp, maxLines = 1, softWrap = false)
        }
    }
}
private fun loginProviderName(provider: String) = if (provider == "wechat") "å¾®ä¿¡" else "QQ"
private fun accountLabel(provider: String, accountId: String?) = if (provider == "wechat") "å¾®ä¿¡è´¦å·å·²ç»‘å®š" else "QQå· ${accountId.orEmpty()}"
private fun vipSummary(profile: UserProfile?, loaded: Boolean, error: String?): String = when {
    !loaded -> "æ­£åœ¨è¯»å–ä¼šå‘˜çŠ¶æ€"
    error != null && profile == null -> error
    profile?.isVipActive() == true -> buildString {
        append(profile.vipName.ifBlank { "ä¼šå‘˜æœ‰æ•ˆ" })
        normalizeEpochSeconds(profile.vipExpireAt)?.let { expiry -> append(" Â· åˆ°æœŸ "); append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(expiry * 1000))) }
    }
    profile?.isVip == false -> "æœªæ£€æµ‹åˆ°ä¼šå‘˜æ’­æ”¾æƒç›Š"
    else -> "æš‚æ— æ³•ç¡®è®¤ä¼šå‘˜æƒç›Šï¼Œç‚¹æ£€æŸ¥ç™»å½•é‡è¯•"
}

@Composable private fun AccountAvatar(
    avatarUrl: String?,
    provider: String,
    accountId: String?,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val qq = accountId.orEmpty().filter(Char::isDigit)
    val resolvedUrl = trustedQMusicMediaUrl(avatarUrl.orEmpty()).ifBlank {
        if (provider == "qq" && qq.isNotBlank()) qmusicAvatarUrl(qq) else ""
    }
    val request = remember(resolvedUrl) {
        resolvedUrl.takeIf(String::isNotBlank)?.let { ImageRequest.Builder(context).data(it).crossfade(false).build() }
    }
    val fallback = painterResource(com.ronan.qmusicwatch.R.drawable.ic_launcher)
    AsyncImage(
        model = request,
        contentDescription = "è´¦å·å¤´åƒ",
        modifier = modifier.size(size).clip(RoundedCornerShape(50)).background(Color.Transparent),
        contentScale = ContentScale.Crop,
        placeholder = fallback,
        error = fallback,
        fallback = fallback,
    )
}
internal fun <T> dailyBatch(items: List<T>, offset: Int, count: Int): List<T> = if (items.isEmpty()) emptyList() else List(minOf(count, items.size)) { items[(offset + it) % items.size] }
internal fun writablePlaylists(items: List<MusicCollection>): List<MusicCollection> = items.filter { it.owned != false && (it.directoryId.toLongOrNull() ?: 0) > 0 }
internal fun downloadProgressSummary(downloadedBytes: Long, totalBytes: Long): String {
    val safeDownloaded = if (totalBytes > 0) downloadedBytes.coerceIn(0, totalBytes) else downloadedBytes.coerceAtLeast(0)
    val downloadedMb = safeDownloaded / 1024f / 1024f
    if (totalBytes <= 0) return "%.1f MB".format(java.util.Locale.US, downloadedMb)
    val totalMb = totalBytes / 1024f / 1024f
    val percent = (safeDownloaded * 100 / totalBytes).toInt()
    return "%.1f / %.1f MB Â· %d%%".format(java.util.Locale.US, downloadedMb, totalMb, percent)
}
internal fun updateStateRelease(update: UpdateUiState): ControlUpdate? = when (update) {
    is UpdateUiState.Available -> update.release
    is UpdateUiState.Downloading -> update.release
    is UpdateUiState.Verifying -> update.release
    is UpdateUiState.Ready -> update.release
    is UpdateUiState.Error -> update.release
    else -> null
}
internal fun automaticInstallCandidate(pendingReleaseId: Long, update: UpdateUiState): UpdateUiState.Ready? =
    (update as? UpdateUiState.Ready)?.takeIf { pendingReleaseId > 0 && it.release.releaseId == pendingReleaseId }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideStatusBar()
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.cß}µæÚ$z{-®éÜj×&ÆU7FFTÆ—7DöcÅ7G&–æsâ‚’Ğ¢fÂÆ–'&'’Ò7FFRæÆ–'&'¢ÆVæ6†VDVffV7B‡7FFRçVWVT–×÷'EF—FÆR’²6VÆV7FVD–G2æ6ÆV"‚’Ğ¢ÆVæ6†VDVffV7B‡VWVRÂG&vv–æuG&6´–B’²–b†G&vv–æuG&6´–BÓÒçVÆÂ’v÷&¶–æuVWVRÒVWVRĞ¢fÂÆ—7E7FFRÒ&VÖVÖ&W$Æ§”Æ—7E7FFR‚¢fÂ†F–72ÒÆö6Ä†F–4fVVF&6²æ7W'&Vç@¢fÂf–WrÒÆö6Åf–Wræ7W'&Vç@¢fÂVFvU‚Òv—F‚†æG&ö–G‚æ6ö×÷6RçV’çÆFf÷&ÒäÆö6ÄFVç6—G’æ7W'&VçB’²s"æGçFõ‚‚’Ğ¢fÂ7W'&VçEG&6´–BÒ7FFRæ7W'&VçEG&6³òæ–Bó¢VWVRævWD÷$çVÆÂ†7W'&VçD–æFW‚“òæ–@¢fÂ6†÷vâÒ&VÖVÖ&W"‡v÷&¶–æuVWVRÂVW'’’²v÷&¶–æuVWVRçv—F„–æFW‚‚’æf–ÇFW"²VW'’æ—4&Ææ²‚’ÇÂ—BçfÇVRçF—FÆRæ6öçF–ç2‡VW'’ÂG'VR’ÇÂ—BçfÇVRæ'F—7G2æç’²'F—7BÓâ'F—7Bæ6öçF–ç2‡VW'’ÂG'VR’ÒÒĞ¢Æ§”6öÇVÖâ„ÖöF–f–W"æf–ÆÄÖ…6—¦R‚’çFF–ær††÷&—¦öçFÂÒ"æG’Â7FFRÒÆ—7E7FFRÂ6öçFVçEFF–ærÒFF–æufÇVW2†&÷GFöÒÒ‚æG’’°¢—FVÒ²&÷r„ÖöF–f–W"æf–ÆÄÖ…v–GF‚‚’ÂfW'F–6ÄÆ–væÖVçBÒÆ–væÖVçBä6VçFW%fW'F–6ÆÇ’’²–6öä'WGFöâ†öä&6²’²–6öâ„–6öç2äWFôÖ—'&÷&VBäf–ÆÆVBä'&÷t&6²Â.‹ùNY¹â"’Ó²FW‡B‚.[Ù>X˜Şi*ŞiKîX‰~Š‚"ÂÖöF–f–W"çvV–v‡Bƒb’ÂföçE6—¦RÒ#Bç7ÂföçEvV–v‡BÒföçEvV–v‡Bä&öÆB“²FW‡D'WGFöâ‡fÓ£§&WfW'6UVWVR’²–6öâ†–b‡&WfW'6VB’–6öç2äFVfVÇBä'&÷uWv&BVÇ6R–6öç2äFVfVÇBä'&÷tF÷vçv&BÂçVÆÂ“²FW‡B†–b‡&WfW'6VB’.X	.[¨ò"VÇ6R.jÚ>[¨ò"’ÒÒĞ¢—FVÒ²÷WFÆ–æVEFW‡Df–VÆB‡VW'’Â²VW'’Ò—BÒÂÖöF–f–W"æf–ÆÄÖ…v–GF‚‚’æ†V–v‡BƒC‚æG’Â6–ævÆTÆ–æRÒG'VRÂ6†RÒ&÷VæFVD6÷&æW%6†Rƒ’æG’Â6öÆ÷'2ÒvF6…6V&6„6öÆ÷'2‚’ÂÆ6V†öÆFW"Ò²FW‡B‚.zÙ¾˜i*ŞiKîX‰~Š‚"ÂföçE6—¦RÒ2ç7Â6öÆ÷"Ò6öÆ÷"äw&’’ÒÂFW‡E7G–ÆRÒæG&ö–G‚æ6ö×÷6RçV’çFW‡BåFW‡E7G–ÆR†föçE6—¦RÒBç7’ÂÆVF–æt–6öâÒ²–6öâ„–6öç2äFVfVÇBå6V&6‚ÂçVÆÂÂF–çBÒ6öÆ÷"ƒ„dd#d$d$’ÂÖöF–f–W"ÒÖöF–f–W"ç6—¦Rƒ’æG’’Ò’Ğ¢—FVÒ²fÆ÷u&÷r„ÖöF–f–W"æf–ÆÄÖ…v–GF‚‚’Â†÷&—¦öçFÄ'&ævVÖVçBÒ'&ævVÖVçBäVæBÂfW'F–6Ä'&ævVÖVçBÒ'&ævVÖVçBç76VD'’ƒæG’’²FW‡D'WGFöâ‡²fÒæ66†TÆÂ‡VWVRÂ.[Ù>X˜Şi*ŞiKîX‰~Š‚"’ÒÂ6öçFVçEFF–ærÒFF–æufÇVW2††÷&—¦öçFÂÒræG’’²–6öâ„–6öç2äFVfVÇBäF÷væÆöBÂçVÆÂÂÖöF–f–W"ç6—¦Rƒ‚æG’“²FW‡B‚.{É>ZÙ‚"ÂföçE6—¦RÒ2ç7’Ó²FW‡D'WGFöâ‡²fÒæ6ÆV%VWVT–×÷'B‚“²–×÷'DF–ÆörÒG'VRÒÂ6öçFVçEFF–ærÒFF–æufÇVW2††÷&—¦öçFÂÒræG’’²–6öâ„–6öç2äFVfVÇBäÆ–'&'”FBÂçVÆÂÂÖöF–f–W"ç6—¦Rƒ‚æG’“²FW‡B‚.˜jØÎk{¾Xª"ÂföçE6—¦RÒ2ç7’Ó²FW‡D'WGFöâ‡²6fTF–ÆörÒG'VRÒÂ6öçFVçEFF–ærÒFF–æufÇVW2††÷&—¦öçFÂÒræG’’²–6öâ„–6öç2äWFôÖ—'&÷&VBäf–ÆÆVBåÆ–Æ—7DFBÂçVÆÂÂÖöF–f–W"ç6—¦Rƒ‚æG’“²FW‡B‚.KùŞZÙ‚"ÂföçE6—¦RÒ2ç7’Ó²FW‡D'WGFöâ‡fÓ£§&VÖ÷fUVWVTGWÆ–6FW2Â6öçFVçEFF–ærÒFF–æufÇVW2††÷&—¦öçFÂÒræG’’²FW‡B‚.Xë¾˜xÒ"ÂföçE6—¦RÒ2ç7’Ó²FW‡D'WGFöâ‡fÓ£¦6ÆV%VWVRÂ6öçFVçEFF–ærÒFF–æufÇVW2††÷&—¦öçFÂÒræG’’²FW‡B‚.kˆ^z›¢"ÂföçE6—¦RÒ2ç7’ÒÒĞ¢—FVÒ²FW‡B‚"G·v÷&¶–æuVWVRç6—¦WÒšib"Â6öÆ÷"Ò6öÆ÷"äw&’’Ğ¢–b‡v÷&¶–æuVWVRæ—4V×G’‚’’—FVÒ²&÷‚„ÖöF–f–W"æf–ÆÅ&VçDÖ„†V–v‡B‚ãvb’æf–ÆÄÖ…v–GF‚‚’Â6öçFVçDÆ–væÖVçBÒÆ–væÖVçBä6VçFW"’²FW‡B‚.i*ŞiKîX‰~ŠK‹®z›¢"Â6öÆ÷"Ò6öÆ÷"äw&’’ÒĞ¢—FV×4–æFW†VB‡6†÷vâÂ¶W’Ò²òÂ—FVÒÓâ—FVÒçfÇVRæ–BÒ’²òÂ–æFW†VBÓà¢fÂ–æFW‚Ò–æFW†VBæ–æFWƒ²fÂG&6²Ò–æFW†VBçfÇVP¢f"G&vvVB'’&VÖVÖ&W"‡G&6²æ–B’²×WF&ÆTfÆöE7FFTöbƒb’Ğ¢fÂG&vv–ærÒG&vv–æuG&6´–BÓÒG&6²æ–@¢f"&÷t†V–v‡E‚'’&VÖVÖ&W"‡G&6²æ–B’²×WF&ÆT–çE7FFTöbƒ’Ğ¢f"†æFÆUF÷–åv–æF÷r'’&VÖVÖ&W"‡G&6²æ–B’²×WF&ÆTfÆöE7FFTöbƒb’Ğ¢f"VFvU67&öÆÄF—&V7F–öâ'’&VÖVÖ&W"‡G&6²æ–B’²×WF&ÆT–çE7FFTöbƒ’Ğ¢f"G&t–æFW‚'’&VÖVÖ&W"‡G&6²æ–B’²×WF&ÆT–çE7FFTöb†–æFW‚’Ğ¢f"G&u7F'D–æFW‚'’&VÖVÖ&W"‡G&6²æ–B’²×WF&ÆT–çE7FFTöb†–æFW‚’Ğ¢fÂ&V÷&FW$'”öfg6WC¢‚’ÓâVæ—BÒ°¢–b‡VW'’æ—4&Ææ²‚’bb&÷t†V–v‡E‚â’°¢f"¶VWÖ÷f–ærÒG'VP¢v†–ÆR†¶VWÖ÷f–ær’°¢fÂ7FWÒVWVU&V÷&FW%7FW†G&vvVBÂ&÷t†V–v‡E‚¢fÂF&vWBÒ†G&t–æFW‚²7FW’æ6öW&6T–â‡v÷&¶–æuVWVRæ–æF–6W2¢–b‡7FWÓÒÇÂF&vWBÓÒG&t–æFW‚’¶VWÖ÷f–ærÒfÇ6RVÇ6R°¢v÷&¶–æuVWVRÒÖ÷fUVWVU&Wf–Wr‡v÷&¶–æuVWVRÂG&t–æFW‚ÂF&vWB¢G&t–æFW‚ÒF&vW@¢G&vvVBÓÒ7FW¢&÷t†V–v‡E€¢Ğ¢Ğ¢Ğ¢Ğ¢ÆVæ6†VDVffV7B†G&vv–ærÂVFvU67&öÆÄF—&V7F–öâÂ&÷t†V–v‡E‚’°¢v†–ÆR†G&vv–ærbbVFvU67&öÆÄF—&V7F–öâÒ’°¢fÂ6öç7VÖVBÒÆ—7E7FFRç67&öÆÄ'’†VFvU67&öÆÄF—&V7F–öâ¢&÷t†V–v‡E‚¢ã&b¢G&vvVB³Ò6öç7VÖV@¢&V÷&FW$'”öfg6WB‚¢–b†6öç7VÖVBÓÒb’VFvU67&öÆÄF—&V7F–öâÒ ¢FVÆ’ƒb¢Ğ¢Ğ¢fÂ†æFÆTÖöF–f–W"ÒÖöF–f–W"ç6—¦RƒCBæG’çFF–ærƒ’æG’æöävÆö&ÆÇ•÷6—F–öæVB²†æFÆUF÷–åv–æF÷rÒ—Bç÷6—F–öä–åv–æF÷r‚’ç’ÒçF†Vâ€¢–b‡VW'’æ—4&Ææ²‚’’ÖöF–f–W"çö–çFW$–çWB‡G&6²æ–BÂ&÷t†V–v‡E‚Âf–Wræ†V–v‡B’°¢FWFV7DG&tvW7GW&W4gFW$Æöæu&W72€¢öäG&u7F'BÒ²†F–72çW&f÷&Ô†F–4fVVF&6²„†F–4fVVF&6µG—RäÆöæu&W72“²v÷&¶–æuVWVRÒVWVS²G&t–æFW‚ÒVWVRæ–æFW„ödf—'7B²—Bæ–BÓÒG&6²æ–BÒæ6öW&6TDÆV7Bƒ“²G&u7F'D–æFW‚ÒG&t–æFWƒ²G&vv–æuG&6´–BÒG&6²æ–C²G&vvVBÒbÒÀ¢öäG&t6æ6VÂÒ²VFvU67&öÆÄF—&V7F–öâÒ²v÷&¶–æuVWVRÒVWVS²G&vv–æuG&6´–BÒçVÆÃ²G&vvVBÒbÒÀ¢öäG&tVæBÒ²–b†G&t–æFW‚ÒG&u7F'D–æFW‚’†F–72çW&f÷&Ô†F–4fVVF&6²„†F–4fVVF&6µG—RäÆöæu&W72“²VFvU67&öÆÄF—&V7F–öâÒ²fÒç&WÆ6UVWVT÷&FW"‡v÷&¶–æuVWVR“²G&vv–æuG&6´–BÒçVÆÃ²G&vvVBÒbÒÀ¢’²6†ævRÂÖ÷VçBÓà¢6†ævRæ6öç7VÖR‚“²G&vvVB³ÒÖ÷VçBç“²&V÷&FW$'”öfg6WB‚¢VFvU67&öÆÄF—&V7F–öâÒVWVTVFvU67&öÆÄF—&V7F–öâ††æFÆUF÷–åv–æF÷r²6†ævRç÷6—F–öâç’Âf–Wræ†V–v‡BÂVFvU‚¢Ğ¢ÒVÇ6RÖöF–f–W"æÇ†‚ã#†b¢¢7W&f6R€¢ÖöF–f–W"ÒÖöF–f–W"çFF–ær‡fW'F–6ÂÒ2æG’ææ–ÖFT—FVÒ€¢fFT–å7V2ÒæG&ö–G‚æ6ö×÷6Rææ–ÖF–öâæ6÷&RçGvVVâƒƒ’À¢Æ6VÖVçE7V2Ò–b†G&vv–ær’æG&ö–G‚æ6ö×÷6Rææ–ÖF–öâæ6÷&Rç6æ‚’VÇ6RæG&ö–G‚æ6ö×÷6Rææ–ÖF–öâæ6÷&Rç7&–ær‡7F–ffæW72ÒæG&ö–G‚æ6ö×÷6Rææ–ÖF–öâæ6÷&Rå7&–ærå7F–ffæW74ÖVF—VÔÆ÷r’À¢fFT÷WE7V2ÒæG&ö–G‚æ6ö×÷6Rææ–ÖF–öâæ6÷&RçGvVVâƒc’À¢’ç¤–æFW‚†–b†G&vv–ær’&bVÇ6Rb’æw&†–74Æ–W"°¢G&ç6ÆF–öå’ÒG&vvVC²66ÆU‚Ò–b†G&vv–ær’ã#VbVÇ6Rc²66ÆU’Ò–b†G&vv–ær’ã#VbVÇ6R`¢6†F÷tVÆWfF–öâÒ–b†G&vv–ær’"æGçFõ‚‚’VÇ6R`¢Òæöå6—¦T6†ævVB²&÷t†V–v‡E‚Ò—Bæ†V–v‡BÒÀ¢6†RÒ&÷VæFVD6÷&æW%6†Rƒ‚æG’Â6öÆ÷"Ò–b‡G&6²æ–BÓÒ7W'&VçEG&6´–B’6öÆ÷"ƒ„dcS#cB’VÇ6R7W&f6RÀ¢’°¢&÷r„ÖöF–f–W"æf–ÆÄÖ…v–GF‚‚’æ6Æ–6¶&ÆR²fÒçÆ•VWVT—FVÒ‡VWVRæ–æFW„ödf—'7B²—Bæ–BÓÒG&6²æ–BÒ’ÒçFF–ær‡7F'BÒæGÂVæBÒ2æGÂF÷ÒræGÂ&÷GFöÒÒræG’ÂfW'F–6ÄÆ–væÖVçBÒÆ–væÖVçBä6VçFW%fW'F–6ÆÇ’’°¢&÷‚„ÖöF–f–W"ç6—¦Rƒ3æG’Â6öçFVçDÆ–væÖVçBÒÆ–væÖVçBä6VçFW"’²–b‡G&6²æ–BÓÒ7W'&VçEG&6´–B’–6öâ„–6öç2äFVfVÇBäw&†–4WÂçVÆÂÂF–çBÒw&VVâÂÖöF–f–W"ÒÖöF–f–W"ç6—¦Rƒ#æG’’VÇ6RFW‡B‚"G¶–æFW‚²Ò"Â6öÆ÷"Ò6öÆ÷"äw&’ÂföçE6—¦RÒ2ç7’Ğ¢76W"„ÖöF–f–W"çv–GF‚ƒræG’“²6öÇVÖâ„ÖöF–f–W"çvV–v‡Bƒb’’²FW‡B‡G&6²çF—FÆRÂ6öÆ÷"Ò–b‡G&6²æ–BÓÒ7W'&VçEG&6´–B’w&VVâVÇ6R6öÆ÷"åv†—FRÂÖ„Æ–æW2ÒÂ÷fW&fÆ÷rÒFW‡D÷fW&fÆ÷räVÆÆ—6—2ÂföçE6—¦RÒRç7“²FW‡B‡G&6²æ'F—7G2æ¦ö–åFõ7G&–ær‚"ò"’Â6öÆ÷"Ò6öÆ÷"äw&’ÂÖ„Æ–æW2ÒÂ÷fW&fÆ÷rÒFW‡D÷fW&fÆ÷räVÆÆ—6—2ÂföçE6—¦RÒ"ç7’Ğ¢–6öâ„–6öç2äFVfVÇBäG&t†æFÆRÂ–b‡VW'’æ—4&Ææ²‚’’.™[şhÈh¹nXªhé.[¨ò"VÇ6R.zÙ¾˜i{nKˆŞXúşhé.[¨ò"Â†æFÆTÖöF–f–W"¢–6öä'WGFöâ‡²fÒç&VÖ÷fTg&öÕVWVR‡VWVRæ–æFW„ödf—'7B²—Bæ–BÓÒG&6²æ–BÒ’ÒÂÖöF–f–W"ç6—¦RƒCBæG’’²–6öâ„–6öç2äFVfVÇBå&VÖ÷fT6—&6ÆT÷WFÆ–æRÂ.z{¾™šB"ÂÖöF–f–W"ç6—¦Rƒ#æG’’Ğ¢Ğ¢Ğ¢Ğ¢Ğ¢–b‡6fTF–Æör’ÆW'DF–Æör†öäF—6Ö—75&WVW7BÒ²6fTF–ÆörÒfÇ6RÒÂF—FÆRÒ²FW‡B‚.KùŞZÙK‹®h‰y¨NjØÎXÙR"’ÒÂFW‡BÒ²÷WFÆ–æVEFW‡Df–VÆB‡Æ–Æ—7EF—FÆRÂ²Æ–Æ—7EF—FÆRÒ—BçF¶RƒS’ÒÂÆ&VÂÒ²FW‡B‚.jØÎXÙ^YŞz{"’ÒÂ6–ævÆTÆ–æRÒG'VR’ÒÂ6öæf—&Ô'WGFöâÒ²FW‡D'WGFöâ‡²–b‡Æ–Æ—7EF—FÆRæ—4æ÷D&Ææ²‚’’fÒç6fUVWVT5Æ–Æ—7B‡Æ–Æ—7EF—FÆR“²6fTF–ÆörÒfÇ6RÒ’²FW‡B‚.KùŞZÙ‚"’ÒÒÂF—6Ö—74'WGFöâÒ²FW‡D'WGFöâ‡²6fTF–ÆörÒfÇ6RÒ’²FW‡B‚.Xùnkh‚"’ÒÒ¢–b†–×÷'DF–Æör’ÆW'DF–Æör€¢öäF—6Ö—75&WVW7BÒ²–×÷'DF–ÆörÒfÇ6S²fÒæ6ÆV%VWVT–×÷'B‚’ÒÀ¢F—FÆRÒ²FW‡B‡7FFRçVWVT–×÷'EF—FÆRæ–d&Ææ²².˜hºjØÎi».iÚ^k©"Ò’ÒÀ¢FW‡BÒ°¢v†Vâ°¢7FFRçVWVT–×÷'EF—FÆRæ—4&Ææ²‚’ÓâÆ§”6öÇVÖâ„ÖöF–f–W"æ†V–v‡D–â†Ö‚Ò3æG’ÂfW'F–6Ä'&ævVÖVçBÒ'&ævVÖVçBç76VD'’ƒ2æG’’°¢—FVÒ²7W&f6R„ÖöF–f–W"æf–ÆÄÖ…v–GF‚‚’æ6Æ–6¶&ÆR²fÒæÆöEVWVT–×÷'DÆ–¶VB‚’ÒÂ6†RÒ&÷VæFVD6÷&æW%6†RƒBæG’Â6öÆ÷"Ò7W&f6R’²&÷r„ÖöF–f–W"çFF–ærƒ"æG’ÂfW'F–6ÄÆ–væÖVçBÒÆ–væÖVçBä6VçFW%fW'F–6ÆÇ’’²–6öâ„–6öç2äFVfVÇBäff÷&—FRÂçVÆÂÂF–çBÒw&VVâ“²76W"„ÖöF–f–W"çv–GF‚ƒ’æG’“²6öÇVÖâ²FW‡B‚.h‰YiÎjÊ""“²FW‡B‚"G¶Æ–'&'“òæÆ–¶VCòç6—¦Ró¢Òšib"Â6öÆ÷"Ò6öÆ÷"äw&’ÂföçE6—¦RÒ2ç7’ÒÒÒĞ¢—FV×2†Æ–'&'“òçÆ–Æ—7G2æ÷$V×G’‚’Â¶W’Ò²"G¶—BæF—&V7F÷'”–GÓ¢G¶—Bæ–GÒ"Ò’²Æ–Æ—7BÓâ7W&f6R„ÖöF–f–W"æf–ÆÄÖ…v–GF‚‚’æ6Æ–6¶&ÆR²fÒæÆöEVWVT–×÷'EÆ–Æ—7B‡Æ–Æ—7B’ÒÂ6†RÒ&÷VæFVD6÷&æW%6†RƒBæG’Â6öÆ÷"Ò7W&f6R’²&÷r„ÖöF–f–W"çFF–ærƒ"æG’ÂfW'F–6ÄÆ–væÖVçBÒÆ–væÖVçBä6VçFW%fW'F–6ÆÇ’’²–6öâ„–6öç2äWFôÖ—'&÷&VBäf–ÆÆVBåVWVT×W6–2ÂçVÆÂÂF–çBÒw&VVâ“²76W"„ÖöF–f–W"çv–GF‚ƒ’æG’“²6öÇVÖâ²FW‡B‡Æ–Æ—7BçF—FÆRÂÖ„Æ–æW2Ò“²FW‡B†–b‡Æ–Æ—7BçG&6´6÷VçBãÒ’"G·Æ–Æ—7BçG&6´6÷VçGÒšib"VÇ6R.x+X{¾Šû¾Xùb"Â6öÆ÷"Ò6öÆ÷"äw&’ÂföçE6—¦RÒ2ç7’ÒÒÒÒĞ¢7FFRçVWVT–×÷'DÆöF–ærÓâ&÷‚„ÖöF–f–W"æf–ÆÄÖ…v–GF‚‚’æ†V–v‡Bƒ#æG’Â6öçFVçDÆ–væÖVçBÒÆ–væÖVçBä6VçFW"’²6—&7VÆ%&öw&W74–æF–6F÷"‚’Ğ¢VÇ6RÓâ6öÇVÖâ°¢&÷r„ÖöF–f–W"æf–ÆÄÖ…v–GF‚‚’ÂfW'F–6ÄÆ–væÖVçBÒÆ–væÖVçBä6VçFW%fW'F–6ÆÇ’’²FW‡B‚.[{.˜’G·6VÆV7FVD–G2ç6—¦WÒšib"ÂÖöF–f–W"çvV–v‡Bƒb’Â6öÆ÷"Òw&VVâ“²FW‡D'WGFöâ‡²–b‡6VÆV7FVD–G2ç6—¦RÓÒ7FFRçVWVT–×÷'EG&6·2ç6—¦R’6VÆV7FVD–G2æ6ÆV"‚’VÇ6R²6VÆV7FVD–G2æ6ÆV"‚“²6VÆV7FVD–G2æFDÆÂ‡7FFRçVWVT–×÷'EG&6·2æÖ…G&6³£¦–B’’ÒÒ’²FW‡B†–b‡6VÆV7FVD–G2ç6—¦RÓÒ7FFRçVWVT–×÷'EG&6·2ç6—¦R’.XùnkhXZ˜’"VÇ6R.XZ˜’"’ÒĞ¢Æ§”6öÇVÖâ„ÖöF–f–W"æ†V–v‡D–â†Ö‚Ò#ƒæG’ÂfW'F–6Ä'&ævVÖVçBÒ'&ævVÖVçBç76VD'’ƒ"æG’’°¢—FV×2‡7FFRçVWVT–×÷'EG&6·2Â¶W’Ò²—Bæ–BÒ’²G&6²Óà¢fÂ6VÆV7FVBÒG&6²æ–B–â6VÆV7FVD–G0¢&÷r„ÖöF–f–W"æf–ÆÄÖ…v–GF‚‚’æ6Æ—…&÷VæFVD6÷&æW%6†Rƒ"æG’’æ6Æ–6¶&ÆR²–b‡6VÆV7FVB’6VÆV7FVD–G2ç&VÖ÷fR‡G&6²æ–B’VÇ6R6VÆV7FVD–G2æFB‡G&6²æ–B’ÒçFF–ær††÷&—¦öçFÂÒbæGÂfW'F–6ÂÒBæG’ÂfW'F–6ÄÆ–væÖVçBÒÆ–væÖVçBä6VçFW%fW'F–6ÆÇ’’²6†V6¶&÷‚‡6VÆV7FVBÂ²6†V6¶VBÓâ–b†6†V6¶VB’6VÆV7FVD–G2æFB‡G&6²æ–B’VÇ6R6VÆV7FVD–G2ç&VÖ÷fR‡G&6²æ–B’ÒÂÖöF–f–W"ç6—¦Rƒ3‚æG’“²6öÇVÖâ„ÖöF–f–W"çvV–v‡Bƒb’’²FW‡B‡G&6²çF—FÆRÂÖ„Æ–æW2ÒÂföçE6—¦RÒRç7“²FW‡B‡G&6²æ'F—7G2æ¦ö–åFõ7G&–ær‚"ò"’ÂÖ„Æ–æW2ÒÂ6öÆ÷"Ò6öÆ÷"äw&’ÂföçE6—¦RÒ"ç7’ÒĞ¢Ğ¢Ğ¢Ğ¢Ğ¢ÒÀ¢6öæf—&Ô'WGFöâÒ²–b‡7FFRçVWVT–×÷'EF—FÆRæ—4æ÷D&Ææ²‚’bb7FFRçVWVT–×÷'DÆöF–ær’FW‡D'WGFöâ‡²fÒæFE6VÆV7FVEVWVUG&6·2‡6VÆV7FVD–G2çFõ6WB‚’“²–×÷'DF–ÆörÒfÇ6RÒÂVæ&ÆVBÒ6VÆV7FVD–G2æ—4æ÷DV×G’‚’’²FW‡B‚.k{¾XªG·6VÆV7FVD–G2ç6—¦WÒšib"’ÒÒÀ¢F—6Ö—74'WGFöâÒ²FW‡D'WGFöâ‡²–b‡7FFRçVWVT–×÷'EF—FÆRæ—4&Ææ²‚’’²–×÷'DF–ÆörÒfÇ6S²fÒæ6ÆV%VWVT–×÷'B‚’ÒVÇ6RfÒæ6ÆV%VWVT–×÷'B‚’Ò’²FW‡B†–b‡7FFRçVWVT–×÷'EF—FÆRæ—4&Ææ²‚’’.Xùnkh‚"VÇ6R.‹ùNY¹â"’ÒÒÀ¢§Ğ ¤6ö×÷6&ÆR&—fFRgVâ6öÆÆV7F–öå&÷r‡fÇVS¢×W6–46öÆÆV7F–öâÂ÷Vã¢‚’ÓâVæ—BÒ·Ò’ÒÆ—7D—FVÒ†ÖöF–f–W"ÒÖöF–f–W"æ6Æ–6¶&ÆR†öä6Æ–6²Ò÷Vâ’Â†VFÆ–æT6öçFVçBÒ²FW‡B‡fÇVRçF—FÆR’ÒÂ7W÷'F–æt6öçFVçBÒ²FW‡B†–b‡fÇVRçG&6´6÷VçBãÒ’"G·fÇVRçG&6´6÷VçGÒšib"VÇ6R.x+X{¾iú^yÈ²"’ÒÂÆVF–æt6öçFVçBÒ²–6öâ„–6öç2äWFôÖ—'&÷&VBäf–ÆÆVBåVWVT×W6–2ÂçVÆÂÂF–çBÒw&VVâ’Ò¤6ö×÷6&ÆR&—fFRgVâ6V7F–öåF—FÆR‡FW‡C¢7G&–ærÂ7F–öã¢7G&–æsòÒçVÆÂÂöä7F–öã¢‚’ÓâVæ—BÒ·Ò’Ò&÷r„ÖöF–f–W"æf–ÆÄÖ…v–GF‚‚’çFF–ær‡F÷Ò‚æG’ÂfW'F–6ÄÆ–væÖVçBÒÆ–væÖVçBä6VçFW%fW'F–6ÆÇ’’²FW‡B‡FW‡BÂföçE6—¦RÒ#ç7ÂföçEvV–v‡BÒföçEvV–v‡Bä&öÆBÂÖöF–f–W"ÒÖöF–f–W"çvV–v‡Bƒb’“²7F–öãòæÆWB²FW‡D'WGFöâ†öä7F–öâ’²FW‡B†—B’ÒÒĞ¤6ö×÷6&ÆR&—fFRgVâÖ–æ•Æ–W"‡G&6³¢G&6³òÂÇ—&–73¢Æ—7CÄÇ—&–4Æ–æSâÂfÓ¢f–WtÖöFVÂÂ÷Vã¢‚’ÓâVæ—B’°¢–b‡G&6²ÓÒçVÆÂ’&WGW&à¢f"÷6—F–öâ'’&VÖVÖ&W"‡G&6²æ–B’²×WF&ÆTÆöæu7FFTöbƒÂ’Ğ¢f"Æ––ær'’&VÖVÖ&W"‡G&6²æ–B’²×WF&ÆU7FFTöb†fÇ6R’Ğ¢ÆVæ6†VDVffV7B‡G&6²æ–B’°¢v†–ÆR†—47F—fR’°¢÷6—F–öâÒfÒçÆ–&6µ÷6—F–öâ‚¢Æ––ærÒfÒæ—5Æ––ær‚¢FVÆ’ƒ3S¢Ğ¢Ğ¢fÂ&Wf–Wt–æFW‚Ò7F—fTÇ—&–4–æFW‚†Ç—&–72Â÷6—F–öâ’çF¶T–b²—BãÒĞ¢ó¢Ç—&–72æ–æFW„ödf—'7B²—BçF–ÖT×2ãÒÒçF¶T–b²—BãÒĞ¢ó¢Ç—&–72æ–æFW„ödf—'7B²—BçFW‡Bæ—4æ÷D&Ææ²‚’Ğ¢fÂ&Wf–WrÒÇ—&–72ævWD÷$çVÆÂ‡&Wf–Wt–æFW‚“òçFW‡CòçF¶T–b²—Bæ—4æ÷D&Ææ²‚’Òó¢.jÚ>YÊi*ŞiKâ ¢7W&f6R†6öÆ÷"Ò7W&f6RÂFöæÄVÆWfF–öâÒ2æG’°¢&÷r€¢ÖöF–f–W"æf–ÆÄÖ…v–GF‚‚’æ†V–v‡Bƒc"æG’çFF–ær‡7F'BÒ‚æGÂVæBÒBæG’æ6Æ–6¶&ÆR†öä6Æ–6²Ò÷Vâ’À¢fW'F–6ÄÆ–væÖVçBÒÆ–væÖVçBä6VçFW%fW'F–6ÆÇ’À¢’°¢7–æ4–ÖvR€¢ÖöFVÂÒ6fTÆö6Ä÷$vFWv•W&’‡G&6²æ'Gv÷&µW&Â’æ–d&Ææ²²çVÆÂÒÀ¢6öçFVçDFW67&—F–öâÒ.[Ù>X˜ŞjØÎi».[™Ú""À¢ÖöF–f–W"ÒÖöF–f–W"ç6—¦RƒCBæG’æ6Æ—…&÷VæFVD6÷&æW%6†RƒæG’’æ&6¶w&÷VæB„6öÆ÷"äF&´w&’’À¢¢76W"„ÖöF–f–W"çv–GF‚ƒ‚æG’¢6öÇVÖâ„ÖöF–f–W"çvV–v‡Bƒb’ÂfW'F–6Ä'&ævVÖVçBÒ'&ævVÖVçBä6VçFW"’°¢FW‡B‡G&6²çF—FÆRÂÖ„Æ–æW2ÒÂ÷fW&fÆ÷rÒFW‡D÷fW&fÆ÷räVÆÆ—6—2ÂföçE6—¦RÒBç7ÂföçEvV–v‡BÒföçEvV–v‡Bå6VÖ”&öÆB¢FW‡B‡&Wf–WrÂÖ„Æ–æW2ÒÂ÷fW&fÆ÷rÒFW‡D÷fW&fÆ÷räVÆÆ—6—2Â6öÆ÷"Ò6öÆ÷"äw&’ÂföçE6—¦RÒç7¢Ğ¢–6öä'WGFöâ‡²–b‡Æ––ær’fÒçW6UÆ–&6²‚’VÇ6RfÒç&W7VÖUÆ–&6²‚’ÒÂÖöF–f–W"ç6—¦RƒCBæG’’°¢–6öâ†–b‡Æ––ær’–6öç2äFVfVÇBåW6RVÇ6R–6öç2äFVfVÇBåÆ”'&÷rÂ–b‡Æ––ær’.i¨.XÂ"VÇ6R.i*ŞiKâ"ÂÖöF–f–W"ç6—¦Rƒ#BæG’ÂF–çBÒ6öÆ÷"åv†—FR¢Ğ¢–6öâ„–6öç2äFVfVÇBä¶W–&ö&D'&÷uWÂ.h™>[Èi*ŞiKîYš‚"ÂÖöF–f–W"ç6—¦Rƒ#æG’ÂF–çBÒ6öÆ÷"äw&’¢Ğ¢Ğ§Ğ 