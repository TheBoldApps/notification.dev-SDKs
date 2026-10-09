package dev.notification.sdk

/** Configuration contains public identifiers only. Firebase is configured by the host app. */
data class SdkConfig(
    val projectId: String,
    val baseUrl: String = "https://app.notification.dev/",
    val smallIcon: Int,
    val channelId: String = "notification_dev",
    val channelName: String = "Notifications",
    val displayInForeground: Boolean = true,
    val allowHttp: Boolean = false,
    /** Opt-in diagnostic logging to Logcat under the NotificationDev tag. */
    val loggingEnabled: Boolean = false,
)

enum class RuntimePermission { GRANTED, NOT_GRANTED, NOT_APPLICABLE }
enum class ChannelStatus { ENABLED, BLOCKED, NOT_CREATED }
data class AndroidPushPermissionStatus(
    val areNotificationsEnabled: Boolean = false,
    val runtimePermission: RuntimePermission = RuntimePermission.NOT_APPLICABLE,
    val channelStatus: ChannelStatus = ChannelStatus.NOT_CREATED
)

sealed interface PushPermissionResult {
    data object Granted : PushPermissionResult
    data object Denied : PushPermissionResult
    data object SettingsRequired : PushPermissionResult
    data object SettingsOpened : PushPermissionResult
    data class Failed(val code: String) : PushPermissionResult
}

enum class SettingsOpenResult { OPENED, UNAVAILABLE }

internal fun SdkConfig.coreConfig() = CoreConfig(projectId, baseUrl, allowHttp, loggingEnabled)

internal fun AndroidPushPermissionStatus.shared() = PushPermissionStatus(areNotificationsEnabled)
