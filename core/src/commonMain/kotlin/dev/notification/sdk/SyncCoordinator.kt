package dev.notification.sdk

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface SyncResult {
    data object Done : SyncResult

    data object Pending : SyncResult

    data class Retry(val minimumDelayMillis: Long) : SyncResult
}

/** The sole owner of retry timing for both foreground attempts and durable worker wakeups. */
internal class SyncCoordinator(
    private val engine: Engine,
    private val schedule: suspend (Long) -> Unit = {},
    private val now: () -> Long = ::currentTimeMillis,
    private val jitter: (Long) -> Long = { Random.nextLong(it / 2 + 1) },
    private val diagnostic: (SdkError) -> Unit = {}
) {
    private val mutex = Mutex()

    suspend fun requestSync() {
        // Persist a recovery wakeup before attempting HTTP in this process.
        // That worker will schedule any retries, even if this process dies first.
        scheduleSafely(0)
        attempt()
    }

    suspend fun runScheduled() {
        val delay = attempt()
        if (delay != null) {
            scheduleSafely(delay)
        }
    }

    private suspend fun scheduleSafely(delay: Long) {
        try {
            schedule(delay)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            diagnostic(SdkError("SCHEDULING_FAILURE", "Background scheduling failed; synchronization remains available"))
        }
    }

    private suspend fun attempt(): Long? = mutex.withLock {
        val previous = engine.retrySchedule()
        val remaining = previous.nextAttemptAt - now()
        if (remaining > 0) {
            return@withLock remaining
        }

        val result = engine.sync()
        val retry = when (result) {
            SyncResult.Done, SyncResult.Pending -> RetrySchedule()
            is SyncResult.Retry -> {
                val backoff = (1000L shl previous.attempts.coerceAtMost(16)).coerceAtMost(6 * 60 * 60 * 1000L)
                val delay = maxOf(result.minimumDelayMillis, backoff + jitter(backoff))
                RetrySchedule(previous.attempts + 1, now() + delay)
            }
        }
        if (retry != previous) {
            engine.saveRetrySchedule(retry)
        }

        when (result) {
            SyncResult.Done -> null
            SyncResult.Pending -> 0L
            is SyncResult.Retry -> (retry.nextAttemptAt - now()).coerceAtLeast(0)
        }
    }
}
