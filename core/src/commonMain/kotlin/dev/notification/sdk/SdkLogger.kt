package dev.notification.sdk

import kotlinx.serialization.SerializationException

internal class SdkLogger(
    private val enabled: Boolean = false,
    private val sink: (Int, String) -> Unit = { _, _ -> }
) {
    fun debug(message: String) {
        if (enabled) {
            sink(3, message)
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
                append("\nCaused by: ").append(current::class.simpleName)
                if (current !is SerializationException) {
                    current.message?.let { append(": ").append(it) }
                }
                current = current.cause
            }
        }

        sink(6, detail)
    }
}
