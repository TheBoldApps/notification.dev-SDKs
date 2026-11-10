@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.notification.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class EmailSubscription(val address: String, val optedIn: Boolean, val suppressed: Boolean = false)

@Serializable
data class SubscriberState(
    val id: String, val externalId: String? = null,
    val tags: Map<String, JsonPrimitive> = emptyMap(), val email: EmailSubscription? = null
)

@Serializable
data class PushSubscription(
    val optedIn: Boolean = true, val tokenRegistered: Boolean = false,
    val permission: String = "not_determined",
    val registration: PushRegistration = PushRegistration()
)

/** Token acquisition health is independent of OS permission and delivery receipts. */
@Serializable
data class PushRegistration(
    val status: String = "unknown",
    val errorCode: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class ServerState(
    val installationId: String, val associationId: String, val user: SubscriberState,
    val push: PushSubscription = PushSubscription(),
    val revision: Long, val updatedAt: String
)

/** Local properties include pending edits. Suppression is always server-owned. */
@Serializable
data class UserProperties(
    val email: EmailSubscription? = null,
    val tags: Map<String, JsonPrimitive> = emptyMap()
)

enum class Availability { LOADING, AVAILABLE, UNAVAILABLE }

@Serializable
data class SdkError(val code: String, val message: String)

class SdkException(val error: SdkError, cause: Throwable? = null) : Exception(error.message, cause)

@Serializable
data class SdkState(
    val availability: Availability = Availability.LOADING,
    val user: UserProperties = UserProperties(),
    val installationId: String? = null,
    val associationId: String? = null,
    val externalId: String? = null,
    val lastSyncedAt: Long? = null,
    val syncing: Boolean = false,
    val hasPendingChanges: Boolean = false,
    val pushOptedIn: Boolean = true,
    val pushRegistration: PushRegistration = PushRegistration(),
    val permission: PushPermissionStatus = PushPermissionStatus(),
    val lastError: SdkError? = null,
    val identityChangePending: Boolean = false,
    val pushTokenRegistered: Boolean = false
)

@Serializable
data class PushPermissionStatus(val areNotificationsEnabled: Boolean = false)

@Serializable
data class NotificationPayload(
    val version: Int = 1, val deliveryId: String, val associationId: String,
    val expiresAt: Long, val title: String, val body: String, val data: JsonObject = JsonObject(emptyMap()),
    val deepLink: String? = null,
    @EncodeDefault(NEVER) val collapseId: String? = null,
    @EncodeDefault(NEVER) val threadId: String? = null,
    @EncodeDefault(NEVER) val ttlSeconds: Int? = null,
    @EncodeDefault(NEVER) val priority: String? = null,
    @EncodeDefault(NEVER) val ios: IosNotificationOptions? = null,
    @EncodeDefault(NEVER) val android: AndroidNotificationOptions? = null,
    @EncodeDefault(NEVER) val imageUrl: String? = null,
    @EncodeDefault(NEVER) val contentAvailable: Boolean? = null
)

@Serializable(with = IosNotificationOptionsSerializer::class)
data class IosNotificationOptions(
    val sound: String? = null, val badge: Int? = null,
    val interruptionLevel: String? = null, val mutableContent: Boolean? = null,
    val subtitle: String? = null,
    val hasSound: Boolean = sound != null
)

@Serializable
data class AndroidNotificationOptions(
    val channelId: String? = null, val groupKey: String? = null, val groupSummary: String? = null
)

@Serializable
data class NotificationOpen(val interactionId: String, val notification: NotificationPayload, val openedAt: Long)

@Serializable
data class NotificationEvent(val notification: NotificationPayload)
