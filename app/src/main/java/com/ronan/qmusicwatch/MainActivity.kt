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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
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
import com.ronan.qmusicwatch.ui.*
import com.ronan.qmusicwatch.update.UpdateInstaller
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.io.File
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val Green = WatchAccent
private val Surface = WatchSurface
internal enum class LibrarySection(val routeValue: String) {
    Liked("liked"),
    Created("created"),
    Collected("collected");

    companion object {
        fun fromRoute(value: String?): LibrarySection = entries.firstOrNull { it.routeValue == value } ?: Liked
    }
}

private fun libraryRoute(section: LibrarySection) = "library/${section.routeValue}"
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
    accent: Color = WatchAccent,
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
            text, color = accent, fontSize = fontSizeSp.sp, fontWeight = fontWeight,
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
        contentDescription = "账号头像",
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
    return "%.1f / %.1f MB · %d%%".format(java.util.Locale.US, downloadedMb, totalMb, percent)
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
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent { QMusicApp() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onResume() { super.onResume(); FramePerformanceMonitor.start() }
    override fun onPause() { FramePerformanceMonitor.stop(); super.onPause() }

    private fun hideStatusBar() = WindowCompat.getInsetsController(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@Composable private fun QMusicApp(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val noticePrefs = remember { context.getSharedPreferences("notice", android.content.Context.MODE_PRIVATE) }
    var showNotice by remember { mutableStateOf(!noticePrefs.getBoolean("accepted", false)) }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val chrome by vm.chromeState.collectAsStateWithLifecycle()
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
    val uiSize by vm.uiSize.collectAsStateWithLifecycle()
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
    val artworkAccent by vm.artworkAccent.collectAsStateWithLifecycle()
    QMusicWatchTheme(uiSize = uiSize, pureBlack = pureBlack) {
    var dismissedAnnouncements by remember { mutableStateOf(emptySet<String>()) }
    var dismissedUpdateId by remember { mutableLongStateOf(0L) }
    var pendingAutomaticInstallId by rememberSaveable { mutableLongStateOf(0L) }
    var permissionInstallId by rememberSaveable { mutableLongStateOf(0L) }
    var installLaunchError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { AppLog.write("PERF", "startup_ui_ready_ms=${android.os.SystemClock.elapsedRealtime() - QMusicApplication.processStartedAt}") }
    val snackbar = remember { SnackbarHostState() }
    val openVerifiedInstaller: (UpdateUiState.Ready) -> Unit = { ready ->
        vm.prepareUpdateInstall(ready.release, ready.filePath) { verified ->
            runCatching { context.startActivity(UpdateInstaller.installIntent(context, verified)) }
                .onSuccess { installLaunchError = null }
                .onFailure { error ->
                    AppLog.write("INTENT", "${error.javaClass.simpleName}:${error.message.orEmpty()}")
                    installLaunchError = "系统没有可用的 APK 安装器"
                }
        }
    }
    val installPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val requestedId = permissionInstallId
        permissionInstallId = 0L
        val ready = (chrome.updateState as? UpdateUiState.Ready)
            ?.takeIf { it.release.releaseId == requestedId }
        if (ready != null && UpdateInstaller.canInstallPackages(context)) {
            openVerifiedInstaller(ready)
        } else if (requestedId > 0) {
            installLaunchError = "需要允许 QMusic Watch 安装未知来源应用"
        }
    }
    val requestUpdateInstall: (UpdateUiState.Ready) -> Unit = { ready ->
        installLaunchError = null
        if (UpdateInstaller.canInstallPackages(context)) {
            openVerifiedInstaller(ready)
        } else {
            permissionInstallId = ready.release.releaseId
            runCatching { installPermissionLauncher.launch(UpdateInstaller.permissionIntent(context)) }
                .onFailure { error ->
                    permissionInstallId = 0L
                    AppLog.write("INTENT", "${error.javaClass.simpleName}:${error.message.orEmpty()}")
                    installLaunchError = "系统没有可用的未知来源安装设置入口"
                }
        }
    }
    val startUpdate: (ControlUpdate) -> Unit = { release ->
        dismissedUpdateId = release.releaseId
        pendingAutomaticInstallId = release.releaseId
        installLaunchError = null
        vm.downloadUpdate(release)
    }
    DisposableEffect(backStack?.destination?.route) {
        FramePerformanceMonitor.section = backStack?.destination?.route ?: "home"
        onDispose { }
    }
    LaunchedEffect(chrome.message) { chrome.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(installLaunchError) {
        installLaunchError?.let {
            snackbar.showSnackbar(it)
            installLaunchError = null
        }
    }
    LaunchedEffect(chrome.updateState, pendingAutomaticInstallId) {
        automaticInstallCandidate(pendingAutomaticInstallId, chrome.updateState)?.let { ready ->
            pendingAutomaticInstallId = 0L
            requestUpdateInstall(ready)
        }
    }
    LaunchedEffect(chrome.playEvent, autoOpenPlayer) {
        if (chrome.playEvent != 0L && autoOpenPlayer && chrome.currentTrack != null && backStack?.destination?.route != "player") nav.navigate("player") { launchSingleTop = true }
    }
    Scaffold(containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom), snackbarHost = { SnackbarHost(snackbar) }, bottomBar = { if (backStack?.destination?.route != "player") MiniPlayer(chrome.currentTrack, chrome.lyrics, vm) { nav.navigate("player") } }) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding),
            enterTransition = {
                fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 12 }
            },
            exitTransition = {
                fadeOut(tween(140)) + slideOutHorizontally(tween(160)) { -it / 16 }
            },
            popEnterTransition = {
                fadeIn(tween(180)) + slideInHorizontally(tween(180)) { -it / 12 }
            },
            popExitTransition = {
                fadeOut(tween(140)) + slideOutHorizontally(tween(160)) { it / 16 }
            },
        ) {
            composable("home") { val pageState by vm.state.collectAsStateWithLifecycle(); HomeScreen(nav, pageState, vm, dailyCount) }
            composable("login") { val pageState by vm.state.collectAsStateWithLifecycle(); LoginScreen(pageState, vm) { nav.popBackStack() } }
            composable("search") { val pageState by vm.state.collectAsStateWithLifecycle(); SearchScreen(nav, pageState, vm, searchHistory) }
            composable("library/{section}") { entry ->
                val pageState by vm.state.collectAsStateWithLifecycle()
                val section = LibrarySection.fromRoute(entry.arguments?.getString("section"))
                LaunchedEffect(section) { vm.loadLibrary() }
                LibraryScreen(nav, pageState, vm, section)
            }
            composable("recent") { val pageState by vm.state.collectAsStateWithLifecycle(); LaunchedEffect(Unit) { vm.loadRecent() }; TrackListScreen("最近播放", pageState.recent, writablePlaylists(pageState.library?.playlists.orEmpty()), vm) }
            composable("downloads") { DownloadScreen(downloads, vm) }
            composable("player") {
                val pageState by vm.state.collectAsStateWithLifecycle()
                PlayerScreen(
                    track = pageState.currentTrack,
                    lyrics = pageState.lyrics,
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
                    activeQuality = pageState.activeStreamQuality,
                    profile = pageState.profile,
                    profileLoaded = pageState.profileLoaded,
                    playlists = writablePlaylists(pageState.library?.playlists.orEmpty()),
                    liked = pageState.library?.liked?.any { it.id == pageState.currentTrack?.id } == true,
                    cachedArtworkAccent = artworkAccent,
                    openQueue = { nav.navigate("queue") },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("queue") { val pageState by vm.state.collectAsStateWithLifecycle(); LaunchedEffect(Unit) { if (vm.signedIn) vm.loadLibrary() }; QueueScreen(queue, queueIndex, queueReversed, pageState, vm) { nav.popBackStack() } }
            composable("detail") { val pageState by vm.state.collectAsStateWithLifecycle(); DetailScreen(pageState.detail, pageState.detailDirectoryId, pageState.detailLoading, pageState.detailError, writablePlaylists(pageState.library?.playlists.orEmpty()), vm) { nav.popBackStack() } }
            composable("settings") { SettingsCenter(nav) { nav.popBackStack() } }
            composable("settings/display") { DisplaySettingsScreen(vm, uiSize, lyricSize, lyricOriginal, lyricTranslation, lyricOffset, lyricAnimation, lyricAlignment, pureBlack, lowPowerPlayer) { nav.popBackStack() } }
            composable("settings/playback") { val pageState by vm.state.collectAsStateWithLifecycle(); PlaybackSettingsScreen(vm, quality, pageState.profile, pageState.profileLoaded, headphoneWarning, autoOpenPlayer, playMode, sleepRemaining, wifiOnlyDownload, lastSleepMinutes) { nav.popBackStack() } }
            composable("settings/network") {
                val pageState by vm.state.collectAsStateWithLifecycle()
                NetworkSettingsScreen(vm, dailyCount, pageState, onAnnouncements = { nav.navigate("settings/announcements") }, onRelogin = {
                    vm.logout()
                    nav.navigate("login") { popUpTo("home") }
                }) { nav.popBackStack() }
            }
            composable("settings/announcements") { AnnouncementsScreen(chrome.announcements, seenAnnouncements, vm) { nav.popBackStack() } }
            composable("settings/about") {
                AboutScreen(vm, chrome.updateState, startUpdate, requestUpdateInstall) { nav.popBackStack() }
            }
        }
    }
    chrome.pendingSpeakerTrack?.let { track ->
        WatchDialog(onDismissRequest = vm::dismissSpeakerPrompt, title = { Text("未检测到耳机") }, text = { Text("建议连接蓝牙或有线耳机，是否仍使用手表扬声器播放？") },
            confirmButton = { TextButton(onClick = vm::continueOnSpeaker) { Text("继续外放") } },
            dismissButton = { TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("连接蓝牙") } })
    }
    val startupAnnouncement = nextStartupAnnouncement(chrome.announcements, seenAnnouncements, dismissedAnnouncements)
    val automaticUpdate = chrome.updateState.takeIf {
        pendingAutomaticInstallId > 0 && updateStateRelease(it)?.releaseId == pendingAutomaticInstallId
    }
    val startupUpdate = when (val update = chrome.updateState) {
        is UpdateUiState.Available -> update.release
        is UpdateUiState.Ready -> update.release
        else -> null
    }?.takeIf { it.releaseId != dismissedUpdateId }
    if (showNotice) WatchDialog(onDismissRequest = {}, title = { Text("第三方非官方客户端") }, text = { Text("QMusic Watch 与腾讯或 QQ 音乐无隶属或认可关系。请尊重版权和账号权益，本项目不会绕过会员、地区、付费或 DRM 限制。") }, confirmButton = { TextButton({ noticePrefs.edit().putBoolean("accepted", true).apply(); showNotice = false }) { Text("我知道了") } })
    else if (startupAnnouncement != null) startupAnnouncement.let { announcement ->
        val dismiss: () -> Unit = {
            dismissedAnnouncements = dismissedAnnouncements + announcement.id
            vm.markAnnouncementSeen(announcement.id)
        }
        WatchDialog(
            onDismissRequest = dismiss,
            title = { Text(announcement.title.take(80), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Box(Modifier.heightIn(max = 165.dp).verticalScroll(rememberScrollState())) { Text(announcement.content.take(1200)) } },
            confirmButton = { TextButton(dismiss) { Text("知道了") } },
        )
    }
    else if (automaticUpdate is UpdateUiState.Downloading) WatchDialog(
        onDismissRequest = {},
        title = { Text("正在更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(downloadProgressSummary(automaticUpdate.downloadedBytes, automaticUpdate.totalBytes))
                LinearProgressIndicator(
                    progress = {
                        if (automaticUpdate.totalBytes > 0) {
                            automaticUpdate.downloadedBytes.toFloat()
                                .div(automaticUpdate.totalBytes)
                                .coerceIn(0f, 1f)
                        } else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("下载完成后将自动打开系统安装器", color = Color.Gray, fontSize = 12.sp)
            }
        },
        confirmButton = {},
    )
    else if (automaticUpdate is UpdateUiState.Verifying) WatchDialog(
        onDismissRequest = {},
        title = { Text("正在校验更新") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(9.dp))
                Text("正在检查签名、包名和文件完整性")
            }
        },
        confirmButton = {},
    )
    else if (automaticUpdate is UpdateUiState.Error) automaticUpdate.release?.let { release ->
        WatchDialog(
            onDismissRequest = { pendingAutomaticInstallId = 0L },
            title = { Text("更新失败") },
            text = { Text(automaticUpdate.message.take(300)) },
            confirmButton = {
                TextButton({ startUpdate(release) }) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重试")
                }
            },
            dismissButton = { TextButton({ pendingAutomaticInstallId = 0L }) { Text("稍后") } },
        )
    }
    else if (startupUpdate != null) startupUpdate.let { update ->
        val ready = (chrome.updateState as? UpdateUiState.Ready)
            ?.takeIf { it.release.releaseId == update.releaseId }
        WatchDialog(
            onDismissRequest = { dismissedUpdateId = update.releaseId },
            title = { Text("发现新版本 ${update.versionName}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (update.title.isNotBlank()) Text(update.title, fontWeight = FontWeight.Bold)
                    if (update.changelog.isNotBlank()) Text(update.changelog.take(1200), color = Color.LightGray, fontSize = 14.sp)
                    Text(formatFileSize(update.apk.sizeBytes), color = Color.Gray, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton({
                    dismissedUpdateId = update.releaseId
                    if (ready != null) requestUpdateInstall(ready) else startUpdate(update)
                }) {
                    Icon(if (ready != null) Icons.Default.InstallMobile else Icons.Default.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (ready != null) "安装更新" else "立即更新")
                }
            },
            dismissButton = { TextButton({ dismissedUpdateId = update.releaseId }) { Text("稍后") } },
        )
    }
    }
}

