package com.musicdl.car.playback

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.musicdl.car.data.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CMApiService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo = MusicRepository()

    private var isBound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d("CMApiService", "PlaybackService connected")
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("CMApiService", "PlaybackService disconnected")
            isBound = false
        }
    }

    private fun bindPlaybackService() {
        if (!isBound) {
            Log.d("CMApiService", "Binding PlaybackService...")
            try {
                val intent = Intent(this, PlaybackService::class.java)
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.e("CMApiService", "Failed to bind PlaybackService", e)
            }
        }
    }

    private fun unbindPlaybackService() {
        if (isBound) {
            Log.d("CMApiService", "Unbinding PlaybackService...")
            try {
                unbindService(connection)
                isBound = false
            } catch (e: Exception) {
                Log.e("CMApiService", "Failed to unbind PlaybackService", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("CMApiService", "onCreate")
        bindPlaybackService()
    }

    override fun onDestroy() {
        unbindPlaybackService()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CMApiService", "onStartCommand intent: $intent")
        intent?.extras?.let { extras ->
            logBundle(extras, "  extra: ")
        }
        
        val query = extractQuery(intent?.extras)
        if (!query.isNullOrBlank()) {
            searchAndPlay(query)
        }
        
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d("CMApiService", "onBind intent: $intent")
        intent?.extras?.let { extras ->
            logBundle(extras, "  extra: ")
        }
        return mBinder
    }

    private val mBinder = object : com.netease.cloudmusic.third.api.contract.ICMApi.Stub() {
        override fun execute(cmd: String?, params: Bundle?): Bundle {
            Log.d("CMApiService", "ICMApi.execute cmd=$cmd params=$params")
            return executeCommand(cmd, params)
        }

        override fun executeAsync(cmd: String?, subCmd: String?, params: Bundle?, callback: IBinder?) {
            Log.d("CMApiService", "ICMApi.executeAsync cmd=$cmd subCmd=$subCmd params=$params")
            if (callback != null) {
                try {
                    val cb = com.netease.cloudmusic.third.api.contract.ICMApiCallback.Stub.asInterface(callback)
                    val result = executeCommand(cmd, params)
                    cb.onReturn(result)
                    Log.d("CMApiService", "Executed callback.onReturn for cmd=$cmd")
                } catch (e: Exception) {
                    Log.e("CMApiService", "Failed to execute callback for cmd=$cmd", e)
                }
            }
        }

        override fun registerEventListener(listener: IBinder?) {
            Log.d("CMApiService", "ICMApi.registerEventListener listener=$listener")
        }

        override fun unregisterEventListener(listener: IBinder?) {
            Log.d("CMApiService", "ICMApi.unregisterEventListener listener=$listener")
        }
    }

    private fun logBundle(bundle: Bundle?, prefix: String = "  ") {
        if (bundle == null) return
        try {
            for (key in bundle.keySet()) {
                val value = bundle.get(key)
                if (value is Bundle) {
                    Log.d("CMApiService", "$prefix$key -> Bundle:")
                    logBundle(value, "$prefix  ")
                } else {
                    Log.d("CMApiService", "$prefix$key -> $value")
                }
            }
        } catch (e: Exception) {
            Log.e("CMApiService", "Failed to log bundle", e)
        }
    }

    private fun executeCommand(cmd: String?, params: Bundle?): Bundle {
        Log.d("CMApiService", "executeCommand cmd=$cmd params=$params")
        params?.let {
            logBundle(it, "  param: ")
        }

        val cmdUpper = cmd?.uppercase() ?: ""
        val result = Bundle()

        // Handle authentication / login checks from the assistant
        if (cmdUpper.contains("GET_TOKEN")) {
            result.putInt("code", 200)
            result.putString("encResult", "fake_token_value_123456")
            result.putString("enc_result", "fake_token_value_123456")
            result.putString("token", "fake_token_value_123456")
            result.putString("tokenSign", "fake_token_value_123456")
            result.putString("token_sign", "fake_token_value_123456")
            result.putLong("expireTime", 360000000L)
            result.putLong("expire_time", 360000000L)
            result.putBoolean("isLogin", true)
            result.putBoolean("is_login", true)
            Log.d("CMApiService", "Returning fake token for GET_TOKEN with fallback keys")
            return result
        }
        if (cmdUpper.contains("LOGIN") || cmdUpper.contains("IS_LOGIN")) {
            result.putInt("code", 200)
            result.putBoolean("isLogin", true)
            result.putBoolean("is_login", true)
            Log.d("CMApiService", "Returning true for LOGIN / IS_LOGIN")
            return result
        }
        if (cmdUpper.contains("USER_INFO") || cmdUpper.contains("GET_USER")) {
            result.putInt("code", 200)
            result.putString("nickname", "MusicDL")
            result.putString("nickName", "MusicDL")
            result.putString("name", "MusicDL")
            result.putString("userId", "123456")
            result.putString("user_id", "123456")
            result.putString("uid", "123456")
            result.putBoolean("isLogin", true)
            result.putBoolean("is_login", true)
            Log.d("CMApiService", "Returning fake user info for USER_INFO")
            return result
        }

        if (cmdUpper.contains("GET_INFO") || cmdUpper.contains("GET_PLAY_INFO") || cmdUpper.contains("PLAY_INFO") || cmdUpper.contains("GET_PLAYING")) {
            val p = PlaybackService.player
            if (p != null) {
                val meta = p.currentMediaItem?.mediaMetadata
                val title = meta?.title?.toString() ?: ""
                val artist = meta?.artist?.toString() ?: ""
                val album = meta?.albumTitle?.toString() ?: ""
                val cover = meta?.artworkUri?.toString() ?: ""
                val isPlaying = p.isPlaying

                // Populate all common key variations (case-insensitive)
                result.putString("EXTRA_MUSIC_NAME", title)
                result.putString("music_name", title)
                result.putString("name", title)
                result.putString("title", title)

                result.putString("EXTRA_MUSIC_ARTIST", artist)
                result.putString("music_artist", artist)
                result.putString("artist", artist)

                result.putString("EXTRA_MUSIC_ALBUM", album)
                result.putString("music_album", album)
                result.putString("album", album)

                result.putString("EXTRA_MUSIC_COVER", cover)
                result.putString("music_cover", cover)
                result.putString("cover", cover)
                result.putString("cover_url", cover)

                result.putInt("EXTRA_PLAY_STATUS", if (isPlaying) 1 else 0)
                result.putInt("play_status", if (isPlaying) 1 else 0)
                result.putInt("status", if (isPlaying) 1 else 0)
                result.putBoolean("isPlaying", isPlaying)
                result.putBoolean("is_playing", isPlaying)
            }
        } else if (cmdUpper.contains("PLAY") || cmdUpper.contains("RESUME") || cmdUpper.contains("START")) {
            val query = extractQuery(params)
            if (!query.isNullOrBlank()) {
                searchAndPlay(query)
            } else {
                scope.launch {
                    ensurePlayerReady()?.play()
                }
            }
            result.putBoolean("success", true)
        } else if (cmdUpper.contains("PAUSE") || cmdUpper.contains("STOP")) {
            scope.launch {
                ensurePlayerReady()?.pause()
            }
            result.putBoolean("success", true)
        } else if (cmdUpper.contains("NEXT") || cmdUpper.contains("SKIP")) {
            scope.launch {
                ensurePlayerReady()?.seekToNext()
            }
            result.putBoolean("success", true)
        } else if (cmdUpper.contains("PREV")) {
            scope.launch {
                ensurePlayerReady()?.seekToPrevious()
            }
            result.putBoolean("success", true)
        }

        return result
    }

    private fun extractQuery(params: Bundle?): String? {
        if (params == null) return null
        
        val queryKeys = arrayOf(
            "EXTRA_KEYWORDS_SEARCH", "keywords", "query", "keyword", "search_word", 
            "searchKey", "EXTRA_SEARCH_KEY", "search_key", "key", "text", "search_text", 
            "EXTRA_SEARCH_WORD", "voice_query", "name", "songName", "song_name", "title", "audio_name"
        )
        
        fun findInBundle(bundle: Bundle): String? {
            for (key in queryKeys) {
                val v = bundle.getString(key) ?: bundle.get(key)?.toString()
                if (!v.isNullOrBlank()) {
                    val artistKeys = arrayOf("EXTRA_ARTIST_SEARCH", "artist", "artist_name", "artistName", "singer", "singer_name")
                    var artistVal: String? = null
                    for (aKey in artistKeys) {
                        val a = bundle.getString(aKey) ?: bundle.get(aKey)?.toString()
                        if (!a.isNullOrBlank()) {
                            artistVal = a
                            break
                        }
                    }
                    return if (!artistVal.isNullOrBlank()) "$v $artistVal" else v
                }
            }
            
            for (key in bundle.keySet()) {
                val valStr = bundle.get(key)?.toString() ?: continue
                if (valStr.contains("audio_name") || valStr.contains("song_name") || valStr.contains("keyword") || valStr.contains("query")) {
                    val nameRegex = """"audio_name"\s*:\s*"([^"]+)"""".toRegex()
                    val artistRegex = """"artist_name"\s*:\s*"([^"]+)"""".toRegex()
                    val nameMatch = nameRegex.find(valStr)?.groupValues?.get(1)
                    val artistMatch = artistRegex.find(valStr)?.groupValues?.get(1)
                    if (!nameMatch.isNullOrBlank()) {
                        return if (!artistMatch.isNullOrBlank()) "$nameMatch $artistMatch" else nameMatch
                    }
                    val queryRegex = """"query"\s*:\s*"([^"]+)"""".toRegex()
                    val queryMatch = queryRegex.find(valStr)?.groupValues?.get(1)
                    if (!queryMatch.isNullOrBlank()) {
                        return queryMatch
                    }
                    val keywordRegex = """"keyword"\s*:\s*"([^"]+)"""".toRegex()
                    val keywordMatch = keywordRegex.find(valStr)?.groupValues?.get(1)
                    if (!keywordMatch.isNullOrBlank()) {
                        return keywordMatch
                    }
                }
            }
            
            for (key in bundle.keySet()) {
                val value = bundle.get(key)
                if (value is Bundle) {
                    val result = findInBundle(value)
                    if (result != null) return result
                }
            }
            return null
        }
        
        return findInBundle(params)
    }

    private suspend fun ensurePlayerReady(): ExoPlayer? {
        var p = PlaybackService.player
        if (p == null) {
            Log.d("CMApiService", "ensurePlayerReady: Player is null, binding PlaybackService...")
            bindPlaybackService()
            var retryCount = 0
            while (p == null && retryCount < 30) {
                kotlinx.coroutines.delay(100)
                p = PlaybackService.player
                retryCount++
            }
        }
        return p
    }

    private fun searchAndPlay(query: String) {
        scope.launch {
            try {
                Log.d("CMApiService", "Searching for query: $query")
                val result = repo.search(query).getOrNull()
                val songs = result?.songsSafe
                if (!songs.isNullOrEmpty()) {
                    Log.d("CMApiService", "Found ${songs.size} songs, playing the first one: ${songs[0].name}")
                    val p = ensurePlayerReady()
                    if (p != null) {
                        p.setMediaItems(songs.map { it.toMediaItem() }, 0, 0L)
                        p.prepare()
                        p.play()
                    } else {
                        Log.e("CMApiService", "Player is null after initialization, cannot play")
                    }
                } else {
                    Log.d("CMApiService", "No songs found for query: $query")
                }
            } catch (e: Exception) {
                Log.e("CMApiService", "Error during search and play", e)
            }
        }
    }
}
