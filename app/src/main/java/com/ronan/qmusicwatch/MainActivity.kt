package com.ronan.qmusicwatch

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.ronan.qmusicwatch.login.MusicCookie
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
private const val OFFICIAL_LOGIN_URL = "https://y.qq.com/m/client/qr_code_login/index.html?tmeAppID=qqmusic&frame=1&ct=11&cv=20030508"
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
private fun playModeName(mode: String) = when (mode) { "repeat_one" -> "单曲循环"; "loop_all" -> "列表循环"; "shuffle" -> "随机播放"; else -> "顺序播放" }
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
    // Progress is sampled by the player clock every 100 ms. Keep the tween shorter
    // than that sample window so it never chases the previous frame or flashes when
    // the active line changes.
    val smoothProgress by animateFloatAsState(
        targetValue = renderProgress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = tween(if (lowPower) 160 else 76, easing = LinearEasing),
        label = "lyricRender",
    )
    Box(Modifier.wrapContentWidth()) {
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
            Icon(Icons.Default.PlayArrow, "跳转到 ${lyricTime(timeMs)}", Modifier.size(13.dp))
            Spacer(Modifier.width(2.dp))
            Text(lyricTime(timeMs), color = Color.White.copy(alpha = .86f), fontSize = 10.sp, maxLines = 1, softWrap = false)
        }
    }
}
private fun loginProviderName(provider: String) = if (provider == "wechat") "微信" else "QQ"
private fun accountLabel(provider: String, accountId: String?) = if (provider == "wechat") "微信账号已绑定" else "QQ号 ${accountId.orEmpty()}"
private fun vipSummary(profile: UserProfile?, loaded: Boolean, error: String?): String = when {
    !loaded -> "正在读取会员状态"
    error != null && profile == null -> error
    profile?.isVipActive() == true -> buildString {
        append(profile.vipName.ifBlank { "会员有效" })
        normalizeEpochSeconds(profile.vipExpireAt)?.let { expiry -> append(" · 到期 "); append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(expiry * 1000))) }
    }
    profile?.isVip == false -> "未检测到会员播放权益"
    else -> "暂无法确认会员权益，点检查登录重试"
}
internal fun <T> dailyBatch(items: List<T>, offset: Int, count: Int): List<T> = if (items.isEmpty()) emptyList() else List(minOf(count, items.size)) { items[(offset + it) % items.size] }
internal fun writablePlaylists(items: List<MusicCollection>): List<MusicCollection> = items.filter { it.owned != false && (it.directoryId.toLongOrNull() ?: 0) > 0 }
internal fun downloadProgressSummary(downloadedBytes: Long, totalBytes: Long): String {
    val safeDownloaded = if (totalBytes > 0) downloadedBytes.coerceIn(0, totalBytes) else downloadedBytes.coerceAtLeast(0)
    val downloadedMb = safeDownloaded / 1024f / 1024f
    if (totalBytes <= 0) return "%.1f MB".format(java.util.Locale.US, downloadedMb)
    val totalMb = totalBytes / 1024f / 1024f
    val percent = (safeDownloaded * 100 / totalBytes).toInt()
    return "%.1f / %.1f MB · %d%%".format(java.util.Locale.US, downloadedMb, totalMb, percent)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideStatusBar()
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent { QMusicTheme { QMusicApp() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onResume() { super.onResume(); FramePerformanceMonitor.start() }
    override fun onPause() { FramePerformanceMonitor.stop(); super.onPause() }

    private fun hideStatusBar() = WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.statusBars())
}

@Composable private fun QMusicTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Green, background = Color(0xFF090C0B), surface = Surface, onBackground = Color.White),
    typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 17.sp)), content = content,
)

@Composable private fun QMusicApp(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val noticePrefs = remember { context.getSharedPreferences("notice", android.content.Context.MODE_PRIVATE) }
    var showNotice by remember { mutableStateOf(!noticePrefs.getBoolean("accepted", false)) }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val state by vm.state.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val headphoneWarning by vm.headphoneWarning.collectAsStateWithLifecycle()
    val autoOpenPlayer by vm.autoOpenPlayer.collectAsStateWithLifecycle()
    val playMode by vm.playMode.collectAsStateWithLifecycle()
    val lyricSize by vm.lyricSize.collectAsStateWithLifecycle()
    val lyricTranslation by vm.lyricTranslation.collectAsStateWithLifecycle()
    val lyricOriginal by vm.lyricOriginal.collectAsStateWithLifecycle()
    val lyricOffset by vm.lyricOffset.collectAsStateWithLifecycle()
    val lyricAnimation by vm.lyricAnimation.collectAsStateWithLifecycle()
    val lyricAlignment by vm.lyricAlignment.collectAsStateWithLifecycle()
    val pureBlack by vm.pureBlack.collectAsStateWithLifecycle()
    val lowPowerPlayer by vm.lowPowerPlayer.collectAsStateWithLifecycle()
    val wifiOnlyDownload by vm.wifiOnlyDownload.collectAsStateWithLifecycle()
    val lastSleepMinutes by vm.lastSleepMinutes.collectAsStateWithLifecycle()
    val dailyCount by vm.dailyCount.collectAsStateWithLifecycle()
    val searchHistory by vm.searchHistory.collectAsStateWithLifecycle()
    val seenAnnouncements by vm.seenAnnouncements.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val queueIndex by vm.queueIndex.collectAsStateWithLifecycle()
    val queueReversed by vm.queueReversed.collectAsStateWithLifecycle()
    val sleepRemaining by vm.sleepRemaining.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { AppLog.write("PERF", "startup_ui_ready_ms=${android.os.SystemClock.elapsedRealtime() - QMusicApplication.processStartedAt}") }
    val snackbar = remember { SnackbarHostState() }
    DisposableEffect(backStack?.destination?.route) {
        FramePerformanceMonitor.section = backStack?.destination?.route ?: "home"
        onDispose { }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(state.playEvent, autoOpenPlayer) {
        if (state.playEvent != 0L && autoOpenPlayer && state.currentTrack != null && backStack?.destination?.route != "player") nav.navigate("player") { launchSingleTop = true }
    }
    Scaffold(containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom), snackbarHost = { SnackbarHost(snackbar) }, bottomBar = { if (backStack?.destination?.route != "player") MiniPlayer(state.currentTrack, state.lyrics, vm) { nav.navigate("player") } }) { padding ->
        NavHost(nav, "home", Modifier.padding(padding)) {
            composable("home") { HomeScreen(nav, state, vm, dailyCount) }
            composable("login") { LoginScreen(state, vm) { nav.popBackStack() } }
            composable("search") { SearchScreen(nav, state, vm, searchHistory) }
            composable("library/{section}") { entry ->
                val section = LibrarySection.fromRoute(entry.arguments?.getString("section"))
                LaunchedEffect(section) { vm.loadLibrary() }
                LibraryScreen(nav, state, vm, section)
            }
            composable("recent") { LaunchedEffect(Unit) { vm.loadRecent() }; TrackListScreen("最近播放", state.recent, writablePlaylists(state.library?.playlists.orEmpty()), vm) }
            composable("downloads") { DownloadScreen(downloads, vm) }
            composable("player") {
                PlayerScreen(
                    track = state.currentTrack,
                    lyrics = state.lyrics,
                    vm = vm,
                    playMode = playMode,
                    lyricSize = lyricSize,
                    showOriginal = lyricOriginal,
                    showTranslation = lyricTranslation,
                    lyricOffset = lyricOffset,
                    lyricAnimation = lyricAnimation,
                    lyricAlignment = lyricAlignment,
                    lowPowerPlayer = lowPowerPlayer,
                    quality = quality,
                    profile = state.profile,
                    profileLoaded = state.profileLoaded,
                    playlists = writablePlaylists(state.library?.playlists.orEmpty()),
                    liked = state.library?.liked?.any { it.id == state.currentTrack?.id } == true,
                    openQueue = { nav.navigate("queue") },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("queue") { LaunchedEffect(Unit) { if (vm.signedIn) vm.loadLibrary() }; QueueScreen(queue, queueIndex, queueReversed, state, vm) { nav.popBackStack() } }
            composable("detail") { DetailScreen(state.detail, state.detailDirectoryId, writablePlaylists(state.library?.playlists.orEmpty()), vm) { nav.popBackStack() } }
            composable("settings") { SettingsCenter(nav) { nav.popBackStack() } }
            composable("settings/display") { DisplaySettingsScreen(vm, lyricSize, lyricOriginal, lyricTranslation, lyricOffset, lyricAnimation, lyricAlignment, pureBlack, lowPowerPlayer) { nav.popBackStack() } }
            composable("settings/playback") { PlaybackSettingsScreen(vm, quality, state.profile, state.profileLoaded, headphoneWarning, autoOpenPlayer, playMode, sleepRemaining, wifiOnlyDownload, lastSleepMinutes) { nav.popBackStack() } }
            composable("settings/network") {
                NetworkSettingsScreen(vm, dailyCount, state, onAnnouncements = { nav.navigate("settings/announcements") }, onRelogin = {
                    vm.logout()
                    nav.navigate("login") { popUpTo("home") }
                }) { nav.popBackStack() }
            }
            composable("settings/announcements") { AnnouncementsScreen(state.announcements, seenAnnouncements, vm) { nav.popBackStack() } }
            composable("settings/about") { AboutScreen(vm, state.updateState) { nav.popBackStack() } }
        }
    }
    state.pendingSpeakerTrack?.let { track ->
        AlertDialog(onDismissRequest = vm::dismissSpeakerPrompt, title = { Text("未检测到耳机") }, text = { Text("建议连接蓝牙或有线耳机，是否仍使用手表扬声器播放？") },
            confirmButton = { TextButton(onClick = vm::continueOnSpeaker) { Text("继续外放") } },
            dismissButton = { TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("连接蓝牙") } })
    }
    if (showNotice) AlertDialog(onDismissRequest = {}, title = { Text("第三方非官方客户端") }, text = { Text("QMusic Watch 与腾讯或 QQ 音乐无隶属或认可关系。请尊重版权和账号权益，本项目不会绕过会员、地区、付费或 DRM 限制。") }, confirmButton = { Button({ noticePrefs.edit().putBoolean("accepted", true).apply(); showNotice = false }) { Text("我知道了") } })
    else state.announcements.firstOrNull { it.pinned && it.id !in seenAnnouncements }?.let { announcement ->
        AlertDialog(
            onDismissRequest = { vm.markAnnouncementSeen(announcement.id) },
            title = { Text(announcement.title.take(80), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Box(Modifier.heightIn(max = 165.dp).verticalScroll(rememberScrollState())) { Text(announcement.content.take(1200)) } },
            confirmButton = { TextButton({ vm.markAnnouncementSeen(announcement.id) }) { Text("知道了") } },
        )
    }
}

@Composable private fun HomeScreen(nav: NavHostController, state: AppUiState, vm: AppViewModel, dailyCount: Int) {
    val context = LocalContext.current
    val pager = rememberPagerState { 2 }
    var dailyOffset by remember { mutableIntStateOf(0) }
    val daily = state.home?.daily.orEmpty()
    val shown = dailyBatch(daily, dailyOffset, dailyCount)
    LaunchedEffect(pager.settledPage, vm.signedIn, state.profileLoaded, state.library, state.recentLoaded) {
        if (pager.settledPage == 1 && vm.signedIn) {
            if (!state.profileLoaded) vm.loadProfile()
            if (state.library == null) vm.loadLibrary()
            if (!state.recentLoaded) vm.loadRecent()
        }
    }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            if (page == 0) LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(8.dp)); Text("QMusic Watch", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("第三方非官方客户端", color = Color.Gray) }
                item { Surface(Modifier.fillMaxWidth().height(52.dp).clickable { nav.navigate("search") }, shape = RoundedCornerShape(20.dp), color = Surface) { Row(Modifier.padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Search, null, tint = Color(0xFFB6BFBA), modifier = Modifier.size(20.dp)); Spacer(Modifier.width(9.dp)); Text("搜索歌曲、歌单、歌手、专辑", color = Color(0xFFB6BFBA), fontSize = 14.sp) } } }
                item { SectionTitle("每日推荐", "换一换") { if (daily.isNotEmpty()) dailyOffset = (dailyOffset + dailyCount) % daily.size } }
                items(shown, key = { it.id }) { TrackRow(it, vm, queue = shown, playlists = writablePlaylists(state.library?.playlists.orEmpty())) }
            } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(8.dp)); Text("我的", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                item {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Surface) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            val avatar = remember(state.profile?.avatarUrl) { state.profile?.avatarUrl?.takeIf(String::isNotBlank)?.let { ImageRequest.Builder(context).data(it).addHeader("Referer", "https://y.qq.com/").build() } }
                            AsyncImage(avatar, null, Modifier.size(68.dp).clip(RoundedCornerShape(50)).background(Color.DarkGray), fallback = androidx.compose.ui.res.painterResource(com.ronan.qmusicwatch.R.drawable.ic_launcher))
                            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(if (vm.signedIn) state.profile?.displayName?.ifBlank { null } ?: "${loginProviderName(vm.loginProvider)}音乐用户" else "尚未登录", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(if (vm.signedIn) accountLabel(vm.loginProvider, vm.accountId) else "登录后同步收藏与歌单", color = Color.Gray); if (vm.signedIn) Text(vipSummary(state.profile, state.profileLoaded, state.profileError), color = if (state.profile?.isVipActive() == true) Color(0xFFFFC857) else Color.Gray, fontSize = 13.sp) }
                        }
                    }
                }
                if (!vm.signedIn) item { Button({ nav.navigate("login") }, Modifier.fillMaxWidth()) { Text("扫码登录") } }
                else {
                    item { SettingsModule("我喜欢", "${state.library?.liked?.size ?: 0} 首歌曲", Icons.Default.Favorite) { nav.navigate(libraryRoute(LibrarySection.Liked)) } }
                    item { SettingsModule("我创建的歌单", "${state.library?.playlists?.count { it.owned != false } ?: 0} 个歌单", Icons.AutoMirrored.Filled.QueueMusic) { nav.navigate(libraryRoute(LibrarySection.Created)) } }
                    item { SettingsModule("收藏歌单", "${state.library?.playlists?.count { it.owned == false } ?: 0} 个歌单", Icons.Default.LibraryMusic) { nav.navigate(libraryRoute(LibrarySection.Collected)) } }
                }
                item { SettingsModule("最近播放", "本机播放记录", Icons.Default.History) { nav.navigate("recent") } }
                item { SettingsModule("离线缓存", "已下载歌曲与任务", Icons.Default.Download) { nav.navigate("downloads") } }
                item { SettingsModule("设置中心", "显示、播放、网络与关于", Icons.Default.Settings) { nav.navigate("settings") } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) { repeat(2) { page -> Box(Modifier.padding(3.dp).size(if (pager.currentPage == page) 8.dp else 5.dp).background(if (pager.currentPage == page) Green else Color.Gray, RoundedCornerShape(50))) } }
    }
}

