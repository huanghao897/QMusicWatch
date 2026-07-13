package com.ronan.qmusicwatch

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ronan.qmusicwatch.data.DownloadEntity
import com.ronan.qmusicwatch.data.AppLog
import com.ronan.qmusicwatch.login.MusicCookie
import com.ronan.qmusicwatch.lyrics.LyricLine
import com.ronan.qmusicwatch.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

private val Green = Color(0xFF6DFF9E)
private val Surface = Color(0xFF111714)
private fun nextPlayMode(mode: String) = when (mode) { "sequential" -> "repeat_one"; "repeat_one" -> "loop_all"; "loop_all" -> "shuffle"; else -> "sequential" }
private fun playModeName(mode: String) = when (mode) { "repeat_one" -> "单曲循环"; "loop_all" -> "列表循环"; "shuffle" -> "随机播放"; else -> "顺序播放" }
private fun playModeIcon(mode: String) = when (mode) { "repeat_one" -> Icons.Default.RepeatOne; "loop_all" -> Icons.Default.Repeat; "shuffle" -> Icons.Default.Shuffle; else -> Icons.Default.FormatListNumbered }
private fun lyricTime(ms: Long) = "${ms.coerceAtLeast(0) / 60_000}:${((ms.coerceAtLeast(0) / 1000) % 60).toString().padStart(2, '0')}"
private fun loginProviderName(provider: String) = if (provider == "wechat") "微信" else "QQ"
private fun accountLabel(provider: String, accountId: String?) = if (provider == "wechat") "微信账号已绑定" else "QQ号 ${accountId.orEmpty()}"
private fun vipSummary(profile: UserProfile?): String = when (profile?.isVip) {
    true -> buildString { append(profile.vipName.ifBlank { "会员有效" }); profile.vipExpireAt?.let { append(" · 到期 "); append(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(it * 1000))) } }
    false -> "未检测到会员播放权益"
    null -> "正在读取会员状态"
}
internal fun <T> dailyBatch(items: List<T>, offset: Int, count: Int): List<T> = if (items.isEmpty()) emptyList() else List(minOf(count, items.size)) { items[(offset + it) % items.size] }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideStatusBar()
        setContent { QMusicTheme { QMusicApp() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

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
    val pureBlack by vm.pureBlack.collectAsStateWithLifecycle()
    val lowPowerPlayer by vm.lowPowerPlayer.collectAsStateWithLifecycle()
    val wifiOnlyDownload by vm.wifiOnlyDownload.collectAsStateWithLifecycle()
    val lastSleepMinutes by vm.lastSleepMinutes.collectAsStateWithLifecycle()
    val dailyCount by vm.dailyCount.collectAsStateWithLifecycle()
    val searchHistory by vm.searchHistory.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val queueIndex by vm.queueIndex.collectAsStateWithLifecycle()
    val queueReversed by vm.queueReversed.collectAsStateWithLifecycle()
    val sleepRemaining by vm.sleepRemaining.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(state.playEvent, autoOpenPlayer) {
        if (state.playEvent != 0L && autoOpenPlayer && state.currentTrack != null && backStack?.destination?.route != "player") nav.navigate("player") { launchSingleTop = true }
    }
    Scaffold(containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom), snackbarHost = { SnackbarHost(snackbar) }, bottomBar = { if (backStack?.destination?.route != "player") MiniPlayer(state.currentTrack) { nav.navigate("player") } }) { padding ->
        NavHost(nav, "home", Modifier.padding(padding)) {
            composable("home") { HomeScreen(nav, state, vm, dailyCount) }
            composable("login") { LoginScreen(state, vm) { nav.popBackStack() } }
            composable("search") { SearchScreen(nav, state, vm, searchHistory) }
            composable("library") { LaunchedEffect(Unit) { vm.loadLibrary() }; LibraryScreen(nav, state, vm) }
            composable("recent") { LaunchedEffect(Unit) { vm.loadRecent() }; TrackListScreen("最近播放", state.recent, vm) }
            composable("downloads") { DownloadScreen(downloads, vm) }
            composable("player") { PlayerScreen(state.currentTrack, state.lyrics, vm, playMode, lyricSize, lyricOriginal, lyricTranslation, lyricOffset, lyricAnimation, lowPowerPlayer, { nav.navigate("queue") }) { nav.popBackStack() } }
            composable("queue") { LaunchedEffect(Unit) { if (vm.signedIn) vm.loadLibrary() }; QueueScreen(queue, queueIndex, queueReversed, state.library, vm) { nav.popBackStack() } }
            composable("detail") { DetailScreen(state.detail, state.detailDirectoryId, vm) }
            composable("settings") { SettingsCenter(nav) { nav.popBackStack() } }
            composable("settings/display") { DisplaySettingsScreen(vm, lyricSize, lyricOriginal, lyricTranslation, lyricOffset, lyricAnimation, pureBlack, lowPowerPlayer) { nav.popBackStack() } }
            composable("settings/playback") { PlaybackSettingsScreen(vm, quality, headphoneWarning, autoOpenPlayer, playMode, sleepRemaining, wifiOnlyDownload, lastSleepMinutes) { nav.popBackStack() } }
            composable("settings/network") {
                NetworkSettingsScreen(vm, dailyCount, state.diagnostic, state.profile, onRelogin = {
                    vm.logout()
                    nav.navigate("login") { popUpTo("home") }
                }) { nav.popBackStack() }
            }
            composable("settings/about") { AboutScreen(vm) { nav.popBackStack() } }
        }
    }
    state.pendingSpeakerTrack?.let { track ->
        AlertDialog(onDismissRequest = vm::dismissSpeakerPrompt, title = { Text("未检测到耳机") }, text = { Text("建议连接蓝牙或有线耳机，是否仍使用手表扬声器播放？") },
            confirmButton = { TextButton(onClick = vm::continueOnSpeaker) { Text("继续外放") } },
            dismissButton = { TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("连接蓝牙") } })
    }
    if (showNotice) AlertDialog(onDismissRequest = {}, title = { Text("第三方非官方客户端") }, text = { Text("QMusic Watch 与腾讯或 QQ 音乐无隶属或认可关系。请尊重版权和账号权益，本项目不会绕过会员、地区、付费或 DRM 限制。") }, confirmButton = { Button({ noticePrefs.edit().putBoolean("accepted", true).apply(); showNotice = false }) { Text("我知道了") } })
}

