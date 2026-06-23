package com.netease.cloudmusic.iot.byd.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import com.musicdl.car.data.MusicRepository
import com.musicdl.car.data.dto.Song
import com.musicdl.car.playback.PlaybackService
import com.musicdl.car.playback.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BydControlBroadcastReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo = MusicRepository()

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d("BydControlReceiver", "onReceive action: $action")
        
        val appContext = context.applicationContext
        
        when (action) {
            "com.byd.action.AUTOVOICE_SEARCH_PLUS", "com.byd.action.AUTOVOICE_SEARCH" -> {
                // Voice search
                val keyword = intent.getStringExtra("EXTRA_KEYWORDS_SEARCH") 
                    ?: intent.getStringExtra("songName") 
                    ?: intent.getStringExtra("keywords")
                    ?: intent.getStringExtra("query")
                val artist = intent.getStringExtra("EXTRA_ARTIST_SEARCH")
                    ?: intent.getStringExtra("artistName")
                    ?: intent.getStringExtra("singer")
                
                var query = keyword
                if (!query.isNullOrBlank() && !artist.isNullOrBlank()) {
                    query = "$query $artist"
                }
                
                if (!query.isNullOrBlank()) {
                    searchAndPlay(appContext, query)
                } else {
                    Log.d("BydControlReceiver", "Voice search received, but query was empty")
                }
            }
            "com.byd.action.AUTOVOICE_FORWARD" -> {
                // Fast forward
                val step = intent.getIntExtra("EXTRA_SET_TIME", 15) * 1000L
                PlaybackService.player?.let { p ->
                    val newPos = (p.currentPosition + step).coerceAtMost(p.duration)
                    p.seekTo(newPos)
                }
            }
            "com.byd.action.AUTOVOICE_REWIND" -> {
                // Rewind
                val step = intent.getIntExtra("EXTRA_SET_TIME", 15) * 1000L
                PlaybackService.player?.let { p ->
                    val newPos = (p.currentPosition - step).coerceAtLeast(0L)
                    p.seekTo(newPos)
                }
            }
            "com.byd.action.AUTOVOICE_JUMP_TO" -> {
                // Jump to specific time
                val time = intent.getIntExtra("EXTRA_SET_TIME", 0) * 1000L
                PlaybackService.player?.let { p ->
                    p.seekTo(time.coerceIn(0L, p.duration))
                }
            }
            "com.byd.action.AUTOVOICE_QUIT" -> {
                // Stop and quit
                PlaybackService.player?.pause()
            }
            "com.byd.action.AUTOVOICE_PLAY_RANDOM" -> {
                PlaybackService.player?.let { p ->
                    p.shuffleModeEnabled = true
                    Log.d("BydControlReceiver", "Enabled shuffle mode")
                }
            }
            "com.byd.action.AUTOVOICE_PLAY_MODE" -> {
                val mode = intent.getStringExtra("EXTRA_PLAY_MODE") ?: intent.getStringExtra("play_mode")
                PlaybackService.player?.let { p ->
                    when (mode?.lowercase()) {
                        "shuffle", "random" -> {
                            p.shuffleModeEnabled = true
                            p.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                        }
                        "single", "loop_single" -> {
                            p.shuffleModeEnabled = false
                            p.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                        }
                        else -> {
                            p.shuffleModeEnabled = false
                            p.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                        }
                    }
                    Log.d("BydControlReceiver", "Set play mode: $mode")
                }
            }
            "byd.intent.action.MEDIA_BUTTON" -> {
                // Media button events
                val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                    PlaybackService.player?.let { p ->
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT -> p.seekToNext()
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> p.seekToPrevious()
                            KeyEvent.KEYCODE_MEDIA_PLAY -> p.play()
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> p.pause()
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                if (p.isPlaying) p.pause() else p.play()
                            }
                        }
                    }
                }
            }
            else -> {
                Log.d("BydControlReceiver", "Unhandled action: $action")
            }
        }
    }

    private fun searchAndPlay(context: Context, query: String) {
        scope.launch {
            try {
                Log.d("BydControlReceiver", "Searching for query: $query")
                val result = repo.search(query).getOrNull()
                val songs = result?.songsSafe
                if (!songs.isNullOrEmpty()) {
                    Log.d("BydControlReceiver", "Found ${songs.size} songs, playing first: ${songs[0].name}")
                    
                    var player = PlaybackService.player
                    if (player == null) {
                        Log.d("BydControlReceiver", "Player is null, starting PlaybackService...")
                        val serviceIntent = Intent(context, PlaybackService::class.java)
                        try {
                            context.startService(serviceIntent)
                        } catch (e: Exception) {
                            Log.e("BydControlReceiver", "Failed to start service", e)
                        }
                        
                        // Wait up to 3 seconds for the player to initialize
                        for (i in 1..30) {
                            delay(100)
                            player = PlaybackService.player
                            if (player != null) break
                        }
                    }
                    
                    player?.let { p ->
                        p.setMediaItems(songs.map { it.toMediaItem() }, 0, 0L)
                        p.prepare()
                        p.play()
                    } ?: Log.e("BydControlReceiver", "PlaybackService.player is still null after waiting")
                }
            } catch (e: Exception) {
                Log.e("BydControlReceiver", "Error in searchAndPlay", e)
            }
        }
    }
}
