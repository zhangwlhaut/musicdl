package com.musicdl.car

import android.app.Application
import android.os.Environment
import android.util.Log
import com.musicdl.car.data.ServerBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mobile.Mobile
import java.io.File

/**
 * Application entry point. Starts the embedded Go HTTP server on a
 * background thread as early as possible so the first screen render
 * usually finds it already listening on 127.0.0.1:37777.
 */
class MusicDlApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this

        appScope.launch {
            try {
                // 下载目录:公共 Download 下的 app 子目录,卸载不删,文件管理器可见。
                val downloadDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "MusicDL"
                ).absolutePath
                Log.i(TAG, "starting embedded server, dataDir=${filesDir.absolutePath}, downloadDir=$downloadDir")
                Mobile.startServer(filesDir.absolutePath, downloadDir, "37777")
                Log.i(TAG, "embedded server ready at ${Mobile.serverURL()}")
                ServerBootstrap.markReady()
            } catch (t: Throwable) {
                Log.e(TAG, "embedded server failed", t)
                ServerBootstrap.markFailed(t)
            }
        }
    }

    companion object {
        private const val TAG = "MusicDL"
        @Volatile var instance: MusicDlApplication? = null
            private set
    }
}
