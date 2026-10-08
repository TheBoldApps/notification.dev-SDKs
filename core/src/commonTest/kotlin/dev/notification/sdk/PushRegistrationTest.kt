package dev.notification.sdk

import kotlin.test.*
import kotlinx.coroutines.test.runTest

class PushRegistrationTest {
    @Test fun failureIsDurableAndSameTokenRecoveryClearsIt() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        var engine = Engine(store, transport)
        engine.start()
        engine.updateDevice(token = "retained-token", status = PushPermissionStatus(true))
        engine.sync()

        engine.reportPushRegistrationFailure("FCM_AUTHENTICATION_FAILED")
        assertEquals("failed", engine.state.value.pushRegistration.status)
        transport.pushFailure = TransportException("NETWORK", true)
        engine.sync()
        assertTrue(engine.state.value.hasPendingChanges)
        val failedRequest = transport.pushes.last()

        engine = Engine(store, transport)
        engine.start()
        assertEquals("FCM_AUTHENTICATION_FAILED", engine.state.value.pushRegistration.errorCode)
        engine.updateDevice(status = PushPermissionStatus(true))
        engine.setPushOptedIn(false)
        engine.setPushOptedIn(true)
        transport.pushFailure = null
        engine.sync()
        assertEquals(failedRequest, transport.pushes.last())
        assertEquals("retained-token", transport.pushes.last().device.token)
        assertEquals("failed", engine.state.value.pushRegistration.status)
        assertFalse(engine.state.value.hasPendingChanges)

        engine.updateDevice(token = "retained-token")
        assertEquals("registered", engine.state.value.pushRegistration.status)
        assertNull(engine.state.value.pushRegistration.errorCode)
        engine.sync()
        assertEquals("registered", transport.pushes.last().device.registration?.status)
        assertNotEquals(failedRequest.id, transport.pushes.last().id)
    }

    @Test fun failureWithoutTokenSurvivesPendingSuccessRequest() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val engine = Engine(store, transport)
        engine.start()
        engine.reportPushRegistrationFailure("FIREBASE_NOT_CONFIGURED")
        engine.sync()
        assertNull(transport.pushes.last().device.token)
        assertEquals("failed", transport.pushes.last().device.registration?.status)

        engine.updateDevice(token = "token")
        transport.losePushResponse = true
        engine.sync()
        val successRequest = transport.pushes.last()
        engine.reportPushRegistrationFailure("FCM_TOKEN_UNAVAILABLE")
        engine.sync()
        engine.sync()
        assertTrue(transport.pushes.count { it.id == successRequest.id } >= 2)
        assertEquals("failed", transport.pushes.last().device.registration?.status)
        assertEquals("failed", engine.state.value.pushRegistration.status)
    }

    @Test fun metadataChangesRetainAmbiguousRequestAcrossRestart() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        var engine = Engine(store, transport)
        engine.start()
        engine.updateMetadata(DeviceMetadata(locale = "en", timezone = "UTC"))
        transport.losePushResponse = true
        engine.sync()
        val original = transport.pushes.last()

        engine = Engine(store, transport)
        engine.start()
        engine.updateMetadata(DeviceMetadata(locale = "cs", timezone = "Europe/Prague"))
        engine.sync()
        assertEquals(original, transport.pushes[1])
        engine.sync()
        val latest = transport.pushes.last()
        assertNotEquals(original.id, latest.id)
        assertEquals("cs", latest.device.metadata?.locale)
        assertEquals("Europe/Prague", latest.device.metadata?.timezone)
    }

}
