package dev.notification.sdk

import com.google.android.gms.tasks.Task
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Bound our wait even when Firebase's task never completes. */
internal suspend fun awaitFirebaseToken(task: Task<String>): String =
    withTimeoutOrNull(30_000) {
        suspendCancellableCoroutine<String> { continuation ->
            task.addOnCompleteListener({ it.run() }) { completed ->
                if (completed.isSuccessful) {
                    continuation.resume(completed.result)
                } else {
                    continuation.resumeWithException(completed.exception ?: IOException("Firebase token request cancelled"))
                }
            }
        }
    } ?: throw IOException("Firebase token acquisition timed out after 30 seconds")

/** Only bounded, known codes cross the SDK/backend boundary. */
internal fun firebaseRegistrationErrorCode(error: Throwable): String {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = error

    while (current != null && seen.add(current)) {
        if (current.message == "AUTHENTICATION_FAILED") {
            return "FCM_AUTHENTICATION_FAILED"
        }

        current = current.cause
    }

    return if (error is IllegalStateException) "FIREBASE_NOT_CONFIGURED" else "FCM_TOKEN_UNAVAILABLE"
}