@Composable private fun HomeScreen(nav: NavHostController, state: AppUiState, vm: AppViewModel, dailyCount: Int) {
    val context = LocalContext.current
    val pager = rememberPagerState { 2 }
    var dailyOffset by remember { mutableIntStateOf(0) }
    val daily = state.home?.daily.orEmpty()
    val shown = dailyBatch(daily, dailyOffset, dailyCount)
    LaunchedEffect(pager.settledPage, vm.signedIn) { if (pager.settledPage == 1 && vm.signedIn) { vm.loadProfile(); vm.loadLibrary(); vm.loadRecent() } }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            if (page == 0) LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(8.dp)); Text("QMusic Watch", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("第三方非官方客户端", color = Color.Gray) }
                item { FilledTonalButton({ nav.navigate("search") }, Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(7.dp)); Text("搜索歌曲、歌单、歌手、专辑") } }
                item { SectionTitle("每日推荐", "换一换") { if (daily.isNotEmpty()) dailyOffset = (dailyOffset + dailyCount) % daily.size } }
                items(shown, key = { it.id }) { TrackRow(it, vm, queue = shown) }
            } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(8.dp)); Text("我的", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                item {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Surface) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            val avatar = remember(state.profile?.avatarUrl) { state.profile?.avatarUrl?.takeIf(String::isNotBlank)?.let { ImageRequest.Builder(context).data(it).addHeader("Referer", "https://y.qq.com/").build() } }
                            AsyncImage(avatar, null, Modifier.size(68.dp).clip(RoundedCornerShape(50)).background(Color.DarkGray), fallback = androidx.compose.ui.res.painterResource(com.ronan.qmusicwatch.R.drawable.ic_launcher))
                            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(if (vm.signedIn) state.profile?.displayName?.ifBlank { null } ?: "${loginProviderName(vm.loginProvider)}音乐用户" else "尚未登录", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(if (vm.signedIn) accountLabel(vm.loginProvider, vm.accountId) else "登录后同步收藏与歌单", color = Color.Gray); if (vm.signedIn) Text(vipSummary(state.profile), color = if (state.profile?.isVip == true) Color(0xFFFFC857) else Color.Gray, fontSize = 13.sp) }
                        }
                    }
                }
                if (!vm.signedIn) item { Button({ nav.navigate("login") }, Modifier.fillMaxWidth()) { Text("扫码登录") } }
                else {
                    item { SettingsModule("我喜欢", "${state.library?.liked?.size ?: 0} 首歌曲", Icons.Default.Favorite) { nav.navigate("library") } }
                    item { SettingsModule("我创建的歌单", "${state.library?.playlists?.count { it.owned != false } ?: 0} 个歌单", Icons.Default.QueueMusic) { nav.navigate("library") } }
                    item { SettingsModule("收藏歌单", "${state.library?.playlists?.count { it.owned == false } ?: 0} 个歌单", Icons.Default.LibraryMusic) { nav.navigate("library") } }
                }
                item { SettingsModule("最近播放", "本地与云端播放记录", Icons.Default.History) { nav.navigate("recent") } }
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
        if (provider == null) {
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
    val cookieManager = remember { CookieManager.getInstance().apply { setAcceptCookie(true) } }
    val bridge = remember(provider) { QrLoginBridge { cookie -> if (!submitted) { submitted = true; onCookie(cookie) } } }
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
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host.orEmpty()
                    return request.url.scheme != "https" || !(host == "qq.com" || host.endsWith(".qq.com"))
                }
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(
                        """(function(){if(window.__qmusicBridge)return;window.__qmusicBridge=true;window.addEventListener('message',function(e){QMusicLogin.onMessage(typeof e.data==='string'?e.data:JSON.stringify(e.data));});if(window.Music&&Music.client&&Music.client.open){var original=Music.client.open.bind(Music.client);Music.client.open=function(scope,action,payload){if(action==='scanLoginResult'){QMusicLogin.onMessage(JSON.stringify(payload||{}));return Promise.resolve({});}return original(scope,action,payload);};}})();""",
                        null
                    )
                }
            }
            loadUrl("https://y.qq.com/m/client/qr_code_login/index.html?tmeAppID=qqmusic&frame=1&ct=11&cv=20030508")
        }
    }, modifier = Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(16.dp)))
    DisposableEffect(Unit) { onDispose { webView?.stopLoading(); webView?.destroy(); webView = null } }
}

private class QrLoginBridge(private val onCookie: (String) -> Unit) {
    @JavascriptInterface fun onMessage(message: String) {
        MusicCookie.fromQrMessage(message)?.let { cookie ->
            Handler(Looper.getMainLooper()).post { onCookie(cookie) }
        }
    }
}

@Composable private fun SearchScreen(nav: NavHostController, state: AppUiState, vm: AppViewModel, history: List<String>) {
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("track") }
    val names = linkedMapOf("track" to "歌曲", "playlist" to "歌单", "artist" to "歌手", "album" to "专辑")
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索") }, trailingIcon = { IconButton({ vm.search(query, type) }) { Icon(Icons.Default.Search, null) } }, keyboardActions = KeyboardActions(onDone = { vm.search(query, type) }))
        if (query.isBlank() && history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("最近搜索", Modifier.weight(1f), color = Color.Gray); TextButton(vm::clearSearchHistory) { Text("清空") } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { history.forEach { value -> AssistChip({ query = value; vm.search(value, type) }, label = { Text(value, maxLines = 1) }, leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(16.dp)) }) } }
        }
        ScrollableTabRow(names.keys.indexOf(type), edgePadding = 0.dp) { names.forEach { (key, label) -> Tab(type == key, { type = key; if (query.isNotBlank()) vm.search(query, key) }, text = { Text(label) }) } }
        LazyColumn { if (type == "track") items(state.searchTracks, key = { it.id }) { TrackRow(it, vm, queue = state.searchTracks) } else items(state.searchCollections, key = { it.id }) { CollectionRow(it) { vm.loadDetail(type, it); nav.navigate("detail") } }; if (state.searchCursor != null) item { TextButton({ vm.search(state.searchQuery, type, loadMore = true) }, Modifier.fillMaxWidth(), enabled = !state.searchLoading) { if (state.searchLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("加载更多") } } }
    }
}

