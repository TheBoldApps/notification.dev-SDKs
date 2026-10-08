package dev.notification.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal val wireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Only events retain an ordered queue. */
@Serializable
internal sealed interface Event {
    val occurredAt: Long

    @Serializable
    data class Track(val name: String, val properties: JsonObject, override val occurredAt: Long) : Event

    @Serializable
    data class Interaction(val deliveryId: String, val kind: String, override val occurredAt: Long) : Event
}

@Serializable
internal data class QueuedEvent(
    val id: String,
    val associationId: String?,
    val event: Event
)

@Serializable
internal data class DeviceProperties(
    val token: String? = null,
    val optedIn: Boolean = true,
    val permission: String = "not_determined",
    val registration: PushRegistration? = null,
    val metadata: DeviceMetadata? = null
)

@Serializable
internal data class PushRequest(val id: String, val associationId: String, val device: DeviceProperties)

/** Presence means device state needs syncing; a captured request survives ambiguous responses. */
@Serializable
internal data class PendingDevice(val request: PushRequest? = null, val rejected: Boolean = false)

/** Owned by SyncCoordinator; stored with the rest of the SDK state. */
@Serializable
internal data class RetrySchedule(val attempts: Int = 0, val nextAttemptAt: Long = 0)

@Serializable
internal data class StoredState(
    val installationId: String,
    val registrationSecret: String,
    val configurationId: String = "test",
    val credential: String? = null,
    val snapshot: ServerState? = null,
    val lastSyncedAt: Long? = null,
    val retry: RetrySchedule = RetrySchedule(),
    val user: PendingUser = PendingUser(),
    val identity: IdentityState = IdentityState(),
    val events: List<QueuedEvent> = emptyList(),
    val device: DeviceProperties = DeviceProperties(),
    val pendingDevice: PendingDevice? = PendingDevice(),
    val opens: List<NotificationOpen> = emptyList(),
    val seenDeliveries: Map<String, Long> = emptyMap()
)

internal interface StateStore {
    suspend fun load(): StoredState?

    suspend fun save(state: StoredState)
}
