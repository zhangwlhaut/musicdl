package com.musicdl.car.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.musicdl.car.data.ApiClient
import com.musicdl.car.data.dto.Playlist
import com.musicdl.car.data.dto.Song
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.UiState
import com.musicdl.car.ui.component.SongRow
import com.musicdl.car.ui.currentWindow
import com.musicdl.car.ui.viewmodel.SearchViewModel

/**
 * 搜索 Tab — 顶部三段式切换 [单曲 / 歌单 / 专辑],搜索框 + 结果区。
 */
@Composable
fun SearchScreen(
    onPlay: (List<Song>, Int) -> Unit,
    onOpenRemote: (String, String, String, String?, String?) -> Unit,
    onOpenAlbum: (String, String, String, String?, String?) -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val query by vm.query.collectAsState()
    val type by vm.type.collectAsState()
    val results by vm.results.collectAsState()
    val win = currentWindow()
    val hPad = AppDimensions.contentPadding(win)

    Column(Modifier.fillMaxSize().padding(start = hPad, end = hPad, top = 16.dp, bottom = 16.dp)) {
        SectionHeader("搜索")
        Spacer(Modifier.height(8.dp))

        SearchTypeSegmented(current = type, onChange = vm::setType)
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        when (type) {
                            "playlist" -> "搜索歌单"
                            "album" -> "搜索专辑"
                            else -> "搜索歌曲"
                        },
                        fontSize = 16.sp,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { vm.run() },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Text("搜索", fontSize = 16.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        when (val s = results) {
            null -> EmptyHint(
                when (type) {
                    "playlist" -> "输入关键词搜索歌单"
                    "album" -> "输入关键词搜索专辑"
                    else -> "输入关键词,点击搜索"
                }
            )
            is UiState.Loading -> EmptyHint("搜索中…")
            is UiState.Error -> EmptyHint("搜索失败:${s.message}")
            is UiState.Success -> {
                val songs = s.data.songsSafe
                val playlists = s.data.playlistsSafe
                when (type) {
                    "song" -> SongResults(songs, onPlay)
                    "playlist" -> PlaylistOrAlbumResults(
                        items = playlists,
                        emptyHint = "没有匹配歌单",
                        onClick = { pl -> onOpenRemote(pl.source, pl.id, pl.name, pl.cover, pl.creator) },
                        leadingIcon = Icons.Default.LibraryMusic,
                    )
                    "album" -> PlaylistOrAlbumResults(
                        items = playlists,
                        emptyHint = "没有匹配专辑",
                        onClick = { pl -> onOpenAlbum(pl.source, pl.id, pl.name, pl.cover, pl.creator) },
                        leadingIcon = Icons.Default.Album,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchTypeSegmented(current: String, onChange: (String) -> Unit) {
    val options = listOf("song" to "单曲", "playlist" to "歌单", "album" to "专辑")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { idx, (value, label) ->
            SegmentedButton(
                selected = current == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
            ) {
                Text(label, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SongResults(songs: List<Song>, onPlay: (List<Song>, Int) -> Unit) {
    if (songs.isEmpty()) { EmptyHint("没有匹配歌曲"); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(songs) { song ->
            SongRow(song, onClick = { onPlay(songs, songs.indexOf(song)) })
        }
    }
}

@Composable
private fun PlaylistOrAlbumResults(
    items: List<Playlist>,
    emptyHint: String,
    onClick: (Playlist) -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    if (items.isEmpty()) { EmptyHint(emptyHint); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { pl ->
            PlaylistResultRow(
                name = pl.name,
                source = pl.source,
                creator = pl.creator,
                cover = pl.cover,
                leadingIcon = leadingIcon,
                onClick = { onClick(pl) },
            )
        }
    }
}

@Composable
private fun PlaylistResultRow(
    name: String,
    source: String,
    creator: String?,
    cover: String?,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val coverUrl = ApiClient.proxiedCover(cover)
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(creator?.takeIf { it.isNotBlank() }, source).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}