@Composable private fun LibraryScreen(nav: NavHostController, state: AppUiState, vm: AppViewModel) {
    var editing by remember { mutableStateOf<MusicCollection?>(null) }
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    val created = state.library?.playlists.orEmpty().filter { it.owned != false }
    val collected = state.library?.playlists.orEmpty().filter { it.owned == false }
    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        item { SectionTitle("我喜欢") }
        items(state.library?.liked.orEmpty(), key = { it.id }) { TrackRow(it, vm, liked = true, playlistId = state.library?.playlists?.firstOrNull { list -> list.directoryId != "201" }?.directoryId, queue = state.library?.liked.orEmpty()) }
        item { SectionTitle("我创建的歌单", "新建") { title = ""; creating = true } }
        items(created, key = { it.id }) { item ->
            ListItem(modifier = Modifier.clickable { vm.loadDetail("playlist", item, editable = true); nav.navigate("detail") }, headlineContent = { Text(item.title) }, supportingContent = { Text("${item.trackCount} 首") }, leadingContent = { Icon(Icons.Default.QueueMusic, null, tint = Green) }, trailingContent = { Row { IconButton({ title = item.title; editing = item }) { Icon(Icons.Default.Edit, null) }; IconButton({ vm.deletePlaylist(item.directoryId) }) { Icon(Icons.Default.Delete, null) } } })
        }
        item { SectionTitle("收藏歌单") }
        items(collected, key = { it.id }) { item -> CollectionRow(item) { vm.loadDetail("playlist", item); nav.navigate("detail") } }
        item { OutlinedButton(vm::logout, Modifier.fillMaxWidth()) { Text("退出登录") } }
    }
    if (creating || editing != null) AlertDialog(onDismissRequest = { creating = false; editing = null }, title = { Text(if (creating) "新建歌单" else "重命名歌单") }, text = { OutlinedTextField(title, { title = it.take(50) }, singleLine = true) }, confirmButton = { TextButton({ if (creating) vm.createPlaylist(title) else vm.renamePlaylist(editing!!.directoryId, title); creating = false; editing = null }) { Text("保存") } }, dismissButton = { TextButton({ creating = false; editing = null }) { Text("取消") } })
}

@Composable private fun TrackListScreen(title: String, tracks: List<Track>, vm: AppViewModel) = LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
    item { SectionTitle(title) }; items(tracks, key = { it.id }) { TrackRow(it, vm, queue = tracks) }
}

@Composable private fun DownloadScreen(downloads: List<DownloadEntity>, vm: AppViewModel) {
    val own = downloads.filter { it.ownerAccountId == vm.accountId }
    val totalBytes = own.sumOf { item -> maxOf(item.downloadedBytes, java.io.File(item.filePath).takeIf { item.status == "complete" && it.exists() }?.length() ?: 0L) }
    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        item { SectionTitle("离线缓存") }
        item { Text("当前账号占用 %.1f MB · ${own.size} 首".format(totalBytes / 1024f / 1024f), color = Color.Gray); TextButton(vm::deleteInvalidDownloads) { Text("一键删除失效缓存") } }
        downloads.groupBy(DownloadEntity::groupName).forEach { (group, values) ->
            item(key = "group-$group") { Text(group, color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp)) }
            items(values, key = { "${it.ownerAccountId}-${it.trackId}" }) { item ->
                val status = when (item.status) { "complete" -> "已完成"; "downloading" -> "下载中"; "paused" -> "已暂停"; "failed_storage" -> "存储不足，需保留 256MB"; else -> "下载失败" }
                ListItem(headlineContent = { Text(item.title) }, supportingContent = { Text("$status · ${item.downloadedBytes / 1024 / 1024} MB${if (vm.accountId != item.ownerAccountId) " · 已锁定" else ""}") },
                    trailingContent = { Row { if (item.status == "downloading") IconButton({ vm.pauseDownload(item.trackId) }) { Icon(Icons.Default.Pause, null) } else if (item.status == "paused" || item.status.startsWith("failed")) IconButton({ vm.resumeDownload(item) }, enabled = vm.accountId == item.ownerAccountId) { Icon(Icons.Default.PlayArrow, null) }; IconButton({ vm.deleteDownload(item.trackId, item.ownerAccountId) }) { Icon(Icons.Default.Delete, null) } } })
            }
        }
    }
}

