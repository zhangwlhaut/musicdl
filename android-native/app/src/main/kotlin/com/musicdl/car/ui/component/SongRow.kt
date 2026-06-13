package com.musicdl.car.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.musicdl.car.data.ApiClient
import com.musicdl.car.data.dto.Song
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.currentWindow
import com.musicdl.car.ui.viewmodel.CollectionsHubViewModel
import com.musicdl.car.ui.viewmodel.DownloadViewModel
import com.musicdl.car.ui.viewmodel.FavoriteToggleViewModel

/**
 * A single row representing a song. Shows cover (48dp), title, artist.
 * Height: 64dp minimum for touch target compliance.
 *
 * 右侧默认依次渲染下载、收藏按钮(对在线音源)。如果调用方传了 [trailing],则用 [trailing] 替代。
 */
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    showFavorite: Boolean = true,
    showDownload: Boolean = true,
    showAddToPlaylist: Boolean = true,
) {
    val win = currentWindow()
    val minHeight = AppDimensions.rowHeight(win)
    val coverSize = AppDimensions.rowCover(win)
    val titleSp = if (win.width == com.musicdl.car.ui.WindowSize.COMPACT) 14 else 16
    val artistSp = if (win.width == com.musicdl.car.ui.WindowSize.COMPACT) 12 else 14

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = minHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val coverUrl = ApiClient.coverUrl(song)
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(coverSize)
                    .clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Spacer(Modifier.size(coverSize))
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = titleSp.sp,
            )
            if (!song.artist.isNullOrBlank()) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = artistSp.sp,
                )
            }
        }

        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        } else if (song.source != "local" && song.source.isNotBlank()) {
            if (showAddToPlaylist) {
                Spacer(Modifier.width(4.dp))
                AddToPlaylistButton(song)
            }
            if (showDownload) {
                Spacer(Modifier.width(4.dp))
                DownloadButton(song)
            }
            if (showFavorite) {
                Spacer(Modifier.width(4.dp))
                FavoriteHeart(song)
            }
        }
    }
}

@Composable
private fun AddToPlaylistButton(song: Song) {
    val owner = LocalContext.current as ComponentActivity
    val hub: CollectionsHubViewModel = viewModel(viewModelStoreOwner = owner)
    val collections by hub.manual.collectAsState()

    var showSheet by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }

    IconButton(
        onClick = {
            hub.refresh()
            showSheet = true
        },
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            Icons.Filled.PlaylistAdd,
            contentDescription = "加入歌单",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showSheet) {
        AddToCollectionSheet(
            collections = collections,
            onDismiss = { showSheet = false },
            onPick = { col ->
                hub.addSong(col.id, col.name, song)
                showSheet = false
            },
            onCreateNew = {
                showSheet = false
                showCreate = true
            },
        )
    }

    if (showCreate) {
        CreateCollectionDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, desc ->
                hub.create(name, desc, thenAddSong = song)
                showCreate = false
            },
        )
    }
}

@Composable
private fun FavoriteHeart(song: Song) {
    // Activity-scoped ViewModel:不同 Tab/详情页共享缓存,收藏后心形全局同步。
    val owner = LocalContext.current as ComponentActivity
    val favVm: FavoriteToggleViewModel = viewModel(viewModelStoreOwner = owner)
    val states by favVm.states.collectAsState()
    val key = song.id + "|" + song.source
    val favorited = states[key] == true

    LaunchedEffect(key) { favVm.probe(song) }

    IconButton(onClick = { favVm.toggle(song) }, modifier = Modifier.size(40.dp)) {
        if (favorited) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "取消收藏",
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                Icons.Filled.FavoriteBorder,
                contentDescription = "收藏",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadButton(song: Song) {
    val owner = LocalContext.current as ComponentActivity
    val dlVm: DownloadViewModel = viewModel(viewModelStoreOwner = owner)
    val states by dlVm.states.collectAsState()
    val key = song.id + "|" + song.source
    val status = states[key] ?: DownloadViewModel.Status.IDLE

    IconButton(
        onClick = { dlVm.download(song) },
        modifier = Modifier.size(40.dp),
        enabled = status != DownloadViewModel.Status.DOWNLOADING,
    ) {
        when (status) {
            DownloadViewModel.Status.DOWNLOADING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            DownloadViewModel.Status.DONE -> {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "已下载",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            DownloadViewModel.Status.FAILED -> {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = "下载失败,点击重试",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            DownloadViewModel.Status.IDLE -> {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "下载",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