@Composable private fun HomeScreen(nav: NavHostController, state: AppUiState, vm: AppViewModel, dailyCount: Int) {
    val dimensions = LocalWatchDimensions.current
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
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
            if (page == 0) LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = dimensions.screenPadding),
                contentPadding = PaddingValues(top = 4.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(dimensions.itemSpacing),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("QMusic", fontSize = dimensions.titleSp.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text("非官方", color = WatchTextSecondary, fontSize = 9.sp)
                    }
                }
                item {
                    Surface(
                        onClick = { nav.navigate("search") },
                        modifier = Modifier.fillMaxWidth().height(dimensions.searchHeight),
                        shape = RoundedCornerShape(dimensions.searchCornerRadius),
                        color = WatchSurface,
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, Modifier.size(dimensions.iconSize), tint = WatchTextSecondary)
                            Spacer(Modifier.width(7.dp))
                            Text("搜索歌曲、歌单、歌手、专辑", color = WatchTextSecondary, fontSize = dimensions.bodySp.sp, maxLines = 1)
                        }
                    }
                }
                item { WatchSectionHeader("每日推荐", action = "换一换") { if (daily.isNotEmpty()) dailyOffset = (dailyOffset + dailyCount) % daily.size } }
                items(shown, key = { it.id }) { TrackRow(it, vm, queue = shown, playlists = writablePlaylists(state.library?.playlists.orEmpty())) }
            } else LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = dimensions.screenPadding),
                contentPadding = PaddingValues(top = 4.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(dimensions.itemSpacing),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                        AccountAvatar(state.profile?.avatarUrl, vm.loginProvider, vm.accountId, 42.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text(
                                if (vm.signedIn) state.profile?.displayName?.ifBlank { null } ?: "${loginProviderName(vm.loginProvider)}音乐用户" else "尚未登录",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (vm.signedIn) vipSummary(state.profile, state.profileLoaded, state.profileError) else "登录后同步音乐库",
                                color = if (state.profile?.isVipActive() == true) WatchVip else WatchTextSecondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (!vm.signedIn) item { WatchPrimaryButton("扫码登录", Modifier.fillMaxWidth()) { nav.navigate("login") } }
                else {
                    item {
                        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            LibraryStat("喜欢", state.library?.liked?.size ?: 0, Icons.Default.Favorite, WatchLike) { nav.navigate(libraryRoute(LibrarySection.Liked)) }
                            LibraryStat("创建", state.library?.playlists?.count { it.owned != false } ?: 0, Icons.AutoMirrored.Filled.QueueMusic, WatchAccent) { nav.navigate(libraryRoute(LibrarySection.Created)) }
                            LibraryStat("收藏", state.library?.playlists?.count { it.owned == false } ?: 0, Icons.Default.LibraryMusic, WatchVip) { nav.navigate(libraryRoute(LibrarySection.Collected)) }
                        }
                    }
                }
                item { SettingsModule("最近播放", null, Icons.Default.History) { nav.navigate("recent") } }
                item { SettingsModule("离线缓存", null, Icons.Default.Download) { nav.navigate("downloads") } }
                item { SettingsModule("设置", null, Icons.Default.Settings) { nav.navigate("settings") } }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp), horizontalArrangement = Arrangement.Center) {
            repeat(2) { page ->
                Box(Modifier.padding(2.dp).size(if (pager.currentPage == page) 6.dp else 4.dp).background(if (pager.currentPage == page) WatchAccent else WatchDivider, RoundedCornerShape(50)))
            }
        }
    }
}

@Composable private fun RowScope.LibraryStat(
    label: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.weight(1f).fillMaxHeight(),
        shape = RoundedCornerShape(10.dp),
        color = WatchSurface,
    ) {
        Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(14.dp), tint = tint)
                Spacer(Modifier.width(3.dp))
                Text(count.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(label, color = WatchTextSecondary, fontSize = 9.sp)
        }
    }
}

@Composable private fun LoginScreen(state: AppUiState, vm: AppViewModel, onSuccess: () -> Unit) {
    val dimensions = LocalWatchDimensions.current
    var provider by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.qrStatus) { if (state.qrStatus == "登录成功") onSuccess() }
    BoxWithConstraints(Modifier.fillMaxSize().padding(dimensions.screenPadding)) {
        if (!vm.featureEnabled("qrLogin")) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.BuildCircle, null, Modifier.size(32.dp), tint = WatchTextSecondary)
                Spacer(Modifier.height(6.dp))
                Text(vm.featureMessage("qrLogin").ifBlank { "扫码登录暂时维护" }, color = WatchTextSecondary, fontSize = dimensions.bodySp.sp)
            }
        } else if (provider == null) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("扫码登录", fontSize = dimensions.titleSp.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                WatchPrimaryButton("使用 QQ 扫码", Modifier.fillMaxWidth()) { provider = "qq" }
                Spacer(Modifier.height(5.dp))
                WatchPrimaryButton("使用微信扫码", Modifier.fillMaxWidth(), outlined = true) { provider = "wechat" }
                Spacer(Modifier.height(7.dp))
                Text("仅通过手机扫码授权", color = WatchTextSecondary, fontSize = dimensions.secondarySp.sp)
            }
        } else {
            val selectedProvider = provider!!
            val qrSide = minOf(maxWidth, (maxHeight - 38.dp).coerceAtLeast(1.dp), 320.dp)
            LaunchedEffect(selectedProvider) { vm.startQrLogin(selectedProvider) }
            DisposableEffect(selectedProvider) { onDispose(vm::cancelQrLogin) }
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    WatchIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回登录方式", Modifier.size(32.dp)) { provider = null }
                    Text(if (selectedProvider == "wechat") "微信登录" else "QQ 登录", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    WatchIconButton(Icons.Default.Refresh, "刷新二维码", Modifier.size(32.dp)) { vm.startQrLogin(selectedProvider) }
                }
                ServerQrLogin(
                    imageBase64 = state.qrImageBase64,
                    modifier = Modifier.size(qrSide),
                )
                Text(
                    state.qrStatus.ifBlank { "扫码后在手机确认" },
                    color = if (state.qrStatus.startsWith("登录失败")) MaterialTheme.colorScheme.error else WatchTextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ServerQrLogin(imageBase64: String, modifier: Modifier = Modifier) {
    val image = remember(imageBase64) { decodeServerQrImage(imageBase64) }
    Surface(modifier, shape = RoundedCornerShape(0.dp), color = Color.Transparent) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "登录二维码",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.None,
                )
            } else {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color(0xFF28312D))
            }
        }
    }
}

