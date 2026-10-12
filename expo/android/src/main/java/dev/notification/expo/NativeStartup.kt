package dev.notification.expo

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer
import dev.notification.sdk.NotificationDev
import dev.notification.sdk.SdkConfig
import org.json.JSONObject

internal object NativeStartup {
    var failure: Exception? = null
        private set

    @Synchronized
    fun start(context: Context) {
        try {
            val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
            val raw = requireNotNull(metadata?.getString("dev.notification.expo.CONFIG")) { "Add the notification.dev Expo config plugin" }
            val config = JSONObject(raw)
            val icon = context.resources.getIdentifier(config.getString("androidSmallIcon"), "drawable", context.packageName)
            require(icon != 0) { "Notification small icon is missing" }
            NotificationDev.initialize(context.applicationContext as Application, SdkConfig(
                projectId = config.getString("projectId"),
                baseUrl = config.getString("baseUrl"),
                smallIcon = icon,
                channelId = config.getString("androidChannelId"),
                channelName = config.getString("androidChannelName"),
                displayInForeground = config.getBoolean("displayInForeground"),
                allowHttp = config.getBoolean("allowHttp"),
                loggingEnabled = config.getBoolean("loggingEnabled"),
            ))
            failure = null
        } catch (error: Exception) {
            failure = error
        }
    }
}

/** Runs on every process startup, including Firebase/WorkManager background startup. */
class NotificationDevInitializer : Initializer<Unit> {
    override fun create(context: Context) = NativeStartup.start(context)
    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(WorkManagerInitializer::class.java)
}
