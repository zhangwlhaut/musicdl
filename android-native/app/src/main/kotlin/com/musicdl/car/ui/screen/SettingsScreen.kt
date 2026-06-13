package com.musicdl.car.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.musicdl.car.data.ApiClient
import com.musicdl.car.data.dto.QrLoginStatus
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.UiState
import com.musicdl.car.ui.component.TileCard
import com.musicdl.car.ui.component.rememberQrBitmap
import com.musicdl.car.ui.currentWindow
import com.musicdl.car.ui.viewmodel.SettingsViewModel

/**
 * 设置 Tab:承载两个区块——
 *  1) "账号 Cookies(扫码登录)" — 三源(网易云 / QQ / 酷狗)
 *  2) "我的在线歌单" — 已登录账号下的歌单列表 + 一键导入到本地集合
 *
 * 使用 Activity 作用域 ViewModel,保证切到别的 tab 再回来时不会重置 cookies/歌单缓存。
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(
        viewModelStoreOwner = LocalContext.current as ComponentActivity,
    ),
) {
    LaunchedEffect(Unit) {
        vm.refreshCookies()
        // 若已有数据则保持,避免每次回来都闪一次 Loading;无数据时才发请求。
        vm.ensureUserPlaylistsLoaded()
    }
    val qrState by vm.qr.collectAsState()
    val cookies by vm.cookies.collectAsState()
    val userPlaylists by vm.userPlaylists.collectAsState()
    val importing by vm.importing.collectAsState()
    val importResult by vm.lastImportResult.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val win = currentWindow()
    val hPad = AppDimensions.contentPadding(win)

    // 收到导入结果就弹 Snackbar
    LaunchedEffect(importResult) {
        val r = importResult ?: return@LaunchedEffect
        val msg = when (r) {
            is SettingsViewModel.ImportResult.Created -> "已导入到本地:${r.name}"
            is SettingsViewModel.ImportResult.Duplicate -> "该歌单已在本地:${r.name}"
            is SettingsViewModel.ImportResult.Failed -> "导入失败:${r.message}"
        }
        snackbar.showSnackbar(msg)
        vm.consumeImportResult()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = hPad, end = hPad, top = 16.dp, bottom = 24.dp),
        ) {
            SectionHeader("账号 Cookies")
            Text(
                text = "扫码登录后,服务端会自动保存 Cookie,用于解锁会员歌曲、" +
                    "下载高音质资源等需要登录的能力。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(12.dp))

            QR_SOURCES.forEach { (source, label) ->
                CookieRow(
                    label = label,
                    loggedIn = !cookies[source].isNullOrBlank(),
                    onScan = { vm.startLogin(source) },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(24.dp))
            UserPlaylistsSection(
                state = userPlaylists,
                cookies = cookies,
                importing = importing,
                onRefresh = { vm.refreshUserPlaylists() },
                onImport = { pl ->
                    vm.importPlaylist(
                        source = pl.source,
                        externalId = pl.id,
                        name = pl.name,
                        cover = pl.cover,
                        creator = pl.creator,
                        trackCount = pl.trackCount,
                    )
                },
            )
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    // 扫码弹窗:Idle 不展示;其他状态都展示
    if (qrState !is SettingsViewModel.QrUiState.Idle) {
        QrLoginDialog(
            state = qrState,
            onDismiss = { vm.cancel() },
            onRefresh = { source -> vm.startLogin(source) },
        )
    }
}

private val QR_SOURCES = listOf(
    "netease" to "网易云音乐",
    "qq" to "QQ音乐",
    "kugou" to "酷狗音乐",
)

@Composable
private fun CookieRow(
    label: String,
    loggedIn: Boolean,
    onScan: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (loggedIn) "已登录" else "未登录",
                color = if (loggedIn) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        Button(
            onClick = onScan,
            shape = RoundedCornerShape(20.dp),
        ) {
            Text(if (loggedIn) "重新扫码" else "扫码登录", fontSize = 14.sp)
        }
    }
}

@Composable
private fun QrLoginDialog(
    state: SettingsViewModel.QrUiState,
    onDismiss: () -> Unit,
    onRefresh: (String) -> Unit,
) {
    val source = sourceOf(state)
    val sourceLabel = labelOf(source)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$sourceLabel · 扫码登录",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(state)
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (state) {
                    is SettingsViewModel.QrUiState.Loading -> {
                        Box(
                            Modifier.size(220.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("正在生成二维码…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is SettingsViewModel.QrUiState.Active -> {
                        val imageUrl = state.session.imageUrl
                        val loginUrl = state.session.url
                        Box(
                            Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (imageUrl.isNotBlank()) {
                                AsyncImage(
                                    model = imageUrl,
                                    contentDescription = "二维码",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().padding(8.dp),
                                )
                            } else if (loginUrl.isNotBlank()) {
                                // 后端只返回了 URL,客户端用 ZXing 把它编码成二维码
                                val bmp = rememberQrBitmap(loginUrl, sizePx = 600)
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "二维码",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize().padding(8.dp),
                                    )
                                } else {
                                    Text(
                                        "二维码生成失败,登录 URL:\n$loginUrl",
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 12.sp,
                                    )
                                }
                            } else {
                                Text(
                                    "服务端未返回二维码内容",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        val r = state.result
                        val hint = when (r?.status) {
                            QrLoginStatus.SCANNED -> "已扫码,请在手机上确认"
                            QrLoginStatus.WAITING, null -> "请使用对应 APP 扫描上方二维码"
                            else -> r.message.ifBlank { "状态:${r.status}" }
                        }
                        Text(
                            hint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    }
                    is SettingsViewModel.QrUiState.Finished -> {
                        val r = state.result
                        val ok = r.status == QrLoginStatus.SUCCESS
                        Box(
                            Modifier.size(220.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (ok) "✓" else "×",
                                fontSize = 96.sp,
                                color = if (ok) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when (r.status) {
                                QrLoginStatus.SUCCESS -> "登录成功,Cookie 已保存"
                                QrLoginStatus.EXPIRED -> "二维码已过期,请点「刷新二维码」重试"
                                QrLoginStatus.FAILED -> r.message.ifBlank { "登录失败" }
                                else -> r.message.ifBlank { "状态:${r.status}" }
                            },
                            color = if (ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    is SettingsViewModel.QrUiState.Error -> {
                        Box(
                            Modifier.size(220.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "×",
                                fontSize = 96.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.message,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                        )
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            // 成功状态没必要再刷新,其他状态都允许重新拉一个新二维码
            val isSuccess = state is SettingsViewModel.QrUiState.Finished &&
                state.result.status == QrLoginStatus.SUCCESS
            if (!isSuccess && source.isNotBlank()) {
                TextButton(onClick = { onRefresh(source) }) { Text("刷新二维码") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 二维码当前状态的彩色徽章:waiting=黄、scanned=蓝、success=绿、expired/failed=红、loading=灰。
 */