@Composable private fun RowScope.HomeButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FilledTonalButton(onClick, Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(18.dp)) { Icon(icon, null); Spacer(Modifier.width(6.dp)); Text(text, maxLines = 1) }
}

@Composable private fun LoginScreen(state: AppUiState, vm: AppViewModel, onSuccess: () -> Unit) {
    var provider by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.qrStatus) { if (state.qrStatus == "登录成功") onSuccess() }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("扫码登录", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        if (!vm.featureEnabled("qrLogin")) {
            Icon(Icons.Default.BuildCircle, null, Modifier.size(42.dp), tint = Color.Gray)
            Text(vm.featureMessage("qrLogin").ifBlank { "扫码登录暂时维护" }, color = Color.Gray)
        } else if (provider == null) {
            Button({ provider = "qq" }, Modifier.fillMaxWidth()) { Text("使用 QQ 扫码") }
            OutlinedButton({ provider = "wechat" }, Modifier.fillMaxWidth()) { Text("使用微信扫码") }
            Text("不接收账号密码或手动 Cookie", color = Color.Gray)
        } else {
            OfficialQrLogin(provider!!, { cookie -> vm.completeOfficialLogin(provider!!, cookie) })
            Text(state.qrStatus.ifBlank { "请按页面提示使用手机扫码确认" })
            TextButton({ provider = null }) { Text("返回") }
        }
    }
}

@SuppressLint("JavascriptInterface") // QrLoginBridge.onMessage is annotated; lint loses the type through remember().
@Composable private fun OfficialQrLogin(provider: String, onCookie: (String) -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var submitted by remember(provider) { mutableStateOf(false) }
    var loading by remember(provider) { mutableStateOf(true) }
    var loadError by remember(provider) { mutableStateOf<String?>(null) }
    val cookieManager = remember { CookieManager.getInstance().apply { setAcceptCookie(true) } }
    val bridge = remember(provider) { QrLoginBridge { cookie -> if (!submitted) { submitted = true; onCookie(cookie) } } }
    Box(Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(16.dp))) {
    AndroidView(factory = { context ->
        WebView(context).apply {
            webView = this
            setBackgroundColor(android.graphics.Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " /ANDROIDQQMUSIC/20030508 QQMusic/20.3.5.8 H5/1 NetType/WIFI QMusicWatch/${BuildConfig.VERSION_NAME}"
            cookieManager.setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(bridge, "QMusicLogin")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    loading = true
                    loadError = null
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host.orEmpty()
                    return request.url.scheme != "https" || !(host == "qq.com" || host.endsWith(".qq.com"))
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) { loading = false; loadError = "二维码页面加载失败（${error.errorCode}）" }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame) { loading = false; loadError = "二维码页面响应 ${errorResponse.statusCode}" }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (loadError == null) loading = false
                    view.evaluateJavascript(
                        """(function(){if(window.__qmusicBridge)return;window.__qmusicBridge=true;window.addEventListener('message',function(e){try{var h=new URL(e.origin).hostname;if(!(h==='qq.com'||h.endsWith('.qq.com')))return;}catch(_){return;}QMusicLogin.onMessage(typeof e.data==='string'?e.data:JSON.stringify(e.data));});if(window.Music&&Music.client&&Music.client.open){var original=Music.client.open.bind(Music.client);Music.client.open=function(scope,action,payload){if(action==='scanLoginResult'){QMusicLogin.onMessage(JSON.stringify(payload||{}));return Promise.resolve({});}return original(scope,action,payload);};}})();""",
                        null
                    )
                }
            }
            val target = this
            cookieManager.removeAllCookies {
                target.post {
                    cookieManager.flush()
                    if (webView === target) target.loadUrl(OFFICIAL_LOGIN_URL)
                }
            }
        }
    }, modifier = Modifier.fillMaxSize())
    if (loading && loadError == null) Surface(Modifier.fillMaxSize(), color = Surface.copy(alpha = .96f)) { Box(contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp); Spacer(Modifier.height(10.dp)); Text("正在加载登录二维码", color = Color.Gray) } } }
    loadError?.let { error -> Surface(Modifier.fillMaxSize(), color = Surface) { Box(contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(error, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(8.dp)); Button({ loading = true; loadError = null; webView?.loadUrl(OFFICIAL_LOGIN_URL) }) { Text("重新加载") } } } } }
    }
    DisposableEffect(Unit) { onDispose { webView?.stopLoading(); webView?.destroy(); webView = null } }
}

private class QrLoginBridge(private val onCookie: (String) -> Unit) {
    @JavascriptInterface fun onMessage(message: String) {
        if (message.length > 16_384) return
        MusicCookie.fromQrMessage(message)?.let { cookie ->
            Handler(Looper.getMainLooper()).post { onCookie(cookie) }
        }
    }
}

