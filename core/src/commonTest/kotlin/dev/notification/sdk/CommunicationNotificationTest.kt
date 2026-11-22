package dev.notification.sdk

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class CommunicationNotificationTest {
    @Test fun senderMetadataSurvivesNotificationAndPendingOpenStorage() {
        val communication = CommunicationNotification(
            CommunicationSender("sender-1", "Anna", "https://example.com/avatar.jpg"), "conversation-1")
        val notification = NotificationPayload(deliveryId = "delivery", associationId = "association",
            expiresAt = 1900000000000, title = "Anna", body = "Hello", communication = communication)
        val restored = wireJson.decodeFromString<NotificationPayload>(wireJson.encodeToString(notification))
        assertEquals(notification, restored)
        assertTrue(restored.communication!!.isValid())
        val state = StoredState("installation", "secret", opens = listOf(NotificationOpen("tap", notification, 1)))
        val opened = decodeStoredState(wireJson.encodeToString(state)).opens.single().notification
        assertEquals("delivery", opened.deliveryId)
        assertEquals(communication, opened.communication)
        assertNull(wireJson.decodeFromString<NotificationPayload>("""{"deliveryId":"d","associationId":"a","expiresAt":1,"title":"t","body":"b"}""").communication)
    }

    @Test fun validatesOptionalSenderAvatarAndOpaqueIdentifiers() {
        val sender = CommunicationSender("sender", "Anna")
        assertTrue(CommunicationNotification(sender, "chat").isValid())
        assertTrue(CommunicationNotification(sender.copy(name = "😀".repeat(255)), "chat").isValid())
        assertFalse(CommunicationNotification(sender.copy(name = "😀".repeat(256)), "chat").isValid())
        for (url in listOf("file:///secret", "https://user:password@example.com/avatar.jpg", "not a URL", "https://example.com/" + "a".repeat(2048))) {
            assertFalse(CommunicationNotification(sender.copy(avatarUrl = url), "chat").isValid())
        }
        assertFalse(CommunicationNotification(sender.copy(id = " "), "chat").isValid())
        assertFalse(CommunicationNotification(sender.copy(name = "a".repeat(256)), "chat").isValid())
        assertFalse(CommunicationNotification(sender, " ").isValid())
        assertTrue(CommunicationNotification(sender.copy(name = "😀".repeat(255)), "chat").isValid())
        assertFalse(CommunicationNotification(sender.copy(name = "😀".repeat(256)), "chat").isValid())
    }
    @Test fun optionalMalformedCommunicationFallsBackWithoutLosingTheDelivery() {
        val config = CoreConfig("project")
        val storage = object : SecureStorage {
            override suspend fun load(): String? = null
            override suspend fun save(state: String) {}
        }
        val client = CoreClient(config, storage, HttpTransport(config, client = MockEngine { respond("{}") }))
        try {
            for (communication in listOf("{}", "42", "null", """{"sender":{"id":"x","name":" "},"conversationId":"chat"}""")) {
                val notification = client.decodeNotification("""{"deliveryId":"d","associationId":"a","expiresAt":1,"title":"Original title","body":"Original body","communication":$communication}""")!!
                assertEquals("d", notification.deliveryId)
                assertEquals("Original title", notification.title)
                assertEquals("Original body", notification.body)
                assertNull(notification.communication)
            }
        } finally { client.close() }
    }

}
