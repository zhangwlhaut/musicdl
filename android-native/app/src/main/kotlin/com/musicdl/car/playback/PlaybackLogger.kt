package com.musicdl.car.playback

import android.content.Context
import android.util.Log
import com.musicdl.car.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object PlaybackLogger {
    private const val TAG = "PlaybackLogger"
    private var logFile: File? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        val dir = context.filesDir
        logFile = File(dir, "playback.log")
        Log.i(TAG, "Initialized playback log file at: ${logFile?.absolutePath}")
        log("--- PlaybackLogger initialized / App started ---")
    }

    fun log(message: String) {
        if (!BuildConfig.DEBUG) return
        val time = dateFormat.format(Date())
        val line = "[$time] $message"
        Log.i(TAG, line) // Also print to system logcat
        scope.launch {
            try {
                logFile?.let { file ->
                    FileWriter(file, true).use { writer ->
                        writer.write(line + "\n")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log line", e)
            }
        }
    }

    fun logError(message: String, throwable: Throwable) {
        if (!BuildConfig.DEBUG) return
        val time = dateFormat.format(Date())
        val line = "[$time] ERROR: $message | Exception: ${throwable.message}"
        Log.e(TAG, line, throwable)
        scope.launch {
            try {
                logFile?.let { file ->
                    FileWriter(file, true).use { fw ->
                        PrintWriter(fw).use { pw ->
                            pw.println(line)
                            throwable.printStackTrace(pw)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write error log", e)
            }
        }
    }
}