@Composable private fun SearchScreen(nav: NavHostController, state: AppUiState, vm: AppViewModel, history: List<String>) {
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("track") }
    val names = linkedMapOf("track" to "歌曲", "playlist" to "歌单", "artist" to "歌手", "album" to "专辑")
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().height(50.dp), singleLine = true, shape = RoundedCornerShape(20.dp), colors = watchSearchColors(), placeholder = { Text("搜索", fontSize = 14.sp, color = Color.Gray) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp), trailingIcon = { IconButton({ vm.search(query, type) }, Modifier.size(38.dp)) { Icon(Icons.Default.Search, null, tint = Color(0xFFB6BFBA), modifier = Modifier.size(20.dp)) } }, keyboardActions = KeyboardActions(onDone = { vm.search(query, type) }))
        if (query.isBlank() && history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) { Text("最近搜索", Modifier.weight(1f), color = Color.Gray, fontSize = 13.sp); TextButton(vm::clearSearchHistory, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("清空", fontSize = 13.sp) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { history.forEach { value -> AssistChip({ query = value; vm.search(value, type) }, label = { Text(value, maxLines = 1, fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(14.dp)) }) } }
        }
        PrimaryScrollableTabRow(names.keys.indexOf(type), edgePadding = 0.dp, modifier = Modifier.height(42.dp)) { names.forEach { (key, label) -> Tab(type == key, { type = key; if (query.isNotBlank()) vm.search(query, key) }, modifier = Modifier.height(42.dp), text = { Text(label, fontSize = 14.sp) }) } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) { if (type == "track") items(state.searchTracks, key = { it.id }) { TrackRow(it, vm, queue = state.searchTracks, playlists = writablePlaylists(state.library?.playlists.orEmpty())) } else items(state.searchCollections, key = { "${it.directoryId}:${it.id}" }) { CollectionRow(it) { vm.loadDetail(type, it); nav.navigate("detail") } }; if (state.searchCursor != null) item { TextButton({ vm.search(state.searchQuery, type, loadMore = true) }, Modifier.fillMaxWidth(), enabled = !state.searchLoading) { if (state.searchLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("加载更多") } } }
    }
}

@Composable private fun LibraryScreen(nav: NavHostController, state: AppUiState, vm: AppViewModel, section: LibrarySection) {
    var editing by remember { mutableStateOf<MusicCollection?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MusicCollection?>(null) }
    var title by remember { mutableStateOf("") }
    val created = state.library?.playlists.orEmpty().filter { it.owned != false }
    val collected = state.library?.playlists.orEmpty().filter { it.owned == false }
    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        when (section) {
            LibrarySection.Liked -> {
                item { SectionTitle("我喜欢") }
                items(state.library?.liked.orEmpty(), key = { it.id }) { TrackRow(it, vm, liked = true, queue = state.library?.liked.orEmpty(), playlists = created) }
                if (state.library != null && state.library.liked.isEmpty()) item { Text("还没有喜欢的歌曲", color = Color.Gray) }
            }
            LibrarySection.Created -> {
                item { SectionTitle("我创建的歌单", "新建") { title = ""; creating = true } }
                items(created, key = { "${it.directoryId}:${it.id}" }) { item ->
                    ListItem(modifier = Modifier.clickable { vm.loadDetail("playlist", item, editable = true); nav.navigate("detail") }, headlineContent = { Text(item.title) }, supportingContent = { Text("${item.trackCount} 首") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Green) }, trailingContent = { Row { IconButton({ title = item.title; editing = item }) { Icon(Icons.Default.Edit, null) }; IconButton({ deleting = item }) { Icon(Icons.Default.Delete, "删除歌单") } } })
                }
                if (state.library != null && created.isEmpty()) item { Text("还没有创建歌单", color = Color.Gray) }
            }
            LibrarySection.Collected -> {
                item { SectionTitle("收藏歌单") }
                items(collected, key = { "${it.directoryId}:${it.id}" }) { item -> CollectionRow(item) { vm.loadDetail("playlist", item); nav.navigate("detail") } }
                if (state.library != null && collected.isEmpty()) item { Text("还没有收藏歌单", color = Color.Gray) }
            }
        }
        item { OutlinedButton(vm::logout, Modifier.fillMaxWidth()) { Text("退出登录") } }
    }
    if (creating || editing != null) AlertDialog(onDismissRequest = { creating = false; editing = null }, title = { Text(if (creating) "新建歌单" else "重命名歌单") }, text = { OutlinedTextField(title, { title = it.take(50) }, singleLine = true) }, confirmButton = { TextButton({ if (creating) vm.createPlaylist(title.trim()) else vm.renamePlaylist(editing!!.directoryId, title.trim()); creating = false; editing = null }, enabled = title.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton({ creating = false; editing = null }) { Text("取消") } })
    deleting?.let { playlist -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除歌单？") }, text = { Text("将从 QQ 音乐永久删除“${playlist.title}”，歌曲本身不会删除。") }, confirmButton = { TextButton({ vm.deletePlaylist(playlist.directoryId); deleting = null }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ deleting = null }) { Text("取消") } }) }
}

@Composable private fun TrackListScreen(title: String, tracks: List<Track>, playlists: List<MusicCollection>, vm: AppViewModel) = LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
    item { SectionTitle(title) }; items(tracks, key = { it.id }) { TrackRow(it, vm, queue = tracks, playlists = playlists) }
}

@Composable private fun DownloadScreen(downloads: List<DownloadEntity>, vm: AppViewModel) {
    var confirmDeleteLocked by remember { mutableStateOf(false) }
    var deletingGroup by remember { mutableStateOf<String?>(null) }
    val own = downloads.filter { it.ownerAccountId == vm.accountId }
    val locked = downloads.filter { it.ownerAccountId != vm.accountId }
    val totalBytes = own.sumOf { item -> maxOf(item.downloadedBytes, java.io.File(item.filePath).takeIf { item.status == "complete" && it.exists() }?.length() ?: 0L) }
    val lockedBytes = locked.sumOf { item -> maxOf(item.downloadedBytes, java.io.File(item.filePath).takeIf { item.status == "complete" && it.exists() }?.length() ?: 0L) }
    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        item { SectionTitle("离线缓存") }
        item { Text("当前账号占用 %.1f MB · ${own.size} 首".format(totalBytes / 1024f / 1024f), color = Color.Gray); TextButton(vm::deleteInvalidDownloads) { Text("一键删除失效缓存") } }
        if (locked.isNotEmpty()) item { Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Surface) { Row(Modifier.padding(12.dp, 9.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("其他账号缓存已锁定"); Text("${locked.size} 首 · %.1f MB，登录原账号后可恢复".format(lockedBytes / 1024f / 1024f), color = Color.Gray, fontSize = 13.sp) }; TextButton({ confirmDeleteLocked = true }) { Text("删除全部") } } } }
        own.groupBy(DownloadEntity::groupName).forEach { (group, values) ->
            item(key = "group-$group") { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(group, Modifier.weight(1f).padding(top = 10.dp), color = Green, fontWeight = FontWeight.Bold); TextButton({ deletingGroup = group }, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("删除本组", fontSize = 12.sp) } } }
            items(values, key = { "${it.ownerAccountId}-${it.trackId}" }) { item ->
                val status = when (item.status) { "complete" -> "已完成"; "queued_wifi" -> "等待 Wi-Fi"; "queued" -> "排队中"; "downloading" -> "下载中"; "paused" -> "已暂停"; "locked" -> "等待原账号登录"; "failed_storage" -> "存储不足，需保留 256MB"; else -> "下载失败" }
                ListItem(headlineContent = { Text(item.title) }, supportingContent = { Text("$status · ${downloadProgressSummary(item.downloadedBytes, item.totalBytes)}${if (vm.accountId != item.ownerAccountId) " · 已锁定" else ""}") },
                    trailingContent = { Row { if (item.status in setOf("downloading", "queued", "queued_wifi")) IconButton({ vm.pauseDownload(item.trackId) }) { Icon(Icons.Default.Pause, null) } else if (item.status == "paused" || item.status == "locked" || item.status.startsWith("failed")) IconButton({ vm.resumeDownload(item) }, enabled = vm.accountId == item.ownerAccountId) { Icon(Icons.Default.PlayArrow, null) }; IconButton({ vm.deleteDownload(item.trackId, item.ownerAccountId) }) { Icon(Icons.Default.Delete, null) } } })
            }
        }
    }
    if (confirmDeleteLocked) AlertDialog(onDismissRequest = { confirmDeleteLocked = false }, title = { Text("删除全部锁定缓存？") }, text = { Text("将永久删除其他账号的 ${locked.size} 首离线歌曲；歌曲名称和账号信息不会显示。") }, confirmButton = { TextButton({ vm.deleteLockedDownloads(); confirmDeleteLocked = false }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ confirmDeleteLocked = false }) { Text("取消") } })
    deletingGroup?.let { group ->
        val count = own.count { it.groupName == group }
        AlertDialog(onDismissRequest = { deletingGroup = null }, title = { Text("删除“$group”？") }, text = { Text("将永久删除本组的 $count 首离线歌曲。") }, confirmButton = { TextButton({ vm.deleteDownloadGroup(group); deletingGroup = null }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ deletingGroup = null }) { Text("取消") } })
    }
}

