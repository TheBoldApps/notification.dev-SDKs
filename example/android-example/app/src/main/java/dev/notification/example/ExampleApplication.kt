package dev.notification.example

import android.app.Application
import dev.notification.sdk.NotificationDev
import dev.notification.sdk.SdkConfig

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Replace this placeholder with your notification.dev project UUID.
        NotificationDev.initialize(this, SdkConfig(
            projectId = "YOUR_PUBLIC_PROJECT_UUID",
            smallIcon = R.drawable.ic_notification,
            allowHttp = BuildConfig.DEBUG,
            loggingEnabled = BuildConfig.DEBUG,
        ))
    }
}
