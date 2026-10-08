package dev.notification.sdk

import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class EngineReadinessTest {
    @Test fun localInitializationReleasesWaitersWithoutNetworkAvailability() = runTest {
        val engine = Engine(MemoryStore(), FakeTransport())
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { engine.awaitReady() }
        assertFalse(waiter.isCompleted)
        assertEquals(Availability.LOADING, engine.state.value.availability)

        engine.start()
        waiter.await()
        engine.awaitReady()
        engine.start()

        assertEquals(Availability.UNAVAILABLE, engine.state.value.availability)
        assertNotNull(engine.state.value.installationId)
    }

    @Test fun cachedInitializationIsReady() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        val original = Engine(store, transport)
        original.start()
        original.sync()
        val engine = Engine(store, transport)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { engine.awaitReady() }

        engine.start()
        waiter.await()
        engine.awaitReady()

        assertEquals(Availability.AVAILABLE, engine.state.value.availability)
        assertEquals(original.state.value.installationId, engine.state.value.installationId)
    }

    @Test fun loadFailureReleasesExistingAndFutureWaiters() = runTest {
        val failure = IOException("Cannot load state")
        val store = object : StateStore {
            override suspend fun load(): StoredState? = throw failure

            override suspend fun save(state: StoredState) = Unit
        }
        val logs = mutableListOf<String>()
        val engine = Engine(store, FakeTransport(), logger = SdkLogger(true) { _, message -> logs.add(message) })
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { engine.awaitReady() }.exceptionOrNull()
        }

        assertSame(failure, runCatching { engine.start() }.exceptionOrNull())
        val waitingFailure = waiter.await()
        assertInitializationFailure(waitingFailure)
        assertSame(failure, waitingFailure?.cause)
        assertTrue(logs.any { it.contains("Cannot load state") && it.contains("IOException") })
        assertInitializationFailure(runCatching { engine.awaitReady() }.exceptionOrNull())
        assertInitializationFailure(runCatching { engine.start() }.exceptionOrNull())
        assertNull(engine.state.value.installationId)
    }

    @Test fun configurationFailureDoesNotMakePartiallyLoadedDataReady() = runTest {
        val store = MemoryStore()
        Engine(store, FakeTransport()).start()
        val engine = Engine(store, FakeTransport(), configurationId = "other")
        val failure = runCatching { engine.start() }.exceptionOrNull()

        assertEquals("CONFIGURATION_CHANGED", (failure as SdkException).error.code)
        assertTrue(engine.state.value.lastError!!.message.contains("Restore the previous configuration"))
        assertInitializationFailure(runCatching { engine.awaitReady() }.exceptionOrNull())
        assertInitializationFailure(runCatching { engine.start() }.exceptionOrNull())
        assertNull(engine.state.value.installationId)
    }

    @Test fun startupCancellationReleasesWaitersDuringLoadAndSave() = runTest {
        for (cancelDuringLoad in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>()
            val store = object : StateStore {
                override suspend fun load(): StoredState? {
                    if (cancelDuringLoad) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }

                    return null
                }

                override suspend fun save(state: StoredState) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val engine = Engine(store, FakeTransport())
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.awaitReady() }.exceptionOrNull()
            }
            val startup = launch { engine.start() }
            entered.await()
            startup.cancelAndJoin()

            assertTrue(startup.isCancelled)
            assertInitializationFailure(waiter.await())
            assertInitializationFailure(runCatching { engine.awaitReady() }.exceptionOrNull())
            assertNull(engine.state.value.installationId)
        }
    }

    @Test fun cancellingOneWaiterDoesNotCancelStartupOrOtherWaiters() = runTest {
        val engine = Engine(MemoryStore(), FakeTransport())
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { engine.awaitReady() }
        val remaining = async(start = CoroutineStart.UNDISPATCHED) { engine.awaitReady() }
        cancelled.cancelAndJoin()

        assertEquals(Availability.LOADING, engine.state.value.availability)
        assertFalse(remaining.isCompleted)
        engine.start()
        remaining.await()
        engine.awaitReady()

        assertTrue(cancelled.isCancelled)
        assertNotNull(engine.state.value.installationId)
    }

    private fun assertInitializationFailure(error: Throwable?) {
        assertTrue(error is SdkException)
        assertEquals("INITIALIZATION_FAILED", (error as SdkException).error.code)
    }
}