@Composable private fun PlayerScreen(
    track: Track?, lyrics: List<LyricLine>, vm: AppViewModel,
    playMode: String, lyricSize: String, showOriginal: Boolean, showTranslation: Boolean, lyricOffset: Long,
    lyricAnimation: String, lyricAlignment: String, lowPowerPlayer: Boolean, quality: String,
    profile: UserProfile?, profileLoaded: Boolean, playlists: List<MusicCollection>, liked: Boolean,
    openQueue: () -> Unit, onBack: () -> Unit,
) {
    if (track == null) return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { IconButton(onBack, Modifier.align(Alignment.TopStart).padding(8.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }; Text("尚未播放") }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    val hasTimeline = lyrics.any { it.timeMs >= 0 }
    val active = activeLyricIndex(lyrics, position + lyricOffset)
    val (renderOriginal, renderTranslation) = lyricLayers(showOriginal, showTranslation, lyrics.any { !it.translation.isNullOrBlank() })
    val lyricSp = when (lyricSize) { "small" -> 15f; "large" -> 21f; else -> 18f }
    val centerLyrics = lyricAlignment == "center"
    val listState = rememberLazyListState()
    val lyricListDragged by listState.interactionSource.collectIsDraggedAsState()
    var manualLyricSelection by remember(track.id) { mutableStateOf(false) }
    var manualLyricInteraction by remember(track.id) { mutableIntStateOf(0) }
    var selectedLike by remember(track.id) { mutableStateOf<Boolean?>(null) }
    var showPlaylistDialog by remember(track.id) { mutableStateOf(false) }
    var showQualityDialog by remember(track.id) { mutableStateOf(false) }
    var showModeDialog by remember(track.id) { mutableStateOf(false) }
    val effectiveLiked = selectedLike ?: liked
    val centeredLyricIndex by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            lyricIndexClosestToCenter(
                layout.viewportStartOffset,
                layout.viewportEndOffset,
                layout.visibleItemsInfo.map { Triple(it.index, it.offset, it.size) },
            )
        }
    }
    val focusedLyricIndex = centeredLyricIndex.takeIf { manualLyricSelection && it >= 0 } ?: active
    val pager = rememberPagerState(initialPage = 0) { 2 }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var locked by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(locked) {
        val previous = view.keepScreenOn
        if (locked) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }
    LaunchedEffect(track.id, lowPowerPlayer) { var ticks = 0; val interval = if (lowPowerPlayer) 500L else 100L; while (true) { position = vm.playbackPosition(); duration = vm.playbackDuration(); playing = vm.isPlaying(); if (++ticks * interval >= 10_000) { ticks = 0; vm.savePlaybackState() }; delay(interval) } }
    LaunchedEffect(lyricListDragged, manualLyricInteraction) {
        if (lyricListDragged) {
            manualLyricSelection = true
        } else if (manualLyricSelection) {
            while (listState.isScrollInProgress) delay(50)
            delay(3_500)
            manualLyricSelection = false
        }
    }
    LaunchedEffect(track.id, active, lyrics.size, manualLyricSelection) {
        if (active >= 0 && lyrics.isNotEmpty() && !manualLyricSelection) {
            while (listState.layoutInfo.viewportSize.height == 0) delay(16)
            val viewport = (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset).coerceAtLeast(1)
            // A fixed, short animation keeps the current line centred without the
            // spring overshoot that used to make the list shake when playback paused.
            listState.animateScrollToItem(active, scrollOffset = -(viewport / 2 - 24).coerceAtLeast(0))
        }
    }
    Box(Modifier.fillMaxSize().focusRequester(focusRequester).focusable().onRotaryScrollEvent { event ->
        if (!locked) {
            if (pager.currentPage == 0) vm.adjustVolume(if (event.verticalScrollPixels < 0) 1 else -1)
            else {
                manualLyricSelection = true
                manualLyricInteraction++
                scope.launch { listState.scrollBy(event.verticalScrollPixels) }
            }
        }
        true
    }) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = !locked) { page ->
            if (page == 1) {
                Column(Modifier.fillMaxSize()) {
                    LyricTrackHeader(track)
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(
                            Modifier.fillMaxSize().padding(start = if (centerLyrics) 14.dp else 20.dp, end = if (centerLyrics) 14.dp else 12.dp),
                            state = listState,
                            contentPadding = PaddingValues(vertical = (maxHeight / 2 - 34.dp).coerceAtLeast(0.dp)),
                            horizontalAlignment = if (centerLyrics) Alignment.CenterHorizontally else Alignment.Start,
                        ) {
                            if (lyrics.isEmpty()) item {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("暂无歌词", color = Color.Gray, fontSize = 18.sp)
                                    TextButton(vm::reloadLyrics) { Text("重新加载") }
                                }
                            }
                            items(lyrics.size, key = { index -> "${track.id}:$index" }) { index ->
                                val line = lyrics[index]
                                val nextTime = lyrics.getOrNull(index + 1)?.timeMs ?: (line.timeMs + 4_000)
                                val distance = if (focusedLyricIndex >= 0) kotlin.math.abs(index - focusedLyricIndex) else Int.MAX_VALUE
                                val isFocused = index == focusedLyricIndex
                                val isPlaybackLine = index == active
                                val targetAlpha = if (!hasTimeline) .86f else when (distance) {
                                    0 -> 1f
                                    1 -> if (lyricAnimation == "strong") .58f else .66f
                                    2 -> if (lyricAnimation == "strong") .28f else .38f
                                    else -> if (lyricAnimation == "off") .42f else .18f
                                }
                                val motionDuration = if (lyricAnimation == "off") 0 else 180
                                val lineAlpha by animateFloatAsState(targetAlpha, tween(motionDuration), label = "lyricFade")
                                val lineScale by animateFloatAsState(if (isFocused) 1.035f else .97f, tween(motionDuration), label = "lyricFocus")
                                val lineFontSize by animateFloatAsState(
                                    if (isFocused) lyricSp + 2f else (lyricSp - 1f).coerceAtLeast(12f),
                                    tween(motionDuration), label = "lyricFontSize",
                                )
                                val karaokeProgress = if (isPlaybackLine) lyricRenderProgress(line, position + lyricOffset, nextTime) else null
                                val seek = {
                                    if (line.timeMs >= 0) {
                                        manualLyricSelection = false
                                        vm.seek((line.timeMs - lyricOffset).coerceAtLeast(0))
                                    }
                                }
                                val showTime = hasTimeline && (isPlaybackLine || (manualLyricSelection && isFocused))
                                Row(
                                    Modifier.fillMaxWidth()
                                        .graphicsLayer {
                                            alpha = lineAlpha
                                            scaleX = lineScale
                                            scaleY = lineScale
                                            transformOrigin = if (centerLyrics) TransformOrigin.Center else TransformOrigin(0f, .5f)
                                        }
                                        .then(if (line.timeMs >= 0) Modifier.clickable(onClick = seek) else Modifier)
                                        .heightIn(min = if (renderTranslation && !line.translation.isNullOrBlank()) 48.dp else 40.dp)
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        Modifier.weight(1f),
                                        horizontalAlignment = if (centerLyrics) Alignment.CenterHorizontally else Alignment.Start,
                                    ) {
                                        if (renderOriginal) SingleLineLyricText(
                                            text = line.text,
                                            modifier = Modifier.fillMaxWidth(),
                                            requestedFontSp = lineFontSize,
                                            color = if (isFocused) Color.White else Color.White.copy(alpha = .72f),
                                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                                            renderProgress = karaokeProgress,
                                            lowPower = lowPowerPlayer,
                                            centered = centerLyrics,
                                        )
                                        if (renderTranslation) line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                                            SingleLineLyricText(
                                                text = translation,
                                                modifier = Modifier.fillMaxWidth(),
                                                requestedFontSp = (lineFontSize - 4f).coerceAtLeast(11f),
                                                color = if (isFocused) Green.copy(alpha = .86f) else Color(0xFF9EB8A8),
                                                fontWeight = if (isFocused && !renderOriginal) FontWeight.Bold else FontWeight.Normal,
                                                centered = centerLyrics,
                                            )
                                        }
                                    }
                                    if (showTime) Text(
                                        lyricTime(line.timeMs),
                                        color = if (isFocused) Green else Color.Gray,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val coverSize = when {
                        maxHeight < 430.dp -> 112.dp
                        maxHeight < 520.dp -> 132.dp
                        else -> 174.dp
                    }
                    Column(
                        Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 48.dp, bottom = 34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        var dragY by remember { mutableFloatStateOf(0f) }
                        AsyncImage(
                            model = track.artworkUrl.ifBlank { null },
                            contentDescription = "歌曲封面",
                            modifier = Modifier.size(coverSize).background(Surface, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                                .pointerInput(track.id) { detectTapGestures(onDoubleTap = { if (vm.isPlaying()) vm.pausePlayback() else vm.resumePlayback() }) }
                                .pointerInput(track.id) { detectVerticalDragGestures(onDragStart = { dragY = 0f }, onDragEnd = { if (abs(dragY) > 60) vm.adjustVolume(if (dragY < 0) 1 else -1) }) { change, amount -> change.consume(); dragY += amount } },
                        )
                        Text(track.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text(track.artists.joinToString(" / "), color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        val previewIndex = active.takeIf { it >= 0 } ?: lyrics.indexOfFirst { it.timeMs >= 0 }.takeIf { it >= 0 } ?: -1
                        val preview = lyrics.getOrNull(previewIndex)?.text?.takeIf { it.isNotBlank() }
                        AnimatedContent(
                            targetState = preview.orEmpty(),
                            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp, max = 28.dp),
                            label = "playerLyricPreview",
                        ) { line ->
                            if (line.isNotBlank()) SingleLineLyricText(line, Modifier.fillMaxWidth(), 14f, Color.White.copy(alpha = .82f), centered = true) else Spacer(Modifier.height(1.dp))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(lyricTime(position), color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Slider(position.toFloat(), { vm.seek(it.toLong()); position = it.toLong() }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f).height(28.dp))
                            Text(lyricTime(duration), color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(vm::skipPrevious, Modifier.size(44.dp)) { Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(27.dp)) }
                            IconButton({ if (playing) vm.pausePlayback() else vm.resumePlayback() }, Modifier.size(54.dp)) { Icon(if (playing) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled, if (playing) "暂停" else "播放", Modifier.size(47.dp), tint = Color.White) }
                            IconButton(vm::skipNext, Modifier.size(44.dp)) { Icon(Icons.Default.SkipNext, "下一首", Modifier.size(27.dp)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            PlayerActionButton(Icons.Default.Favorite.takeIf { effectiveLiked } ?: Icons.Default.FavoriteBorder, if (effectiveLiked) "已喜欢" else "喜欢", tint = if (effectiveLiked) Color(0xFFFF718B) else Color.White) {
                                selectedLike = !effectiveLiked
                                vm.like(track, !effectiveLiked)
                            }
                            PlayerActionButton(Icons.AutoMirrored.Filled.PlaylistAdd, "加歌单") { showPlaylistDialog = true }
                            PlayerActionButton(Icons.Default.Tune, qualityOptionShortName(quality), tint = Green) { showQualityDialog = true }
                            PlayerActionButton(playModeIcon(playMode), playModeName(playMode), tint = Green) { showModeDialog = true }
                            PlayerActionButton(Icons.AutoMirrored.Filled.QueueMusic, "队列") { openQueue() }
                        }
                    }
                }
            }
        }
        IconButton(onBack, Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = .35f), RoundedCornerShape(20.dp))) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        if (!locked) IconButton({ locked = true }, Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = .35f), RoundedCornerShape(20.dp))) { Icon(Icons.Default.LockOpen, "锁定触控") }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) { page -> Box(Modifier.size(if (pager.currentPage == page) 7.dp else 5.dp).background(if (pager.currentPage == page) Green else Color.Gray, RoundedCornerShape(50))) }
        }
        if (locked) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} })
            IconButton({ locked = false }, Modifier.align(Alignment.TopEnd).padding(8.dp).background(Surface, RoundedCornerShape(20.dp))) { Icon(Icons.Default.Lock, "解除锁定", tint = Green) }
        }
    }
    if (showPlaylistDialog) PlayerPlaylistDialog(track, playlists, vm) { showPlaylistDialog = false }
    if (showQualityDialog) QualityDialog(track, quality, profile, profileLoaded, vm) { showQualityDialog = false }
    if (showModeDialog) PlayModeDialog(playMode, vm) { showModeDialog = false }
}

