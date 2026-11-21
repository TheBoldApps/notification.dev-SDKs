package dev.notification.sdk

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class NotificationOpenReliabilityTest {
    @Test fun aQueueAtTheByteLimitStillPreservesAConfirmedTap() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport, now = { 100L })
        engine.start()
        engine.sync()
        val association = engine.state.value.associationId!!
        fun custom(blob: String) = QueuedEvent("custom", association,
            Event.Track("custom", JsonObject(mapOf("blob" to JsonPrimitive(blob))), 100L))
        val overhead = wireJson.encodeToString(listOf(custom(""))).encodeToByteArray().size
        val full = listOf(custom("x".repeat(5 * 1024 * 1024 - overhead - 1)))
        store.value = store.value!!.copy(events = full)
        val restarted = Engine(store, transport, now = { 100L })
        restarted.start()

        assertTrue(restarted.open(NotificationPayload(deliveryId = "controlled",
            associationId = association, expiresAt = 10_000, title = "Title", body = "Body")))

        assertEquals("opened:controlled", store.value!!.events.single().id)
        assertEquals("EVENT_DISCARDED", restarted.state.value.lastError?.code)
    }

    @Test fun aFullCustomEventQueueMustNotLoseAConfirmedNotificationTap() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport, now = { 100L })
        engine.start()
        engine.sync()
        val association = engine.state.value.associationId!!
        store.value = store.value!!.copy(events = (1..1000).map {
            QueuedEvent("custom-$it", association, Event.Track("custom", JsonObject(emptyMap()), 100L))
        })
        val diagnostics = mutableListOf<SdkError>()
        val restarted = Engine(store, transport, now = { 100L }, diagnostic = diagnostics::add)
        restarted.start()
        val payload = NotificationPayload(deliveryId = "controlled", associationId = association,
            expiresAt = 10_000, title = "Title", body = "Body")

        assertTrue(restarted.open(payload))
        assertTrue(store.value!!.events.any { it.id == "opened:controlled" },
            "A tap accepted for navigation must also be queued for analytics")
        assertEquals(1000, store.value!!.events.size)
        assertTrue(diagnostics.any { it.code == "EVENT_DISCARDED" })
        restarted.acknowledgeOpen("open:controlled")
        restarted.sync()
        assertTrue(transport.events.any { it.id == "opened:controlled" })
    }

    @Test fun aFullInteractionQueueReportsFailureWithoutBlockingDisplayOrNavigation() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport, now = { 100L })
        engine.start()
        engine.sync()
        val association = engine.state.value.associationId!!
        val interactions = (1..1000).map {
            QueuedEvent("opened-$it", association, Event.Interaction("delivery-$it", "opened", 100L))
        }
        store.value = store.value!!.copy(events = interactions)
        val diagnostics = mutableListOf<SdkError>()
        val restarted = Engine(store, transport, now = { 100L }, diagnostic = diagnostics::add)
        restarted.start()
        val payload = NotificationPayload(deliveryId = "controlled", associationId = association,
            expiresAt = 10_000, title = "Title", body = "Body")

        assertTrue(restarted.open(payload))
        assertTrue(restarted.receive(payload))
        assertEquals(interactions, store.value!!.events)
        assertEquals("open:controlled", restarted.opens.value.single().interactionId)
        assertTrue(store.value!!.seenDeliveries.containsKey("controlled"))
        assertEquals(listOf("QUEUE_FULL", "QUEUE_FULL"), diagnostics.map { it.code })
    }

    @Test fun retryableOpenRejectionSurvivesRestartAndNavigationAcknowledgment() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport, now = { 100L })
        engine.start()
        engine.sync()
        val payload = NotificationPayload(deliveryId = "controlled",
            associationId = engine.state.value.associationId!!,
            expiresAt = 10_000, title = "Title", body = "Body")
        assertTrue(engine.open(payload))
        engine.acknowledgeOpen("open:controlled")
        transport.eventFailure = TransportException("TEMPORARY_FAILURE", true)
        engine.sync()
        assertEquals("opened:controlled", store.value!!.events.single().id)

        val restarted = Engine(store, transport, now = { 100L })
        restarted.start()
        transport.eventFailure = null
        restarted.sync()

        assertEquals("opened:controlled", transport.events.single().id)
        assertTrue(store.value!!.events.isEmpty())
        assertTrue(restarted.opens.value.isEmpty())
    }

    @Test fun permanentOpenRejectionIsReportedAndRemovedFromUploadQueue() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val diagnostics = mutableListOf<SdkError>()
        val engine = Engine(store, transport, now = { 100L }, diagnostic = diagnostics::add)
        engine.start()
        engine.sync()
        val payload = NotificationPayload(deliveryId = "controlled",
            associationId = engine.state.value.associationId!!,
            expiresAt = 10_000, title = "Title", body = "Body")
        assertTrue(engine.open(payload))
        transport.eventFailure = TransportException("DELIVERY_INSTALLATION_MISMATCH", false)

        engine.sync()

        assertEquals("DELIVERY_INSTALLATION_MISMATCH", diagnostics.last().code)
        assertTrue(store.value!!.events.isEmpty())
        assertEquals("open:controlled", engine.opens.value.single().interactionId)
    }

    @Test fun declinedOpensReportTheExactLocalEligibilityReason() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val diagnostics = mutableListOf<SdkError>()
        val engine = Engine(store, transport, now = { 100L }, diagnostic = diagnostics::add)
        engine.start()
        engine.sync()
        val payload = NotificationPayload(deliveryId = "controlled",
            associationId = engine.state.value.associationId!!,
            expiresAt = 10_000, title = "Title", body = "Body")

        for ((invalid, code) in listOf(
            payload.copy(version = 2) to "NOTIFICATION_OPEN_UNSUPPORTED_VERSION",
            payload.copy(expiresAt = 100) to "NOTIFICATION_OPEN_EXPIRED",
            payload.copy(associationId = "another-user") to "NOTIFICATION_OPEN_ASSOCIATION_MISMATCH"
        )) {
            assertFalse(engine.open(invalid))
            assertEquals(code, diagnostics.lastOrNull()?.code)
            assertEquals(code, engine.state.value.lastError?.code)
        }
        engine.setPushOptedIn(false)
        assertFalse(engine.open(payload))
        assertEquals("NOTIFICATION_OPEN_OPTED_OUT", diagnostics.last().code)
        assertTrue(engine.opens.value.isEmpty())
        assertTrue(store.value!!.events.none { it.event is Event.Interaction })
    }
}
