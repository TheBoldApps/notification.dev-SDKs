package dev.notification.sdk

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CoreClientTest {
    private class Storage : SecureStorage {
        var value: String? = null

        override suspend fun load(): String? = value

        override suspend fun save(state: String) {
            value = state
        }
    }

    @Test
    fun nativeBoundaryValidatesAndPersistsLocalEditsWithoutNetwork() = runTest {
        val storage = Storage()
        val config = CoreConfig("project", "https://example.com")
        val client = CoreClient(config, storage, DevicePlatform.ANDROID, PushProvider.FCM)
        try {
            client.start()
            client.setEmail(" User@EXAMPLE.COM ", true)
            client.setTags(mapOf("plan" to JsonPrimitive("pro"), "count" to JsonPrimitive(3)))
            val eventId = client.track("purchase")

            assertEquals("User@example.com", client.state.value.user.email!!.address)
            assertEquals(JsonPrimitive(3), client.state.value.user.tags["count"])
            assertTrue(client.state.value.hasPendingChanges)
            assertEquals(eventId, decodeStoredState(storage.value!!).events.single().id)
            assertFailsWith<IllegalArgumentException> { client.setTags(mapOf("" to JsonPrimitive(true))) }
            assertFailsWith<IllegalArgumentException> { client.setTags(mapOf("number" to JsonPrimitive(Double.NaN))) }
            assertFailsWith<IllegalArgumentException> { client.track("") }
            assertFailsWith<IllegalArgumentException> { client.track(APP_OPENED_EVENT) }
            assertFailsWith<IllegalArgumentException> { client.track("notification_dev.subscriber_created") }
            assertFailsWith<IllegalArgumentException> { client.updateDevice(token = "") }

            val restarted = CoreClient(config, storage, DevicePlatform.ANDROID, PushProvider.FCM)
            try {
                restarted.start()
                assertEquals(client.state.value.user, restarted.state.value.user)
                assertEquals(client.state.value.installationId, restarted.state.value.installationId)
            } finally {
                restarted.close()
            }
        } finally {
            client.close()
        }
    }
}