@Composable private fun PlayerScreen(
    track: Track?, lyrics: List<LyricLine>, vm: AppViewModel,
    playMode: String, lyricSize: String, showOriginal: Boolean, showTranslation: Boolean, lyricOffset: Long,
    lyricAnimation: String, lowPowerPlayer: Boolean,
    openQueue: () -> Unit, onBack: () -> Unit,
) {
    if (track == null) return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { IconButton(onBack, Modifier.align(Alignment.TopStart).padding(8.dp)) { Icon(Icons.Default.ArrowBack, "返回") }; Text("尚未播放") }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    val active = lyrics.indexOfLast { it.timeMs <= position + lyricOffset }.coerceAtLeast(0)
    val lyricSp = when (lyricSize) { "small" -> 16; "large" -> 22; else -> 18 }
    val listState = rememberLazyListState()
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
    LaunchedEffect(track.id, lowPowerPlayer) { var ticks = 0; val interval = if (lowPowerPlayer) 1_000L else 500L; while (true) { position = vm.playbackPosition(); duration = vm.playbackDuration(); playing = vm.isPlaying(); if (++ticks * interval >= 10_000) { ticks = 0; vm.savePlaybackState() }; delay(interval) } }
    LaunchedEffect(active, lyrics.size) {
        if (lyrics.isNotEmpty() && !listState.isScrollInProgress) {
            while (listState.layoutInfo.viewportSize.height == 0) delay(16)
            listState.animateScrollToItem(active)
        }
    }
    Box(Modifier.fillMaxSize().focusRequester(focusRequester).focusable().onRotaryScrollEvent { event ->
        if (!locked) { if (pager.currentPage == 0) vm.adjustVolume(if (event.verticalScrollPixels < 0) 1 else -1) else scope.launch { listState.scrollBy(event.verticalScrollPixels) } }
        true
    }) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = !locked) { page ->
            if (page == 1) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), state = listState, contentPadding = PaddingValues(vertical = (maxHeight / 2 - 32.dp).coerceAtLeast(0.dp)), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (lyrics.isEmpty()) item { Text("暂无歌词", color = Color.Gray, fontSize = 20.sp) }
                    items(lyrics.size) { index ->
                        val line = lyrics[index]
                        val distance = kotlin.math.abs(index - active)
                        val targetAlpha = when (lyricAnimation) { "off" -> if (distance == 0) 1f else .55f; "strong" -> when (distance) { 0 -> 1f; 1 -> .48f; 2 -> .22f; else -> .08f }; else -> when (distance) { 0 -> 1f; 1 -> .65f; 2 -> .4f; else -> .2f } }
                        val lineAlpha by androidx.compose.animation.core.animateFloatAsState(targetAlpha, androidx.compose.animation.core.tween(if (lyricAnimation == "off") 0 else if (lyricAnimation == "strong") 650 else 350), label = "lyricFade")
                        Column(Modifier.fillMaxWidth().alpha(lineAlpha).clickable { vm.seek((line.timeMs - lyricOffset).coerceAtLeast(0)) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (showOriginal) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(line.text, Modifier.weight(1f), fontSize = (if (index == active) lyricSp + 3 else lyricSp).sp, color = if (index == active) Green else Color.White, fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal, textAlign = androidx.compose.ui.text.style.TextAlign.Center); Text(lyricTime(line.timeMs), color = if (index == active) Green else Color.Gray, fontSize = 12.sp) }
                            if (showTranslation) line.translation?.let { Text(it, color = if (index == active) Green.copy(alpha = .78f) else Color(0xFFB7C9FF), fontSize = (lyricSp - 4).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                            if (!showOriginal && showTranslation) Text(lyricTime(line.timeMs), color = if (index == active) Green else Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    var dragX by remember { mutableFloatStateOf(0f) }; var dragY by remember { mutableFloatStateOf(0f) }
                    AsyncImage(track.artworkUrl.ifBlank { null }, null, Modifier.size(if (lowPowerPlayer) 148.dp else 170.dp).background(Surface, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                        .pointerInput(track.id) { detectTapGestures(onDoubleTap = { if (vm.isPlaying()) vm.pausePlayback() else vm.resumePlayback() }) }
                        .pointerInput(track.id) { detectDragGestures(onDragStart = { dragX = 0f; dragY = 0f }, onDragEnd = { if (abs(dragX) > abs(dragY) && abs(dragX) > 60) { if (dragX < 0) vm.skipNext() else vm.skipPrevious() } else if (abs(dragY) > 60) vm.adjustVolume(if (dragY < 0) 1 else -1) }) { change, amount -> change.consume(); dragX += amount.x; dragY += amount.y } })
                    Spacer(Modifier.height(10.dp)); Text(track.title, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artists.joinToString(" / "), color = Color.Gray, maxLines = 1)
                    Slider(position.toFloat(), { vm.seek(it.toLong()); position = it.toLong() }, valueRange = 0f..duration.coerceAtLeast(1).toFloat())
                    Row { IconButton(vm::skipPrevious) { Icon(Icons.Default.SkipPrevious, "上一首") }; IconButton({ if (playing) vm.pausePlayback() else vm.resumePlayback() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(38.dp)) }; IconButton(vm::skipNext) { Icon(Icons.Default.SkipNext, "下一首") }; IconButton(openQueue) { Icon(Icons.Default.QueueMusic, "播放列表") } }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { listOf("sequential", "loop_all", "repeat_one", "shuffle").forEach { mode -> IconToggleButton(playMode == mode, { vm.setPlayMode(mode) }) { Icon(playModeIcon(mode), playModeName(mode), tint = if (playMode == mode) Green else Color.Gray) } } }
                    Text(playModeName(playMode), color = Green, fontSize = 13.sp)
                    Text("向左滑查看歌词", color = Color.Gray, fontSize = 13.sp)
                }
            }
        }
        IconButton(onBack, Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = .35f), RoundedCornerShape(20.dp))) { Icon(Icons.Default.ArrowBack, "返回") }
        if (!locked) IconButton({ locked = true }, Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = .35f), RoundedCornerShape(20.dp))) { Icon(Icons.Default.LockOpen, "锁定触控") }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) { page -> Box(Modifier.size(if (pager.currentPage == page) 7.dp else 5.dp).background(if (pager.currentPage == page) Green else Color.Gray, RoundedCornerShape(50))) }
        }
        if (locked) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} })
            IconButton({ locked = false }, Modifier.align(Alignment.TopEnd).padding(8.dp).background(Surface, RoundedCornerShape(20.dp))) { Icon(Icons.Default.Lock, "解除锁定", tint = Green) }
        }
    }
}

@Composable private fun DetailScreen(detail: CollectionDetail?, editableDirectoryId: String?, vm: AppViewModel) = LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
    item { SectionTitle(detail?.title ?: "加载中", if (detail != null) "全部缓存" else null) { detail?.let { vm.cacheAll(it.tracks, it.title) } } }
    items(detail?.tracks.orEmpty(), key = { it.id }) { TrackRow(it, vm, playlistId = editableDirectoryId, removeFromPlaylist = editableDirectoryId != null, queue = detail?.tracks.orEmpty()) }
}

