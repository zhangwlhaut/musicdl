package com.musicdl.car.ui.component

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 把任意字符串编码为正方形黑白二维码 Bitmap。
 * 失败(空串/不可编码)返回 null,UI 自行降级到"展示原始 URL"。
 *
 * 用 ZXing core,纯 Java 实现,不依赖 Android 视图栈,可以放心在任意线程调用。
 */
fun encodeQrToBitmap(text: String, sizePx: Int = 600): Bitmap? {
    if (text.isBlank() || sizePx <= 0) return null
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        bmp
    } catch (_: Throwable) {
        null
    }
}

@Composable
fun rememberQrBitmap(text: String, sizePx: Int = 600): Bitmap? = remember(text, sizePx) {
    encodeQrToBitmap(text, sizePx)
}
