package dev.notification.notification_dev

import android.app.Application
import android.content.Context
import android.content.res.Resources
import dev.notification.sdk.SdkException
import io.flutter.plugin.common.MethodCall
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import org.mockito.Mockito.*

class NativeStartupTest {
    private fun context(): Context {
        val context = mock(Context::class.java)
        val resources = mock(Resources::class.java)
        `when`(context.resources).thenReturn(resources)
        `when`(context.packageName).thenReturn("test.app")
        `when`(resources.getIdentifier("ic_notification", "drawable", "test.app")).thenReturn(42)

        return context
    }

    private fun values() = buildJsonObject {
        put("projectId", "project")
        put("baseUrl", "http://192.168.2.20:5173/")
        put("allowHttp", true)
        put("androidSmallIcon", "ic_notification")
        put("androidChannelId", "notification_dev")
        put("androidChannelName", "Notifications")
    }

    @Test fun savedAutomaticConfigurationStartsNativeSdkWithoutDart() {
        val directory = Files.createTempDirectory("notification-flutter-startup").toFile()
        try {
            val context = context()
            val app = mock(Application::class.java)
            `when`(context.noBackupFilesDir).thenReturn(directory)
            `when`(context.applicationContext).thenReturn(app)
            val saved = java.io.File(directory, "notification-flutter-config.json")
            saved.writeText(values().toString())
            var calls = 0
            NativeStartup.restore(context) { application, config ->
                assertSame(app, application)
                assertEquals("project", config.projectId)
                assertEquals("http://192.168.2.20:5173/", config.baseUrl)
                assertTrue(config.allowHttp)
                calls++
            }

            assertEquals(1, calls)
            assertEquals(values().toString(), saved.readText())
            assertNull(NativeStartup.error)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun manualStartupAndCorruptConfigurationNeverInitializeOrOverwrite() {
        val directory = Files.createTempDirectory("notification-flutter-manual").toFile()
        try {
            val context = context()
            `when`(context.noBackupFilesDir).thenReturn(directory)
            val saved = java.io.File(directory, "notification-flutter-config.json")
            saved.writeText(JsonObject(values() + ("automaticIntegration" to JsonPrimitive(false))).toString())
            NativeStartup.restore(context) { _, _ -> fail("Manual mode must not initialize") }
            assertFalse(NativeStartup.automaticMode)
            saved.writeText("corrupt")
            NativeStartup.restore(context) { _, _ -> fail("Corrupt configuration must not initialize") }

            assertEquals("corrupt", saved.readText())
            assertNotNull(NativeStartup.error)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun configurationResolvesHostResourcesAndPreservesNativeDefaults() {
        val config = NativeStartup.config(context(), values())

        assertEquals(42, config.smallIcon)
        assertTrue(config.displayInForeground)
        assertTrue(config.allowHttp)
        assertFalse(NativeStartup.config(context(), JsonObject(values() - "allowHttp")).allowHttp)
        assertTrue(NativeStartup.automatic(values()))
        assertFalse(NativeStartup.automatic(JsonObject(values() + ("automaticIntegration" to JsonPrimitive(false)))))
    }

    @Test fun missingSmallIconFailsRatherThanUsingAnInvisibleNotification() {
        val values = JsonObject(values() + ("androidSmallIcon" to JsonPrimitive("missing")))
        assertThrows(IllegalArgumentException::class.java) { NativeStartup.config(context(), values) }
    }

    @Test fun codecKeepsBooleansNumbersAndNestedJsonDistinct() {
        val value = codecObject(mapOf("bool" to true, "integer" to 42L, "nested" to listOf(null, "text"))).jsonObject

        assertTrue(value.getValue("bool").jsonPrimitive.boolean)
        assertEquals(42L, value.getValue("integer").jsonPrimitive.long)
        assertEquals(JsonNull, value.getValue("nested").jsonArray.first())
        assertEquals("text", value.getValue("nested").jsonArray.last().jsonPrimitive.content)
    }

    @Test fun errorsAreSanitizedExceptForNativePublicErrors() {
        assertEquals("INVALID_ARGUMENT", bridgeError(IllegalArgumentException("secret")).code)
        assertFalse(bridgeError(Exception("secret")).message.contains("secret"))
        val error = dev.notification.sdk.SdkError("CUSTOM", "Public error")

        assertEquals(error, bridgeError(SdkException(error)))
    }

    @Test fun missingArgumentsFailAtBridgeBoundary() {
        assertThrows(IllegalArgumentException::class.java) { MethodCall("login", emptyMap<String, Any>()).string("externalId") }
    }
}