@Composable private fun LyricTrackHeader(track: Track) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 7.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.artworkUrl.ifBlank { null },
            contentDescription = "歌曲封面",
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Surface),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(track.artists.joinToString(" / "), track.album).filter { it.isNotBlank() }.joinToString(" · "),
                color = Color.Gray,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.requiresVip) Text("VIP", color = Color(0xFFFFC857), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun PlayerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(
        Modifier.widthIn(min = 48.dp).height(48.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, Modifier.size(21.dp), tint = tint)
        Text(label, color = Color.Gray, fontSize = 9.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

private fun qualityOptionShortName(value: String): String = when (value) {
    "320" -> "320k"
    "flac" -> "无损"
    "hires", "hi-res" -> "Hi-Res"
    "master" -> "臻品"
    else -> "128k"
}

private fun qualityOptionLabel(value: String): String = when (value) {
    "320" -> "高品质 320k"
    "flac" -> "无损 FLAC"
    "hires", "hi-res" -> "Hi-Res"
    "master" -> "臻品母带"
    else -> "标准 128k"
}

private fun qualityOptionRank(value: String): Int = when (value) {
    "128" -> 0
    "320" -> 1
    "flac" -> 2
    "hires", "hi-res" -> 3
    "master" -> 4
    else -> 5
}

@Composable private fun QualityDialog(
    track: Track,
    selectedQuality: String,
    profile: UserProfile?,
    profileLoaded: Boolean,
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    val options = (listOf("128", "320") + track.qualities).distinct().sortedBy(::qualityOptionRank)
    val rights = when {
        !profileLoaded -> "会员权益尚未确认，登录检查后可解锁对应音质"
        profile?.isVipActive() == true -> "${profile.vipName.ifBlank { "VIP 会员" }} · 可用音质以歌曲实际返回为准"
        else -> "普通账号 · VIP 音质会显示为不可用"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择音质", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(rights, color = Color.Gray, fontSize = 12.sp)
                options.forEach { option ->
                    val songSupports = option == "128" || option in track.qualities
                    val membershipRequired = option != "128" || track.requiresVip
                    val membershipKnown = profileLoaded && profile?.isVipActive() == true
                    val enabled = songSupports && (!membershipRequired || membershipKnown)
                    val reason = when {
                        !songSupports -> "歌曲未提供"
                        membershipRequired && !profileLoaded -> "待确认会员"
                        membershipRequired && !membershipKnown -> "需要 VIP"
                        else -> "可用"
                    }
                    Surface(
                        onClick = { if (enabled) { vm.setQuality(option); onDismiss() } },
                        enabled = enabled,
                        shape = RoundedCornerShape(12.dp),
                        color = if (selectedQuality == option) Green.copy(alpha = .14f) else Surface,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selectedQuality == option, onClick = null, enabled = enabled)
                            Column(Modifier.weight(1f)) {
                                Text(qualityOptionLabel(option), color = if (enabled) Color.White else Color.Gray, fontSize = 14.sp)
                                Text(reason, color = if (enabled) Color.Gray else Color(0xFFFFC857), fontSize = 11.sp)
                            }
                            if (option == selectedQuality) Text("当前", color = Green, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("关闭") } },
    )
}

@Composable private fun PlayModeDialog(mode: String, vm: AppViewModel, onDismiss: () -> Unit) {
    val modes = listOf("sequential", "loop_all", "repeat_one", "shuffle")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放顺序") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                modes.forEach { value ->
                    Surface(
                        onClick = { vm.setPlayMode(value); onDismiss() },
                        shape = RoundedCornerShape(12.dp),
                        color = if (mode == value) Green.copy(alpha = .14f) else Surface,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(playModeIcon(value), null, Modifier.size(20.dp), tint = if (mode == value) Green else Color.Gray)
                            Spacer(Modifier.width(10.dp))
                            Text(playModeName(value), Modifier.weight(1f), fontSize = 14.sp)
                            RadioButton(mode == value, onClick = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("关闭") } },
    )
}

@Composable private fun PlayerPlaylistDialog(track: Track, playlists: List<MusicCollection>, vm: AppViewModel, onDismiss: () -> Unit) {
    val candidates = playlists.filter { it.directoryId != "201" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入哪个歌单？") },
        text = {
            if (candidates.isEmpty()) {
                Text("暂无可编辑歌单，请先在“我的”中创建歌单。", color = Color.Gray)
            } else {
                LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(candidates, key = { "player:${it.directoryId}" }) { playlist ->
                        Surface(
                            onClick = { vm.addToPlaylist(track, playlist.directoryId); onDismiss() },
                            shape = RoundedCornerShape(12.dp),
                            color = Surface,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(19.dp), tint = Green)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                    if (playlist.trackCount >= 0) Text("${playlist.trackCount} 首", color = Color.Gray, fontSize = 11.sp)
                                }
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = Color.Gray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable private fun DetailScreen(detail: CollectionDetail?, editableDirectoryId: String?, playlists: List<MusicCollection>, vm: AppViewModel, onBack: () -> Unit) = LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
    item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }; Text(detail?.title ?: "加载中", Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); if (detail != null) TextButton({ vm.cacheAll(detail.tracks, detail.title) }) { Text("全部缓存") } } }
    items(detail?.tracks.orEmpty(), key = { it.id }) { TrackRow(it, vm, playlistId = editableDirectoryId, removeFromPlaylist = editableDirectoryId != null, queue = detail?.tracks.orEmpty(), playlists = playlists) }
}

@Composable private fun SettingsModule(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, open: () -> Unit) = Surface(
    Modifier.fillMaxWidth().clickable(onClick = open), shape = RoundedCornerShape(16.dp), color = Surface,
) { ListItem(
    modifier = Modifier.heightIn(min = 66.dp),
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    headlineContent = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    supportingContent = { Text(subtitle, color = Color.Gray, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    leadingContent = { Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF252A28)) { Icon(icon, null, Modifier.padding(10.dp).size(21.dp), tint = Color.White) } },
    trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(20.dp)) },
) }

@Composable private fun SettingsGroup(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) = Surface(
    Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Surface,
) {
    Column {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, color = Color.Gray, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        HorizontalDivider(color = Color.White.copy(alpha = .06f))
        content()
    }
}

@Composable private fun SettingsSwitchRow(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 13.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f).padding(end = 8.dp)) {
        Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, color = Color.Gray, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
    Switch(checked, onCheckedChange, modifier = Modifier.width(52.dp).height(32.dp))
}

@Composable private fun SettingsChoiceBlock(title: String, content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 8.dp),
) {
    Text(title, color = Color.Gray, fontSize = 12.sp)
    Spacer(Modifier.height(5.dp))
    content()
}

@Composable private fun SettingsHeader(title: String, onBack: () -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }; Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold)
}

@Composable private fun SettingsCenter(nav: NavHostController, onBack: () -> Unit) = LazyColumn(
    Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    item { SettingsHeader("设置中心", onBack) }
    item { SettingsModule("显示与主题", "主题、歌词、字号与界面显示", Icons.Default.Palette) { nav.navigate("settings/display") } }
    item { SettingsModule("播放与缓存", "音质、播放模式、耳机与定时关闭", Icons.Default.PlayCircle) { nav.navigate("settings/playback") } }
    item { SettingsModule("内容与网络", "每日推荐、账号、诊断与日志", Icons.Default.Language) { nav.navigate("settings/network") } }
    item { SettingsModule("关于", "${BuildConfig.VERSION_NAME} · 开发者 Ronan", Icons.Default.Info) { nav.navigate("settings/about") } }
}

