package dev.notification.sdk

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class PermissionAction { GRANTED, PROMPT, SETTINGS, SETTINGS_REQUIRED }

internal fun permissionAction(
    enabled: Boolean, runtimeSupported: Boolean, requested: Boolean,
    rationale: Boolean, fallback: Boolean
): PermissionAction = when {
    enabled -> PermissionAction.GRANTED
    runtimeSupported && (!requested || rationale) -> PermissionAction.PROMPT
    fallback -> PermissionAction.SETTINGS
    else -> PermissionAction.SETTINGS_REQUIRED
}

internal object Permissions {
    private var pending: CompletableDeferred<PushPermissionResult>? = null
    fun status(context: Context, channelId: String): AndroidPushPermissionStatus {
        val manager = NotificationManagerCompat.from(context)
        val channel = if (Build.VERSION.SDK_INT >= 26) manager.getNotificationChannel(channelId) else null
        val blockedGroup = if (Build.VERSION.SDK_INT >= 28 && channel?.group != null)
            manager.getNotificationChannelGroup(channel.group)?.isBlocked == true else false
        return AndroidPushPermissionStatus(
            areNotificationsEnabled = manager.areNotificationsEnabled(),
            runtimePermission = if (Build.VERSION.SDK_INT < 33) RuntimePermission.NOT_APPLICABLE else
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                )
                    RuntimePermission.GRANTED else RuntimePermission.NOT_GRANTED,
            channelStatus = when {
                Build.VERSION.SDK_INT < 26 -> ChannelStatus.ENABLED
                channel == null -> ChannelStatus.NOT_CREATED
                channel.importance == android.app.NotificationManager.IMPORTANCE_NONE || blockedGroup -> ChannelStatus.BLOCKED
                else -> ChannelStatus.ENABLED
            }
        )
    }

    private fun history(context: Context) = File(context.noBackupFilesDir, "notification-permission-requested")
    suspend fun request(activity: Activity, channelId: String, fallback: Boolean): PushPermissionResult =
        withContext(Dispatchers.Main.immediate) {
            if (activity.isFinishing || activity.isDestroyed) return@withContext PushPermissionResult.Failed("ACTIVITY_UNAVAILABLE")
            if (pending != null) return@withContext PushPermissionResult.Failed("REQUEST_IN_PROGRESS")
            val current = status(activity, channelId)
            val runtime = Build.VERSION.SDK_INT >= 33 && current.runtimePermission != RuntimePermission.GRANTED
            val rationale =
                runtime && activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            val action = permissionAction(
                current.areNotificationsEnabled,
                runtime,
                history(activity).exists(),
                rationale,
                fallback
            )
            when (action) {
                PermissionAction.GRANTED -> PushPermissionResult.Granted
                PermissionAction.SETTINGS_REQUIRED -> PushPermissionResult.SettingsRequired
                PermissionAction.SETTINGS -> if (open(activity) == SettingsOpenResult.OPENED) PushPermissionResult.SettingsOpened else PushPermissionResult.Failed(
                    "SETTINGS_UNAVAILABLE"
                )

                PermissionAction.PROMPT -> {
                    val result = CompletableDeferred<PushPermissionResult>()
                    pending = result
                    try {
                        activity.startActivity(Intent(activity, PermissionActivity::class.java))
                        result.await()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        PushPermissionResult.Failed("PROMPT_UNAVAILABLE")
                    } finally {
                        if (pending === result) pending = null
                    }
                }
            }
        }

    fun complete(result: PushPermissionResult) {
        pending?.complete(result)
    }

    fun hasRequest(): Boolean = pending != null
    fun recordDecision(context: Context) {
        history(context).writeText("1")
    }

    fun open(activity: Activity): SettingsOpenResult {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= 26) add(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                    Settings.EXTRA_APP_PACKAGE,
                    activity.packageName
                )
            )
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        }
        for (intent in intents) {
            try {
                activity.startActivity(intent); return SettingsOpenResult.OPENED
            } catch (_: android.content.ActivityNotFoundException) { /* OEM fallback. */
            } catch (_: SecurityException) { /* Try app details. */
            }
        }
        return SettingsOpenResult.UNAVAILABLE
    }
}

/** Internal transparent activity avoids forcing hosts to register ActivityResult launchers before STARTED. */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Permissions.hasRequest()) {
            finish(); return
        }
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4201)
        } else if (Build.VERSION.SDK_INT < 33) finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 4201) return
        // An interrupted/dismissed request with no result is not a recorded denial.
        if (grantResults.isNotEmpty()) Permissions.recordDecision(this)
        Permissions.complete(
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                PushPermissionResult.Granted else PushPermissionResult.Denied
        )
        finish()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) Permissions.complete(PushPermissionResult.Failed("ACTIVITY_DESTROYED"))
        super.onDestroy()
    }
}
