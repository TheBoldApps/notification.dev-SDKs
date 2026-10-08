package dev.notification.sdk

import android.util.Log
import kotlinx.serialization.SerializationException

internal class AndroidLogger(private val enabled: Boolean) {
    fun debug(message: String) {
        if (enabled) {
            Log.d("NotificationDev", message)
        }
    }

    fun error(message: String, cause: Throwable? = null) {
        if (!enabled) {
            return
        }

        val detail = buildString {
            append(message)
            val seen = mutableSetOf<Throwable>()
            var current = cause
            while (current != null && seen.add(current)) {
                append("\nCaused by: ").append(current.javaClass.name)
                if (current !is SerializationException) {
                    current.message?.let { append(": ").append(it) }
                }
                current.stackTrace.forEach { append("\n    at ").append(it) }
                current = current.cause
            }
        }

        Log.e("NotificationDev", detail)
    }
}