@Composable private fun SettingsModule(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, open: () -> Unit) = Surface(
    Modifier.fillMaxWidth().clickable(onClick = open), shape = RoundedCornerShape(20.dp), color = Surface,
) { ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold) }, supportingContent = { Text(subtitle, color = Color.Gray) }, leadingContent = { Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF252A28)) { Icon(icon, null, Modifier.padding(12.dp), tint = Color.White) } }, trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = Color.Gray) }) }

@Composable private fun SettingsHeader(title: String, onBack: () -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onBack) { Icon(Icons.Default.ArrowBack, "返回") }; Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold)
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

@Composable private fun DisplaySettingsScreen(vm: AppViewModel, lyricSize: String, lyricOriginal: Boolean, lyricTranslation: Boolean, lyricOffset: Long, lyricAnimation: String, pureBlack: Boolean, lowPowerPlayer: Boolean, onBack: () -> Unit) = LazyColumn(
    Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
) {
    item { SettingsHeader("显示与主题", onBack) }
    item { ListItem(headlineContent = { Text("AMOLED 纯黑背景") }, supportingContent = { Text("方屏手表省电显示") }, trailingContent = { Switch(pureBlack, vm::setPureBlack) }) }
    item { ListItem(headlineContent = { Text("低功耗播放器") }, supportingContent = { Text("降低进度刷新频率并缩小封面") }, trailingContent = { Switch(lowPowerPlayer, vm::setLowPowerPlayer) }) }
    item { Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) { Text("歌词字号"); Row { listOf("small" to "小", "normal" to "标准", "large" to "大").forEach { (value, label) -> FilterChip(lyricSize == value, { vm.setLyricSize(value) }, label = { Text(label) }); Spacer(Modifier.width(5.dp)) } } } }
    item { ListItem(headlineContent = { Text("显示原文歌词") }, trailingContent = { Switch(lyricOriginal, { if (it || lyricTranslation) vm.setLyricOriginal(it) }) }) }
    item { ListItem(headlineContent = { Text("显示翻译歌词") }, trailingContent = { Switch(lyricTranslation, { if (it || lyricOriginal) vm.setLyricTranslation(it) }) }) }
    item { Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) { Text("歌词动画强度"); Row { listOf("off" to "关闭", "soft" to "柔和", "strong" to "明显").forEach { (value, label) -> FilterChip(lyricAnimation == value, { vm.setLyricAnimation(value) }, label = { Text(label) }); Spacer(Modifier.width(5.dp)) } } } }
    item { Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) { Text("歌词时间偏移 ${if (lyricOffset >= 0) "+" else ""}${lyricOffset}ms"); Row { TextButton({ vm.setLyricOffset(lyricOffset - 500) }) { Text("-0.5秒") }; TextButton({ vm.setLyricOffset(0) }) { Text("归零") }; TextButton({ vm.setLyricOffset(lyricOffset + 500) }) { Text("+0.5秒") } } } }
}

@Composable private fun PlaybackSettingsScreen(vm: AppViewModel, quality: String, headphoneWarning: Boolean, autoOpenPlayer: Boolean, playMode: String, sleepRemaining: Long, wifiOnlyDownload: Boolean, lastSleepMinutes: Int?, onBack: () -> Unit) {
    val context = LocalContext.current
    var customTimer by remember { mutableStateOf(false) }; var customMinutes by remember { mutableStateOf("") }; var finishCurrent by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SettingsHeader("播放与缓存", onBack) }
        item { Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) { Text("默认音质"); Row { FilterChip(quality == "128", { vm.setQuality("128") }, label = { Text("128k") }); Spacer(Modifier.width(7.dp)); FilterChip(quality == "320", { vm.setQuality("320") }, label = { Text("320k") }) } } }
        item { ListItem(headlineContent = { Text("无耳机播放提醒") }, supportingContent = { Text("未连接耳机时播放前确认") }, trailingContent = { Switch(headphoneWarning, vm::setHeadphoneWarning) }) }
        item { ListItem(headlineContent = { Text("自动进入播放器") }, trailingContent = { Switch(autoOpenPlayer, vm::setAutoOpenPlayer) }) }
        item { ListItem(headlineContent = { Text("仅 Wi-Fi 下载") }, supportingContent = { Text("关闭后允许移动网络缓存") }, trailingContent = { Switch(wifiOnlyDownload, vm::setWifiOnlyDownload) }) }
        item { Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) { Text("播放模式"); FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("sequential", "repeat_one", "loop_all", "shuffle").forEach { mode -> FilterChip(playMode == mode, { vm.setPlayMode(mode) }, label = { Text(playModeName(mode)) }) } } } }
        item { Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) { Text("定时关闭"); if (sleepRemaining > 0) Text("剩余 ${sleepRemaining / 60}:${(sleepRemaining % 60).toString().padStart(2, '0')}", color = Green); FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { (listOfNotNull(lastSleepMinutes) + listOf(15, 30, 60)).distinct().take(4).forEach { minutes -> FilterChip(false, { vm.startSleepTimer(minutes, finishCurrent) }, label = { Text(if (minutes == lastSleepMinutes) "上次${minutes}分" else "${minutes}分") }) } }; Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(finishCurrent, { finishCurrent = it }); Text("播完当前歌曲再关闭") }; Row { TextButton({ customTimer = true }) { Text("自定义") }; if (sleepRemaining > 0) TextButton(vm::cancelSleepTimer) { Text("取消") } } } }
        item { OutlinedButton({ context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Bluetooth, null); Spacer(Modifier.width(7.dp)); Text("蓝牙耳机设置") } }
    }
    if (customTimer) AlertDialog(onDismissRequest = { customTimer = false }, title = { Text("自定义播放时间") }, text = { OutlinedTextField(customMinutes, { customMinutes = it.filter(Char::isDigit).take(4) }, label = { Text("分钟（1-1440）") }, singleLine = true) }, confirmButton = { TextButton({ customMinutes.toIntOrNull()?.coerceIn(1, 1440)?.let { vm.startSleepTimer(it, finishCurrent) }; customTimer = false }) { Text("开始") } }, dismissButton = { TextButton({ customTimer = false }) { Text("取消") } })
}

