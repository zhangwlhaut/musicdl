package com.musicdl.car

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Environment
import android.util.Log
import com.musicdl.car.data.ServerBootstrap
import kotlinx.coroutines.*
import mobile.Mobile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Application entry point. Starts the embedded Go HTTP server on a
 * background thread as early as possible so the first screen render
 * usually finds it already listening on 127.0.0.1:37777.
 *
 * 息屏续播保障:
 * - **WakeLock 滚动续约** → 见 PlaybackService 的定时续期机制
 * - **ConnectivityManager 网络回调** → 防止 MIUI 等厂商息屏断网
 * - **Go 服务器健康检测** → 检测到不响应时,仅打日志告警(因 Go 端 sync.Once 限制,
 *   完整重启需扩展 Mobile API)
 */
class MusicDlApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverKeepaliveJob: Job? = null
    private var serverRetryCount = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashHandler.init(this)
        com.musicdl.car.playback.PlaybackLogger.init(this)

        startServer()
        requestBackgroundNetwork()
        startServerKeepalive()
    }

    private fun startServer() {
        appScope.launch {
            try {
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

    /**
     * 定期检测本地 Go 服务器是否存活。
     *
     * **注意**: Go 端 `Mobile.startServer()` 使用 `sync.Once`,进程生命周期内只能
     * 启动一次,因此此处检测到不存活时仅做日志记录。如需完整自动重启,需要 Go 端
     * 新增 `Mobile.stopServer()` API 并移除 sync.Once 限制。
     */
    private fun startServerKeepalive() {
        serverKeepaliveJob?.cancel()
        serverKeepaliveJob = appScope.launch {
            while (isActive) {
                delay(30_000L)
                if (!isServerAlive()) {
                    Log.w(TAG, "Server health check FAILED — Go server not responding on ${Mobile.healthURL("37777")}")
                    serverRetryCount++
                    if (serverRetryCount > 10) {
                        Log.e(TAG, "Server health check failed ${serverRetryCount} times, giving up")
                        // 不再继续检测,避免无意义日志刷屏
                        break
                    }
                } else {
                    if (serverRetryCount > 0) {
                        Log.i(TAG, "Server health check recovered after $serverRetryCount failures")
                    }
                    serverRetryCount = 0
                }
            }
        }
    }

    /** 通过 HTTP GET 检测本地服务器是否存活 */
    private fun isServerAlive(): Boolean {
        return try {
            val healthUrl = Mobile.healthURL("37777")
            val url = URL(healthUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 向 ConnectivityManager 注册一个前台网络请求,提示系统该 app 需要保持
     * 网络连通性（抵抗 MIUI 等厂商息屏断网的策略）。
     */
    private fun requestBackgroundNetwork() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Background network callback: onAvailable $network")
                }
                override fun onLost(network: Network) {
                    Log.d(TAG, "Background network callback: onLost $network")
                }
            })
            Log.i(TAG, "Background network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register background network callback", e)
        }
    }

    companion object {
        private const val TAG = "MusicDL"
        @Volatile var instance: MusicDlApplication? = null
            private set
    }
}
