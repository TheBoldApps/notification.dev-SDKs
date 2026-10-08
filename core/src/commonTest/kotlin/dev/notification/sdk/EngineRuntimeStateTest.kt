package dev.notification.sdk

import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class EngineRuntimeStateTest {
    @Test fun syncingSurvivesLocalPublicationsAndResetsOnSuccessFailureAndCancellation() = runTest {
        for (refresh in listOf(false, true)) {
            for (outcome in listOf("success", "failure", "cancellation")) {
                val transport = FakeTransport()
                val engine = Engine(MemoryStore(), transport)
                engine.start()
                engine.sync()
                engine.requestRefresh()
                val entered = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                transport.beforeRead = {
                    entered.complete(Unit)
                    finish.await()
                }
                val operation = async {
                    runCatching { if (refresh) engine.refresh() else engine.sync() }
                }
                entered.await()

                assertTrue(engine.state.value.syncing)
                engine.setTags(mapOf("plan" to JsonPrimitive("premium")))
                assertTrue(engine.state.value.syncing)
                assertEquals(JsonPrimitive("premium"), engine.state.value.user.tags["plan"])

                if (outcome == "cancellation") {
                    operation.cancelAndJoin()
                    assertTrue(operation.isCancelled)
                } else {
                    if (outcome == "failure") {
                        transport.readFailure = TransportException("NETWORK", true)
                    }

                    finish.complete(Unit)
                    val result = operation.await()

                    if (outcome == "failure") {
                        assertEquals("NETWORK", engine.state.value.lastError!!.code)
                        assertEquals(refresh, result.isFailure)
                    } else {
                        assertTrue(result.isSuccess)
                    }
                }

                assertFalse(engine.state.value.syncing)
                assertEquals(JsonPrimitive("premium"), engine.state.value.user.tags["plan"])
            }
        }
    }

    @Test fun permissionSurvivesLocalEditsTokenUpdatesAndSynchronization() = runTest {
        val transport = FakeTransport()
        val engine = Engine(MemoryStore(), transport)
        val permission = PushPermissionStatus(
            areNotificationsEnabled = true,
        )
        engine.start()
        engine.updateDevice(status = permission)
        engine.setTags(mapOf("plan" to JsonPrimitive("premium")))
        assertEquals(permission, engine.state.value.permission)

        engine.updateDevice(token = "token")
        assertEquals(permission, engine.state.value.permission)
        engine.sync()

        assertEquals(permission, engine.state.value.permission)
        assertEquals("authorized", transport.pushes.last().device.permission)
        assertEquals("token", transport.pushes.last().device.token)
    }

    @Test fun rejectedErrorSurvivesPublicationsAndClearsWhenCorrected() = runTest {
        val diagnostics = mutableListOf<SdkError>()
        val transport = FakeTransport()
        val engine = Engine(MemoryStore(), transport, diagnostic = { diagnostics.add(it) })
        engine.start()
        engine.sync()
        engine.setEmail("a@example.com", true)
        transport.userFailure = TransportException("HTTP_403", false)
        engine.sync()
        val error = engine.state.value.lastError
        assertEquals("HTTP_403", error!!.code)
        assertTrue(diagnostics.contains(error))

        engine.updateDevice(status = PushPermissionStatus())
        engine.acknowledgeOpen("unknown")
        engine.sync()
        assertEquals(error, engine.state.value.lastError)
        assertFalse(engine.state.value.syncing)

        engine.setEmail("corrected@example.com", true)
        assertNull(engine.state.value.lastError)
        transport.userFailure = null
        engine.sync()

        assertNull(engine.state.value.lastError)
        assertFalse(engine.state.value.syncing)
    }
}