@Composable private fun NetworkSettingsScreen(vm: AppViewModel, dailyCount: Int, diagnostic: String?, profile: UserProfile?, onRelogin: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { runCatching { AppLog.copyTo(context, it) } } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SettingsHeader("内容与网络", onBack) }
        item { Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) { Text("每日推荐显示数量"); Row { FilterChip(dailyCount == 5, { vm.setDailyCount(5) }, label = { Text("5 首") }); Spacer(Modifier.width(7.dp)); FilterChip(dailyCount == 10, { vm.setDailyCount(10) }, label = { Text("10 首") }) } } }
        if (vm.signedIn) {
            item { Text("账号", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            item { ListItem(headlineContent = { Text(profile?.displayName?.ifBlank { null } ?: "${loginProviderName(vm.loginProvider)}音乐用户") }, supportingContent = { Text("${accountLabel(vm.loginProvider, vm.accountId)}\n${vipSummary(profile)}") }, leadingContent = { Icon(Icons.Default.AccountCircle, null, tint = Green) }) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton(vm::diagnose) { Icon(Icons.Default.HealthAndSafety, null); Spacer(Modifier.width(5.dp)); Text("检查登录") }; OutlinedButton(onRelogin) { Text("重新登录") } } }
        }
        diagnostic?.let { item { Text(it, color = if (it.startsWith("诊断失败")) MaterialTheme.colorScheme.error else Green, fontSize = 14.sp) } }
        item { Text("日志最多 256KB，不记录 Cookie、令牌或播放 URL。", color = Color.Gray, fontSize = 14.sp) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { OutlinedButton({ context.startActivity(Intent.createChooser(AppLog.shareIntent(context), "分享日志")) }) { Icon(Icons.Default.Share, null); Text("分享") }; OutlinedButton({ saveLog.launch("QMusicWatch-${BuildConfig.VERSION_NAME}.log") }) { Icon(Icons.Default.Save, null); Text("保存") }; TextButton(AppLog::clear) { Text("清空") } } }
        item { if (vm.signedIn) OutlinedButton(vm::logout, Modifier.fillMaxWidth()) { Text("退出登录") } }
    }
}

@Composable private fun AboutScreen(vm: AppViewModel, onBack: () -> Unit) = LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { SettingsHeader("关于", onBack) }
    item { ListItem(headlineContent = { Text("QMusic Watch") }, supportingContent = { Text("版本 ${BuildConfig.VERSION_NAME}\n第三方非官方、开源、非商业客户端") }) }
    item { ListItem(headlineContent = { Text("开发者") }, supportingContent = { Text("Ronan") }, leadingContent = { Icon(Icons.Default.Code, null, tint = Green) }) }
    item { Text("本项目与腾讯或 QQ 音乐无隶属、赞助或认可关系。不绕过会员、地区、付费或 DRM 限制。", color = Color.Gray) }
    item { Text("开源与致谢", fontWeight = FontWeight.Bold); Text("QQMusicApi（GPL-3.0，未复制代码）\nQQmusic-API（Apache-2.0，协议实现参考）\nTides-WearOS（GPL-3.0，未复制代码）\nHorologist（Apache-2.0）\nHeyWear（MIT）", color = Color.Gray, fontSize = 14.sp) }
}

