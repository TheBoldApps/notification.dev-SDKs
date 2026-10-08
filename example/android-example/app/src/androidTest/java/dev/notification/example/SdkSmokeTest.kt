package dev.notification.example

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.notification.sdk.NotificationDev
import dev.notification.sdk.RuntimePermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a real backend, an active FCM provider, and a fresh disposable test installation. */
@RunWith(AndroidJUnit4::class)
class SdkSmokeTest {
    @Test fun realEncryptedFileStorageNetworkAndChannelPreferences() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(15000) { while (NotificationDev.state.value.associationId == null) delay(100) }
            val email = "smoke-${System.currentTimeMillis()}@example.com"
            NotificationDev.setEmail(email, optedIn = true)
            withTimeout(15000) { while (NotificationDev.getEmail()?.address != email || NotificationDev.state.value.hasPendingChanges) delay(100) }
            assertNotNull(NotificationDev.getEmail())
            val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
                "notification-sdk.state")
            assertTrue(file.isFile)
            assertFalse(file.readBytes().decodeToString().contains(email))
            NotificationDev.setPushOptedIn(false)
            NotificationDev.setTags(mapOf("smoke" to true))
            withTimeout(15000) { while (NotificationDev.getTags().get("smoke")?.content != "true" || NotificationDev.state.value.hasPendingChanges) delay(100) }
            assertFalse(NotificationDev.getState().pushOptedIn)
            assertEquals(true, NotificationDev.getEmail()?.optedIn)
            NotificationDev.login("smoke-user-${System.currentTimeMillis()}")
            assertEquals(email, NotificationDev.getEmail()?.address)
            NotificationDev.setEmailOptedIn(false)
            withTimeout(15000) { while (NotificationDev.getEmail()?.optedIn != false) delay(100) }
            NotificationDev.removeEmail()
            withTimeout(15000) { while (NotificationDev.getEmail() != null) delay(100) }
            NotificationDev.logout()
            assertNull(NotificationDev.getState().associationId)
        }
    }
    @Test fun nativeMessageDisplayDeduplicationAndOptOut() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant dev.notification.example android.permission.POST_NOTIFICATIONS").use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(15000) { while (NotificationDev.state.value.associationId == null) delay(100) }
            NotificationDev.setPushOptedIn(true)
            val association = NotificationDev.getState().associationId!!
            val raw = """{"version":1,"deliveryId":"smoke-delivery-${System.currentTimeMillis()}","associationId":"$association","expiresAt":${System.currentTimeMillis() + 60000},"title":"SDK smoke","body":"Native callback test"}"""
            val message = com.google.firebase.messaging.RemoteMessage.Builder("sender").addData("notification_dev", raw).build()
            assertTrue(NotificationDev.handleRemoteMessage(message))
            val manager = instrumentation.targetContext.getSystemService(android.app.NotificationManager::class.java)
            withTimeout(5000) { while (manager.activeNotifications.none { n -> n.tag?.startsWith("notification.dev:") == true }) delay(50) }
            assertTrue(NotificationDev.handleRemoteMessage(message))
            assertEquals(1, manager.activeNotifications.count { n -> n.tag?.startsWith("notification.dev:") == true })
            NotificationDev.setPushOptedIn(false)
            withTimeout(5000) { while (manager.activeNotifications.any { n -> n.tag?.startsWith("notification.dev:") == true }) delay(50) }
        }
    }
    @Test fun devicePermissionReadsOsRatherThanSdkOptIn() = runBlocking {
        if (android.os.Build.VERSION.SDK_INT < 33) return@runBlocking
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        } }
        shell("pm grant dev.notification.example android.permission.POST_NOTIFICATIONS")
        assertTrue(NotificationDev.getPushPermissionStatus().areNotificationsEnabled)
        NotificationDev.setPushOptedIn(false)
        assertTrue(NotificationDev.getPushPermissionStatus().areNotificationsEnabled)
        assertEquals(RuntimePermission.GRANTED, NotificationDev.getPushPermissionStatus().runtimePermission)
        // Revocation can kill the process. Exercise denial/fallback in the separate UI smoke script.
    }
}
