package com.netease.cloudmusic.audio.launch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.musicdl.car.MainActivity
import com.musicdl.car.data.MusicRepository
import com.musicdl.car.data.dto.Song
import com.musicdl.car.playback.PlaybackService
import com.musicdl.car.playback.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class IotRedirectActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo = MusicRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("IotRedirectActivity", "onCreate intent: $intent")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d("IotRedirectActivity", "onNewIntent intent: $intent")
        if (intent != null) {
            handleIntent(intent)
        }
    }

    private fun handleIntent(intent: Intent) {
        var query: String? = null

        // 1. Handle view intents (dueros://, cmcc://, voice:// etc.)
        if (Intent.ACTION_VIEW == intent.action) {
            val uri: Uri? = intent.data
            Log.d("IotRedirectActivity", "Parsing view intent URI: $uri")
            if (uri != null) {
                query = extractQueryFromUri(uri)
            }
        } 
        // 2. Handle Geely ECARX voice open intent
        else if ("ecarx.intent.action.ECARX_VR_APP_OPEN" == intent.action) {
            Log.d("IotRedirectActivity", "Parsing Geely ECARX voice intent")
            query = intent.getStringExtra("keywords") 
                ?: intent.getStringExtra("query")
                ?: intent.getStringExtra("songName")
            
            val artist = intent.getStringExtra("artistName") ?: intent.getStringExtra("singer")
            if (!query.isNullOrBlank() && !artist.isNullOrBlank()) {
                query = "$query $artist"
            }
        }

        // 3. Fallback to extras if query is still empty
        if (query.isNullOrBlank()) {
            query = extractQueryFromBundle(intent.extras)
        }

        if (!query.isNullOrBlank()) {
            searchAndPlay(this, query)
        } else {
            Log.d("IotRedirectActivity", "No valid search query extracted from intent")
        }

        // 4. Bring MainActivity to the front to show the UI, then close redirect activity
        try {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(mainIntent)
        } catch (e: Exception) {
            Log.e("IotRedirectActivity", "Failed to launch MainActivity", e)
        }
        finish()
    }

    private fun extractQueryFromUri(uri: Uri): String? {
        val queryKeys = arrayOf(
            "keywords", "keyword", "query", "songName", "search_word", "searchKey", "text"
        )
        for (key in queryKeys) {
            try {
                val value = uri.getQueryParameter(key)
                if (!value.isNullOrBlank()) {
                    val artist = uri.getQueryParameter("artistName") ?: uri.getQueryParameter("singer")
                    return if (!artist.isNullOrBlank()) "$value $artist" else value
                }
            } catch (e: Exception) {
                // Ignore missing parameters
            }
        }
        return null
    }

    private fun extractQueryFromBundle(bundle: Bundle?): String? {
        if (bundle == null) return null
        val queryKeys = arrayOf(
            "EXTRA_KEYWORDS_SEARCH", "keywords", "keyword", "query", "songName", "song_name",
            "search_word", "searchKey", "EXTRA_SEARCH_KEY", "search_key", "key", "text", 
            "search_text", "EXTRA_SEARCH_WORD", "voice_query", "name", "title", "audio_name"
        )
        for (key in queryKeys) {
            val value = bundle.getString(key) ?: bundle.get(key)?.toString()
            if (!value.isNullOrBlank()) {
                val artistKeys = arrayOf("EXTRA_ARTIST_SEARCH", "artist", "artist_name", "artistName", "singer", "singer_name")
                var artistVal: String? = null
                for (aKey in artistKeys) {
                    val a = bundle.getString(aKey) ?: bundle.get(aKey)?.toString()
                    if (!a.isNullOrBlank()) {
                        artistVal = a
                        break
                    }
                }
                return if (!artistVal.isNullOrBlank()) "$value $artistVal" else value
            }
        }
        return null
    }

    private fun searchAndPlay(context: Context, query: String) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                Log.d("IotRedirectActivity", "Searching for query: $query")
                val result = repo.search(query).getOrNull()
                val songs = result?.songsSafe
                if (!songs.isNullOrEmpty()) {
                    Log.d("IotRedirectActivity", "Found ${songs.size} songs, playing first: ${songs[0].name}")
                    
                    var player = PlaybackService.player
                    if (player == null) {
                        Log.d("IotRedirectActivity", "Player is null, starting PlaybackService...")
                        val serviceIntent = Intent(appContext, PlaybackService::class.java)
                        try {
                            appContext.startService(serviceIntent)
                        } catch (e: Exception) {
                            Log.e("IotRedirectActivity", "Failed to start service", e)
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
                        Log.d("IotRedirectActivity", "Playback command sent to player")
                    } ?: Log.e("IotRedirectActivity", "PlaybackService.player is still null after waiting")
                } else {
                    Log.d("IotRedirectActivity", "No songs found for query: $query")
                }
            } catch (e: Exception) {
                Log.e("IotRedirectActivity", "Error in searchAndPlay", e)
            }
        }
    }
}
