package dev.notification.sdk

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

class NotificationQueueRecoveryTest {
    @Test fun notificationTapSurvivesSaturationEvenWhenOtherAnalyticsLaterSucceed() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val diagnostics = mutableListOf<SdkError>()
        val engine = Engine(store, transport, now = { 100L }, diagnostic = diagnostics::add)
        engine.start()
        engine.sync()
        repeat(999) { engine.track("queued", JsonObject(emptyMap()), "queued-$it") }
        engine.track(APP_OPENED_EVENT, JsonObject(emptyMap()), "app-open")
        val payload = NotificationPayload(deliveryId = "controlled",
            associationId = engine.state.value.associationId!!,
            expiresAt = 10_000, title = "Title", body = "Body")

        assertTrue(engine.open(payload))
        assertEquals("open:controlled", engine.opens.value.single().interactionId)
        engine.acknowledgeOpen("open:controlled")
        repeat(21) { engine.sync() }
        engine.track("paywall_viewed", JsonObject(emptyMap()), "paywall")
        engine.sync()

        val appOpenAccepted = transport.events.any { it.id == "app-open" }
        val paywallAccepted = transport.events.any { it.id == "paywall" }
        val tapAccepted = transport.events.any { it.id == "opened:controlled" }
        assertTrue(appOpenAccepted)
        assertTrue(paywallAccepted)
        assertTrue(tapAccepted, "Same delivery must still submit opened after capacity recovers")
    }
}