@Composable private fun SettingsScreen(
    vm: AppViewModel, quality: String, headphoneWarning: Boolean, autoOpenPlayer: Boolean,
    playMode: String, lyricSize: String, lyricTranslation: Boolean, lyricOffset: Long,
    pureBlack: Boolean, sleepRemaining: Long, diagnostic: String?, onBack: () -> Unit,
) {
    val context = LocalContext.current
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { runCatching { AppLog.copyTo(context, it) } } }
    var customTimer by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("") }
    var finishCurrent by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "返回") }; Text("设置", fontSize = 27.sp, fontWeight = FontWeight.Bold) } }
        item { Text("播放", color = Green, fontWeight = FontWeight.Bold) }
        item { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { Text("默认音质", fontSize = 17.sp); Text("无对应权益时自动使用可用音质", color = Color.Gray, fontSize = 14.sp); Row { FilterChip(quality == "128", { vm.setQuality("128") }, label = { Text("128k") }); Spacer(Modifier.width(7.dp)); FilterChip(quality == "320", { vm.setQuality("320") }, label = { Text("320k") }) } } }
        item { ListItem(headlineContent = { Text("无耳机播放提醒") }, supportingContent = { Text("未连接耳机时播放前确认") }, trailingContent = { Switch(headphoneWarning, vm::setHeadphoneWarning) }) }
        item { ListItem(headlineContent = { Text("自动进入播放器") }, supportingContent = { Text("歌曲开始后打开播放器页面") }, trailingContent = { Switch(autoOpenPlayer, vm::setAutoOpenPlayer) }) }
        item { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { Text("播放模式", fontSize = 17.sp); FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("sequential", "repeat_one", "loop_all", "shuffle").forEach { mode -> FilterChip(playMode == mode, { vm.setPlayMode(mode) }, label = { Text(playModeName(mode)) }) } } } }
        item { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { Text("定时关闭", fontSize = 17.sp); if (sleepRemaining > 0) Text("剩余 ${sleepRemaining / 60}:${(sleepRemaining % 60).toString().padStart(2, '0')}", color = Green); Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf(15, 30, 60).forEach { minutes -> FilterChip(false, { vm.startSleepTimer(minutes, finishCurrent) }, label = { Text("${minutes}分") }) } }; Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(finishCurrent, { finishCurrent = it }); Text("到时播完当前歌曲再关闭") }; Row { TextButton({ customTimer = true }) { Text("自定义") }; if (sleepRemaining > 0) TextButton(vm::cancelSleepTimer) { Text("取消定时") } } } }
        item { HorizontalDivider(Modifier.padding(vertical = 5.dp)); Text("歌词与显示", color = Green, fontWeight = FontWeight.Bold) }
        item { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { Text("歌词字号"); Row { listOf("small" to "小", "normal" to "标准", "large" to "大").forEach { (value, label) -> FilterChip(lyricSize == value, { vm.setLyricSize(value) }, label = { Text(label) }); Spacer(Modifier.width(5.dp)) } } } }
        item { ListItem(headlineContent = { Text("显示翻译歌词") }, trailingContent = { Switch(lyricTranslation, vm::setLyricTranslation) }) }
        item { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { Text("歌词时间偏移 ${if (lyricOffset >= 0) "+" else ""}${lyricOffset}ms"); Row { TextButton({ vm.setLyricOffset(lyricOffset - 500) }) { Text("-0.5秒") }; TextButton({ vm.setLyricOffset(0) }) { Text("归零") }; TextButton({ vm.setLyricOffset(lyricOffset + 500) }) { Text("+0.5秒") } } } }
        item { ListItem(headlineContent = { Text("AMOLED 纯黑背景") }, supportingContent = { Text("方屏手表省电显示") }, trailingContent = { Switch(pureBlack, vm::setPureBlack) }) }
        item { OutlinedButton({ context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Bluetooth, null); Spacer(Modifier.width(7.dp)); Text("蓝牙耳机设置") } }
        item { HorizontalDivider(Modifier.padding(vertical = 5.dp)); Text("关于", color = Green, fontWeight = FontWeight.Bold) }
        item { ListItem(headlineContent = { Text("QMusic Watch") }, supportingContent = { Text("版本 ${BuildConfig.VERSION_NAME}\n第三方非官方、开源、非商业客户端") }) }
        item { ListItem(headlineContent = { Text("开发者") }, supportingContent = { Text("Ronan") }, leadingContent = { Icon(Icons.Default.Code, null, tint = Green) }) }
        item { if (vm.signedIn) ListItem(headlineContent = { Text("当前账号") }, supportingContent = { Text("QQ ${vm.accountId}\n会员权益由 QQ 音乐播放接口实时判断") }, leadingContent = { Icon(Icons.Default.AccountCircle, null, tint = Green) }) }
        item { if (vm.signedIn) OutlinedButton(vm::diagnose, Modifier.fillMaxWidth()) { Icon(Icons.Default.HealthAndSafety, null); Spacer(Modifier.width(7.dp)); Text("账号与播放诊断") } }
        diagnostic?.let { result -> item { Text(result, color = if (result.startsWith("诊断失败")) MaterialTheme.colorScheme.error else Green, fontSize = 14.sp) } }
        item { HorizontalDivider(Modifier.padding(vertical = 5.dp)); Text("问题反馈", color = Green, fontWeight = FontWeight.Bold) }
        item { Text("日志保存在应用私有目录，最多 256KB，不记录 Cookie、令牌或播放 URL。", color = Color.Gray, fontSize = 14.sp) }
        item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) { OutlinedButton({ context.startActivity(Intent.createChooser(AppLog.shareIntent(context), "分享日志")) }) { Icon(Icons.Default.Share, null); Text("分享日志") }; OutlinedButton({ saveLog.launch("QMusicWatch-${BuildConfig.VERSION_NAME}.log") }) { Icon(Icons.Default.Save, null); Text("保存日志") }; TextButton(AppLog::clear) { Text("清空") } } }
        item { Text("本项目与腾讯或 QQ 音乐无隶属、赞助或认可关系。音乐、封面、歌词及账户数据归各权利人所有。不绕过会员、地区、付费或 DRM 限制。", color = Color.Gray, fontSize = 14.sp) }
        item { Text("开源与致谢", fontWeight = FontWeight.Bold); Text("QQMusicApi（GPL-3.0，未复制代码）\nQQmusic-API（Apache-2.0，协议实现参考）\nTides-WearOS（GPL-3.0，未复制代码）\nHorologist（Apache-2.0）\nHeyWear（MIT）", color = Color.Gray, fontSize = 14.sp) }
        item { if (vm.signedIn) OutlinedButton(vm::logout, Modifier.fillMaxWidth()) { Text("退出登录") } }
    }
    if (customTimer) AlertDialog(onDismissRequest = { customTimer = false }, title = { Text("自定义播放时间") }, text = { OutlinedTextField(customMinutes, { customMinutes = it.filter(Char::isDigit).take(4) }, label = { Text("分钟（1-1440）") }, singleLine = true) }, confirmButton = { TextButton({ customMinutes.toIntOrNull()?.coerceIn(1, 1440)?.let { vm.startSleepTimer(it, finishCurrent) }; customTimer = false }) { Text("开始") } }, dismissButton = { TextButton({ customTimer = false }) { Text("取消") } })
}

@Composable private fun TrackRow(track: Track, vm: AppViewModel, liked: Boolean = false, playlistId: String? = null, removeFromPlaylist: Boolean = false, queue: List<Track> = listOf(track)) {
    var menu by remember { mutableStateOf(false) }
    ListItem(modifier = Modifier.clickable { vm.requestPlay(track, sourceQueue = queue) }, headlineContent = { Row(verticalAlignment = Alignment.CenterVertically) { Text(track.title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis); if (track.requiresVip) { Spacer(Modifier.width(5.dp)); Text("VIP", color = Color(0xFFFFC857), fontSize = 11.sp, fontWeight = FontWeight.Bold) } } }, supportingContent = { Text(track.artists.joinToString(" / "), maxLines = 1) },
        leadingContent = { AsyncImage(track.artworkUrl.ifBlank { null }, null, Modifier.size(48.dp).background(Color.DarkGray, RoundedCornerShape(8.dp))) },
        trailingContent = { Row { IconButton({ vm.cache(track) }) { Icon(Icons.Default.Download, null) }; IconButton({ vm.like(track, !liked) }) { Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (liked) Green else LocalContentColor.current) }; Box { IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "更多") }; DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("下一首播放") }, { vm.enqueueNext(track); menu = false }); DropdownMenuItem({ Text("添加到播放列表") }, { vm.addToQueue(track); menu = false }); playlistId?.let { id -> DropdownMenuItem({ Text(if (removeFromPlaylist) "从此歌单移除" else "加入我的歌单") }, { if (removeFromPlaylist) vm.removeFromPlaylist(track, id) else vm.addToPlaylist(track, id); menu = false }) } } } } })
}

