package com.musicdl.car.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 全局 Toast 工具。MainActivity.onCreate 中调一次 [init] 注入 ApplicationContext,
 * 之后任意线程都能调 [show]。所有 Toast 强制走主线程,避免 ViewModel/IO 调用崩溃。
 */
object Toaster {

    @Volatile
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 短 Toast。message 为空时静默。 */
    fun show(message: String?) {
        val ctx = appContext ?: return
        val msg = message?.trim().orEmpty()
        if (msg.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        } else {
            main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    fun long(message: String?) {
        val ctx = appContext ?: return
        val msg = message?.trim().orEmpty()
        if (msg.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        } else {
            main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
        }
    }
}
