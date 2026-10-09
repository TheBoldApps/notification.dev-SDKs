package dev.notification.sdk

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class PublicModelsTest {
    @Test
    fun configurationDefaultsToHostedApiAndPreservesOverrides() {
        assertEquals("https://app.notification.dev/", CoreConfig("project").baseUrl)
        assertEquals("http://localhost:3000/", CoreConfig("project", "http://localhost:3000/", allowHttp = true).baseUrl)
    }

    @Test
    fun snapshotPreservesMixedTagsNullEmailAndLongRevision() {
        val json = """{
            "installationId":"installation","associationId":"association",
            "user":{"id":"user","externalId":null,"email":null,
                "tags":{"text":"value","count":42,"enabled":true,"decimal":1.25}},
            "push":{"optedIn":true,"tokenRegistered":false,"permission":"future_permission","registration":{"status":"unknown","errorCode":null,"updatedAt":null}},
            "revision":9007199254740991,"updatedAt":"2026-09-18T00:00:00Z"
        }"""
        val state = wireJson.decodeFromString<ServerState>(json)

        assertNull(state.user.email)
        assertNull(state.user.externalId)
        assertEquals(9007199254740991L, state.revision)
        assertEquals("future_permission", state.push.permission)
        assertEquals(JsonPrimitive(true), state.user.tags["enabled"])
        assertEquals(JsonPrimitive(42), state.user.tags["count"])
        assertEquals(JsonPrimitive(1.25), state.user.tags["decimal"])
        assertEquals(wireJson.parseToJsonElement(json), wireJson.encodeToJsonElement(state))
    }

    @Test
    fun notificationPreservesNestedJsonAndEpochMilliseconds() {
        val json = """{
            "version":1,"deliveryId":"delivery","associationId":"association",
            "expiresAt":1900000000000,"title":"Title","body":"Body",
            "data":{"nested":{"items":[1,true,null,"text"]}},"deepLink":null
        }"""
        val payload = wireJson.decodeFromString<NotificationPayload>(json)

        assertEquals(1900000000000L, payload.expiresAt)
        assertEquals(wireJson.parseToJsonElement(json), wireJson.encodeToJsonElement(payload))
    }

    @Test
    fun persistedSnapshotsStillDecodeWithCompatibilityDefaults() {
        val state = wireJson.decodeFromString<ServerState>(
            """{"installationId":"i","associationId":"a","user":{"id":"u"},"revision":1,"updatedAt":"now"}"""
        )

        assertEquals(SubscriberState("u"), state.user)
        assertEquals(PushSubscription(), state.push)

        val registration = Registration("credential", state)

        assertEquals(registration, wireJson.decodeFromString<Registration>(wireJson.encodeToString(registration)))
    }
}
