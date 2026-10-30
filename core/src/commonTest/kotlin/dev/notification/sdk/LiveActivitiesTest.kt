package dev.notification.sdk

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class LiveActivitiesTest {
    private val grant = "g".repeat(43)
    private fun token(value: String, grant: String? = this.grant) = buildJsonObject {
        put("token", value)
        grant?.let { put("registrationToken", it) }
    }
    private val start = buildJsonObject {
        put("activityType", "DeliveryAttributes")
        put("providerConfigurationId", "00000000-0000-0000-0000-000000000001")
        put("enabled", true)
        put("token", "00ff")
    }

    @Test fun capturedRequestSurvivesRestartAndPrecedesRotationAndExit() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport)
        engine.start(); engine.sync()
        engine.liveActivity("enter", "order-1", token("0011"))
        engine.liveActivity("enter", "order-1", token("0022"))
        transport.liveActivityFailure = TransportException("NETWORK", true)
        assertEquals(SyncResult.Retry(0), engine.sync())
        val captured = transport.liveActivities.single()
        assertEquals("0022", captured.body.getValue("token").jsonPrimitive.content)
        engine.liveActivity("enter", "order-1", token("0033"))
        engine.liveActivity("exit", "order-1", JsonObject(emptyMap()))
        // Persist/decode, not merely reuse the in-memory state object.
        store.value = decodeStoredState(wireJson.encodeToString(store.value!!))
        val restored = Engine(store, transport)
        restored.start()
        transport.liveActivityFailure = null
        assertEquals(SyncResult.Done, restored.sync())
        assertEquals(captured, transport.liveActivities[1])
        assertEquals(listOf("enter", "enter", "exit"), transport.liveActivities.map { it.kind })
        assertFalse(restored.state.value.hasPendingChanges)
        assertFailsWith<SdkException> { restored.liveActivity("enter", "order-1", token("0044")) }
    }

    @Test fun associationChangeDropsPrivateRequestsAndReissuesOnlyStartEligibility() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport)
        engine.start(); engine.sync()
        engine.liveActivity("start", "provider:type", start)
        engine.bindLiveActivity("order-1")
        engine.liveActivity("enter", "order-1", token("0011"))
        engine.login("new-user")
        assertFailsWith<SdkException> { engine.bindLiveActivity("order-1") }
        assertEquals(SyncResult.Done, engine.sync())
        assertEquals(listOf("start"), transport.liveActivities.map { it.kind })
        assertEquals(engine.state.value.associationId, transport.liveActivities.single().associationId)
        engine.logout()
        assertFailsWith<SdkException> { engine.liveActivity("enter", "order-2", token("0022")) }
        engine.sync()
        assertFailsWith<SdkException> { engine.liveActivity("enter", "order-1", token("0022")) }
    }

    @Test fun logoutDuringUploadNeverRestoresAnOldRegistration() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport)
        engine.start(); engine.sync()
        engine.liveActivity("enter", "order-1", token("0011"))
        transport.afterLiveActivity = { engine.logout() }
        engine.sync()
        transport.afterLiveActivity = {}
        engine.sync()
        assertEquals(1, transport.liveActivities.size)
        assertFailsWith<SdkException> { engine.bindLiveActivity("order-1") }
        assertTrue(store.value!!.liveActivities.requests.isEmpty())
    }

    @Test fun terminalFailureDoesNotBlockOtherActivitiesOrLoopOnReconciliation() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val errors = mutableListOf<SdkError>()
        val engine = Engine(store, transport, diagnostic = { errors.add(it) })
        engine.start(); engine.sync()
        engine.liveActivity("enter", "order-1", token("0011"))
        transport.liveActivityFailure = TransportException("REGISTRATION_FORBIDDEN", false)
        engine.sync()
        engine.liveActivity("enter", "order-1", token("0011"))
        engine.sync()
        assertEquals(1, transport.liveActivities.size)
        assertEquals("REGISTRATION_FORBIDDEN", errors.last().code)
        transport.liveActivityFailure = null
        engine.liveActivity("enter", "order-1", token("0011", "h".repeat(43)))
        engine.sync()
        assertEquals(2, transport.liveActivities.size)
    }

    @Test fun queueBeforeRegistrationAndOldSnapshotCompatibility() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport)
        engine.start()
        val old = wireJson.encodeToString(store.value!!)
        val withoutLiveActivities = JsonObject(wireJson.parseToJsonElement(old).jsonObject - "liveActivities")
        assertEquals(LiveActivityStorage(), decodeStoredState(withoutLiveActivities.toString()).liveActivities)
        assertFailsWith<SdkException> { engine.bindLiveActivity("order-1") }
        engine.liveActivity("start", "provider:type", start)
        engine.sync()
        assertEquals(engine.state.value.associationId, transport.liveActivities.single().associationId)
        assertTrue(transport.liveActivities.single().attempted)
    }

    @Test fun oldBindingsAndSecretsArePrunedWithoutDroppingStartEligibility() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        var clock = 1_000L
        val engine = Engine(store, transport, now = { clock })
        engine.start(); engine.sync()
        engine.liveActivity("enter", "order-1", token("0011"))
        engine.liveActivity("start", "provider:type", start)
        clock += RETENTION + 1
        val restored = Engine(store, transport, now = { clock })
        restored.start()
        assertTrue(store.value!!.liveActivities.bindings.isEmpty())
        assertEquals(listOf("start"), store.value!!.liveActivities.requests.map { it.kind })
        assertEquals(setOf("start:provider:type"), store.value!!.liveActivities.values.keys)
    }

    @Test fun validationRejectsMalformedPathsAndTokens() {
        listOf("", ".", "../order", "order/a", "a?b", "a".repeat(256)).forEach {
            assertFailsWith<SdkException> { validateLiveActivityText(it) }
        }
        listOf("", "0", "xx", "aa ", "a".repeat(8194)).forEach {
            assertFailsWith<SdkException> { validateLiveActivityToken(it) }
        }
        validateLiveActivityText("order:ABC_1.2-3")
        validateLiveActivityToken("00aAFF")
    }
}
