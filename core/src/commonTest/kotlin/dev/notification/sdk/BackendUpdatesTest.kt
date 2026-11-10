package dev.notification.sdk

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class BackendUpdatesTest {
    @Test fun acknowledgedRoutingKeepsActivitiesIndependentOfOrdinaryPushRegistration() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport)
        engine.start()
        engine.sync()
        assertFalse(store.value!!.routingMetadataPending)
        engine.liveActivity("enter", "delivery", buildJsonObject { put("token", "00ff") })
        engine.updateDevice(token = "ordinary-token")
        transport.pushFailure = TransportException("PROVIDER_CONFIGURATION_REQUIRED", false, httpStatus = 409)
        assertEquals(SyncResult.Done, engine.sync())
        assertEquals(1, transport.liveActivities.size)
    }

    @Test fun explicitLogoutRecoversWhenBootstrapWasRevokedBeforeCredentialRecovery() = runTest {
        val transport = FakeTransport().apply {
            registrationFailure = TransportException("UNAUTHENTICATED", false, httpStatus = 401)
        }
        val engine = Engine(MemoryStore(), transport)
        engine.start()
        engine.sync()
        engine.logout()
        transport.registrationFailure = null
        engine.sync()
        assertEquals(2, transport.registrations.size)
        assertNotEquals(transport.registrations[0].first, transport.registrations[1].first)
        assertEquals(Availability.AVAILABLE, engine.state.value.availability)
        assertNull(engine.state.value.externalId)
    }

    @Test fun routingChangesFenceActivitiesAndRejectedRoutingDoesNotBusyLoop() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport)
        engine.start()
        engine.updateMetadata(DeviceMetadata(appIdentifier = "dev.example.app", apnsEnvironment = "sandbox"))
        engine.sync()
        engine.liveActivity("enter", "private-activity", buildJsonObject { put("token", "00ff") })
        engine.updateMetadata(DeviceMetadata(appIdentifier = "dev.example.app", apnsEnvironment = "production"))
        assertTrue(store.value!!.liveActivities.requests.isEmpty())
        assertFailsWith<SdkException> {
            engine.liveActivity("enter", "private-activity", buildJsonObject { put("token", "00aa") })
        }
        engine.liveActivity("start", "provider:type", buildJsonObject {
            put("activityType", "DeliveryAttributes"); put("providerConfigurationId", "provider")
            put("enabled", true); put("token", "00ff")
        })
        transport.pushFailure = TransportException("PROVIDER_CONFIGURATION_REQUIRED", false, httpStatus = 409)
        assertEquals(SyncResult.Done, engine.sync())
        assertTrue(transport.liveActivities.isEmpty())
        assertTrue(engine.state.value.hasPendingChanges)
        transport.pushFailure = null
        engine.updateDevice(token = "fresh-token")
        engine.sync()
        assertEquals(1, transport.liveActivities.size)
    }

    @Test fun bootstrapReplaySurvivesAnUpgradeAndStorageFailurePreventsSubmission() = runTest {
        val store = MemoryStore()
        val first = FakeTransport().apply {
            registrationTemplate = "original metadata and SDK version"
            registrationFailure = TransportException("NETWORK", true)
        }
        val engine = Engine(store, first)
        engine.start()
        store.failSave = true
        engine.sync()
        assertTrue(first.registrations.isEmpty())
        store.failSave = false
        engine.sync()
        assertEquals(first.registrationTemplate, store.value!!.registrationBody)

        val upgraded = FakeTransport().apply { registrationTemplate = "new metadata and SDK version" }
        val restarted = Engine(store, upgraded)
        restarted.start()
        restarted.sync()
        assertEquals(listOf(first.registrationTemplate), upgraded.registrationBodies)
        assertNotEquals(upgraded.registrationTemplate, upgraded.registrationBodies.single())
    }

    @Test fun revokedCredentialsFenceNotificationsAndWorkUntilExplicitLogout() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        var engine = Engine(store, transport)
        engine.start()
        engine.sync()
        engine.login("account")
        val payload = NotificationPayload(deliveryId = "delivery", associationId = engine.state.value.associationId!!,
            expiresAt = currentTimeMillis() + 60_000, title = "Title", body = "Body")
        assertTrue(engine.open(payload))
        engine.setEmail("private@example.com", true)
        transport.userFailure = TransportException("UNAUTHENTICATED", false, httpStatus = 401)
        engine.sync()
        assertTrue(store.value!!.credentialRevoked)
        assertNull(engine.state.value.externalId)
        assertTrue(engine.opens.value.isEmpty())
        assertFalse(engine.receive(payload))
        assertTrue(store.value!!.events.isEmpty())
        assertFailsWith<SdkException> { engine.track("event", JsonObject(emptyMap()), "event") }

        engine = Engine(store, transport)
        engine.start()
        engine.sync()
        assertEquals(1, transport.registrations.size)
        transport.identityFailure = TransportException("UNAUTHENTICATED", false, httpStatus = 401)
        engine.logout()
        engine.sync()
        assertEquals(2, transport.registrations.size)
        assertFalse(store.value!!.credentialRevoked)
        assertNull(engine.state.value.externalId)
        assertNull(engine.state.value.user.email)
    }

    @Test fun advancedPayloadSurvivesStorageAndSoundNullRemainsDistinctFromOmission() {
        val json = """{"version":1,"deliveryId":"d","associationId":"a","expiresAt":1900000000000,
            "title":"Title","body":"Body","data":{"nested":{"key":[1,true,null]}},"deepLink":null,
            "collapseId":"chat:1","threadId":"chat:1","ttlSeconds":60,"priority":"normal",
            "ios":{"sound":null,"badge":0,"interruptionLevel":"time-sensitive","mutableContent":true,"subtitle":"Subtitle"},
            "android":{"channelId":"chat","groupKey":"chat:1","groupSummary":"Two messages"},
            "imageUrl":"https://example.com/image.png","contentAvailable":true}"""
        val payload = wireJson.decodeFromString<NotificationPayload>(json)
        assertEquals(wireJson.parseToJsonElement(json), wireJson.encodeToJsonElement(payload))
        assertTrue(payload.ios!!.hasSound)
        val omitted = wireJson.decodeFromString<IosNotificationOptions>("{}")
        assertFalse(omitted.hasSound)
        assertEquals("{}", wireJson.encodeToString(omitted))
        val state = StoredState("i", "s", opens = listOf(NotificationOpen("open", payload, 1)))
        assertEquals(payload, decodeStoredState(wireJson.encodeToString(state)).opens.single().notification)
    }
}
