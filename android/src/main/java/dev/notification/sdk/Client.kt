package dev.notification.sdk

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessaging
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Android lifecycle and background-work wiring; synchronization policy lives in SyncCoordinator. */
internal class Client(val app: Application, val config: SdkConfig) : DefaultLifecycleObserver {
    private val logger = AndroidLogger(config.loggingEnabled)
    val diagnosticEvents = MutableSharedFlow<SdkError>(extraBufferCapacity = 16)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        logger.error("SDK background work failed", error)
        diagnosticEvents.tryEmit(SdkError("BACKGROUND_FAILURE", "SDK background work failed"))
    })
    val engine: CoreClient = CoreClient(config.coreConfig(), FileStateStore(app), DevicePlatform.ANDROID, PushProvider.FCM, metadata = DeviceMetadata(
        appVersion = app.packageManager.getPackageInfo(app.packageName, 0).versionName?.takeIf { it.isNotBlank() },
        osVersion = android.os.Build.VERSION.RELEASE,
        locale = Locale.getDefault().toLanguageTag(),
        timezone = java.util.TimeZone.getDefault().id
    ), wake = ::wake, schedule = { delay -> scheduleSync(app, delay) },
        diagnostic = {
            logger.error("${it.code}: ${it.message}")
            diagnosticEvents.tryEmit(it)
        }, log = { priority, message -> android.util.Log.println(priority, "NotificationDev", message) },
        clearNotifications = { presenter.clear() })
    private val tokenAcquisition = Mutex()
    private val registrationUpdates = Mutex()
    private var tokenGeneration = 0L
    private val wakes = Channel<Unit>(Channel.CONFLATED)
    val presenter = NotificationPresenter(app, config, engine::encodeNotification)

    init {
        presenter.createChannel()
        scope.launch {
            engine.start()

            engine.updateDevice(status = Permissions.status(app, config.channelId).shared())
            wake()

            acquireFirebaseToken()
        }
        scope.launch {
            for (ignored in wakes) {
                delay(250)
                try {
                    engine.awaitReady()
                    engine.requestSync()
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    logger.error("Could not schedule synchronization", error)
                    diagnosticEvents.tryEmit(SdkError("BACKGROUND_FAILURE", "Could not schedule synchronization"))
                }
            }
        }
        scope.launch(Dispatchers.Main.immediate) { ProcessLifecycleOwner.get().lifecycle.addObserver(this@Client) }
    }

    suspend fun onNewToken(token: String) = registrationUpdates.withLock {
        tokenGeneration++
        logger.debug("FCM token refresh received; updating local push registration")
        engine.updateDevice(token = token)
    }

    private fun acquireFirebaseToken() {
        if (!tokenAcquisition.tryLock()) {
            return
        }

        scope.launch {
            try {
                val generation = registrationUpdates.withLock { tokenGeneration }
                val token = try {
                    logger.debug("Requesting FCM token")
                    awaitFirebaseToken(FirebaseMessaging.getInstance().token)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val code = firebaseRegistrationErrorCode(error)
                    logger.error("Firebase token acquisition failed", error)
                    registrationUpdates.withLock {
                        if (generation == tokenGeneration) {
                            engine.reportPushRegistrationFailure(code)
                            diagnosticEvents.tryEmit(SdkError(code, "Firebase token acquisition failed"))
                        }
                    }

                    return@launch
                }

                registrationUpdates.withLock {
                    if (generation == tokenGeneration) {
                        tokenGeneration++
                        logger.debug("FCM token acquired; updating local push registration")
                        engine.updateDevice(token = token)
                    }
                }
            } finally {
                tokenAcquisition.unlock()
            }
        }
    }

    fun wake() {
        wakes.trySend(Unit)
    }

    override fun onStart(owner: LifecycleOwner) {
        scope.launch {
            engine.awaitReady()
            engine.setForeground(isForeground())
            engine.updateDevice(status = Permissions.status(app, config.channelId).shared())
            acquireFirebaseToken()
            engine.requestRefresh()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        scope.launch {
            engine.awaitReady()
            engine.setForeground(isForeground())
        }
    }

    suspend fun isForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}

/** WorkManager provides durable wakeups; it does not calculate SDK retry backoff. */
private suspend fun scheduleSync(app: Application, delayMillis: Long) = withContext(Dispatchers.IO) {
    WorkManager.getInstance(app).enqueueUniqueWork(
        "notification-sdk-sync",
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
    ).result.get()

    Unit
}

class SyncWorker(context: android.content.Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        NotificationDev.syncWorker()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Startup/configuration failures need host correction, not a second retry policy.
        Result.failure()
    }
}