@Composable private fun DisplaySettingsScreen(vm: AppViewModel, lyricSize: String, lyricOriginal: Boolean, lyricTranslation: Boolean, lyricOffset: Long, lyricAnimation: String, lyricAlignment: String, pureBlack: Boolean, lowPowerPlayer: Boolean, onBack: () -> Unit) = LazyColumn(
    Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
) {
    item { SettingsHeader("显示与主题", onBack) }
    item { SettingsGroup("屏幕", "为方屏手表保留清晰度和续航平衡") {
        SettingsSwitchRow("AMOLED 纯黑背景", "减少息屏播放器的耗电", pureBlack, vm::setPureBlack)
        SettingsSwitchRow("低功耗播放器", "降低进度刷新频率并缩小封面", lowPowerPlayer, vm::setLowPowerPlayer)
    } }
    item { SettingsGroup("歌词", "左滑进入歌词；点击任意有时间轴的句子跳转") {
        SettingsChoiceBlock("对齐方式") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("left" to "靠左", "center" to "居中").forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = lyricAlignment == value,
                        onClick = { vm.setLyricAlignment(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        icon = { Icon(if (value == "left") Icons.AutoMirrored.Filled.FormatAlignLeft else Icons.Default.FormatAlignCenter, null, Modifier.size(16.dp)) },
                    ) { Text(label, fontSize = 12.sp) }
                }
            }
        }
        SettingsChoiceBlock("字号") {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("small" to "小", "normal" to "标准", "large" to "大").forEach { (value, label) -> FilterChip(lyricSize == value, { vm.setLyricSize(value) }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.height(32.dp)) } }
        }
        SettingsSwitchRow("显示原文歌词", checked = lyricOriginal, onCheckedChange = { if (it || lyricTranslation) vm.setLyricOriginal(it) })
        SettingsSwitchRow("显示翻译歌词", "没有翻译时自动隐藏", checked = lyricTranslation, onCheckedChange = { if (it || lyricOriginal) vm.setLyricTranslation(it) })
        SettingsChoiceBlock("动效") {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("off" to "关闭", "soft" to "柔和", "strong" to "明显").forEach { (value, label) -> FilterChip(lyricAnimation == value, { vm.setLyricAnimation(value) }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.height(32.dp)) } }
        }
        SettingsChoiceBlock("时间偏移 ${if (lyricOffset >= 0) "+" else ""}${lyricOffset}ms") {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { TextButton({ vm.setLyricOffset(lyricOffset - 500) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("-0.5秒", fontSize = 12.sp) }; TextButton({ vm.setLyricOffset(0) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("归零", fontSize = 12.sp) }; TextButton({ vm.setLyricOffset(lyricOffset + 500) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+0.5秒", fontSize = 12.sp) } }
        }
    } }
}

@Composable private fun PlaybackSettingsScreen(vm: AppViewModel, quality: String, profile: UserProfile?, profileLoaded: Boolean, headphoneWarning: Boolean, autoOpenPlayer: Boolean, playMode: String, sleepRemaining: Long, wifiOnlyDownload: Boolean, lastSleepMinutes: Int?, onBack: () -> Unit) {
    val context = LocalContext.current
    var customTimer by remember { mutableStateOf(false) }; var customMinutes by remember { mutableStateOf("") }; var finishCurrent by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SettingsHeader("播放与缓存", onBack) }
        item { SettingsGroup("音质", "与账号权益和歌曲可用资源同步") {
            SettingsChoiceBlock("默认音质") {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { FilterChip(quality == "128", { vm.setQuality("128") }, label = { Text("标准 128k", fontSize = 12.sp) }, modifier = Modifier.height(32.dp)); FilterChip(quality == "320", { vm.setQuality("320") }, label = { Text("高品 320k", fontSize = 12.sp) }, modifier = Modifier.height(32.dp)) }
                val rightsText = when { !profileLoaded -> "会员权益尚未确认，点内容与网络中的检查按钮"; profile?.isVipActive() == true -> "${profile.vipName.ifBlank { "会员" }} 可使用高品，歌曲仍以实际返回为准"; else -> "普通账号仅保证标准 128k；VIP 歌曲会标记为不可播放" }
                Text(rightsText, color = Color.Gray, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } }
        item { SettingsGroup("播放", "手表按键和耳机断开行为") {
            SettingsSwitchRow("无耳机播放提醒", "未连接耳机时播放前确认", headphoneWarning, vm::setHeadphoneWarning)
            SettingsSwitchRow("自动进入播放器", "点歌后直接打开播放器页面", autoOpenPlayer, vm::setAutoOpenPlayer)
            SettingsChoiceBlock("播放顺序") { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(playModeIcon(playMode), null, tint = Green, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(playModeName(playMode), Modifier.weight(1f), fontSize = 14.sp); TextButton({ vm.setPlayMode(nextPlayMode(playMode)) }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("切换", fontSize = 12.sp) } } }
        } }
        item { SettingsGroup("定时关闭", "播放一段时间后自动停止") {
            if (sleepRemaining > 0) SettingsChoiceBlock("剩余 ${sleepRemaining / 60}:${(sleepRemaining % 60).toString().padStart(2, '0')}") { Text("计时器运行中", color = Green, fontSize = 12.sp) }
            SettingsChoiceBlock("常用时长") { FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { (listOfNotNull(lastSleepMinutes) + listOf(15, 30, 60)).distinct().take(4).forEach { minutes -> FilterChip(false, { vm.startSleepTimer(minutes, finishCurrent) }, label = { Text(if (minutes == lastSleepMinutes) "上次${minutes}分" else "${minutes}分", fontSize = 12.sp) }, modifier = Modifier.height(32.dp)) } } }
            SettingsSwitchRow("播完当前歌曲再关闭", checked = finishCurrent, onCheckedChange = { finishCurrent = it })
            Row(Modifier.padding(horizontal = 13.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) { TextButton({ customTimer = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("自定义", fontSize = 12.sp) }; if (sleepRemaining > 0) TextButton(vm::cancelSleepTimer, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("取消", fontSize = 12.sp) } }
        } }
        item { SettingsGroup("设备与下载", "蓝牙连接交给 Android 系统管理") {
            SettingsSwitchRow("仅 Wi-Fi 下载", "关闭后允许移动网络缓存", wifiOnlyDownload, vm::setWifiOnlyDownload)
            OutlinedButton({ context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 7.dp)) { Icon(Icons.Default.Bluetooth, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("打开蓝牙设置", fontSize = 13.sp) }
        } }
    }
    if (customTimer) AlertDialog(onDismissRequest = { customTimer = false }, title = { Text("自定义播放时间") }, text = { OutlinedTextField(customMinutes, { customMinutes = it.filter(Char::isDigit).take(4) }, label = { Text("分钟（1-1440）") }, singleLine = true) }, confirmButton = { TextButton({ customMinutes.toIntOrNull()?.coerceIn(1, 1440)?.let { vm.startSleepTimer(it, finishCurrent) }; customTimer = false }) { Text("开始") } }, dismissButton = { TextButton({ customTimer = false }) { Text("取消") } })
}

@Composable private fun NetworkSettingsScreen(vm: AppViewModel, dailyCount: Int, state: AppUiState, onAnnouncements: () -> Unit, onRelogin: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var confirmUpload by remember { mutableStateOf(false) }
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { runCatching { AppLog.copyTo(context, it) } } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SettingsHeader("内容与网络", onBack) }
        item { SettingsGroup("推荐", "控制首页每日推荐的密度") {
            SettingsChoiceBlock("每日推荐显示数量") { Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { FilterChip(dailyCount == 5, { vm.setDailyCount(5) }, label = { Text("5 首", fontSize = 12.sp) }, modifier = Modifier.height(32.dp)); FilterChip(dailyCount == 10, { vm.setDailyCount(10) }, label = { Text("10 首", fontSize = 12.sp) }, modifier = Modifier.height(32.dp)) } }
        } }
        if (vm.signedIn) item { SettingsGroup("账号与权益", "登录方式、会员状态和歌单同步") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                val avatar = remember(state.profile?.avatarUrl) { state.profile?.avatarUrl?.takeIf(String::isNotBlank)?.let { ImageRequest.Builder(context).data(it).addHeader("Referer", "https://y.qq.com/").build() } }
                AsyncImage(avatar, "账号头像", Modifier.size(42.dp).clip(RoundedCornerShape(50)).background(Color.DarkGray), fallback = androidx.compose.ui.res.painterResource(com.ronan.qmusicwatch.R.drawable.ic_launcher))
                Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) {
                    Text(state.profile?.displayName?.ifBlank { null } ?: "${loginProviderName(vm.loginProvider)}音乐用户", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(accountLabel(vm.loginProvider, vm.accountId), color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(vipSummary(state.profile, state.profileLoaded, state.profileError), color = if (state.profile?.isVipActive() == true) Color(0xFFFFC857) else Color.Gray, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedButton(vm::refreshMembership, Modifier.weight(1f), enabled = !state.profileLoaded || state.profileError != null || state.profile != null) { Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("刷新权益", fontSize = 12.sp) }
                OutlinedButton(vm::diagnose, Modifier.weight(1f)) { Icon(Icons.Default.HealthAndSafety, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("检查登录", fontSize = 12.sp) }
                OutlinedButton(onRelogin, Modifier.weight(1f)) { Text("重新登录", fontSize = 12.sp) }
            }
        } }
        state.diagnostic?.let { item { Text(it, color = if (it.startsWith("诊断失败")) MaterialTheme.colorScheme.error else Green, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp)) } }
        item { SettingsGroup("服务与公告", "远程配置不可用时继续使用本地音乐功能") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (state.controlError == null) Icons.Default.CloudDone else Icons.Default.CloudOff, null, tint = if (state.controlError == null) Green else Color.Gray, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) {
                    Text(when { state.controlError != null -> "正在使用本地配置"; state.controlFetchedAt > 0 -> "服务配置已同步"; else -> "服务尚未同步" }, fontSize = 14.sp)
                    Text(state.controlError?.take(100) ?: state.controlFetchedAt.takeIf { it > 0 }?.let { "上次同步 ${android.text.format.DateFormat.format("MM-dd HH:mm", it)}" } ?: "尚未同步", color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ vm.refreshControlPlane() }, enabled = !state.controlRefreshing, modifier = Modifier.size(38.dp)) { if (state.controlRefreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, "刷新", modifier = Modifier.size(19.dp)) }
            }
            Row(Modifier.fillMaxWidth().clickable(onClick = onAnnouncements).padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Campaign, null, tint = Green, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("公告", fontSize = 14.sp); Text("${state.announcements.size} 条可用公告", color = Color.Gray, fontSize = 11.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(19.dp))
            }
        } }
        item { SettingsGroup("诊断与日志", "日志会脱敏，不记录 Cookie、令牌或播放 URL") {
            OutlinedButton({ confirmUpload = true }, Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 7.dp), enabled = state.diagnosticUploadState !is DiagnosticUploadState.Uploading && vm.featureEnabled("diagnostics")) { Icon(Icons.Default.BugReport, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (state.diagnosticUploadState is DiagnosticUploadState.Uploading) "正在提交" else "提交脱敏诊断", fontSize = 13.sp) }
            when (val upload = state.diagnosticUploadState) {
                is DiagnosticUploadState.Success -> Text("诊断已提交${upload.requestId.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", color = Green, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 4.dp))
                is DiagnosticUploadState.Error -> Text(upload.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 4.dp))
                else -> Unit
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedButton({ context.startActivity(Intent.createChooser(AppLog.shareIntent(context), "分享日志")) }, Modifier.weight(1f)) { Icon(Icons.Default.Share, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("分享", fontSize = 12.sp) }
                OutlinedButton({ saveLog.launch("QMusicWatch-${BuildConfig.VERSION_NAME}.log") }, Modifier.weight(1f)) { Icon(Icons.Default.Save, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("保存", fontSize = 12.sp) }
                TextButton(AppLog::clear, Modifier.weight(1f)) { Text("清空", fontSize = 12.sp) }
            }
        } }
        if (vm.signedIn) item { OutlinedButton(vm::logout, Modifier.fillMaxWidth()) { Text("退出登录", fontSize = 13.sp) } }
    }
    if (confirmUpload) AlertDialog(
        onDismissRequest = { confirmUpload = false },
        title = { Text("提交诊断？") },
        text = { Text("仅上传版本、设备型号和经过二次脱敏的日志片段，不包含账号、Cookie、二维码、搜索词或播放地址。") },
        confirmButton = { TextButton({ confirmUpload = false; vm.submitDiagnostics() }) { Text("提交") } },
        dismissButton = { TextButton({ confirmUpload = false }) { Text("取消") } },
    )
}