private fun decodeServerQrImage(value: String) = runCatching {
    require(value.length in 128..700_000)
    val bytes = Base64.decode(value, Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 32..2_048 && bounds.outHeight in 32..2_048)
    require(bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304L)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() ?: error("二维码图片无法解码")
}.getOrNull()

@Composable private fun SearchScreen(nav: NavHostController, state: AppUiState, vm: AppViewModel, history: List<String>) {
    val dimensions = LocalWatchDimensions.current
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("track") }
    val names = linkedMapOf("track" to "歌曲", "playlist" to "歌单", "artist" to "歌手", "album" to "专辑")
    Column(Modifier.fillMaxSize().padding(horizontal = dimensions.screenPadding, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        WatchSearchField(query, { query = it }, "搜索", Modifier.fillMaxWidth(), leadingIcon = Icons.Default.Search, trailingIcon = Icons.AutoMirrored.Filled.ArrowForward, onSearch = { vm.search(query, type) })
        if (query.isBlank() && history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) { Text("最近搜索", Modifier.weight(1f), color = WatchTextSecondary, fontSize = 11.sp); TextButton(vm::clearSearchHistory, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("清空", fontSize = 11.sp) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                history.forEach { value ->
                    Surface(onClick = { query = value; vm.search(value, type) }, shape = RoundedCornerShape(9.dp), color = WatchSurface) {
                        Text(value, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = WatchTextSecondary, maxLines = 1, fontSize = 11.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            names.forEach { (key, label) ->
                Surface(
                    onClick = { type = key; if (query.isNotBlank()) vm.search(query, key) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(9.dp),
                    color = if (type == key) WatchSurfaceRaised else Color.Transparent,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(label, color = if (type == key) WatchAccent else WatchTextSecondary, fontSize = 11.sp, fontWeight = if (type == key) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) { if (type == "track") items(state.searchTracks, key = { it.id }) { TrackRow(it, vm, queue = state.searchTracks, playlists = writablePlaylists(state.library?.playlists.orEmpty())) } else items(state.searchCollections, key = { "${it.directoryId}:${it.id}" }) { CollectionRow(it) { vm.loadDetail(type, it); nav.navigate("detail") } }; if (state.searchCursor != null) item { TextButton({ vm.search(state.searchQuery, type, loadMore = true) }, Modifier.fillMaxWidth(), enabled = !state.searchLoading) { if (state.searchLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("加载更多") } } }
    }
}

@Composable private fun LibraryScreen(nav: NavHostController, state: AppUiState, vm: AppViewModel, section: LibrarySection) {
    val dimensions = LocalWatchDimensions.current
    var editing by remember { mutableStateOf<MusicCollection?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MusicCollection?>(null) }
    var actionPlaylist by remember { mutableStateOf<MusicCollection?>(null) }
    var title by remember { mutableStateOf("") }
    val created = state.library?.playlists.orEmpty().filter { it.owned != false }
    val collected = state.library?.playlists.orEmpty().filter { it.owned == false }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = dimensions.screenPadding),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when (section) {
            LibrarySection.Liked -> {
                item { SectionTitle("我喜欢") }
                items(state.library?.liked.orEmpty(), key = { it.id }) { TrackRow(it, vm, liked = true, queue = state.library?.liked.orEmpty(), playlists = created) }
                if (state.library != null && state.library.liked.isEmpty()) item { Text("还没有喜欢的歌曲", color = Color.Gray) }
            }
            LibrarySection.Created -> {
                item { SectionTitle("我创建的歌单", "新建") { title = ""; creating = true } }
                items(created, key = { "${it.directoryId}:${it.id}" }) { item ->
                    WatchListRow(
                        title = item.title,
                        subtitle = "${item.trackCount} 首",
                        leading = { Box(Modifier.fillMaxSize().background(WatchSurfaceRaised, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(18.dp), tint = WatchAccent) } },
                        trailing = { WatchIconButton(Icons.Default.MoreVert, "歌单操作") { actionPlaylist = item } },
                        onClick = { vm.loadDetail("playlist", item, editable = true); nav.navigate("detail") },
                    )
                }
                if (state.library != null && created.isEmpty()) item { Text("还没有创建歌单", color = Color.Gray) }
            }
            LibrarySection.Collected -> {
                item { SectionTitle("收藏歌单") }
                items(collected, key = { "${it.directoryId}:${it.id}" }) { item -> CollectionRow(item) { vm.loadDetail("playlist", item); nav.navigate("detail") } }
                if (state.library != null && collected.isEmpty()) item { Text("还没有收藏歌单", color = Color.Gray) }
            }
        }
    }
    actionPlaylist?.let { playlist ->
        WatchDialog(
            onDismissRequest = { actionPlaylist = null },
            title = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    WatchActionRow(Icons.Default.Edit, "重命名") { title = playlist.title; editing = playlist; actionPlaylist = null }
                    WatchActionRow(Icons.Default.Delete, "删除歌单", MaterialTheme.colorScheme.error) { deleting = playlist; actionPlaylist = null }
                }
            },
            confirmButton = { TextButton({ actionPlaylist = null }) { Text("关闭") } },
        )
    }
    if (creating || editing != null) WatchDialog(onDismissRequest = { creating = false; editing = null }, title = { Text(if (creating) "新建歌单" else "重命名歌单") }, text = { WatchSearchField(title, { title = it.take(50) }, "歌单名称") }, confirmButton = { TextButton({ if (creating) vm.createPlaylist(title.trim()) else vm.renamePlaylist(editing!!.directoryId, title.trim()); creating = false; editing = null }, enabled = title.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton({ creating = false; editing = null }) { Text("取消") } })
    deleting?.let { playlist -> WatchDialog(onDismissRequest = { deleting = null }, title = { Text("删除歌单？") }, text = { Text("将从 QQ 音乐永久删除“${playlist.title}”，歌曲本身不会删除。") }, confirmButton = { TextButton({ vm.deletePlaylist(playlist.directoryId); deleting = null }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ deleting = null }) { Text("取消") } }) }
}

@Composable private fun TrackListScreen(title: String, tracks: List<Track>, playlists: List<MusicCollection>, vm: AppViewModel) = LazyColumn(Modifier.fillMaxSize().padding(horizontal = LocalWatchDimensions.current.screenPadding)) {
    item { SectionTitle(title) }; items(tracks, key = { it.id }) { TrackRow(it, vm, queue = tracks, playlists = playlists) }
}

@Composable private fun DownloadScreen(downloads: List<DownloadEntity>, vm: AppViewModel) {
    val dimensions = LocalWatchDimensions.current
    var confirmDeleteLocked by remember { mutableStateOf(false) }
    var deletingGroup by remember { mutableStateOf<String?>(null) }
    var actionDownload by remember { mutableStateOf<DownloadEntity?>(null) }
    val own = downloads.filter { it.ownerAccountId == vm.accountId }
    val locked = downloads.filter { it.ownerAccountId != vm.accountId }
    val totalBytes = own.sumOf { item -> maxOf(item.downloadedBytes, java.io.File(item.filePath).takeIf { item.status == "complete" && it.exists() }?.length() ?: 0L) }
    val lockedBytes = locked.sumOf { item -> maxOf(item.downloadedBytes, java.io.File(item.filePath).takeIf { item.status == "complete" && it.exists() }?.length() ?: 0L) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = dimensions.screenPadding), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { SectionTitle("离线缓存") }
        item { Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) { Text("%.1f MB · ${own.size} 首".format(totalBytes / 1024f / 1024f), Modifier.weight(1f), color = WatchTextSecondary, fontSize = 10.sp); TextButton(vm::deleteInvalidDownloads, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("清理失效", fontSize = 10.sp) } } }
        if (locked.isNotEmpty()) item { WatchListRow("其他账号缓存已锁定", "${locked.size} 首 · %.1f MB".format(lockedBytes / 1024f / 1024f), trailing = { WatchIconButton(Icons.Default.Delete, "删除全部锁定缓存", tint = MaterialTheme.colorScheme.error) { confirmDeleteLocked = true } }) }
        own.groupBy(DownloadEntity::groupName).forEach { (group, values) ->
            item(key = "group-$group") { Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) { Text(group, Modifier.weight(1f), color = WatchAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold); TextButton({ deletingGroup = group }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("删除本组", fontSize = 10.sp) } } }
            items(values, key = { "${it.ownerAccountId}-${it.trackId}" }) { item ->
                val status = when (item.status) { "complete" -> "已完成"; "queued_wifi" -> "等待 Wi-Fi"; "queued" -> "排队中"; "downloading" -> "下载中"; "paused" -> "已暂停"; "locked" -> "等待原账号登录"; "failed_storage" -> "存储不足，需保留 256MB"; else -> "下载失败" }
                WatchListRow(
                    title = item.title,
                    subtitle = "$status · ${downloadProgressSummary(item.downloadedBytes, item.totalBytes)}",
                    trailing = { WatchIconButton(Icons.Default.MoreVert, "缓存操作") { actionDownload = item } },
                )
            }
        }
    }
    actionDownload?.let { item ->
        WatchDialog(
            onDismissRequest = { actionDownload = null },
            title = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (item.status in setOf("downloading", "queued", "queued_wifi")) WatchActionRow(Icons.Default.Pause, "暂停下载") { vm.pauseDownload(item.trackId); actionDownload = null }
                    else if (item.status == "paused" || item.status == "locked" || item.status.startsWith("failed")) WatchActionRow(Icons.Default.PlayArrow, "继续下载", if (vm.accountId == item.ownerAccountId) WatchTextPrimary else WatchTextSecondary) { if (vm.accountId == item.ownerAccountId) vm.resumeDownload(item); actionDownload = null }
                    WatchActionRow(Icons.Default.Delete, "删除缓存", MaterialTheme.colorScheme.error) { vm.deleteDownload(item.trackId, item.ownerAccountId); actionDownload = null }
                }
            },
            confirmButton = { TextButton({ actionDownload = null }) { Text("关闭") } },
        )
    }
    if (confirmDeleteLocked) WatchDialog(onDismissRequest = { confirmDeleteLocked = false }, title = { Text("删除全部锁定缓存？") }, text = { Text("将永久删除其他账号的 ${locked.size} 首离线歌曲；歌曲名称和账号信息不会显示。") }, confirmButton = { TextButton({ vm.deleteLockedDownloads(); confirmDeleteLocked = false }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ confirmDeleteLocked = false }) { Text("取消") } })
    deletingGroup?.let { group ->
        val count = own.count { it.groupName == group }
        WatchDialog(onDismissRequest = { deletingGroup = null }, title = { Text("删除“$group”？") }, text = { Text("将永久删除本组的 $count 首离线歌曲。") }, confirmButton = { TextButton({ vm.deleteDownloadGroup(group); deletingGroup = null }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ deletingGroup = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerScreen(
    track: Track?, lyrics: List<LyricLine>, vm: AppViewModel,
    playMode: String, lyricSize: String, showOriginal: Boolean, showTranslation: Boolean, lyricOffset: Long,
    lyricAnimation: String, lyricAlignment: String, lowPowerPlayer: Boolean, quality: String,
    activeQuality: String,
    profile: UserProfile?, profileLoaded: Boolean, playlists: List<MusicCollection>, liked: Boolean,
    cachedArtworkAccent: String,
    openQueue: () -> Unit, onBack: () -> Unit,
) {
    val dimensions = LocalWatchDimensions.current
    if (track == null) return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { WatchIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.align(Alignment.TopStart).padding(4.dp), onClick = onBack); Text("尚未播放", fontSize = dimensions.bodySp.sp) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    val hasTimeline = lyrics.any { it.timeMs >= 0 }
    val active = activeLyricIndex(lyrics, position + lyricOffset)
    val (renderOriginal, renderTranslation) = lyricLayers(showOriginal, showTranslation, lyrics.any { !it.translation.isNullOrBlank() })
    val lyricSp = when (lyricSize) { "small" -> 15f; "large" -> 19f; else -> 17f }
    val centerLyrics = lyricAlignment == "center"
    val listState = key(track.id) { rememberLazyListState() }
    val lyricLineHeightPx = with(LocalDensity.current) { dimensions.lyricRowHeight.roundToPx() }
    val lyricListDragged by listState.interactionSource.collectIsDraggedAsState()
    var manualLyricSelection by remember(track.id) { mutableStateOf(false) }
    var manualLyricInteraction by remember(track.id) { mutableIntStateOf(0) }
    var selectedLike by remember(track.id) { mutableStateOf<Boolean?>(null) }
    var likePending by remember(track.id) { mutableStateOf(false) }
    var showPlaylistDialog by remember(track.id) { mutableStateOf(false) }
    var showQualityDialog by remember(track.id) { mutableStateOf(false) }
    var showModeDialog by remember(track.id) { mutableStateOf(false) }
    val effectiveLiked = selectedLike ?: liked
    val playerArtwork = safeLocalOrGatewayUri(track.artworkUrl)
    val playerAccent = rememberArtworkAccent(playerArtwork, cachedArtworkAccent, vm::cacheArtworkAccent)
    val centeredLyricIndex by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            lyricIndexClosestToCenter(
                layout.viewportStartOffset,
                layout.viewportEndOffset,
                layout.visibleItemsInfo.map { Triple(it.index, it.offset, it.size) },
            )
        }
    }
    // During automatic playback, avoid observing layoutInfo. That observation
    // makes every scroll animation frame recompose the whole player.
    val focusedLyricIndex = if (manualLyricSelection) {
        centeredLyricIndex.takeIf { it >= 0 } ?: active
    } else active
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
    suspend fun centerLyric(index: Int) {
        if (index !in lyrics.indices) return
        while (listState.layoutInfo.viewportSize.height == 0) delay(16)
        var layout = listState.layoutInfo
        var item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null) {
            listState.scrollToItem(
                index,
                scrollOffset = lyricCenterScrollOffset(
                    layout.viewportStartOffset,
                    layout.viewportEndOffset,
                    lyricLineHeightPx,
                ),
            )
            delay(16)
            layout = listState.layoutInfo
            item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        }
        item ?: return
        val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
        val itemCenter = item.offset + item.size / 2f
        val delta = itemCenter - viewportCenter
        if (abs(delta) < 1f) return
        if (lyricAnimation == "off") {
            listState.scrollBy(delta)
        } else {
            listState.animateScrollBy(
                value = delta,
                animationSpec = tween(210, easing = FastOutSlowInEasing),
            )
        }
    }
    LaunchedEffect(track.id, lyricListDragged, manualLyricInteraction) {
        if (lyricListDragged) {
            manualLyricSelection = true
        } else if (manualLyricSelection) {
            while (listState.isScrollInProgress) delay(50)
            delay(3_000)
            manualLyricSelection = false
        }
    }
    LaunchedEffect(track.id, active, lyrics.size, manualLyricSelection) {
        if (active >= 0 && lyrics.isNotEmpty() && !manualLyricSelection) {
            centerLyric(active)
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
                BoxWithConstraints(Modifier.fillMaxSize()) {
                        LazyColumn(
                            Modifier.fillMaxSize().padding(start = if (centerLyrics) 8.dp else 12.dp, end = 8.dp),
                            state = listState,
                            contentPadding = PaddingValues(vertical = (maxHeight / 2 - 22.dp).coerceAtLeast(0.dp)),
                            horizontalAlignment = if (centerLyrics) Alignment.CenterHorizontally else Alignment.Start,
                        ) {
                            if (lyrics.isEmpty()) item {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("暂无歌词", color = WatchTextSecondary, fontSize = 14.sp)
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
                                    1 -> if (lyricAnimation == "strong") .52f else .62f
                                    2 -> if (lyricAnimation == "strong") .24f else .34f
                                    else -> if (lyricAnimation == "off") .36f else .14f
                                }
                                val motionDuration = if (lyricAnimation == "off") 0 else 200
                                val lineAlpha by animateFloatAsState(targetAlpha, tween(motionDuration), label = "lyricFade")
                                // Keep row measurement fixed. The active-line
                                // emphasis is applied in the draw layer below.
                                val lineScale by animateFloatAsState(
                                    1f,
                                    tween(motionDuration), label = "lyricFocusScale",
                                )
                                val lineFontSize = if (hasTimeline) {
                                    if (isFocused) lyricSp else (lyricSp - 4f).coerceAtLeast(11f)
                                } else lyricSp
                                val karaokeProgress = if (isPlaybackLine) lyricRenderProgress(line, position + lyricOffset, nextTime) else null
                                val seek = {
                                    if (line.timeMs >= 0) {
                                        manualLyricSelection = false
                                        vm.seek((line.timeMs - lyricOffset).coerceAtLeast(0))
                                    }
                                }
                                val showTime = hasTimeline && line.timeMs >= 0 && manualLyricSelection && isFocused
                                Row(
                                    Modifier.fillMaxWidth()
                                        .graphicsLayer {
                                            alpha = lineAlpha
                                        }
                                        .then(if (line.timeMs >= 0) Modifier.clickable(onClick = seek) else Modifier)
                                        .height(
                                            if (renderTranslation && !line.translation.isNullOrBlank()) {
                                                dimensions.lyricRowHeight + 8.dp
                                            } else {
                                                dimensions.lyricRowHeight
                                            },
                                        )
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (centerLyrics && hasTimeline) Spacer(Modifier.width(34.dp))
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
                                            emphasisScale = lineScale,
                                            accent = playerAccent,
                                        )
                                        if (renderTranslation) line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                                            SingleLineLyricText(
                                                text = translation,
                                                modifier = Modifier.fillMaxWidth(),
                                                requestedFontSp = (lineFontSize - 4f).coerceAtLeast(11f),
                                                color = if (isFocused) playerAccent.copy(alpha = .86f) else WatchTextSecondary,
                                                fontWeight = if (isFocused && !renderOriginal) FontWeight.Bold else FontWeight.Normal,
                                                centered = centerLyrics,
                                                emphasisScale = lineScale,
                                            )
                                        }
                                    }
                                    if (hasTimeline) Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterEnd) {
                                        Text(
                                            lyricTime(line.timeMs),
                                            color = if (isFocused) playerAccent else WatchTextSecondary,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.alpha(if (showTime) 1f else 0f),
                                        )
                                    }
                                }
                            }
                        }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val compactPlayer = maxHeight <= 320.dp
                    val coverSize = dimensions.playerArtworkSize.coerceAtMost((maxHeight * .34f))
                    if (!lowPowerPlayer && playerArtwork.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(playerArtwork)
                                .size(256)
                                .crossfade(false)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().alpha(.14f),
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.Low,
                        )
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)))
                    }
                    Column(
                        Modifier.fillMaxSize().padding(
                            start = dimensions.screenPadding,
                            end = dimensions.screenPadding,
                            top = 4.dp,
                            bottom = 4.dp,
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        var dragY by remember { mutableFloatStateOf(0f) }
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(playerArtwork.ifBlank { null })
                                .size(256)
                                .crossfade(false)
                                .build(),
                            contentDescription = "歌曲封面",
                            modifier = Modifier.size(coverSize).background(WatchSurface, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
                                .pointerInput(track.id) { detectTapGestures(onDoubleTap = { if (vm.isPlaying()) vm.pausePlayback() else vm.resumePlayback() }) }
                                .pointerInput(track.id) { detectVerticalDragGestures(onDragStart = { dragY = 0f }, onDragEnd = { if (abs(dragY) > 60) vm.adjustVolume(if (dragY < 0) 1 else -1) }) { change, amount -> change.consume(); dragY += amount } },
                        )
                        Text(track.title, fontSize = if (compactPlayer) 14.sp else 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        Text(track.artists.joinToString(" / "), color = WatchTextSecondary, fontSize = if (compactPlayer) 10.sp else 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        val previewIndex = active.takeIf { it >= 0 }
                            ?: lyrics.indexOfFirst { it.timeMs >= 0 }.takeIf { it >= 0 }
                            ?: lyrics.indexOfFirst { it.text.isNotBlank() }
                        val preview = lyrics.getOrNull(previewIndex)?.text?.takeIf { it.isNotBlank() }
                        AnimatedContent(
                            targetState = preview.orEmpty(),
                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                            modifier = Modifier.fillMaxWidth().height(if (compactPlayer) 17.dp else 22.dp),
                            label = "playerLyricPreview",
                        ) { line ->
                            if (line.isNotBlank()) SingleLineLyricText(line, Modifier.fillMaxWidth(), if (compactPlayer) 11.5f else 13f, WatchTextPrimary.copy(alpha = .82f), centered = true) else Spacer(Modifier.height(1.dp))
                        }
                        Row(Modifier.fillMaxWidth().height(if (compactPlayer) 20.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(lyricTime(position), color = WatchTextSecondary, fontSize = 9.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                            val safeDuration = duration.coerceAtLeast(1L)
                            val safePosition = position.coerceIn(0L, safeDuration)
                            val sliderFraction = (safePosition.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                            Slider(
                                value = safePosition.toFloat(),
                                onValueChange = { vm.seek(it.toLong()); position = it.toLong() },
                                valueRange = 0f..safeDuration.toFloat(),
                                modifier = Modifier.weight(1f).height(if (compactPlayer) 20.dp else 24.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = playerAccent,
                                    activeTrackColor = playerAccent,
                                    inactiveTrackColor = Color(0xFF343B38),
                                ),
                                thumb = {
                                    Box(Modifier.width(10.dp).height(14.dp), contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(10.dp).background(playerAccent, RoundedCornerShape(50)))
                                    }
                                },
                                track = {
                                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(Color(0xFF343B38))) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth(sliderFraction).background(playerAccent))
                                    }
                                },
                            )
                            Text(lyricTime(duration), color = WatchTextSecondary, fontSize = 9.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                        }
                        Row(Modifier.height(if (compactPlayer) 42.dp else 48.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            WatchIconButton(Icons.Default.SkipPrevious, "上一首", onClick = vm::skipPrevious)
                            Surface(
                                onClick = { if (playing) vm.pausePlayback() else vm.resumePlayback() },
                                modifier = Modifier.size(if (compactPlayer) 42.dp else 46.dp),
                                shape = RoundedCornerShape(50),
                                color = WatchTextPrimary,
                                contentColor = Color.Black,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(24.dp))
                                }
                            }
                            WatchIconButton(Icons.Default.SkipNext, "下一首", onClick = vm::skipNext)
                        }
                        Row(Modifier.fillMaxWidth().height(if (compactPlayer) 34.dp else 40.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            PlayerActionButton(Icons.Default.Favorite.takeIf { effectiveLiked } ?: Icons.Default.FavoriteBorder, if (effectiveLiked) "已喜欢" else "喜欢", tint = if (effectiveLiked) WatchLike else WatchTextPrimary, compact = compactPlayer) {
                                if (!likePending) {
                                    val target = !effectiveLiked
                                    selectedLike = target
                                    likePending = true
                                    vm.like(track, target) { success ->
                                        likePending = false
                                        selectedLike = target.takeIf { success }
                                    }
                                }
                            }
                            PlayerActionButton(Icons.AutoMirrored.Filled.PlaylistAdd, "加歌单", compact = compactPlayer) { showPlaylistDialog = true }
                            PlayerActionButton(Icons.Default.Tune, qualityShortLabel(activeQuality), tint = playerAccent, compact = compactPlayer) { showQualityDialog = true }
                            PlayerActionButton(playModeIcon(playMode), playModeName(playMode), tint = playerAccent, compact = compactPlayer) { showModeDialog = true }
                            PlayerActionButton(Icons.AutoMirrored.Filled.QueueMusic, "队列", compact = compactPlayer) { openQueue() }
                        }
                    }
                }
            }
        }
        WatchIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.align(Alignment.TopStart).padding(2.dp), onClick = onBack)
        if (!locked) WatchIconButton(Icons.Default.LockOpen, "锁定触控", Modifier.align(Alignment.TopEnd).padding(2.dp)) { locked = true }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(2) { page -> Box(Modifier.size(if (pager.currentPage == page) 6.dp else 4.dp).background(if (pager.currentPage == page) playerAccent else WatchDivider, RoundedCornerShape(50))) }
        }
        if (locked) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} })
            WatchIconButton(Icons.Default.Lock, "解除锁定", Modifier.align(Alignment.TopEnd).padding(2.dp), tint = playerAccent) { locked = false }
        }
    }
    if (showPlaylistDialog) PlayerPlaylistDialog(track, playlists, vm) { showPlaylistDialog = false }
    if (showQualityDialog) QualityDialog(track, quality, activeQuality, profile, profileLoaded, vm) { showQualityDialog = false }
    if (showModeDialog) PlayModeDialog(playMode, vm) { showModeDialog = false }
}