@Composable
private fun StatusBadge(state: SettingsViewModel.QrUiState) {
    val (text, bg, fg) = when (state) {
        is SettingsViewModel.QrUiState.Loading -> Triple(
            "生成中", Color(0xFF666666), Color.White,
        )
        is SettingsViewModel.QrUiState.Active -> {
            val s = state.result?.status ?: QrLoginStatus.WAITING
            when (s) {
                QrLoginStatus.SCANNED -> Triple("已扫码", Color(0xFF1976D2), Color.White)
                QrLoginStatus.WAITING -> Triple("等待扫码", Color(0xFFEAA63B), Color.White)
                else -> Triple(s, Color(0xFF666666), Color.White)
            }
        }
        is SettingsViewModel.QrUiState.Finished -> when (state.result.status) {
            QrLoginStatus.SUCCESS -> Triple("登录成功", Color(0xFF2E7D32), Color.White)
            QrLoginStatus.EXPIRED -> Triple("已过期", Color(0xFFC62828), Color.White)
            QrLoginStatus.FAILED -> Triple("失败", Color(0xFFC62828), Color.White)
            else -> Triple(state.result.status, Color(0xFF666666), Color.White)
        }
        is SettingsViewModel.QrUiState.Error -> Triple("错误", Color(0xFFC62828), Color.White)
        else -> Triple("", Color.Transparent, Color.Transparent)
    }
    if (text.isBlank()) return
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun sourceOf(state: SettingsViewModel.QrUiState): String = when (state) {
    is SettingsViewModel.QrUiState.Loading -> state.source
    is SettingsViewModel.QrUiState.Active -> state.source
    is SettingsViewModel.QrUiState.Finished -> state.source
    is SettingsViewModel.QrUiState.Error -> state.source
    else -> ""
}

private fun labelOf(source: String): String = when (source) {
    "netease" -> "网易云音乐"
    "qq" -> "QQ音乐"
    "kugou" -> "酷狗音乐"
    else -> source
}

/** "我的在线歌单"区块:仅在有任何源已登录时才有内容。 */
@Composable
private fun UserPlaylistsSection(
    state: SettingsViewModel.UserPlaylistsUiState,
    cookies: Map<String, String>,
    importing: Set<String>,
    onRefresh: () -> Unit,
    onImport: (com.musicdl.car.data.dto.Playlist) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "我的在线歌单",
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRefresh) { Text("刷新") }
    }
    Text(
        "扫码登录后,这里会列出该账号的歌单。点「导入本地」会复制歌单到「我的歌单」。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    Spacer(Modifier.height(8.dp))

    val loggedInSources = cookies.filter { it.value.isNotBlank() }.keys
        .filter { it in SettingsViewModel.SUPPORTED_USER_PLAYLIST_SOURCES }
    if (loggedInSources.isEmpty()) {
        EmptyHint("未登录任何音源,请先在上方扫码登录")
        return
    }

    when (state) {
        is SettingsViewModel.UserPlaylistsUiState.Idle,
        is SettingsViewModel.UserPlaylistsUiState.Loading -> EmptyHint("加载中…")
        is SettingsViewModel.UserPlaylistsUiState.Error -> EmptyHint("加载失败:${state.message}")
        is SettingsViewModel.UserPlaylistsUiState.Success -> {
            val tabs = state.data.tabs
            if (tabs.all { it.safePlaylists.isEmpty() }) {
                EmptyHint("该账号下暂无歌单")
                return
            }
            tabs.forEach { tab ->
                val pls = tab.safePlaylists
                if (pls.isEmpty() && tab.error.isNullOrBlank()) return@forEach
                Text(
                    tab.sourceName.ifBlank { labelOf(tab.source) },
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                if (!tab.error.isNullOrBlank()) {
                    Text(
                        "加载失败:${tab.error}",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
                if (pls.isNotEmpty()) {
                    LazyRow {
                        items(pls) { pl ->
                            val key = "${pl.source}|${pl.id}"
                            UserPlaylistTile(
                                title = pl.name,
                                subtitle = pl.creator,
                                cover = pl.cover,
                                importing = key in importing,
                                onImport = { onImport(pl) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserPlaylistTile(
    title: String,
    subtitle: String?,
    cover: String?,
    importing: Boolean,
    onImport: () -> Unit,
) {
    val win = currentWindow()
    // 让所有 tile 等高 —— TileCard 内部标题 maxLines=2 + 副标题 1 行,长短不一会导致按钮上下错位。
    // 用 IntrinsicSize.Max 让 Row 高度对齐到最高项,但 LazyRow 不支持 intrinsic;
    // 改用预算高度:封面(=tileWidth) + 标题 2 行(~44dp) + 副标题(~20dp) + spacing(~16dp) + 按钮(~36dp) + 余量
    val tileWidth = AppDimensions.tileWidth(win)
    val totalHeight = tileWidth + 120.dp  // 封面方形 + 标题/副标题/按钮 区域
    Column(
        modifier = Modifier
            .padding(end = 12.dp)
            .height(totalHeight),
    ) {
        TileCard(
            title = title,
            coverUrl = ApiClient.proxiedCover(cover),
            subtitle = subtitle,
            onClick = onImport,
            modifier = Modifier.padding(end = 0.dp), // 已在外层补 end padding,清掉内层
        )
        Spacer(Modifier.weight(1f)) // 把按钮顶到底部,标题/副标题长短不影响按钮 Y
        OutlinedButton(
            onClick = onImport,
            enabled = !importing,
            shape = RoundedCornerShape(18.dp),
        ) {
            if (importing) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("导入中…", fontSize = 13.sp)
            } else {
                Text("导入本地", fontSize = 13.sp)
            }
        }
    }
}
