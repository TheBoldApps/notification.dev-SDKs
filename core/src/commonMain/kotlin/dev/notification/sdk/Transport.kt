package dev.notification.sdk

import dev.notification.sdk.generated.model.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.fromHttpToGmtDate
import kotlinx.serialization.Serializable

internal class TransportException(val code: String, val retryable: Boolean, val retryAfterMs: Long = 0, val httpStatus: Int? = null) :
    Exception("SDK request failed ($code)")

internal interface Transport {
    suspend fun register(id: String, secret: String): Registration
    suspend fun read(id: String, credential: String): ServerState
    suspend fun updateUser(id: String, credential: String, request: UserRequest): ServerState
    suspend fun sendEvent(id: String, credential: String, event: QueuedEvent)
    suspend fun identity(
        id: String, credential: String, operationId: String, associationId: String,
        externalId: String?
    ): ServerState

    suspend fun push(id: String, credential: String, request: PushRequest)
}

internal class HttpTransport(
    config: CoreConfig,
    private val metadata: DeviceMetadata = DeviceMetadata(),
    private val platform: DevicePlatform = DevicePlatform.ANDROID,
    private val provider: PushProvider = PushProvider.FCM,
    client: HttpClientEngine = platformHttpEngine(),
    logger: SdkLogger = SdkLogger()
) : Transport {
    private val projectId = config.projectId
    private val http = ApiHttpClient(config, client, logger)

    fun close() = http.close()

    override suspend fun register(id: String, secret: String): Registration {
        val response = http.execute { api ->
            api.registerInstallation(id, RegistrationInput(
                projectId, id, secret, SubscribersPlatform.valueOf(platform.name), "0.2.0", metadata.toWire()
            ))
        }

        return Registration(response.installationCredential, response.state.toState())
    }

    override suspend fun read(id: String, credential: String): ServerState =
        http.execute(credential, pathId = id) { it.getSdkState(INSTALLATION_PATH_ID) }.toState()

    override suspend fun identity(
        id: String, credential: String, operationId: String, associationId: String,
        externalId: String?
    ): ServerState = http.execute(credential, pathId = id) { api ->
        if (externalId == null) {
            api.detachInstallationIdentity(INSTALLATION_PATH_ID, operationId, LogoutInput(associationId))
        } else {
            api.linkInstallationIdentity(INSTALLATION_PATH_ID, operationId, LoginInput(associationId, externalId))
        }
    }.toState()

    override suspend fun push(id: String, credential: String, request: PushRequest) {
        http.execute(credential, pathId = id) { api ->
            api.syncPushToken(INSTALLATION_PATH_ID, request.id, PushInput(
                associationId = request.associationId,
                provider = SdkProvider.valueOf(provider.name),
                token = request.device.token,
                registration = request.device.registration?.let {
                    SubscribersPushRegistrationReport(status = it.status, errorCode = it.errorCode)
                },
                permission = SubscribersPermissionState.entries.first { it.value == request.device.permission },
                optedIn = request.device.optedIn,
                metadata = (request.device.metadata ?: metadata).toWire()
            ))
        }
    }

    override suspend fun updateUser(id: String, credential: String, request: UserRequest): ServerState {
        val email = request.changes.email
        val tags = request.changes.tags.mapNotNull { (key, change) ->
            change.value?.let { key to it }
        }.toMap().takeIf { it.isNotEmpty() }
        val removedTags = request.changes.tags.filterValues { it.value == null }.keys.takeIf { it.isNotEmpty() }
        val patch = UserPatch(
            associationId = request.associationId,
            email = email?.takeUnless { it.remove }?.let {
                EmailUpdate(optedIn = requireNotNull(it.optedIn), address = it.address)
            },
            clearEmail = true.takeIf { email?.remove == true },
            tags = tags,
            removeTags = removedTags
        )

        return http.execute(credential, pathId = id) { it.updateSdkUser(INSTALLATION_PATH_ID, request.id, patch) }.toState()
    }

    override suspend fun sendEvent(id: String, credential: String, event: QueuedEvent) {
        val operation = when (val value = event.event) {
            is Event.Track -> SdkOperation(event.id, event = EventPayload(
                event.id, value.name, value.occurredAt, value.properties
            ))
            is Event.Interaction -> SdkOperation(event.id, interaction = InteractionPayload(
                value.deliveryId,
                InteractionType.entries.first { it.value == value.kind },
                value.occurredAt
            ))
        }
        val response = http.execute(credential, pathId = id) {
            it.submitSdkOperations(INSTALLATION_PATH_ID, event.id, OperationsInput(requireNotNull(event.associationId), listOf(operation)))
        }
        val result = response.results.singleOrNull()
            ?: throw TransportException("INVALID_RESPONSE", false)

        if (result.operationId != event.id) {
            throw TransportException("INVALID_RESPONSE", false)
        }

        if (!result.succeeded) {
            throw TransportException(result.code ?: "EVENT_REJECTED", result.retryable == true)
        }
    }
}

@Serializable
internal data class Registration(val installationCredential: String, val state: ServerState)

private fun dev.notification.sdk.generated.model.ServerState.toState() = ServerState(
    installationId = installationId,
    associationId = associationId,
    user = SubscriberState(
        id = user.id,
        externalId = user.externalId,
        tags = user.tags,
        email = user.email?.let { EmailSubscription(it.address, it.optedIn, it.suppressed) }
    ),
    push = PushSubscription(push.optedIn, push.tokenRegistered, push.permission,
        push.registration?.let { PushRegistration(it.status, it.errorCode, it.updatedAt) } ?: PushRegistration()),
    revision = revision,
    updatedAt = updatedAt
)

internal fun retryAfterMillis(value: String?, now: Long = currentTimeMillis()): Long {
    if (value == null) {
        return 0
    }

    value.toLongOrNull()?.let { return it.coerceIn(0, 86400) * 1000 }

    return runCatching { (value.fromHttpToGmtDate().timestamp - now).coerceIn(0, 86_400_000) }.getOrDefault(0L)
}

private fun DeviceMetadata.toWire() = SubscribersInstallationMetadata(appVersion = appVersion, osVersion = osVersion, locale = locale, timezone = timezone)
