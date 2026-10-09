package dev.notification.notification_dev

import android.app.Application
import android.content.Context
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer
import dev.notification.sdk.NotificationDev
import dev.notification.sdk.SdkConfig
import java.io.File
import kotlinx.serialization.json.*

/** Public startup configuration only. Identity and secrets remain in native encrypted storage. */
internal object NativeStartup {
    @Volatile var error: Throwable? = null
        private set

    @Volatile var configuration: JsonObject? = null
        private set

    @Volatile var automaticMode = true
        private set

    private fun file(context: Context) = File(context.noBackupFilesDir, "notification-flutter-config.json")

    fun automatic(values: JsonObject): Boolean = values["automaticIntegration"]?.jsonPrimitive?.boolean ?: true

    fun config(context: Context, values: JsonObject): SdkConfig {
        fun string(key: String) = requireNotNull(values[key]?.jsonPrimitive?.content) { "Missing configuration" }
        val icon = string("androidSmallIcon")
        val resources = context.resources
        val resourceId = resources.getIdentifier(icon, "drawable", context.packageName)
            .takeIf { it != 0 } ?: resources.getIdentifier(icon, "mipmap", context.packageName)
        require(resourceId != 0) { "Android small icon resource does not exist" }

        return SdkConfig(
            projectId = string("projectId"), baseUrl = string("baseUrl"), smallIcon = resourceId,
            channelId = string("androidChannelId"), channelName = string("androidChannelName"),
            displayInForeground = values["displayInForeground"]?.jsonPrimitive?.boolean ?: true,
            allowHttp = values["allowHttp"]?.jsonPrimitive?.boolean ?: false,
            loggingEnabled = values["loggingEnabled"]?.jsonPrimitive?.boolean ?: false,
        )
    }

    @Synchronized
    fun restore(context: Context, initialize: (Application, SdkConfig) -> Unit = NotificationDev::initialize) {
        val saved = file(context)
        if (!saved.exists()) {
            return
        }

        try {
            val values = Json.parseToJsonElement(saved.readText()).jsonObject
            automaticMode = automatic(values)
            if (automaticMode) {
                initialize(context.applicationContext as Application, config(context, values))
                configuration = values
            }
            error = null
        } catch (failure: Exception) {
            // Keep the file and native identity intact. Report a sanitized error to Dart.
            error = failure
        }
    }

    fun checkConfiguration(values: JsonObject) {
        if (configuration != null && configuration != values) {
            throw dev.notification.sdk.SdkException(dev.notification.sdk.SdkError(
                "CONFIGURATION_CHANGED", "SDK already initialized with different configuration"))
        }
    }

    fun save(context: Context, values: JsonObject) {
        val target = android.util.AtomicFile(file(context))
        val output = target.startWrite()
        try {
            output.write(values.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
            configuration = values
            automaticMode = automatic(values)
            error = null
        } catch (failure: Exception) {
            target.failWrite(output)
            throw failure
        }
    }
}

/** FirebaseInitProvider precedes AndroidX Startup; WorkManager must precede SDK wakeups. */
class NotificationSdkInitializer : Initializer<Unit> {
    override fun create(context: Context) = NativeStartup.restore(context)

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(WorkManagerInitializer::class.java)
}
