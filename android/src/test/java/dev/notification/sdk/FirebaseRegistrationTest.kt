package dev.notification.sdk

import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FirebaseRegistrationTest {
    @Test fun retrievesCompletedToken() = runTest {
        val task = TaskCompletionSource<String>()
        task.setResult("test-token")

        assertEquals("test-token", awaitFirebaseToken(task.task))
    }

    @Test fun pendingTaskTimesOutAndLateCompletionIsSafe() = runTest {
        val task = TaskCompletionSource<String>()
        val failure = runCatching { awaitFirebaseToken(task.task) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("timed out"))
        assertEquals("FCM_TOKEN_UNAVAILABLE", firebaseRegistrationErrorCode(failure!!))
        task.setResult("late-token")
    }

    @Test fun cancellationPropagatesAndLateFailureIsSafe() = runTest {
        val task = TaskCompletionSource<String>()
        val pending = async { awaitFirebaseToken(task.task) }
        runCurrent()
        pending.cancelAndJoin()
        task.setException(IOException("late failure"))

        assertTrue(pending.isCancelled)
    }

    @Test fun taskFailureReachesCaller() = runTest {
        val task = TaskCompletionSource<String>()
        task.setException(IOException("AUTHENTICATION_FAILED"))
        val failure = runCatching { awaitFirebaseToken(task.task) }.exceptionOrNull()

        assertEquals("FCM_AUTHENTICATION_FAILED", firebaseRegistrationErrorCode(failure!!))
    }

    @Test fun reportsOnlySanitizedCodes() {
        assertEquals("FCM_AUTHENTICATION_FAILED", firebaseRegistrationErrorCode(Exception(IOException("AUTHENTICATION_FAILED"))))
        assertEquals("FIREBASE_NOT_CONFIGURED", firebaseRegistrationErrorCode(IllegalStateException("private configuration")))
        assertEquals("FCM_TOKEN_UNAVAILABLE", firebaseRegistrationErrorCode(IOException("private response with credentials")))
    }
}
