package dev.notification.expo

import android.app.Activity
import android.content.Intent
import dev.notification.sdk.*
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class NotificationDevModule : Module() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observers: Job? = null
    private var observing = false
    private var initialized = false
    private val json = Json { encodeDefaults = true }

    override fun definition() = ModuleDefinition {
        Name("NotificationDev")
        Events("onEvent")

        AsyncFunction("invoke") { method: String, argumentsJson: String, promise: Promise ->
            scope.launch {
                try {
                    promise.resolve(invoke(method, json.parseToJsonElement(argumentsJson).jsonObject))
                } catch (error: CancellationException) {
                    promise.reject("ENGINE_DETACHED", "JavaScript runtime detached", null)
                } catch (error: Exception) {
                    val failure = bridgeError(error)
                    promise.reject(failure.code, failure.message, null)
                }
            }
        }
        OnStartObserving { observing = true; startObservers() }
        OnStopObserving { observing = false; observers?.cancel(); observers = null }
        OnNewIntent { intent -> handleIntent(intent) }
        OnActivityEntersForeground { handleIntent(appContext.currentActivity?.intent) }
        OnDestroy { scope.cancel() }
    }

    private suspend fun invoke(method: String, args: JsonObject): String? {
        if (method == "initialize") {
            val context = requireNotNull(appContext.reactContext)
            NativeStartup.start(context)
            NativeStartup.failure?.let { throw it }
            NotificationDev.awaitInitialization()
            initialized = true
            startObservers()
            handleIntent(appContext.currentActivity?.intent)
            return null
        }
        check(initialized) { "Initialize the Expo SDK first" }
        NotificationDev.awaitInitialization()
        fun string(key: String) = args.getValue(key).jsonPrimitive.also { require(it.isString) }.content
        fun boolean(key: String) = args.getValue(key).jsonPrimitive.boolean
        when (method) {
            "getState" -> return json.encodeToString(NotificationDev.getState())
            "getTags" -> return json.encodeToString(NotificationDev.getTags())
            "getEmail" -> return json.encodeToString(NotificationDev.getEmail())
            "login" -> NotificationDev.login(string("externalId"))
            "logout" -> NotificationDev.logout()
            "setEmail" -> NotificationDev.setEmail(string("address"), boolean("optedIn"))
            "removeEmail" -> NotificationDev.removeEmail()
            "setEmailOptedIn" -> NotificationDev.setEmailOptedIn(boolean("enabled"))
            "setPushOptedIn" -> NotificationDev.setPushOptedIn(boolean("enabled"))
            "setTags" -> NotificationDev.setTags(args.getValue("values").jsonObject.mapValues { (_, element) ->
                val value = element.jsonPrimitive
                require(value !is JsonNull)
                when {
                    value.isString -> value.content
                    value.booleanOrNull != null -> value.boolean
                    value.longOrNull != null -> value.long
                    else -> value.double.also { require(it.isFinite()) }
                }
            })
            "removeTags" -> NotificationDev.removeTags(args.getValue("keys").jsonArray.map {
                it.jsonPrimitive.also { value -> require(value.isString) }.content
            }.toSet())
            "track" -> return json.encodeToString(NotificationDev.track(string("name"), args.getValue("properties").jsonObject,
                args["eventId"]?.jsonPrimitive?.content))
            "refresh" -> return json.encodeToString(NotificationDev.refresh())
            "acknowledgeOpen" -> NotificationDev.acknowledgeOpen(string("interactionId"))
            "getPushPermissionStatus" -> {
                val status = NotificationDev.getPushPermissionStatus()
                return buildJsonObject {
                    put("areNotificationsEnabled", status.areNotificationsEnabled)
                    put("runtimePermission", status.runtimePermission.name)
                    put("channelStatus", status.channelStatus.name)
                }.toString()
            }
            "requestPushPermission" -> {
                val result = NotificationDev.requestPushPermission(activity(), boolean("settingsFallback"))
                val (outcome, code) = when (result) {
                    PushPermissionResult.Granted -> "granted" to null
                    PushPermissionResult.Denied -> "denied" to null
                    PushPermissionResult.SettingsRequired -> "settingsRequired" to null
                    PushPermissionResult.SettingsOpened -> "settingsOpened" to null
                    is PushPermissionResult.Failed -> "failed" to result.code
                }
                return buildJsonObject {
                    put("outcome", outcome)
                    if (code != null) put("errorCode", code)
                }.toString()
            }
            "openPushSettings" -> return (NotificationDev.openPushSettings(activity()) == SettingsOpenResult.OPENED).toString()
            else -> throw IllegalArgumentException("Unknown SDK method")
        }
        return null
    }

    private fun activity(): Activity = appContext.currentActivity
        ?: throw SdkException(SdkError("ACTIVITY_UNAVAILABLE", "An attached activity is required"))

    private fun handleIntent(intent: Intent?) {
        if (intent == null || !NotificationDev.isInitialized) return
        scope.launch {
            try { NotificationDev.handleIntent(intent) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { emit("diagnostics", json.encodeToString(bridgeError(error))) }
        }
    }

    private fun startObservers() {
        if (!initialized || !observing || observers != null) return
        observers = scope.launch {
            launch { NotificationDev.state.collect { emit("states", json.encodeToString(it)) } }
            launch { NotificationDev.pendingOpens.collect { emit("pendingOpens", json.encodeToString(it)) } }
            launch { NotificationDev.notifications.collect { emit("notifications", json.encodeToString(it)) } }
            launch { NotificationDev.diagnostics.collect { emit("diagnostics", json.encodeToString(it)) } }
        }
    }

    private fun emit(name: String, value: String) = sendEvent("onEvent", mapOf("name" to name, "json" to value))
}

private fun bridgeError(error: Exception): SdkError = when (error) {
    is SdkException -> error.error
    is IllegalArgumentException, is NoSuchElementException, is kotlinx.serialization.SerializationException, is org.json.JSONException ->
        SdkError("INVALID_ARGUMENT", "Invalid SDK arguments or plugin configuration")
    is IllegalStateException -> SdkError("INVALID_STATE", "Initialize the SDK with matching configuration first")
    else -> SdkError("NATIVE_FAILURE", "Native SDK operation failed")
}
