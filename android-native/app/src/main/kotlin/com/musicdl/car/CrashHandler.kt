package com.musicdl.car

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器。
 *
 * 在 [MusicDlApplication.onCreate] 中注册，自动捕获所有未被 try-catch 处理的
 * 异常（主线程 + 子线程），将堆栈写入文件，方便事后检查崩溃原因。
 *
 * 文件路径: [app filesDir]/crash_logs/crash_yyyyMMdd_HHmmss.log
 */
object CrashHandler {

    private const val TAG = "CrashHandler"
    private var logDir: File? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /** 初始化并注册 [defaultHandler]。由 [MusicDlApplication.onCreate] 调用。 */
    fun init(app: android.app.Application) {
        logDir = File(app.filesDir, "crash_logs").also { it.mkdirs() }
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(thread, throwable)

            // 转发给系统默认处理器（弹出"应用已停止运行"对话框）
            previousHandler?.uncaughtException(thread, throwable)
        }

        Log.i(TAG, "CrashHandler registered, log dir: ${logDir?.absolutePath}")
    }

    private fun handleCrash(thread: Thread, throwable: Throwable) {
        val now = Date()
        val fileName = "crash_${fileDateFormat.format(now)}.log"
        val file = File(logDir, fileName)

        try {
            FileWriter(file).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println("========================================")
                    pw.println("  Crash Report")
                    pw.println("  Time: ${dateFormat.format(now)}")
                    pw.println("  Thread: ${thread.name} (id=${thread.id})")
                    pw.println("  Thread Group: ${thread.threadGroup?.name}")
                    pw.println("  Priority: ${thread.priority}")
                    pw.println("  Daemon: ${thread.isDaemon}")
                    pw.println("========================================")
                    pw.println()
                    pw.println("Exception: ${throwable.javaClass.name}")
                    pw.println("Message: ${throwable.message}")
                    pw.println()
                    pw.println("Stack Trace:")
                    throwable.printStackTrace(pw)

                    // 打印完整 Cause Chain
                    var cause = throwable.cause
                    var level = 0
                    while (cause != null && level < 10) {
                        level++
                        pw.println()
                        pw.println("Caused by (level $level): ${cause.javaClass.name}")
                        pw.println("Message: ${cause.message}")
                        cause.printStackTrace(pw)
                        cause = cause.cause
                    }

                    // 打印所有被抑制的异常
                    val suppressed = throwable.suppressed
                    if (suppressed.isNotEmpty()) {
                        pw.println()
                        pw.println("Suppressed Exceptions (${suppressed.size}):")
                        suppressed.forEachIndexed { idx, se ->
                            pw.println("  [$idx] ${se.javaClass.name}: ${se.message}")
                        }
                    }

                    pw.println()
                    pw.println("========================================")
                    pw.println("  Device Info")
                    pw.println("========================================")
                    pw.println("  Model: ${android.os.Build.MODEL}")
                    pw.println("  Manufacturer: ${android.os.Build.MANUFACTURER}")
                    pw.println("  Android API: ${android.os.Build.VERSION.SDK_INT}")
                    pw.println("  Android Version: ${android.os.Build.VERSION.RELEASE}")
                    pw.println("  Board: ${android.os.Build.BOARD}")
                    pw.println("  Fingerprint: ${android.os.Build.FINGERPRINT}")
                }
            }
            Log.e(TAG, "Crash log saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash log", e)
        }

        // 也输出到 logcat，方便 adb logcat 实时查看
        Log.e(TAG, """
            ============ UNCAUGHT CRASH ============
            Thread: ${thread.name}
            ${throwable.javaClass.name}: ${throwable.message}
            ${throwable.stackTraceToString()}
            ========================================
        """.trimIndent())
    }
}
