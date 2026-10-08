package dev.notification.sdk

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Integration boundary for native facades. Scheduling is optional and always best effort. */
class CoreClient internal constructor(
    config: CoreConfig,
    storage: SecureStorage,
    private val transport: HttpTransport,
    wake: () -> Unit = {},
    schedule: suspend (Long) -> Unit = {},
    diagnostic: (SdkError) -> Unit = {},
    logger: SdkLogger = SdkLogger(),
    private val clearNotifications: () -> Unit = {}
) {
    constructor(
        config: CoreConfig,
        storage: SecureStorage,
        platform: DevicePlatform,
        provider: PushProvider,
        metadata: DeviceMetadata = DeviceMetadata(),
        wake: () -> Unit = {},
        schedule: suspend (Long) -> Unit = {},
        diagnostic: (SdkError) -> Unit = {},
        log: (Int, String) -> Unit = { _, _ -> },
        clearNotifications: () -> Unit = {}
    ) : this(
        config, storage,
        HttpTransport(config, metadata, platform, provider, logger = SdkLogger(config.loggingEnabled, log)),
        wake, schedule, diagnostic, SdkLogger(config.loggingEnabled, log), clearNotifications
    )

    private val engine = Engine(object : StateStore {
        override suspend fun load(): StoredState? = storage.load()?.let(::decodeStoredState)

        override suspend fun save(state: StoredState) = storage.save(wireJson.encodeToString(state))
    }, transport, wake = wake, configurationId = "${config.projectId}|${config.baseUrl.trimEnd('/')}",
        diagnostic = diagnostic, logger = logger)
    private val coordinator = SyncCoordinator(engine, schedule, diagnostic = diagnostic)

    private val notificationEvents = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 32)

    /** Live accepted notifications, with no replay. Native adapters retain display ownership. */
    val notifications: SharedFlow<NotificationEvent> = notificationEvents.asSharedFlow()

    val state get() = engine.state
    val opens get() = engine.opens

    suspend fun start() = engine.start()

    suspend fun awaitReady() = engine.awaitReady()

    suspend fun requestSync() = coordinator.requestSync()

    suspend fun runScheduled() = coordinator.runScheduled()

    suspend fun setForeground(active: Boolean) = engine.setForeground(active)

    suspend fun requestRefresh() = engine.requestRefresh()

    suspend fun refresh(): SdkState = engine.refresh()

    suspend fun login(externalId: String) {
        val before = state.value.associationId
        engine.login(externalId)

        if (before != state.value.associationId) {
            clearNotifications()
        }
    }

    suspend fun logout() {
        engine.logout()
        clearNotifications()
    }

    suspend fun setPushOptedIn(enabled: Boolean) {
        engine.setPushOptedIn(enabled)

        if (!enabled) {
            clearNotifications()
        }
    }

    suspend fun setEmail(address: String, optedIn: Boolean) = engine.setEmail(normalizeEmail(address), optedIn)

    suspend fun removeEmail() = engine.removeEmail()

    suspend fun setEmailOptedIn(enabled: Boolean) = engine.setEmailOptedIn(enabled)

    suspend fun setTags(values: Map<String, JsonPrimitive?>) {
        require(values.isNotEmpty() && values.size <= 100)
        values.forEach { (key, value) ->
            require(key.isNotBlank() && key.length <= 128)
            if (value != null) {
                require(value.content.length <= 4096)
                require(value.isString || value.content == "true" || value.content == "false" || value.content.toDoubleOrNull()?.isFinite() == true)
            }
        }

        engine.setTags(values)
    }

    suspend fun track(name: String, properties: JsonObject = JsonObject(emptyMap()), eventId: String? = null): String {
        require(name.isNotBlank() && name.length <= 256 && name != APP_OPENED_EVENT && name != "notification_dev.subscriber_created")
        val id = eventId ?: newId()
        require(id.isNotBlank() && id.length <= 256)
        require(properties.toString().encodeToByteArray().size <= 64 * 1024)

        return engine.track(name, properties, id)
    }

    suspend fun updateDevice(token: String? = null, status: PushPermissionStatus? = null) {
        require(token == null || token.isNotBlank())

        engine.updateDevice(token, status)
    }

    suspend fun updateMetadata(metadata: DeviceMetadata) = engine.updateMetadata(metadata)

    suspend fun reportPushRegistrationFailure(code: String) {
        require(code in setOf("FCM_AUTHENTICATION_FAILED", "FCM_TOKEN_UNAVAILABLE", "FIREBASE_NOT_CONFIGURED", "APNS_REGISTRATION_FAILED"))

        engine.reportPushRegistrationFailure(code)
    }

    fun decodeNotification(json: String): NotificationPayload? = runCatching {
        wireJson.decodeFromString<NotificationPayload>(json).takeIf { it.title.length <= 1024 && it.body.length <= 4096 }
    }.getOrNull()

    suspend fun receive(payload: NotificationPayload): Boolean {
        if (!engine.receive(payload)) {
            return false
        }

        notificationEvents.emit(NotificationEvent(payload))

        return true
    }

    suspend fun open(payload: NotificationPayload): Boolean = engine.open(payload)

    suspend fun acknowledgeOpen(id: String) = engine.acknowledgeOpen(id)

    suspend fun displayIfEligible(payload: NotificationPayload, display: () -> Unit) = engine.displayIfEligible(payload, display)

    fun encodeNotification(payload: NotificationPayload): String = wireJson.encodeToString(payload)

    /** Cancel the owning platform scope before closing its HTTP engine. */
    fun close() = transport.close()
}