@Composable private fun QueueScreen(queue: List<Track>, currentIndex: Int, reversed: Boolean, library: LibraryData?, vm: AppViewModel, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var saveDialog by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }
    var playlistTitle by remember { mutableStateOf("") }
    val shown = remember(queue, query) { queue.withIndex().filter { query.isBlank() || it.value.title.contains(query, true) || it.value.artists.any { artist -> artist.contains(query, true) } } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "返回") }; Text("当前播放列表", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold); TextButton(vm::reverseQueue) { Icon(if (reversed) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null); Text(if (reversed) "倒序" else "正序") } } }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("筛选播放列表") }, leadingIcon = { Icon(Icons.Default.Search, null) }) }
        item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton({ vm.cacheAll(queue, "当前播放列表") }) { Icon(Icons.Default.Download, null); Text("缓存全部") }; TextButton({ importDialog = true }) { Icon(Icons.Default.LibraryAdd, null); Text("从歌单添加") }; TextButton({ saveDialog = true }) { Icon(Icons.Default.PlaylistAdd, null); Text("保存为歌单") }; TextButton(vm::removeQueueDuplicates) { Text("移除重复") }; TextButton(vm::clearQueue) { Text("清空") } } }
        item { Text("${queue.size} 首", color = Color.Gray) }
        if (queue.isEmpty()) item { Box(Modifier.fillParentMaxHeight(.7f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("播放列表为空", color = Color.Gray) } }
        val visibleIndices = shown.map { it.index }
        itemsIndexed(shown, key = { _, item -> item.value.id }) { visiblePosition, indexed ->
            val index = indexed.index; val track = indexed.value
            var dragged by remember(track.id) { mutableFloatStateOf(0f) }
            var dragging by remember(track.id) { mutableStateOf(false) }
            var rowHeightPx by remember(track.id) { mutableIntStateOf(1) }
            ListItem(modifier = Modifier.animateItem().zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = dragged; alpha = if (dragging) .88f else 1f; scaleX = if (dragging) .98f else 1f }.onSizeChanged { rowHeightPx = it.height }.clickable { vm.playQueueItem(index) }, headlineContent = { Text(track.title, color = if (index == currentIndex) Green else Color.White, maxLines = 1) }, supportingContent = { Text(track.artists.joinToString(" / "), maxLines = 1) }, leadingContent = { if (index == currentIndex) Icon(Icons.Default.GraphicEq, null, tint = Green) else Text("${index + 1}", color = Color.Gray) }, trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.DragHandle, "长按拖动排序", Modifier.size(40.dp).padding(8.dp).pointerInput(track.id, rowHeightPx, visibleIndices) { detectDragGesturesAfterLongPress(onDragStart = { dragging = true; dragged = 0f }, onDragCancel = { dragging = false; dragged = 0f }, onDragEnd = { queueDropIndex(visibleIndices, visiblePosition, dragged, rowHeightPx)?.let { target -> if (target != index) vm.moveQueue(index, target - index) }; dragging = false; dragged = 0f }) { change, amount -> change.consume(); dragged += amount.y } }); IconButton({ vm.removeFromQueue(index) }) { Icon(Icons.Default.RemoveCircleOutline, "移除") } } })
        }
    }
    if (saveDialog) AlertDialog(onDismissRequest = { saveDialog = false }, title = { Text("保存为我的歌单") }, text = { OutlinedTextField(playlistTitle, { playlistTitle = it.take(50) }, label = { Text("歌单名称") }, singleLine = true) }, confirmButton = { TextButton({ if (playlistTitle.isNotBlank()) vm.saveQueueAsPlaylist(playlistTitle); saveDialog = false }) { Text("保存") } }, dismissButton = { TextButton({ saveDialog = false }) { Text("取消") } })
    if (importDialog) AlertDialog(onDismissRequest = { importDialog = false }, title = { Text("从歌单批量添加") }, text = { LazyColumn(Modifier.heightIn(max = 300.dp)) { items(library?.playlists.orEmpty(), key = { it.id }) { playlist -> ListItem(modifier = Modifier.clickable { vm.importPlaylistToQueue(playlist); importDialog = false }, headlineContent = { Text(playlist.title) }, supportingContent = { Text("${playlist.trackCount} 首") }) } } }, confirmButton = {}, dismissButton = { TextButton({ importDialog = false }) { Text("取消") } })
}

@Composable private fun CollectionRow(value: MusicCollection, open: () -> Unit = {}) = ListItem(modifier = Modifier.clickable(onClick = open), headlineContent = { Text(value.title) }, supportingContent = { Text(if (value.trackCount >= 0) "${value.trackCount} 首" else "点击查看") }, leadingContent = { Icon(Icons.Default.QueueMusic, null, tint = Green) })
@Composable private fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) = Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(text, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); action?.let { TextButton(onAction) { Text(it) } } }
@Composable private fun MiniPlayer(track: Track?, open: () -> Unit) { if (track != null) Surface(Modifier.fillMaxWidth().clickable(onClick = open), color = Surface) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.GraphicEq, null, tint = Green); Spacer(Modifier.width(8.dp)); Text(track.title, Modifier.weight(1f), maxLines = 1); Icon(Icons.Default.KeyboardArrowUp, null) } } }
