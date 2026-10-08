package dev.notification.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val APP_OPENED_EVENT = "notification_dev.app_opened"

internal const val RETENTION = 7 * 24 * 60 * 60 * 1000L

internal class Engine(
    private val store: StateStore,
    private val transport: Transport,
    private val now: () -> Long = ::currentTimeMillis,
    private val wake: () -> Unit = {},
    private val configurationId: String = "test",
    private val diagnostic: (SdkError) -> Unit = {},
    private val logger: SdkLogger = SdkLogger()
) {
    private var initializationFailure: Exception? = null
    private val mutex = Mutex()
    private val network = Mutex()
    private lateinit var data: StoredState
    private var refreshRequested = true
    private var foreground = false
    private var pendingAppOpen = false
    private val mutableState = MutableStateFlow(SdkState())
    private val mutableOpens = MutableStateFlow<List<NotificationOpen>>(emptyList())
    val state = mutableState.asStateFlow()
    val opens = mutableOpens.asStateFlow()

    suspend fun awaitReady() {
        val initialized = state.first { it.availability != Availability.LOADING }

        if (initialized.installationId == null) {
            throw SdkException(checkNotNull(initialized.lastError), initializationFailure)
        }
    }

    suspend fun start() = mutex.withLock {
        if (state.value.availability != Availability.LOADING) {
            awaitReady()

            return@withLock
        }

        logger.debug("Initializing local SDK state")

        try {
            data = store.load() ?: StoredState(
                newId(),
                registrationSecret(),
                configurationId = configurationId
            )

            if (data.configurationId != configurationId) {
                sdkFailure("CONFIGURATION_CHANGED")
            }

            if (data.user.blocked) {
                report("USER_SYNC_BLOCKED")
            }

            persist()
            logger.debug("Local SDK initialization complete")
        } catch (e: Exception) {
            initializationFailure = e
            val reason = if (e is SdkException && e.error.code == "CONFIGURATION_CHANGED") {
                "CONFIGURATION_CHANGED: stored state belongs to a different project or base URL. " +
                    "Restore the previous configuration, or clear app data for a fresh development installation."
            } else {
                "Local state could not be initialized (${e::class.simpleName}); enable loggingEnabled for details."
            }
            val error = SdkError("INITIALIZATION_FAILED", "SDK initialization failed: $reason")
            mutableState.value = SdkState(
                availability = Availability.UNAVAILABLE,
                lastError = error
            )
            if (e !is CancellationException) {
                logger.error(error.message, e)
                diagnostic(error)
            }

            throw e
        }
    }

    // All three steps run under the local-state lock.
    private suspend fun persist(lastError: SdkError? = state.value.lastError) {
        pruneNotificationHistory()
        save()
        publish(lastError = lastError)
    }

    private fun pruneNotificationHistory() {
        val cutoff = now() - RETENTION
        data = data.copy(
            opens = data.opens.filter { it.openedAt >= cutoff }.takeLast(100),
            seenDeliveries = data.seenDeliveries.filterValues { it >= cutoff }.entries.toList().takeLast(1000)
                .associate { it.toPair() }
        )
    }

    // Best effort: local edits and synchronization remain available if storage fails.
    private suspend fun save() {
        try {
            store.save(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Local persistence failed; synchronization continues in memory", e)
            diagnostic(SdkError("STORAGE_FAILURE", "Local persistence failed; synchronization continues in memory"))
        }
    }

    private fun localUser(): UserProperties {
        if (data.identity.isLoggingOut) {
            return UserProperties()
        }
        val user = data.snapshot?.user

        return data.user.changes.applyTo(UserProperties(user?.email, user?.tags ?: emptyMap()))
    }

    private fun publish(lastError: SdkError? = state.value.lastError) {
        val visible = data.snapshot.takeIf { !data.identity.isLoggingOut }
        mutableState.value = state.value.copy(
            availability = if (visible != null) Availability.AVAILABLE else Availability.UNAVAILABLE,
            user = localUser(),
            installationId = data.installationId,
            associationId = visible?.associationId,
            externalId = visible?.user?.externalId,
            lastSyncedAt = data.lastSyncedAt.takeIf { visible != null },
            hasPendingChanges = !data.user.changes.isEmpty || data.events.isNotEmpty() || data.pendingDevice != null ||
                data.identity.isPending,
            pushOptedIn = data.device.optedIn,
            pushRegistration = data.device.registration?.let { local ->
                visible?.push?.registration?.takeIf { it.status == local.status && it.errorCode == local.errorCode } ?: local
            } ?: visible?.push?.registration ?: PushRegistration(),
            lastError = lastError,
            identityChangePending = data.identity.isPending
        )
        mutableOpens.value = if (!data.identity.isLoggingOut) data.opens else emptyList()
    }

    private fun report(code: String) {
        val error = SdkError(code, "SDK synchronization: $code")
        mutableState.value = state.value.copy(lastError = error)
        diagnostic(error)
    }

    private fun validate(snapshot: ServerState) {
        if (snapshot.installationId != data.installationId || snapshot.associationId.isBlank() || snapshot.user.id.isBlank()) {
            sdkFailure("INVALID_RESPONSE")
        }
    }

    private fun accept(snapshot: ServerState, transition: Boolean = false, updatePush: Boolean = true) {
        validate(snapshot)
        val previous = data.snapshot
        if (!transition && previous != null && previous.associationId != snapshot.associationId) {
            sdkFailure("ASSOCIATION_CHANGED")
        }
        if (!transition && previous != null && snapshot.revision < previous.revision) {
            sdkFailure("STALE_RESPONSE")
        }
        refreshRequested = false

        if (transition && foreground && previous != null && previous.associationId != snapshot.associationId) {
            pendingAppOpen = true
        }

        data = data.copy(snapshot = snapshot, lastSyncedAt = now(),
            device = if (data.pendingDevice != null || !updatePush) data.device else data.device.copy(optedIn = snapshot.push.optedIn))
    }

    /** Native lifecycle adapters call this; background synchronization never records an open. */
    suspend fun setForeground(active: Boolean) = mutex.withLock {
        if (foreground == active) {
            return@withLock
        }

        foreground = active

        if (active) {
            pendingAppOpen = true
            flushAppOpen()
            persist()
            wake()
        }
    }

    private fun flushAppOpen() {
        if (!foreground || !pendingAppOpen || data.identity.isPending) {
            return
        }

        val event = QueuedEvent(
            newId(), data.snapshot?.associationId,
            Event.Track(APP_OPENED_EVENT, JsonObject(emptyMap()), now())
        )

        if (addEvent(event)) {
            pendingAppOpen = false
        } else {
            report("QUEUE_FULL")
        }
    }

    // All HTTP paths hold network, but acquire mutex only to capture/apply state.
    private suspend fun register() {
        val before = mutex.withLock { data.takeIf { it.credential == null } } ?: return
        val response = transport.register(before.installationId, before.registrationSecret)
        mutex.withLock {
            validate(response.state)
            if (response.installationCredential.isBlank()) {
                sdkFailure("INVALID_RESPONSE")
            }
            data = data.copy(credential = response.installationCredential,
                identity = if (before.snapshot == null && data.identity.isLoggingOut) IdentityState() else data.identity,
                events = data.events.map { if (it.associationId == null) it.copy(associationId = response.state.associationId) else it })
            accept(response.state, transition = true)
            flushAppOpen()
            persist()
        }
    }

    private suspend fun detach() {
        val before = mutex.withLock { data.takeIf { it.identity.isLoggingOut } } ?: return
        val response = try {
            transport.identity(before.installationId, before.credential!!,
                before.identity.transition!!.id, before.snapshot!!.associationId, null)
        } catch (error: TransportException) {
            if (error.httpStatus != 401 && error.code != "HTTP_401") {
                throw error
            }

            // A deleted subscriber or reset backend leaves an unusable installation credential.
            // Logout may bootstrap a new anonymous installation, but must never restore old user work.
            mutex.withLock {
                data = StoredState(
                    installationId = newId(),
                    registrationSecret = registrationSecret(),
                    configurationId = data.configurationId,
                    identity = data.identity,
                    device = data.device
                )
                refreshRequested = true
                persist(lastError = null)
            }
            register()

            return
        }
        mutex.withLock {
            accept(response, transition = true)
            data = data.copy(identity = IdentityState(), pendingDevice = PendingDevice())
            flushAppOpen()
            persist()
        }
    }

    private suspend fun read() {
        val before = mutex.withLock { data }
        val response = transport.read(before.installationId, before.credential!!)
        mutex.withLock {
            accept(response)
            logger.debug("Backend push state: tokenRegistered=${response.push.tokenRegistered}, " +
                "status=${response.push.registration.status}, permission=${response.push.permission}, " +
                "optedIn=${response.push.optedIn}")
            persist()
        }
    }

    private suspend fun failure(error: Exception) = mutex.withLock {
        if (error is CancellationException) {
            throw error
        }
        logger.error("SDK synchronization failed", error)
        report(when (error) {
            is TransportException -> error.code
            is SdkException -> error.error.code
            else -> "LOCAL_FAILURE"
        })
        persist()
    }

    // Scheduling metadata shares the encrypted file, but only the coordinator interprets it.
    suspend fun retrySchedule(): RetrySchedule = mutex.withLock { data.retry }

    suspend fun saveRetrySchedule(retry: RetrySchedule) = mutex.withLock {
        data = data.copy(retry = retry)
        save()
    }

    suspend fun requestRefresh() = mutex.withLock {
        refreshRequested = true
        wake()
    }

    // Call while holding the network lock. Explicit refresh/login bypass automatic retry deadlines.
    private suspend fun beginSync(requireSettledIdentity: Boolean = false) = mutex.withLock {
        if (requireSettledIdentity) {
            data.identity.requireSettled()
        }

        mutableState.value = state.value.copy(
            syncing = true,
            lastError = state.value.lastError.takeIf { data.user.blocked || data.pendingDevice?.rejected == true }
        )
    }

    private suspend fun endSync() = withContext(NonCancellable) {
        mutex.withLock {
            mutableState.value = state.value.copy(syncing = false)
        }
    }

    suspend fun refresh(): SdkState = network.withLock {
        beginSync(requireSettledIdentity = true)

        try {
            register()
            read()
        } catch (e: Exception) {
            failure(e)
            throw e
        } finally {
            endSync()
        }

        state.value
    }

    suspend fun login(externalId: String) = network.withLock {
        require(externalId.isNotBlank() && externalId.length <= 255)
        try {
            register()
            detach()
            val before = mutex.withLock {
                data = data.copy(
                    identity = data.identity.beginLogin(
                        externalId
                    )
                )
                persist()

                data
            }
            val response = transport.identity(before.installationId, before.credential!!,
                before.identity.transition!!.id, before.snapshot!!.associationId, externalId)
            mutex.withLock {
                validate(response)
                if (response.user.externalId != externalId) {
                    sdkFailure("INVALID_RESPONSE")
                }
                if (response.associationId != data.snapshot!!.associationId) {
                    discardUserWork()
                    data = data.copy(pendingDevice = PendingDevice())
                }
                accept(response, transition = true)
                data = data.copy(identity = IdentityState())
                flushAppOpen()
                persist(lastError = null)
            }
            wake()
        } catch (e: Exception) {
            if (e is TransportException && !e.retryable) {
                mutex.withLock {
                    data = data.copy(identity = data.identity.rejectLogin())
                }
            }
            failure(e)
            throw e
        }
    }

    private fun discardUserWork() {
        if (!data.user.changes.isEmpty || data.events.isNotEmpty()) {
            diagnostic(SdkError("STALE_ASSOCIATION", "Discarded pending work for the previous user"))
        }
        data = data.copy(
            user = data.user.discard(),
            events = emptyList(),
            opens = emptyList()
        )
    }

    suspend fun logout() = mutex.withLock {
        val identity = data.identity.beginLogout()
        discardUserWork()

        if (foreground) {
            pendingAppOpen = true
        }

        data = data.copy(identity = identity)
        persist(lastError = null)
        wake()
    }

    private suspend fun change(edit: UserChanges.(Long) -> UserChanges) = mutex.withLock {
        data.identity.requireSettled()
        data = data.copy(user = data.user.edit(edit))
        persist(lastError = null)
        wake()
    }

    suspend fun setEmail(address: String, optedIn: Boolean) = change { version ->
        copy(email = EmailChange(version, address = address, optedIn = optedIn))
    }

    suspend fun removeEmail() = change { version ->
        copy(email = EmailChange(version, remove = true))
    }

    suspend fun setEmailOptedIn(enabled: Boolean) = change { version ->
        if (localUser().email == null) {
            sdkFailure("EMAIL_NOT_ADDED")
        }

        copy(email = (email ?: EmailChange(version)).copy(version = version, optedIn = enabled))
    }

    suspend fun setTags(tags: Map<String, JsonPrimitive?>) = change { version ->
        copy(tags = this.tags + tags.mapValues { TagChange(version, it.value) })
    }

    suspend fun track(name: String, properties: JsonObject, id: String): String = mutex.withLock {
        data.identity.requireSettled()
        if (!addEvent(QueuedEvent(id, data.snapshot?.associationId, Event.Track(name, properties, now())))) {
            sdkFailure("QUEUE_FULL")
        }
        persist()
        wake()

        id
    }

    private fun addEvent(event: QueuedEvent): Boolean {
        if (data.events.any { it.id == event.id }) {
            return true
        }
        val events = data.events + event
        if (events.size > 1000 || wireJson.encodeToString(events).encodeToByteArray().size > 5 * 1024 * 1024) {
            return false
        }
        data = data.copy(events = events)

        return true
    }

    suspend fun setPushOptedIn(enabled: Boolean) = mutex.withLock {
        updateDevice(data.device.copy(optedIn = enabled), retryRejected = true)
    }

    suspend fun updateDevice(token: String? = null, status: PushPermissionStatus? = null) = mutex.withLock {
        if (status != null) {
            mutableState.value = state.value.copy(permission = status)
        }
        updateDevice(data.device.copy(
            token = token ?: data.device.token,
            registration = if (token != null) PushRegistration("registered") else data.device.registration,
            permission = if (state.value.permission.areNotificationsEnabled) "authorized" else "denied"
        ), retryRejected = token != null)
    }

    suspend fun updateMetadata(metadata: DeviceMetadata) = mutex.withLock {
        updateDevice(data.device.copy(metadata = metadata))
    }

    suspend fun reportPushRegistrationFailure(code: String) = mutex.withLock {
        updateDevice(data.device.copy(registration = PushRegistration("failed", code)), retryRejected = true)
    }

    private suspend fun updateDevice(device: DeviceProperties, retryRejected: Boolean = false) {
        if (device == data.device && (!retryRejected || data.pendingDevice?.rejected != true)) {
            publish()

            return
        }
        data = data.copy(device = device, pendingDevice = PendingDevice(data.pendingDevice?.request))
        persist()
        wake()
    }

    private suspend fun syncPush() {
        val before = mutex.withLock {
            val pending = data.pendingDevice ?: return
            if (pending.rejected || data.identity.isLoggingOut) {
                return
            }
            val request = pending.request ?: PushRequest(newId(),
                data.snapshot!!.associationId, data.device)
            data = data.copy(pendingDevice = PendingDevice(request))
            persist()

            data
        }
        val request = before.pendingDevice!!.request!!
        try {
            logger.debug("Syncing push registration: tokenPresent=${request.device.token != null}, " +
                "status=${request.device.registration?.status ?: "unknown"}, permission=${request.device.permission}, " +
                "optedIn=${request.device.optedIn}")
            transport.push(before.installationId, before.credential!!, request)
            logger.debug("Backend acknowledged push registration")
            mutex.withLock {
                refreshRequested = true
                data = data.copy(pendingDevice = if (data.device == request.device) null else PendingDevice())
                persist()
            }
        } catch (e: TransportException) {
            if (e.retryable) {
                throw e
            }
            mutex.withLock {
                data = data.copy(pendingDevice = PendingDevice(rejected = data.device == request.device))
                report(e.code)
                persist()
            }
        }
    }

    private suspend fun syncUser(): Boolean {
        val before = mutex.withLock {
            if (!data.user.needsSync || data.identity.isLoggingOut) {
                return false
            }
            data = data.copy(user = data.user.capture(data.snapshot!!.associationId))
            persist()

            data
        }
        val request = before.user.request!!
        try {
            val response = transport.updateUser(before.installationId, before.credential!!, request)
            mutex.withLock {
                if (!data.identity.isLoggingOut) {
                    validate(response)
                    if (response.associationId != data.snapshot!!.associationId) {
                        sdkFailure("ASSOCIATION_CHANGED")
                    }
                    // A replay may return an older successful response after a newer refresh.
                    // Acknowledge it without rolling back the newer cache or device preference.
                    if (response.revision >= data.snapshot!!.revision) {
                        accept(response, updatePush = false)
                    }
                    data = data.copy(user = data.user.acknowledge(request))
                    persist(lastError = state.value.lastError.takeIf { data.pendingDevice?.rejected == true })
                }
            }
        } catch (e: TransportException) {
            if (e.retryable) {
                throw e
            }
            mutex.withLock {
                if (!data.identity.isLoggingOut) {
                    data = data.copy(user = data.user.reject(before.user))
                    report(e.code)
                    persist()
                }
            }
        }

        return true
    }

    private suspend fun syncEvents() {
        repeat(50) {
            val before = mutex.withLock {
                if (data.identity.isLoggingOut) {
                    return
                }
                val valid = data.events.filter {
                    it.event.occurredAt >= now() - RETENTION && it.associationId == data.snapshot!!.associationId
                }
                if (valid.size != data.events.size) {
                    report("EVENT_DISCARDED")
                    data = data.copy(events = valid)
                    persist()
                }
                if (data.events.isEmpty()) {
                    return
                }

                data
            }
            val event = before.events.first()
            try {
                transport.sendEvent(before.installationId, before.credential!!, event)
            } catch (e: TransportException) {
                if (e.retryable) {
                    throw e
                }
                mutex.withLock { report(e.code) }
            }
            mutex.withLock {
                data = data.copy(events = data.events.filterNot { it.id == event.id })
                persist()
            }
        }
    }

    /** Performs one pass. Scheduling and retry delays belong to SyncCoordinator. */
    suspend fun sync(): SyncResult = network.withLock {
        mutex.withLock {
            if (pendingAppOpen && foreground && !data.identity.isPending) {
                flushAppOpen()
                persist()
            }
        }

        beginSync()

        try {
            register()
            detach()
            if (mutex.withLock { data.identity.isLoggingIn }) {
                return@withLock SyncResult.Done
            }
            syncPush()
            val updated = syncUser()
            syncEvents()
            if (!updated && mutex.withLock { refreshRequested && !data.identity.isLoggingOut }) {
                read()
            }
            mutex.withLock {
                val pending = data.identity.isLoggingOut || data.pendingDevice?.rejected == false ||
                    data.user.needsSync || data.events.isNotEmpty()

                if (pending) SyncResult.Pending else SyncResult.Done
            }
        } catch (e: Exception) {
            failure(e)

            if (e is TransportException && e.retryable) SyncResult.Retry(e.retryAfterMs) else SyncResult.Done
        } finally {
            endSync()
        }
    }

    suspend fun acknowledgeOpen(id: String) = mutex.withLock {
        data = data.copy(opens = data.opens.filterNot { it.interactionId == id })
        persist()
    }

    suspend fun receive(payload: NotificationPayload): Boolean = mutex.withLock {
        if (!eligible(payload) || data.seenDeliveries.containsKey(payload.deliveryId)) {
            return@withLock false
        }
        data = data.copy(seenDeliveries = data.seenDeliveries + (payload.deliveryId to now()))
        interaction(payload, "received")
        persist()
        wake()

        true
    }

    suspend fun open(payload: NotificationPayload): Boolean = mutex.withLock {
        if (!eligible(payload)) {
            return@withLock false
        }
        val id = "open:${payload.deliveryId}"
        if (data.opens.none { it.interactionId == id }) {
            data = data.copy(opens = (data.opens + NotificationOpen(id, payload, now())).takeLast(100))
            interaction(payload, "opened")
            persist()
            wake()
        }

        true
    }

    suspend fun displayIfEligible(payload: NotificationPayload, display: () -> Unit) = mutex.withLock {
        if (eligible(payload)) {
            display()
        }
    }

    private fun eligible(payload: NotificationPayload): Boolean =
        payload.version == 1 && !data.identity.isPending && data.device.optedIn &&
            payload.expiresAt > now() && payload.associationId == data.snapshot?.associationId

    private fun interaction(payload: NotificationPayload, kind: String) {
        if (!addEvent(QueuedEvent("$kind:${payload.deliveryId}", payload.associationId,
                Event.Interaction(payload.deliveryId, kind, now())))) {
            report("QUEUE_FULL")
        }
    }
}