@Composable private fun PlayerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    WatchIconButton(
        icon = icon,
        contentDescription = label,
        modifier = Modifier.size(if (compact) 34.dp else LocalWatchDimensions.current.playerActionSize),
        tint = tint,
        onLongClick = {},
        onClick = onClick,
    )
}

@Composable private fun QualityDialog(
    track: Track?,
    selectedQuality: String,
    activeQuality: String?,
    profile: UserProfile?,
    profileLoaded: Boolean,
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    val options = qualityAvailability(track, profile)
    val rights = when {
        !profileLoaded -> "会员资料尚未确认，播放时仍会由 QQ 音乐验证实际权益"
        profile?.isVipActive() == true -> "${profile.vipName.ifBlank { "音乐会员" }} · 最终以歌曲 vkey 返回为准"
        else -> "会员音质可选择，QQ 音乐会在播放时验证实际权益"
    }
    WatchDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (track == null) "默认音质" else "选择音质", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 292.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(rights, color = Color.Gray, fontSize = 12.sp)
                options.forEach { option ->
                    val spec = audioQualitySpec(option.id)
                    val enabled = option.available
                    Surface(
                        onClick = { if (enabled) { vm.setQuality(option.id); onDismiss() } },
                        enabled = enabled,
                        shape = RoundedCornerShape(10.dp),
                        color = if (normalizeQualityId(selectedQuality) == option.id) Green.copy(alpha = .14f) else Surface,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(normalizeQualityId(selectedQuality) == option.id, onClick = null, enabled = enabled)
                            Column(Modifier.weight(1f)) {
                                Text(option.label, color = if (enabled) Color.White else Color.Gray, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(spec.format, option.reason).filter(String::isNotBlank).joinToString(" · "),
                                    color = if (enabled) Color.Gray else Color(0xFFFFC857),
                                    fontSize = 10.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val status = when {
                                activeQuality != null && activeQuality != QUALITY_LEGACY_UNKNOWN && normalizeQualityId(activeQuality) == option.id -> "正在播放"
                                normalizeQualityId(selectedQuality) == option.id -> "默认"
                                else -> ""
                            }
                            if (status.isNotBlank()) Text(status, color = Green, fontSize = 10.sp, maxLines = 1)
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
    WatchDialog(
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
    WatchDialog(
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

@Composable private fun DetailScreen(detail: CollectionDetail?, editableDirectoryId: String?, loading: Boolean, error: String?, playlists: List<MusicCollection>, vm: AppViewModel, onBack: () -> Unit) = LazyColumn(Modifier.fillMaxSize().padding(horizontal = LocalWatchDimensions.current.screenPadding), contentPadding = PaddingValues(bottom = 8.dp)) {
    item {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            WatchIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.size(34.dp), onClick = onBack)
            AsyncImage(
                model = detail?.tracks?.firstOrNull()?.artworkUrl?.let(::safeLocalOrGatewayUri)?.ifBlank { null },
                contentDescription = null,
                modifier = Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)).background(WatchSurface),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(detail?.title ?: if (loading) "加载中" else "歌单详情", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text("${detail.tracks.size} 首", color = WatchTextSecondary, fontSize = 10.sp)
                Row {
                    detail?.tracks?.firstOrNull()?.let { first ->
                        WatchIconButton(Icons.Default.PlayArrow, "播放全部", Modifier.size(30.dp)) { vm.requestPlay(first, sourceQueue = detail.tracks) }
                    }
                    if (detail != null) WatchIconButton(Icons.Default.Download, "全部缓存", Modifier.size(30.dp)) { vm.cacheAll(detail.tracks, detail.title) }
                }
            }
        }
    }
    if (loading) item { Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) } }
    if (!loading && error != null) item { Text(error, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    if (!loading && error == null && detail != null && detail.tracks.isEmpty()) item { Text("这个歌单暂时没有可显示的歌曲", Modifier.fillMaxWidth().padding(16.dp), color = Color.Gray, textAlign = TextAlign.Center) }
    items(detail?.tracks.orEmpty(), key = { it.id }) { TrackRow(it, vm, playlistId = editableDirectoryId, removeFromPlaylist = editableDirectoryId != null, queue = detail?.tracks.orEmpty(), playlists = playlists) }
}

@Composable private fun SettingsModule(title: String, subtitle: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, open: () -> Unit) =
    WatchListRow(
        title = title,
        subtitle = subtitle,
        leading = {
            Box(Modifier.fillMaxSize().background(WatchSurface, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = WatchAccent)
            }
        },
        trailing = { Icon(Icons.Default.ChevronRight, null, Modifier.size(17.dp), tint = WatchTextSecondary) },
        onClick = open,
    )

@Composable private fun SettingsGroup(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = WatchTextPrimary)
            subtitle?.let { Text(it, color = WatchTextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        HorizontalDivider(color = WatchDivider)
        content()
    }
}

@Composable private fun SettingsSwitchRow(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 5.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f).padding(end = 8.dp)) {
        Text(title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, color = WatchTextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    Switch(checked, onCheckedChange, modifier = Modifier.width(44.dp).height(28.dp))
}

@Composable private fun SettingsChoiceBlock(title: String, content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 5.dp),
) {
    Text(title, color = WatchTextSecondary, fontSize = 10.sp)
    Spacer(Modifier.height(3.dp))
    content()
}

@Composable private fun CompactChoices(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { (value, label) ->
            Surface(
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f).height(32.dp),
                shape = RoundedCornerShape(9.dp),
                color = if (selected == value) WatchAccent.copy(alpha = .18f) else WatchSurface,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected == value) WatchAccent else WatchTextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable private fun SettingsHeader(title: String, onBack: () -> Unit) = Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
    WatchIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.size(34.dp), onClick = onBack)
    Spacer(Modifier.width(2.dp))
    Text(title, fontSize = LocalWatchDimensions.current.titleSp.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable private fun SettingsCenter(nav: NavHostController, onBack: () -> Unit) = LazyColumn(
    Modifier.fillMaxSize().padding(horizontal = LocalWatchDimensions.current.screenPadding), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    item { SettingsHeader("设置中心", onBack) }
    item { SettingsModule("显示与主题", "主题、歌词、字号与界面显示", Icons.Default.Palette) { nav.navigate("settings/display") } }
    item { SettingsModule("播放与缓存", "音质、播放模式、耳机与定时关闭", Icons.Default.PlayCircle) { nav.navigate("settings/playback") } }
    item { SettingsModule("内容与网络", "每日推荐、账号、诊断与日志", Icons.Default.Language) { nav.navigate("settings/network") } }
    item { SettingsModule("关于", "${BuildConfig.VERSION_NAME} · 开发者 Ronan", Icons.Default.Info) { nav.navigate("settings/about") } }
}

@Composable private fun DisplaySettingsScreen(vm: AppViewModel, uiSize: String, lyricSize: String, lyricOriginal: Boolean, lyricTranslation: Boolean, lyricOffset: Long, lyricAnimation: String, lyricAlignment: String, pureBlack: Boolean, lowPowerPlayer: Boolean, onBack: () -> Unit) = LazyColumn(
    Modifier.fillMaxSize().padding(horizontal = LocalWatchDimensions.current.screenPadding), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
) {
    item { SettingsHeader("显示与主题", onBack) }
    item { SettingsGroup("屏幕", "为方屏手表保留清晰度和续航平衡") {
        SettingsChoiceBlock("界面大小") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("compact" to "紧凑", "standard" to "标准", "large" to "大字").forEach { (value, label) ->
                    Surface(
                        onClick = { vm.setUiSize(value) },
                        modifier = Modifier.weight(1f).height(32.dp),
                        shape = RoundedCornerShape(9.dp),
                        color = if (uiSize == value) WatchAccent.copy(alpha = .18f) else WatchSurface,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(label, color = if (uiSize == value) WatchAccent else WatchTextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        SettingsSwitchRow("AMOLED 纯黑背景", "减少息屏播放器的耗电", pureBlack, vm::setPureBlack)
        SettingsSwitchRow("低功耗播放器", "会降低歌词与进度动画帧率", lowPowerPlayer, vm::setLowPowerPlayer)
    } }
    item { SettingsGroup("歌词", "左滑进入歌词；点击任意有时间轴的句子跳转") {
        SettingsChoiceBlock("对齐方式") {
            CompactChoices(listOf("left" to "靠左", "center" to "居中"), lyricAlignment, vm::setLyricAlignment)
        }
        SettingsChoiceBlock("字号") {
            CompactChoices(listOf("small" to "小", "normal" to "标准", "large" to "大"), lyricSize, vm::setLyricSize)
        }
        SettingsSwitchRow("显示原文歌词", checked = lyricOriginal, onCheckedChange = { if (it || lyricTranslation) vm.setLyricOriginal(it) })
        SettingsSwitchRow("显示翻译歌词", "没有翻译时自动隐藏", checked = lyricTranslation, onCheckedChange = { if (it || lyricOriginal) vm.setLyricTranslation(it) })
        SettingsChoiceBlock("动效") {
            CompactChoices(listOf("off" to "关闭", "soft" to "柔和", "strong" to "明显"), lyricAnimation, vm::setLyricAnimation)
        }
        SettingsChoiceBlock("时间偏移 ${if (lyricOffset >= 0) "+" else ""}${lyricOffset}ms") {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { TextButton({ vm.setLyricOffset(lyricOffset - 500) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("-0.5秒", fontSize = 12.sp) }; TextButton({ vm.setLyricOffset(0) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("归零", fontSize = 12.sp) }; TextButton({ vm.setLyricOffset(lyricOffset + 500) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+0.5秒", fontSize = 12.sp) } }
        }
    } }
}

@Composable private fun PlaybackSettingsScreen(vm: AppViewModel, quality: String, profile: UserProfile?, profileLoaded: Boolean, headphoneWarning: Boolean, autoOpenPlayer: Boolean, playMode: String, sleepRemaining: Long, wifiOnlyDownload: Boolean, lastSleepMinutes: Int?, onBack: () -> Unit) {
    val context = LocalContext.current
    var customTimer by remember { mutableStateOf(false) }; var customMinutes by remember { mutableStateOf("") }; var finishCurrent by remember { mutableStateOf(false) }; var showQualityDialog by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = LocalWatchDimensions.current.screenPadding), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        item { SettingsHeader("播放与缓存", onBack) }
        item { SettingsGroup("音质", "与账号权益和歌曲可用资源同步") {
            Row(
                Modifier.fillMaxWidth().clickable { showQualityDialog = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Tune, null, Modifier.size(20.dp), tint = Green)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("默认音质", fontSize = 14.sp)
                    Text(qualityLabel(quality), color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ChevronRight, null, Modifier.size(19.dp), tint = Color.Gray)
            }
            Text(
                when {
                    !profileLoaded -> "会员资料未知时不会拦截播放，由 QQ 音乐按歌曲验证"
                    profile?.isVipActive() == true -> "${profile.vipName.ifBlank { "音乐会员" }} · 实际音质以 vkey 返回为准"
                    else -> "HQ 与 SQ 可能需要音乐会员，选择后由 QQ 音乐验证"
                },
                color = Color.Gray,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 41.dp, end = 12.dp, bottom = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
    if (showQualityDialog) QualityDialog(null, quality, null, profile, profileLoaded, vm) { showQualityDialog = false }
    if (customTimer) WatchDialog(onDismissRequest = { customTimer = false }, title = { Text("自定义播放时间") }, text = { WatchSearchField(customMinutes, { customMinutes = it.filter(Char::isDigit).take(4) }, "分钟（1-1440）", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }, confirmButton = { TextButton({ customMinutes.toIntOrNull()?.coerceIn(1, 1440)?.let { vm.startSleepTimer(it, finishCurrent) }; customTimer = false }) { Text("开始") } }, dismissButton = { TextButton({ customTimer = false }) { Text("取消") } })
}

@Composable private fun NetworkSettingsScreen(vm: AppViewModel, dailyCount: Int, state: AppUiState, onAnnouncements: () -> Unit, onRelogin: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var confirmUpload by remember { mutableStateOf(false) }
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { runCatching { AppLog.copyTo(context, it) } } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = LocalWatchDimensions.current.screenPadding), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        item { SettingsHeader("内容与网络", onBack) }
        item { SettingsGroup("推荐", "控制首页每日推荐的密度") {
            SettingsChoiceBlock("每日推荐显示数量") { CompactChoices(listOf("5" to "5 首", "10" to "10 首"), dailyCount.toString()) { vm.setDailyCount(it.toInt()) } }
        } }
        if (vm.signedIn) item { SettingsGroup("账号与权益", "登录方式、会员状态和歌单同步") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(state.profile?.avatarUrl, vm.loginProvider, vm.accountId, 44.dp)
                Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) {
                    Text(state.profile?.displayName?.ifBlank { null } ?: "${loginProviderName(vm.loginProvider)}音乐用户", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(accountLabel(vm.loginProvider, vm.accountId), color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(vipSummary(state.profile, state.profileLoaded, state.profileError), color = if (state.profile?.isVipActive() == true) Color(0xFFFFC857) else Color.Gray, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(vm::refreshMembership, Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 6.dp), enabled = !state.profileLoaded || state.profileError != null || state.profile != null) { Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("刷新资料", fontSize = 11.sp, maxLines = 1) }
                OutlinedButton(onRelogin, Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.AutoMirrored.Filled.Login, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("重新登录", fontSize = 11.sp, maxLines = 1) }
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(vm::diagnose, Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Default.HealthAndSafety, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("完整诊断", fontSize = 11.sp, maxLines = 1) }
                OutlinedButton({ confirmUpload = true }, Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 6.dp), enabled = state.diagnosticUploadState !is DiagnosticUploadState.Uploading && vm.featureEnabled("diagnostics")) { Icon(Icons.Default.BugReport, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (state.diagnosticUploadState is DiagnosticUploadState.Uploading) "正在提交" else "提交诊断", fontSize = 11.sp, maxLines = 1) }
            }
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
    if (confirmUpload) WatchDialog(
        onDismissRequest = { confirmUpload = false },
        title = { Text("提交诊断？") },
        text = { Text("仅上传版本、设备型号和经过二次脱敏的日志片段，不包含账号、Cookie、二维码、搜索词或播放地址。") },
        confirmButton = { TextButton({ confirmUpload = false; vm.submitDiagnostics() }) { Text("提交") } },
        dismissButton = { TextButton({ confirmUpload = false }) { Text("取消") } },
    )
}

@Composable private fun AnnouncementsScreen(items: List<ControlAnnouncement>, seen: Set<String>, vm: AppViewModel, onBack: () -> Unit) {
    LaunchedEffect(items.map(ControlAnnouncement::id)) { items.forEach { vm.markAnnouncementSeen(it.id) } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = LocalWatchDimensions.current.screenPadding), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        item { SettingsHeader("公告", onBack) }
        if (items.isEmpty()) item { Box(Modifier.fillParentMaxHeight(.7f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("暂无公告", color = Color.Gray) } }
        items(items, key = ControlAnnouncement::id) { announcement ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = WatchSurface) {
                Column(Modifier.padding(8.dp, 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(announcement.title.take(80), Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); if (announcement.pinned) Icon(Icons.Default.PushPin, "置顶", Modifier.size(16.dp), tint = Green) }
                    Text(announcement.content.take(2000), color = WatchTextSecondary, fontSize = 11.sp)
                    if (announcement.id !in seen) Text("新公告", color = WatchAccent, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable private fun AboutScreen(
    vm: AppViewModel,
    update: UpdateUiState,
    onDownloadUpdate: (ControlUpdate) -> Unit,
    onInstallUpdate: (UpdateUiState.Ready) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var externalError by rememberSaveable { mutableStateOf<String?>(null) }
    val openExternal: (Intent, String) -> Unit = { intent, failureMessage ->
        runCatching { context.startActivity(intent) }
            .onSuccess { externalError = null }
            .onFailure { error -> AppLog.write("INTENT", "${error.javaClass.simpleName}:${error.message.orEmpty()}"); externalError = failureMessage }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = LocalWatchDimensions.current.screenPadding), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    item { SettingsHeader("关于", onBack) }
    item { WatchListRow("QMusic Watch", "版本 ${BuildConfig.VERSION_NAME} · 第三方非官方客户端", leading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, Modifier.size(19.dp), tint = WatchAccent) } }) }
    item { WatchListRow("开发者", "Ronan", leading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.Code, null, Modifier.size(19.dp), tint = WatchAccent) } }) }
    when (update) {
        UpdateUiState.Idle -> item { OutlinedButton(vm::checkForUpdate, Modifier.fillMaxWidth()) { Icon(Icons.Default.SystemUpdate, null); Spacer(Modifier.width(7.dp)); Text("检查服务器更新") } }
        UpdateUiState.Checking -> item { OutlinedButton({}, Modifier.fillMaxWidth(), enabled = false) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(7.dp)); Text("正在检查") } }
        UpdateUiState.NoUpdate -> { item { WatchListRow("当前已是最新版本", leading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.Verified, null, Modifier.size(19.dp), tint = WatchAccent) } }) }; item { TextButton(vm::checkForUpdate, Modifier.fillMaxWidth()) { Text("重新检查") } } }
        is UpdateUiState.Available -> {
            item { WatchListRow("发现 ${update.release.versionName}${if (update.release.forceUpdate) " · 重要更新" else ""}", "${update.release.title} · ${formatFileSize(update.release.apk.sizeBytes)}", leading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.NewReleases, null, Modifier.size(19.dp), tint = WatchAccent) } }) }
            item { WatchPrimaryButton("下载并安装", Modifier.fillMaxWidth()) { onDownloadUpdate(update.release) } }
        }
        is UpdateUiState.Downloading -> { item { Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text("正在下载 ${formatFileSize(update.downloadedBytes)} / ${formatFileSize(update.totalBytes)}"); LinearProgressIndicator(progress = { if (update.totalBytes > 0) update.downloadedBytes.toFloat() / update.totalBytes else 0f }, modifier = Modifier.fillMaxWidth()) } } }
        is UpdateUiState.Verifying -> item { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("正在校验安装包") } }
        is UpdateUiState.Ready -> {
            item { WatchListRow("安装包校验通过", "${update.release.versionName} · 签名、包名和哈希均一致", leading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.Verified, null, Modifier.size(19.dp), tint = WatchAccent) } }) }
            item { WatchPrimaryButton("打开系统安装器", Modifier.fillMaxWidth()) { onInstallUpdate(update) } }
        }
        is UpdateUiState.Error -> {
            item { Text(update.message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
            item { OutlinedButton({ update.release?.let(onDownloadUpdate) ?: vm.checkForUpdate() }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("重试") } }
        }
    }
    externalError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
    item { TextButton({ openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/huanghao897/QMusicWatch/releases")), "系统没有可用的浏览器") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.OpenInBrowser, null); Spacer(Modifier.width(6.dp)); Text("手动打开 GitHub 发布页") } }
    item { Text("本项目与腾讯或 QQ 音乐无隶属、赞助或认可关系。不绕过会员、地区、付费或 DRM 限制。", color = WatchTextSecondary, fontSize = 10.sp) }
    item { Text("开源与致谢", fontWeight = FontWeight.Bold, fontSize = 12.sp); Text("QQMusicApi · QQmusic-API · LX Music · Tides-WearOS · Horologist · HeyWear", color = WatchTextSecondary, fontSize = 10.sp) }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    else -> "${bytes / 1024} KB"
}

@Composable private fun WatchActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = WatchTextPrimary,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(9.dp)) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
            Text(label, color = tint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun TrackRow(track: Track, vm: AppViewModel, liked: Boolean = false, playlistId: String? = null, removeFromPlaylist: Boolean = false, queue: List<Track> = listOf(track), playlists: List<MusicCollection> = emptyList()) {
    val dimensions = LocalWatchDimensions.current
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var choosePlaylist by remember { mutableStateOf(false) }
    var selectedLike by remember(track.id) { mutableStateOf<Boolean?>(null) }
    var likePending by remember(track.id) { mutableStateOf(false) }
    val effectiveLiked = selectedLike ?: liked
    Surface(
        onClick = { vm.requestPlay(track, sourceQueue = queue) },
        modifier = Modifier.fillMaxWidth().height(dimensions.trackRowHeight),
        shape = RoundedCornerShape(dimensions.cornerRadius),
        color = Color.Transparent,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(safeLocalOrGatewayUri(track.artworkUrl).ifBlank { null })
                    .size(96)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                modifier = Modifier.size(dimensions.artworkSize).clip(RoundedCornerShape(8.dp)).background(WatchSurfaceRaised),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(track.title, Modifier.weight(1f, fill = false), fontSize = dimensions.bodySp.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (track.requiresVip) { Spacer(Modifier.width(4.dp)); Text("VIP", color = WatchVip, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                    if (effectiveLiked) { Spacer(Modifier.width(4.dp)); Icon(Icons.Default.Favorite, null, Modifier.size(12.dp), tint = WatchLike) }
                }
                Text(track.artists.joinToString(" / "), color = WatchTextSecondary, fontSize = dimensions.secondarySp.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            WatchIconButton(Icons.Default.MoreVert, "更多") { menu = true }
        }
    }
    if (menu) WatchDialog(
        onDismissRequest = { menu = false },
        title = { Text(track.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                WatchActionRow(if (effectiveLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (effectiveLiked) "取消喜欢" else "喜欢", if (effectiveLiked) WatchLike else WatchTextPrimary) {
                    if (!likePending) {
                        val target = !effectiveLiked
                        selectedLike = target
                        likePending = true
                        vm.like(track, target) { success -> likePending = false; selectedLike = target.takeIf { success } }
                    }
                    menu = false
                }
                WatchActionRow(Icons.Default.Download, "缓存歌曲") { vm.cache(track); menu = false }
                WatchActionRow(Icons.Default.SkipNext, "下一首播放") { vm.enqueueNext(track); menu = false }
                WatchActionRow(Icons.AutoMirrored.Filled.PlaylistAdd, "添加到播放列表") { vm.addToQueue(track); menu = false }
                if (playlists.isNotEmpty()) WatchActionRow(Icons.Default.LibraryAdd, "加入我的歌单") { menu = false; choosePlaylist = true }
                if (removeFromPlaylist && playlistId != null) WatchActionRow(Icons.Default.RemoveCircleOutline, "从此歌单移除", MaterialTheme.colorScheme.error) { vm.removeFromPlaylist(track, playlistId); menu = false }
            }
        },
        confirmButton = { TextButton({ menu = false }) { Text("关闭") } },
    )
    if (choosePlaylist) WatchDialog(
        onDismissRequest = { choosePlaylist = false },
        title = { Text("加入歌单") },
        text = {
            LazyColumn(Modifier.heightIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(playlists.filter { it.owned != false && it.directoryId != "201" }, key = { it.directoryId }) { playlist ->
                    WatchListRow(
                        title = playlist.title,
                        subtitle = if (playlist.trackCount >= 0) "${playlist.trackCount} 首" else "我的歌单",
                        onClick = { vm.addToPlaylist(track, playlist.directoryId); choosePlaylist = false },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ choosePlaylist = false }) { Text("取消") } },
    )
}

@Composable private fun QueueScreen(queue: List<Track>, currentIndex: Int, reversed: Boolean, state: AppUiState, vm: AppViewModel, onBack: () -> Unit) {
    val dimensions = LocalWatchDimensions.current
    var query by remember { mutableStateOf("") }
    var saveDialog by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }
    var queueMenu by remember { mutableStateOf(false) }
    var playlistTitle by remember { mutableStateOf("") }
    var workingEntries by remember(queue) { mutableStateOf(stableQueueEntries(queue)) }
    var dragInProgress by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    val library = state.library
    LaunchedEffect(state.queueImportTitle) { selectedIds.clear() }
    LaunchedEffect(queue, dragInProgress) {
        if (!dragInProgress) workingEntries = stableQueueEntries(queue)
    }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        workingEntries = moveQueueEntry(workingEntries, from.index, to.index)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val currentTrackId = state.currentTrack?.id ?: queue.getOrNull(currentIndex)?.id
    val shown = remember(workingEntries, query) {
        workingEntries.withIndex().filter { indexed ->
            query.isBlank() ||
                indexed.value.track.title.contains(query, true) ||
                indexed.value.track.artists.any { artist -> artist.contains(query, true) }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = dimensions.screenPadding)) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            WatchIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.size(34.dp), onClick = onBack)
            Text("播放列表", Modifier.weight(1f), fontSize = dimensions.titleSp.sp, fontWeight = FontWeight.Bold)
            WatchIconButton(
                if (reversed) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                if (reversed) "倒序" else "正序",
                onClick = vm::reverseQueue,
            )
            WatchIconButton(Icons.Default.MoreVert, "播放列表操作") { queueMenu = true }
        }
        WatchSearchField(
            query,
            { query = it },
            "筛选播放列表",
            Modifier.fillMaxWidth(),
            leadingIcon = Icons.Default.Search,
        )
        Text(
            "${workingEntries.size} 首",
            color = WatchTextSecondary,
            fontSize = dimensions.secondarySp.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (workingEntries.isEmpty()) item {
                Box(
                    Modifier.fillParentMaxHeight().fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("播放列表为空", color = WatchTextSecondary, fontSize = dimensions.bodySp.sp)
                }
            } else if (shown.isEmpty()) item {
                Box(
                    Modifier.fillParentMaxHeight().fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("没有匹配的歌曲", color = WatchTextSecondary, fontSize = dimensions.bodySp.sp)
                }
            }
            items(shown, key = { it.value.stableKey }) { indexed ->
                val index = indexed.index
                val entry = indexed.value
                val track = entry.track
                ReorderableItem(reorderableState, key = entry.stableKey) { dragging ->
                    val elevation by animateDpAsState(
                        if (dragging) 5.dp else 0.dp,
                        tween(140),
                        label = "queueElevation",
                    )
                    val handleModifier = if (query.isBlank()) {
                        Modifier.draggableHandle(
                            onDragStarted = {
                                dragInProgress = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragStopped = {
                                vm.replaceQueueOrder(workingEntries.map(WatchQueueEntry::track))
                                dragInProgress = false
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                        )
                    } else {
                        Modifier.alpha(.28f)
                    }
                    Surface(
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = null,
                                placementSpec = tween(160),
                                fadeOutSpec = null,
                            )
                            .height(dimensions.trackRowHeight),
                        shape = RoundedCornerShape(dimensions.rowCornerRadius),
                        color = if (track.id == currentTrackId) {
                            WatchAccent.copy(alpha = .12f)
                        } else {
                            WatchSurface
                        },
                        shadowElevation = elevation,
                    ) {
                        Row(
                            Modifier.fillMaxSize()
                                .clickable {
                                    workingEntries.indexOfFirst { it.stableKey == entry.stableKey }
                                        .takeIf { it >= 0 }
                                        ?.let(vm::playQueueItem)
                                }
                                .padding(start = 5.dp, end = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                if (track.id == currentTrackId) {
                                    Icon(
                                        Icons.Default.GraphicEq,
                                        null,
                                        tint = WatchAccent,
                                        modifier = Modifier.size(17.dp),
                                    )
                                } else {
                                    Text(
                                        "${index + 1}",
                                        color = WatchTextSecondary,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    color = if (track.id == currentTrackId) WatchAccent else WatchTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = dimensions.bodySp.sp,
                                )
                                Text(
                                    track.artists.joinToString(" / "),
                                    color = WatchTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = dimensions.secondarySp.sp,
                                )
                            }
                            Icon(
                                Icons.Default.DragHandle,
                                if (query.isBlank()) "拖动排序" else "筛选时不可排序",
                                Modifier.size(36.dp).then(handleModifier).padding(8.dp),
                                tint = WatchTextSecondary,
                            )
                            WatchIconButton(
                                Icons.Default.RemoveCircleOutline,
                                "移除",
                                Modifier.size(36.dp),
                            ) {
                                workingEntries.indexOfFirst { it.stableKey == entry.stableKey }
                                    .takeIf { it >= 0 }
                                    ?.let(vm::removeFromQueue)
                            }
                        }
                    }
                }
            }
        }
    }
    if (queueMenu) WatchDialog(
        onDismissRequest = { queueMenu = false },
        title = { Text("播放列表操作", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                WatchActionRow(Icons.Default.Download, "缓存全部") { vm.cacheAll(queue, "当前播放列表"); queueMenu = false }
                WatchActionRow(Icons.Default.LibraryAdd, "选歌添加") { vm.clearQueueImport(); importDialog = true; queueMenu = false }
                WatchActionRow(Icons.AutoMirrored.Filled.PlaylistAdd, "保存为歌单") { saveDialog = true; queueMenu = false }
                WatchActionRow(Icons.Default.FilterAltOff, "移除重复歌曲") { vm.removeQueueDuplicates(); queueMenu = false }
                WatchActionRow(Icons.Default.DeleteSweep, "清空播放列表", MaterialTheme.colorScheme.error) { vm.clearQueue(); queueMenu = false }
            }
        },
        confirmButton = { TextButton({ queueMenu = false }) { Text("关闭") } },
    )
    if (saveDialog) WatchDialog(onDismissRequest = { saveDialog = false }, title = { Text("保存为我的歌单") }, text = { WatchSearchField(playlistTitle, { playlistTitle = it.take(50) }, "歌单名称") }, confirmButton = { TextButton({ if (playlistTitle.isNotBlank()) vm.saveQueueAsPlaylist(playlistTitle); saveDialog = false }) { Text("保存") } }, dismissButton = { TextButton({ saveDialog = false }) { Text("取消") } })
    if (importDialog) WatchDialog(
        onDismissRequest = { importDialog = false; vm.clearQueueImport() },
        title = { Text(state.queueImportTitle.ifBlank { "选择歌曲来源" }) },
        text = {
            when {
                state.queueImportTitle.isBlank() -> LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    item {
                        WatchListRow(
                            title = "我喜欢",
                            subtitle = "${library?.liked?.size ?: 0} 首",
                            leading = { Icon(Icons.Default.Favorite, null, Modifier.align(Alignment.Center), tint = WatchLike) },
                            onClick = vm::loadQueueImportLiked,
                        )
                    }
                    items(library?.playlists.orEmpty(), key = { "${it.directoryId}:${it.id}" }) { playlist ->
                        WatchListRow(
                            title = playlist.title,
                            subtitle = if (playlist.trackCount >= 0) "${playlist.trackCount} 首" else "点击读取",
                            leading = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.align(Alignment.Center), tint = WatchAccent) },
                            onClick = { vm.loadQueueImportPlaylist(playlist) },
                        )
                    }
                }
                state.queueImportLoading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("已选 ${selectedIds.size} 首", Modifier.weight(1f), color = Green); TextButton({ if (selectedIds.size == state.queueImportTracks.size) selectedIds.clear() else { selectedIds.clear(); selectedIds.addAll(state.queueImportTracks.map(Track::id)) } }) { Text(if (selectedIds.size == state.queueImportTracks.size) "取消全选" else "全选") } }
                    LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(state.queueImportTracks, key = { it.id }) { track ->
                            val selected = track.id in selectedIds
                            WatchListRow(
                                title = track.title,
                                subtitle = track.artists.joinToString(" / "),
                                trailing = {
                                    Checkbox(
                                        selected,
                                        { checked -> if (checked) selectedIds.add(track.id) else selectedIds.remove(track.id) },
                                        Modifier.size(36.dp),
                                    )
                                },
                                onClick = {
                                    if (selected) selectedIds.remove(track.id) else selectedIds.add(track.id)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { if (state.queueImportTitle.isNotBlank() && !state.queueImportLoading) TextButton({ vm.addSelectedQueueTracks(selectedIds.toSet()); importDialog = false }, enabled = selectedIds.isNotEmpty()) { Text("添加 ${selectedIds.size} 首") } },
        dismissButton = { TextButton({ if (state.queueImportTitle.isBlank()) { importDialog = false; vm.clearQueueImport() } else vm.clearQueueImport() }) { Text(if (state.queueImportTitle.isBlank()) "取消" else "返回") } },
    )
}

@Composable private fun CollectionRow(value: MusicCollection, open: () -> Unit = {}) = WatchListRow(
    title = value.title,
    subtitle = if (value.trackCount >= 0) "${value.trackCount} 首" else "点击查看",
    leading = { Box(Modifier.fillMaxSize().background(WatchSurface, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(18.dp), tint = WatchAccent) } },
    trailing = { Icon(Icons.Default.ChevronRight, null, Modifier.size(17.dp), tint = WatchTextSecondary) },
    onClick = open,
)
@Composable private fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) = WatchSectionHeader(text, action = action, onAction = onAction)
@Composable private fun MiniPlayer(track: Track?, lyrics: List<LyricLine>, vm: AppViewModel, open: () -> Unit) {
    if (track == null) return
    val dimensions = LocalWatchDimensions.current
    val context = LocalContext.current
    var position by remember(track.id) { mutableLongStateOf(0L) }
    var duration by remember(track.id) { mutableLongStateOf(0L) }
    var playing by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id) {
        while (isActive) {
            position = vm.playbackPosition()
            duration = vm.playbackDuration()
            playing = vm.isPlaying()
            delay(350)
        }
    }
    val previewIndex = activeLyricIndex(lyrics, position).takeIf { it >= 0 }
        ?: lyrics.indexOfFirst { it.timeMs >= 0 }.takeIf { it >= 0 }
        ?: lyrics.indexOfFirst { it.text.isNotBlank() }
    val preview = lyrics.getOrNull(previewIndex)?.text?.takeIf { it.isNotBlank() } ?: "正在播放"
    val progress = if (duration > 0L) {
        (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Surface(color = WatchSurface, tonalElevation = 0.dp, border = BorderStroke(1.dp, WatchDivider)) {
        Box(Modifier.fillMaxWidth().height(dimensions.miniPlayerHeight)) {
            Row(
                Modifier.fillMaxSize().padding(start = 6.dp, end = 2.dp, bottom = 2.dp).clickable(onClick = open),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(safeLocalOrGatewayUri(track.artworkUrl).ifBlank { null })
                        .size(96)
                        .crossfade(false)
                        .build(),
                    contentDescription = "当前歌曲封面",
                    modifier = Modifier.size(dimensions.artworkSize).clip(RoundedCornerShape(8.dp)).background(WatchSurfaceRaised),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = dimensions.bodySp.sp, fontWeight = FontWeight.SemiBold)
                    Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, color = WatchTextSecondary, fontSize = dimensions.secondarySp.sp)
                }
                WatchIconButton(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "播放") {
                    if (playing) vm.pausePlayback() else vm.resumePlayback()
                }
            }
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(WatchDivider),
            ) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(WatchAccent),
                )
            }
        }
    }
}
