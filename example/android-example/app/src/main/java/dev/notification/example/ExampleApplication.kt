package dev.notification.example

import android.app.Application
import dev.notification.sdk.NotificationDev
import dev.notification.sdk.SdkConfig

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Configure this example through local.properties.
        NotificationDev.initialize(this, SdkConfig(
            projectId = BuildConfig.NOTIFICATION_PROJECT_ID,
            baseUrl = BuildConfig.NOTIFICATION_BASE_URL,
            smallIcon = R.drawable.ic_notification,
            allowHttp = BuildConfig.DEBUG,
            loggingEnabled = BuildConfig.DEBUG,
        ))
    }
}
