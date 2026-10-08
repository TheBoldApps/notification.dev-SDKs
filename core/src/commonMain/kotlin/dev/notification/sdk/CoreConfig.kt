package dev.notification.sdk

data class CoreConfig(
    val projectId: String,
    val baseUrl: String = "https://app.notification.dev/",
    val allowLocalhostHttp: Boolean = false,
    val loggingEnabled: Boolean = false
) {
    init {
        require(projectId.isNotBlank()) { "Project ID is required" }
    }
}

enum class DevicePlatform { ANDROID, IOS }

enum class PushProvider { FCM, APNS }

@kotlinx.serialization.Serializable
data class DeviceMetadata(
    val appVersion: String? = null,
    val osVersion: String? = null,
    val locale: String? = null,
    val timezone: String? = null
)

/** Platform adapter must encrypt and atomically replace the complete snapshot. */
interface SecureStorage {
    suspend fun load(): String?

    suspend fun save(state: String)
}