@Composable private fun AnnouncementsScreen(items: List<ControlAnnouncement>, seen: Set<String>, vm: AppViewModel, onBack: () -> Unit) {
    LaunchedEffect(items.map(ControlAnnouncement::id)) { items.forEach { vm.markAnnouncementSeen(it.id) } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { SettingsHeader("公告", onBack) }
        if (items.isEmpty()) item { Box(Modifier.fillParentMaxHeight(.7f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("暂无公告", color = Color.Gray) } }
        items(items, key = ControlAnnouncement::id) { announcement ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Surface) {
                Column(Modifier.padding(12.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(announcement.title.take(80), Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); if (announcement.pinned) Icon(Icons.Default.PushPin, "置顶", Modifier.size(16.dp), tint = Green) }
                    Text(announcement.content.take(2000), color = Color.LightGray, fontSize = 14.sp)
                    if (announcement.id !in seen) Text("新公告", color = Green, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable private fun AboutScreen(vm: AppViewModel, update: UpdateUiState, onBack: () -> Unit) {
    val context = LocalContext.current
    var permissionHint by remember { mutableStateOf(false) }
    var externalError by rememberSaveable { mutableStateOf<String?>(null) }
    val openExternal: (Intent, String) -> Unit = { intent, failureMessage ->
        runCatching { context.startActivity(intent) }
            .onSuccess { externalError = null }
            .onFailure { error -> AppLog.write("INTENT", "${error.javaClass.simpleName}:${error.message.orEmpty()}"); externalError = failureMessage }
    }
    val install: (ControlUpdate, String) -> Unit = { release, path ->
        if (!UpdateInstaller.canInstallPackages(context)) {
            permissionHint = true
            openExternal(UpdateInstaller.permissionIntent(context), "系统没有可用的未知来源安装设置入口")
        } else vm.prepareUpdateInstall(release, path) { verified ->
            openExternal(UpdateInstaller.installIntent(context, verified), "系统没有可用的 APK 安装器")
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { SettingsHeader("关于", onBack) }
    item { ListItem(headlineContent = { Text("QMusic Watch") }, supportingContent = { Text("版本 ${BuildConfig.VERSION_NAME}\n第三方非官方、开源、非商业客户端") }) }
    item { ListItem(headlineContent = { Text("开发者") }, supportingContent = { Text("Ronan") }, leadingContent = { Icon(Icons.Default.Code, null, tint = Green) }) }
    when (update) {
        UpdateUiState.Idle -> item { OutlinedButton(vm::checkForUpdate, Modifier.fillMaxWidth()) { Icon(Icons.Default.SystemUpdate, null); Spacer(Modifier.width(7.dp)); Text("检查服务器更新") } }
        UpdateUiState.Checking -> item { OutlinedButton({}, Modifier.fillMaxWidth(), enabled = false) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(7.dp)); Text("正在检查") } }
        UpdateUiState.NoUpdate -> { item { ListItem(headlineContent = { Text("当前已是最新版本") }, leadingContent = { Icon(Icons.Default.Verified, null, tint = Green) }) }; item { TextButton(vm::checkForUpdate, Modifier.fillMaxWidth()) { Text("重新检查") } } }
        is UpdateUiState.Available -> {
            item { ListItem(headlineContent = { Text("发现 ${update.release.versionName}${if (update.release.forceUpdate) " · 重要更新" else ""}") }, supportingContent = { Text("${update.release.title}\n${update.release.changelog.take(400)}\n${formatFileSize(update.release.apk.sizeBytes)}") }, leadingContent = { Icon(Icons.Default.NewReleases, null, tint = Green) }) }
            item { Button({ vm.downloadUpdate(update.release) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("下载并校验") } }
        }
        is UpdateUiState.Downloading -> { item { Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text("正在下载 ${formatFileSize(update.downloadedBytes)} / ${formatFileSize(update.totalBytes)}"); LinearProgressIndicator(progress = { if (update.totalBytes > 0) update.downloadedBytes.toFloat() / update.totalBytes else 0f }, modifier = Modifier.fillMaxWidth()) } } }
        is UpdateUiState.Verifying -> item { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("正在校验安装包") } }
        is UpdateUiState.Ready -> {
            item { ListItem(headlineContent = { Text("安装包校验通过") }, supportingContent = { Text("${update.release.versionName} · 签名、包名和哈希均一致") }, leadingContent = { Icon(Icons.Default.Verified, null, tint = Green) }) }
            item { Button({ install(update.release, update.filePath) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.InstallMobile, null); Spacer(Modifier.width(6.dp)); Text("打开系统安装器") } }
            if (permissionHint) item { Text("请允许 QMusic Watch 安装未知来源应用，返回后再次点安装。", color = Color(0xFFFFC857), fontSize = 13.sp) }
        }
        is UpdateUiState.Error -> {
            item { Text(update.message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
            item { OutlinedButton({ update.release?.let(vm::downloadUpdate) ?: vm.checkForUpdate() }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("重试") } }
        }
    }
    externalError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
    item { TextButton({ openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/huanghao897/QMusicWatch/releases")), "系统没有可用的浏览器") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.OpenInBrowser, null); Spacer(Modifier.width(6.dp)); Text("手动打开 GitHub 发布页") } }
    item { Text("本项目与腾讯或 QQ 音乐无隶属、赞助或认可关系。不绕过会员、地区、付费或 DRM 限制。", color = Color.Gray) }
    item { Text("开源与致谢", fontWeight = FontWeight.Bold); Text("QQMusicApi（GPL-3.0，未复制代码）\nQQmusic-API（Apache-2.0，协议实现参考）\nTides-WearOS（GPL-3.0，未复制代码）\nHorologist（Apache-2.0）\nHeyWear（MIT）", color = Color.Gray, fontSize = 14.sp) }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    else -> "${bytes / 1024} KB"
}

@Composable private fun TrackRow(track: Track, vm: AppViewModel, liked: Boolean = false, playlistId: String? = null, removeFromPlaylist: Boolean = false, queue: List<Track> = listOf(track), playlists: List<MusicCollection> = emptyList()) {
    var menu by remember { mutableStateOf(false) }
    var choosePlaylist by remember { mutableStateOf(false) }
    ListItem(modifier = Modifier.clickable { vm.requestPlay(track, sourceQueue = queue) }, headlineContent = { Row(verticalAlignment = Alignment.CenterVertically) { Text(track.title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis); if (track.requiresVip) { Spacer(Modifier.width(5.dp)); Text("VIP", color = Color(0xFFFFC857), fontSize = 11.sp, fontWeight = FontWeight.Bold) } } }, supportingContent = { Text(track.artists.joinToString(" / "), maxLines = 1) },
        leadingContent = { AsyncImage(track.artworkUrl.ifBlank { null }, null, Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(Color.DarkGray)) },
        trailingContent = { Row { IconButton({ vm.cache(track) }, Modifier.size(38.dp)) { Icon(Icons.Default.Download, null, Modifier.size(21.dp)) }; IconButton({ vm.like(track, !liked) }, Modifier.size(38.dp)) { Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, Modifier.size(21.dp), tint = if (liked) Green else LocalContentColor.current) }; Box { IconButton({ menu = true }, Modifier.size(38.dp)) { Icon(Icons.Default.MoreVert, "更多", Modifier.size(21.dp)) }; DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("下一首播放") }, { vm.enqueueNext(track); menu = false }); DropdownMenuItem({ Text("添加到播放列表") }, { vm.addToQueue(track); menu = false }); if (playlists.isNotEmpty()) DropdownMenuItem({ Text("加入我的歌单") }, { menu = false; choosePlaylist = true }); if (removeFromPlaylist && playlistId != null) DropdownMenuItem({ Text("从此歌单移除") }, { vm.removeFromPlaylist(track, playlistId); menu = false }) } } } })
    if (choosePlaylist) AlertDialog(onDismissRequest = { choosePlaylist = false }, title = { Text("加入哪个歌单？") }, text = { LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { items(playlists.filter { it.owned != false && it.directoryId != "201" }, key = { it.directoryId }) { playlist -> Surface(Modifier.fillMaxWidth().clickable { vm.addToPlaylist(track, playlist.directoryId); choosePlaylist = false }, shape = RoundedCornerShape(14.dp), color = Surface) { Column(Modifier.padding(12.dp, 9.dp)) { Text(playlist.title, maxLines = 1); Text(if (playlist.trackCount >= 0) "${playlist.trackCount} 首" else "我的歌单", color = Color.Gray, fontSize = 12.sp) } } } } }, confirmButton = {}, dismissButton = { TextButton({ choosePlaylist = false }) { Text("取消") } })
}

@Composable private fun QueueScreen(queue: List<Track>, currentIndex: Int, reversed: Boolean, state: AppUiState, vm: AppViewModel, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var saveDialog by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }
    var playlistTitle by remember { mutableStateOf("") }
    var workingQueue by remember { mutableStateOf(queue) }
    var draggingTrackId by remember { mutableStateOf<String?>(null) }
    val selectedIds = remember { mutableStateListOf<String>() }
    val library = state.library
    LaunchedEffect(state.queueImportTitle) { selectedIds.clear() }
    LaunchedEffect(queue, draggingTrackId) { if (draggingTrackId == null) workingQueue = queue }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val edgePx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val currentTrackId = state.currentTrack?.id ?: queue.getOrNull(currentIndex)?.id
    val shown = remember(workingQueue, query) { workingQueue.withIndex().filter { query.isBlank() || it.value.title.contains(query, true) || it.value.artists.any { artist -> artist.contains(query, true) } } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState, contentPadding = PaddingValues(bottom = 18.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }; Text("当前播放列表", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold); TextButton(vm::reverseQueue) { Icon(if (reversed) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null); Text(if (reversed) "倒序" else "正序") } } }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().height(48.dp), singleLine = true, shape = RoundedCornerShape(19.dp), colors = watchSearchColors(), placeholder = { Text("筛选播放列表", fontSize = 13.sp, color = Color.Gray) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp), leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFFB6BFBA), modifier = Modifier.size(19.dp)) }) }
        item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.spacedBy(0.dp)) { TextButton({ vm.cacheAll(queue, "当前播放列表") }, contentPadding = PaddingValues(horizontal = 7.dp)) { Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Text("缓存", fontSize = 13.sp) }; TextButton({ vm.clearQueueImport(); importDialog = true }, contentPadding = PaddingValues(horizontal = 7.dp)) { Icon(Icons.Default.LibraryAdd, null, Modifier.size(18.dp)); Text("选歌添加", fontSize = 13.sp) }; TextButton({ saveDialog = true }, contentPadding = PaddingValues(horizontal = 7.dp)) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(18.dp)); Text("保存", fontSize = 13.sp) }; TextButton(vm::removeQueueDuplicates, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("去重", fontSize = 13.sp) }; TextButton(vm::clearQueue, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("清空", fontSize = 13.sp) } } }
        item { Text("${workingQueue.size} 首", color = Color.Gray) }
        if (workingQueue.isEmpty()) item { Box(Modifier.fillParentMaxHeight(.7f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("播放列表为空", color = Color.Gray) } }
        itemsIndexed(shown, key = { _, item -> item.value.id }) { _, indexed ->
            val index = indexed.index; val track = indexed.value
            var dragged by remember(track.id) { mutableFloatStateOf(0f) }
            val dragging = draggingTrackId == track.id
            var rowHeightPx by remember(track.id) { mutableIntStateOf(1) }
            var handleTopInWindow by remember(track.id) { mutableFloatStateOf(0f) }
            var edgeScrollDirection by remember(track.id) { mutableIntStateOf(0) }
            var dragIndex by remember(track.id) { mutableIntStateOf(index) }
            var dragStartIndex by remember(track.id) { mutableIntStateOf(index) }
            val reorderByOffset: () -> Unit = {
                if (query.isBlank() && rowHeightPx > 1) {
                    var keepMoving = true
                    while (keepMoving) {
                        val step = queueReorderStep(dragged, rowHeightPx)
                        val target = (dragIndex + step).coerceIn(workingQueue.indices)
                        if (step == 0 || target == dragIndex) keepMoving = false else {
                            workingQueue = moveQueuePreview(workingQueue, dragIndex, target)
                            dragIndex = target
                            dragged -= step * rowHeightPx
                        }
                    }
                }
            }
            LaunchedEffect(dragging, edgeScrollDirection, rowHeightPx) {
                while (dragging && edgeScrollDirection != 0) {
                    val consumed = listState.scrollBy(edgeScrollDirection * rowHeightPx * .12f)
                    dragged += consumed
                    reorderByOffset()
                    if (consumed == 0f) edgeScrollDirection = 0
                    delay(16)
                }
            }
            val handleModifier = Modifier.size(44.dp).padding(9.dp).onGloballyPositioned { handleTopInWindow = it.positionInWindow().y }.then(
                if (query.isBlank()) Modifier.pointerInput(track.id, rowHeightPx, view.height) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); workingQueue = queue; dragIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0); dragStartIndex = dragIndex; draggingTrackId = track.id; dragged = 0f },
                        onDragCancel = { edgeScrollDirection = 0; workingQueue = queue; draggingTrackId = null; dragged = 0f },
                        onDragEnd = { if (dragIndex != dragStartIndex) haptics.performHapticFeedback(HapticFeedbackType.LongPress); edgeScrollDirection = 0; vm.replaceQueueOrder(workingQueue); draggingTrackId = null; dragged = 0f },
                    ) { change, amount ->
                        change.consume(); dragged += amount.y; reorderByOffset()
                        edgeScrollDirection = queueEdgeScrollDirection(handleTopInWindow + change.position.y, view.height, edgePx)
                    }
                } else Modifier.alpha(.28f)
            )
            Surface(
                modifier = Modifier.padding(vertical = 3.dp).animateItem(
                    fadeInSpec = androidx.compose.animation.core.tween(180),
                    placementSpec = if (dragging) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                    fadeOutSpec = androidx.compose.animation.core.tween(160),
                ).zIndex(if (dragging) 2f else 0f).graphicsLayer {
                    translationY = dragged; scaleX = if (dragging) 1.025f else 1f; scaleY = if (dragging) 1.025f else 1f
                    shadowElevation = if (dragging) 12.dp.toPx() else 0f
                }.onSizeChanged { rowHeightPx = it.height },
                shape = RoundedCornerShape(18.dp), color = if (track.id == currentTrackId) Color(0xFF15261D) else Surface,
            ) {
                Row(Modifier.fillMaxWidth().clickable { vm.playQueueItem(queue.indexOfFirst { it.id == track.id }) }.padding(start = 11.dp, end = 3.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { if (track.id == currentTrackId) Icon(Icons.Default.GraphicEq, null, tint = Green, modifier = Modifier.size(21.dp)) else Text("${index + 1}", color = Color.Gray, fontSize = 13.sp) }
                    Spacer(Modifier.width(7.dp)); Column(Modifier.weight(1f)) { Text(track.title, color = if (track.id == currentTrackId) Green else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp); Text(track.artists.joinToString(" / "), color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) }
                    Icon(Icons.Default.DragHandle, if (query.isBlank()) "长按拖动排序" else "筛选时不可排序", handleModifier)
                    IconButton({ vm.removeFromQueue(queue.indexOfFirst { it.id == track.id }) }, Modifier.size(44.dp)) { Icon(Icons.Default.RemoveCircleOutline, "移除", Modifier.size(21.dp)) }
                }
            }
        }
    }
    if (saveDialog) AlertDialog(onDismissRequest = { saveDialog = false }, title = { Text("保存为我的歌单") }, text = { OutlinedTextField(playlistTitle, { playlistTitle = it.take(50) }, label = { Text("歌单名称") }, singleLine = true) }, confirmButton = { TextButton({ if (playlistTitle.isNotBlank()) vm.saveQueueAsPlaylist(playlistTitle); saveDialog = false }) { Text("保存") } }, dismissButton = { TextButton({ saveDialog = false }) { Text("取消") } })
    if (importDialog) AlertDialog(
        onDismissRequest = { importDialog = false; vm.clearQueueImport() },
        title = { Text(state.queueImportTitle.ifBlank { "选择歌曲来源" }) },
        text = {
            when {
                state.queueImportTitle.isBlank() -> LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    item { Surface(Modifier.fillMaxWidth().clickable { vm.loadQueueImportLiked() }, shape = RoundedCornerShape(14.dp), color = Surface) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Favorite, null, tint = Green); Spacer(Modifier.width(9.dp)); Column { Text("我喜欢"); Text("${library?.liked?.size ?: 0} 首", color = Color.Gray, fontSize = 13.sp) } } } }
                    items(library?.playlists.orEmpty(), key = { "${it.directoryId}:${it.id}" }) { playlist -> Surface(Modifier.fillMaxWidth().clickable { vm.loadQueueImportPlaylist(playlist) }, shape = RoundedCornerShape(14.dp), color = Surface) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Green); Spacer(Modifier.width(9.dp)); Column { Text(playlist.title, maxLines = 1); Text(if (playlist.trackCount >= 0) "${playlist.trackCount} 首" else "点击读取", color = Color.Gray, fontSize = 13.sp) } } } } }
                state.queueImportLoading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("已选 ${selectedIds.size} 首", Modifier.weight(1f), color = Green); TextButton({ if (selectedIds.size == state.queueImportTracks.size) selectedIds.clear() else { selectedIds.clear(); selectedIds.addAll(state.queueImportTracks.map(Track::id)) } }) { Text(if (selectedIds.size == state.queueImportTracks.size) "取消全选" else "全选") } }
                    LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(state.queueImportTracks, key = { it.id }) { track ->
                            val selected = track.id in selectedIds
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (selected) selectedIds.remove(track.id) else selectedIds.add(track.id) }.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(selected, { checked -> if (checked) selectedIds.add(track.id) else selectedIds.remove(track.id) }, Modifier.size(38.dp)); Column(Modifier.weight(1f)) { Text(track.title, maxLines = 1, fontSize = 15.sp); Text(track.artists.joinToString(" / "), maxLines = 1, color = Color.Gray, fontSize = 12.sp) } }
                        }
                    }
                }
            }
        },
        confirmButton = { if (state.queueImportTitle.isNotBlank() && !state.queueImportLoading) TextButton({ vm.addSelectedQueueTracks(selectedIds.toSet()); importDialog = false }, enabled = selectedIds.isNotEmpty()) { Text("添加 ${selectedIds.size} 首") } },
        dismissButton = { TextButton({ if (state.queueImportTitle.isBlank()) { importDialog = false; vm.clearQueueImport() } else vm.clearQueueImport() }) { Text(if (state.queueImportTitle.isBlank()) "取消" else "返回") } },
    )
}

