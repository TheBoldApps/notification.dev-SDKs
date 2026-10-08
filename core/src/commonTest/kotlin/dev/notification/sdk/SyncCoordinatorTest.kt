package dev.notification.sdk

import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

class SyncCoordinatorTest {
    @Test fun foregroundAttemptHasRecoveryScheduledBeforeNetworkAndNoWorkerBackoff() = runTest {
        val engine = Engine(MemoryStore(), FakeTransport())
        engine.start()
        val scheduled = mutableListOf<Long>()
        val coordinator = SyncCoordinator(engine, schedule = { scheduled.add(it) })
        engine.setEmail("a@example.com", true)
        coordinator.requestSync()
        assertEquals(listOf(0L), scheduled)
        assertFalse(engine.state.value.hasPendingChanges)
        coordinator.runScheduled()
        assertEquals(listOf(0L), scheduled)
        assertEquals(RetrySchedule(), engine.retrySchedule())
    }

    @Test fun retryAfterSurvivesRestartAndForegroundWakesCannotBypassIt() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport()
        var clock = 1_000L
        val engine = Engine(store, transport, now = { clock })
        engine.start()
        engine.setEmail("a@example.com", true)
        transport.userFailure = TransportException("HTTP_429", true, 120_000)
        val scheduled = mutableListOf<Long>()
        val coordinator = SyncCoordinator(engine, { scheduled.add(it) }, now = { clock }, jitter = { 0 })
        coordinator.requestSync()
        assertEquals(RetrySchedule(1, 121_000), store.value!!.retry)
        assertEquals(1, transport.requests.size)
        clock += 60_000
        coordinator.requestSync()
        assertEquals(1, transport.requests.size)

        val restarted = Engine(store, transport, now = { clock })
        restarted.start()
        val background = SyncCoordinator(restarted, { scheduled.add(it) }, now = { clock }, jitter = { 0 })
        background.runScheduled()
        assertEquals(60_000L, scheduled.last())
        assertEquals(1, transport.requests.size)
        transport.userFailure = null
        clock += 60_000
        background.runScheduled()
        assertEquals(2, transport.requests.size)
        assertEquals(transport.requests[0], transport.requests[1])
        assertEquals(RetrySchedule(), store.value!!.retry)
        assertFalse(restarted.state.value.hasPendingChanges)
    }

    @Test fun backoffIsSharedAcrossAttemptsAndResetsOnSuccess() = runTest {
        var clock = 0L
        val transport = FakeTransport()
        val engine = Engine(MemoryStore(), transport)
        engine.start()
        transport.failure = TransportException("NETWORK", true)
        val scheduled = mutableListOf<Long>()
        val coordinator = SyncCoordinator(engine, { scheduled.add(it) }, now = { clock }, jitter = { 0 })
        coordinator.runScheduled()
        assertEquals(1_000L, scheduled.last())
        clock += 1_000
        coordinator.runScheduled()
        assertEquals(2_000L, scheduled.last())
        clock += 2_000
        transport.failure = null
        coordinator.runScheduled()
        assertEquals(RetrySchedule(), engine.retrySchedule())
        engine.track("event", JsonObject(emptyMap()), "event")
        transport.eventFailure = TransportException("NETWORK", true)
        coordinator.runScheduled()
        assertEquals(1_000L, scheduled.last())
    }

    @Test fun rejectedPropertiesDoNotScheduleAutomaticRetries() = runTest {
        val transport = FakeTransport()
        val engine = Engine(MemoryStore(), transport)
        engine.start()
        engine.setEmail("a@example.com", true)
        transport.userFailure = TransportException("HTTP_403", false)
        val scheduled = mutableListOf<Long>()
        val coordinator = SyncCoordinator(engine, { scheduled.add(it) })
        coordinator.runScheduled()
        assertTrue(scheduled.isEmpty())
        assertTrue(engine.state.value.hasPendingChanges)
        assertEquals(RetrySchedule(), engine.retrySchedule())
    }

    @Test fun remainingEventsScheduleImmediateContinuation() = runTest {
        val transport = FakeTransport()
        val engine = Engine(MemoryStore(), transport)
        engine.start()
        repeat(51) { engine.track("event", JsonObject(emptyMap()), "$it") }
        val scheduled = mutableListOf<Long>()
        val coordinator = SyncCoordinator(engine, { scheduled.add(it) })
        coordinator.runScheduled()
        assertEquals(50, transport.events.size)
        assertEquals(listOf(0L), scheduled)
        coordinator.runScheduled()
        assertEquals(51, transport.events.size)
        assertEquals(listOf(0L), scheduled)
    }

    @Test fun concurrentForegroundAndWorkerAttemptsUseOneDeadline() = runTest {
        val transport = FakeTransport()
        val engine = Engine(MemoryStore(), transport)
        engine.start()
        engine.setEmail("a@example.com", true)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val scheduled = mutableListOf<Long>()
        transport.afterUser = {
            assertTrue(scheduled.contains(0))
            started.complete(Unit)
            finish.await()
            throw TransportException("NETWORK", true, 10_000)
        }
        val coordinator = SyncCoordinator(engine, { scheduled.add(it) }, now = { 0 }, jitter = { 0 })
        val foreground = launch { coordinator.requestSync() }
        started.await()
        val worker = launch { coordinator.runScheduled() }
        finish.complete(Unit)
        foreground.join()
        worker.join()
        assertEquals(1, transport.requests.size)
        assertEquals(listOf(0L, 10_000L), scheduled)
    }
    @Test
    fun schedulingFailureDoesNotPreventImmediateSync() = runTest {
        val engine = Engine(MemoryStore(), FakeTransport())
        engine.start()
        engine.setEmail("a@example.com", true)
        val diagnostics = mutableListOf<SdkError>()
        val coordinator = SyncCoordinator(engine, schedule = { error("OS declined") }, diagnostic = { diagnostics.add(it) })

        coordinator.requestSync()

        assertFalse(engine.state.value.hasPendingChanges)
        assertEquals("SCHEDULING_FAILURE", diagnostics.single().code)
    }

    @Test
    fun directExecutionNeedsNoSchedulerAndKeepsRetryDeadline() = runTest {
        val transport = FakeTransport().apply { failure = TransportException("NETWORK", true, 10_000) }
        val store = MemoryStore()
        val engine = Engine(store, transport)
        engine.start()
        val coordinator = SyncCoordinator(engine, now = { 0 }, jitter = { 0 })

        coordinator.requestSync()

        assertEquals(RetrySchedule(1, 10_000), store.value!!.retry)
        transport.failure = null
        coordinator.requestSync()
        assertNull(engine.state.value.associationId)
    }
}
