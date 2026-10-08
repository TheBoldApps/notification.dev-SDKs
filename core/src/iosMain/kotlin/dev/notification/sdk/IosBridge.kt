package dev.notification.sdk

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Explicit callback bridge: no Kotlin exception or coroutine type crosses the Swift facade. */
class IosCancellation internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

class IosBridge @Throws(Exception::class) constructor(
    projectId: String,
    baseUrl: String,
    allowLocalhostHttp: Boolean,
    loggingEnabled: Boolean,
    appVersion: String,
    osVersion: String,
    locale: String,
    timezone: String,
    load: () -> String,
    save: (String) -> String?,
    wake: () -> Unit,
    schedule: (Long) -> Unit,
    diagnostic: (String) -> Unit,
    clearNotifications: () -> Unit,
    log: (Int, String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = CoreClient(
        CoreConfig(projectId, baseUrl, allowLocalhostHttp, loggingEnabled),
        object : SecureStorage {
            override suspend fun load(): String? {
                val result = wireJson.parseToJsonElement(load()).jsonObject
                result["error"]?.jsonPrimitive?.contentOrNull?.let { error(it) }

                return result["value"]?.jsonPrimitive?.contentOrNull
            }

            override suspend fun save(state: String) {
                save(state)?.let { error(it) }
            }
        },
        DevicePlatform.IOS, PushProvider.APNS,
        DeviceMetadata(appVersion, osVersion, locale, timezone),
        wake = wake, schedule = { schedule(it) },
        diagnostic = { diagnostic(wireJson.encodeToString(it)) },
        clearNotifications = clearNotifications, log = log
    )

    fun state(): String = wireJson.encodeToString(client.state.value)

    fun observe(kind: String, emit: (String) -> Unit): IosCancellation = IosCancellation(scope.launch {
        when (kind) {
            "state" -> client.state.collect { emit(wireJson.encodeToString(it)) }
            "opens" -> client.opens.collect { emit(wireJson.encodeToString(it)) }
            "notifications" -> client.notifications.collect { emit(wireJson.encodeToString(it)) }
        }
    })

    fun call(method: String, arguments: String, completion: (String?, String?, String?) -> Unit): IosCancellation =
        IosCancellation(scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val args = wireJson.parseToJsonElement(arguments).jsonObject
                fun string(key: String) = args.getValue(key).jsonPrimitive.content
                fun bool(key: String) = args.getValue(key).jsonPrimitive.boolean
                var result = "null"

                if (method != "start") {
                    client.awaitReady()
                }

                when (method) {
                    "start" -> client.start()
                    "sync" -> client.runScheduled()
                    "foreground" -> client.setForeground(bool("active"))
                    "requestRefresh" -> client.requestRefresh()
                    "metadata" -> client.updateMetadata(DeviceMetadata(
                        string("appVersion"), string("osVersion"), string("locale"), string("timezone")
                    ))
                    "refresh" -> result = wireJson.encodeToString(client.refresh())
                    "login" -> client.login(string("externalId"))
                    "logout" -> client.logout()
                    "pushOptIn" -> client.setPushOptedIn(bool("enabled"))
                    "email" -> client.setEmail(string("address"), bool("optedIn"))
                    "removeEmail" -> client.removeEmail()
                    "emailOptIn" -> client.setEmailOptedIn(bool("enabled"))
                    "tags" -> client.setTags(args.getValue("values").jsonObject.mapValues { (_, v) ->
                        if (v is JsonNull) null else v.jsonPrimitive
                    })
                    "track" -> result = wireJson.encodeToString(client.track(
                        string("name"), args["properties"]?.jsonObject ?: JsonObject(emptyMap()),
                        args["eventId"]?.jsonPrimitive?.contentOrNull
                    ))
                    "acknowledge" -> client.acknowledgeOpen(string("id"))
                    "device" -> client.updateDevice(
                        args["token"]?.jsonPrimitive?.contentOrNull,
                        args["enabled"]?.jsonPrimitive?.boolean?.let(::PushPermissionStatus)
                    )
                    "registrationFailed" -> client.reportPushRegistrationFailure("APNS_REGISTRATION_FAILED")
                    "receive", "open" -> {
                        val payload = client.decodeNotification(args.getValue("payload").toString())
                        var accepted = false

                        if (payload != null) {
                            if (method == "open") {
                                accepted = client.open(payload)
                            } else if (client.receive(payload)) {
                                client.displayIfEligible(payload) { accepted = true }
                            }
                        }

                        result = accepted.toString()
                    }
                    else -> error("Unknown bridge method")
                }

                completion(result, null, null)
            } catch (error: CancellationException) {
                completion(null, "CANCELLED", "Operation cancelled")
            } catch (error: Exception) {
                val sdkError = (error as? SdkException)?.error
                completion(null, sdkError?.code ?: "SDK_ERROR", sdkError?.message ?: "SDK operation failed")
            }
        })

    fun close() {
        scope.cancel()
        client.close()
    }
}
