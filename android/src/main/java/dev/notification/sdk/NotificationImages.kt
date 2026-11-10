package dev.notification.sdk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

internal fun readImageBytes(input: InputStream, deadline: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        check(System.nanoTime() < deadline) { "Image deadline exceeded" }
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        check(output.size() + count <= MAX_IMAGE_BYTES) { "Image exceeds size limit" }
        output.write(buffer, 0, count)
    }
}

internal suspend fun loadNotificationImage(raw: String?): Bitmap? = withContext(Dispatchers.IO) {
    if (raw == null) return@withContext null
    try {
        var url = URL(raw)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var bytes: ByteArray? = null
        repeat(4) {
            if (bytes != null) return@repeat
            require(url.protocol in listOf("http", "https") && url.userInfo == null)
            val connection = url.openConnection() as HttpURLConnection
            try {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).toInt()
                check(remaining > 0)
                connection.connectTimeout = remaining
                connection.readTimeout = remaining
                connection.instanceFollowRedirects = false
                val status = connection.responseCode
                if (status in 300..399) {
                    url = URL(url, requireNotNull(connection.getHeaderField("Location")))
                } else {
                    val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
                    check(status in 200..299 && (length == null || length <= MAX_IMAGE_BYTES))
                    bytes = connection.inputStream.use { readImageBytes(it, deadline) }
                }
            } finally {
                connection.disconnect()
            }
        }
        val data = bytes ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0)
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (bounds.outWidth / options.inSampleSize > 2048 || bounds.outHeight / options.inSampleSize > 2048) {
            options.inSampleSize *= 2
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
