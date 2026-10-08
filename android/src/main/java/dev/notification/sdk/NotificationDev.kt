package dev.notification.sdk

import android.app.Activity
import android.app.Application
import android.content.Intent
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Native entry point. Call initialize from Application.onCreate, including background process startup. */
object NotificationDev {
    @Volatile
    private var client: Client? = null

    @Synchronized
    fun initialize(application: Application, config: SdkConfig) {
        require(config.projectId.isNotBlank() && config.smallIcon != 0 && config.channelId.isNotBlank())
        client?.let {
            require(it.config == config) { "SDK already initialized with different configuration" }

            return
        }

        val logger = AndroidLogger(config.loggingEnabled)

        try {
            client = Client(application, config)
        } catch (error: Exception) {
            logger.error("SDK client creation failed", error)
            throw error
        }
    }

    private fun requireClient(): Client =
        checkNotNull(client) { "Call NotificationDev.initialize in Application.onCreate first" }

    val isInitialized: Boolean get() = client != null

    /** Await local initialization, without waiting for server registration. */
    suspend fun awaitInitialization() {
        ready()
    }

    val state: StateFlow<SdkState> get() = requireClient().engine.state
    val pendingOpens: StateFlow<List<NotificationOpen>> get() = requireClient().engine.opens
    val notifications: SharedFlow<NotificationEvent> get() = requireClient().engine.notifications
    val diagnostics: SharedFlow<SdkError> get() = requireClient().diagnosticEvents.asSharedFlow()

    fun getState(): SdkState = state.value

    fun getTags(): Map<String, JsonPrimitive> = state.value.user.tags

    fun getEmail(): EmailSubscription? = state.value.user.email

    suspend fun refresh(): SdkState = ready().engine.refresh()

    suspend fun login(externalId: String) {
        ready().engine.login(externalId)
    }

    suspend fun logout() {
        ready().engine.logout()
    }

    suspend fun setPushOptedIn(enabled: Boolean) {
        ready().engine.setPushOptedIn(enabled)
    }

    suspend fun setEmail(email: String, optedIn: Boolean) {
        ready().engine.setEmail(email, optedIn)
    }

    suspend fun removeEmail() {
        ready().engine.removeEmail()
    }

    suspend fun setEmailOptedIn(enabled: Boolean) {
        ready().engine.setEmailOptedIn(enabled)
    }

    suspend fun setTags(values: Map<String, Any>) {
        val tags = values.mapValues { (_, value) ->
            when (value) {
                is String -> {
                    JsonPrimitive(value)
                }

                is Boolean -> JsonPrimitive(value)
                is Number -> {
                    JsonPrimitive(value)
                }

                else -> throw IllegalArgumentException("Tag values must be strings, booleans, or finite numbers")
            }
        }
        ready().engine.setTags(tags)
    }

    suspend fun removeTags(keys: Set<String>) {
        ready().engine.setTags(keys.associateWith { null })
    }

    suspend fun track(name: String, properties: JsonObject = JsonObject(emptyMap()), eventId: String? = null): String {
        return ready().engine.track(name, properties, eventId)
    }

    suspend fun acknowledgeOpen(interactionId: String) {
        ready().engine.acknowledgeOpen(interactionId)
    }

    fun getPushPermissionStatus(): AndroidPushPermissionStatus {
        val c = requireClient()
        val status = Permissions.status(c.app, c.config.channelId)
        c.scope.launch {
            c.engine.awaitReady()
            c.engine.updateDevice(status = status.shared())
        }

        return status
    }

    suspend fun requestPushPermission(
        activity: Activity,
        settingFallback: Boolean = false
    ): PushPermissionResult {
        val c = ready()
        val result = Permissions.request(activity, c.config.channelId, settingFallback)
        c.engine.updateDevice(status = Permissions.status(c.app, c.config.channelId).shared())

        return result
    }

    fun openPushSettings(activity: Activity): SettingsOpenResult = Permissions.open(activity)

    /** Forward from an existing FirebaseMessagingService; returns false for messages not owned by this SDK. */
    suspend fun handleRemoteMessage(message: RemoteMessage): Boolean {
        val payload = message.data["notification_dev"] ?: return false
        return handlePayload(payload)
    }

    internal suspend fun handlePayload(json: String): Boolean {
        val c = ready()
        val payload = c.engine.decodeNotification(json) ?: return false

        if (c.engine.receive(payload)) {
            if (c.config.displayInForeground || !c.isForeground()) {
                c.engine.displayIfEligible(payload) { c.presenter.display(payload) }
            }
        }

        return true
    }

    suspend fun onNewToken(token: String) {
        ready().onNewToken(token)
    }

    /** Call from both Activity.onCreate(intent) and onNewIntent(intent). */
    suspend fun handleIntent(intent: Intent?): Boolean {
        val raw = intent?.getStringExtra(NotificationPresenter.EXTRA_PAYLOAD) ?: return false
        val c = ready()
        val payload = c.engine.decodeNotification(raw) ?: return false
        // Only process intents created by our pending intent, authenticated with an installation-local key.
        val signature = intent.getStringExtra(NotificationPresenter.EXTRA_SIGNATURE) ?: return false
        if (!c.presenter.verify(raw, signature)) {
            return false
        }

        val handled = c.engine.open(payload)
        intent.removeExtra(NotificationPresenter.EXTRA_PAYLOAD)
        intent.removeExtra(NotificationPresenter.EXTRA_SIGNATURE)

        return handled
    }

    internal suspend fun syncWorker() {
        val c = requireClient()
        c.engine.awaitReady()
        c.engine.runScheduled()
    }

    private suspend fun ready(): Client = requireClient().also { it.engine.awaitReady() }
}