@Composable private fun CollectionRow(value: MusicCollection, open: () -> Unit = {}) = ListItem(modifier = Modifier.clickable(onClick = open), headlineContent = { Text(value.title) }, supportingContent = { Text(if (value.trackCount >= 0) "${value.trackCount} 首" else "点击查看") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Green) })
@Composable private fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) = Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(text, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); action?.let { TextButton(onAction) { Text(it) } } }
@Composable private fun MiniPlayer(track: Track?, lyrics: List<LyricLine>, vm: AppViewModel, open: () -> Unit) {
    if (track == null) return
    var position by remember(track.id) { mutableLongStateOf(0L) }
    var playing by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id) {
        while (isActive) {
            position = vm.playbackPosition()
            playing = vm.isPlaying()
            delay(350)
        }
    }
    val previewIndex = (activeLyricIndex(lyrics, position).takeIf { it >= 0 }
        ?: lyrics.indexOfFirst { it.timeMs >= 0 }.takeIf { it >= 0 } ?: -1)
    val preview = lyrics.getOrNull(previewIndex)?.text?.takeIf { it.isNotBlank() } ?: "正在播放"
    Surface(color = Surface, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().height(62.dp).padding(start = 8.dp, end = 4.dp).clickable(onClick = open),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = track.artworkUrl.ifBlank { null },
                contentDescription = "当前歌曲封面",
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.DarkGray),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray, fontSize = 11.sp)
            }
            IconButton({ if (playing) vm.pausePlayback() else vm.resumePlayback() }, Modifier.size(44.dp)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(24.dp), tint = Color.White)
            }
            Icon(Icons.Default.KeyboardArrowUp, "打开播放器", Modifier.size(21.dp), tint = Color.Gray)
        }
    }
}
