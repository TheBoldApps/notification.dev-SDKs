package dev.notification.notification_dev

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import dev.notification.sdk.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class NotificationDevPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware,
    PluginRegistry.NewIntentListener {
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activityBinding: ActivityPluginBinding? = null
    private var automatic = true
    private var initialized = false
    private var pendingIntent: Intent? = null
    private val streams = mutableListOf<NativeStream>()
    private val json = Json { encodeDefaults = true }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        automatic = NativeStartup.automaticMode
        initialized = false
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        channel = MethodChannel(binding.binaryMessenger, "dev.notification/sdk")
        channel.setMethodCallHandler(this)

        listOf("states", "pendingOpens", "notifications", "diagnostics").forEach { name ->
            val stream = NativeStream(name)
            streams.add(stream)
            EventChannel(binding.binaryMessenger, "dev.notification/sdk/$name").setStreamHandler(stream)
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        scope.launch {
            try {
                if (call.method == "initialize") {
                    val values = codecObject(call.arguments).jsonObject
                    NativeStartup.checkConfiguration(values)
                    val config = NativeStartup.config(context, values)
                    automatic = NativeStartup.automatic(values)
                    withContext(Dispatchers.IO) {
                        NotificationDev.initialize(context.applicationContext as Application, config)
                        NotificationDev.awaitInitialization()
                        NativeStartup.save(context, values)
                    }
                    initialized = true
                    handleInitialIntent()
                    result.success(null)
                    return@launch
                }
                check(initialized) { "Initialize the Flutter SDK first" }
                NotificationDev.awaitInitialization()
                val value: Any? = when (call.method) {
                    "getState" -> json.encodeToString(NotificationDev.getState())
                    "getTags" -> json.encodeToString(NotificationDev.getTags())
                    "getEmail" -> json.encodeToString(NotificationDev.getEmail())
                    "login" -> NotificationDev.login(call.string("externalId"))
                    "logout" -> NotificationDev.logout()
                    "setEmail" -> NotificationDev.setEmail(call.string("address"), call.boolean("optedIn"))
                    "removeEmail" -> NotificationDev.removeEmail()
                    "setEmailOptedIn" -> NotificationDev.setEmailOptedIn(call.boolean("enabled"))
                    "setPushOptedIn" -> NotificationDev.setPushOptedIn(call.boolean("enabled"))
                    "setTags" -> {
                        val values = json.parseToJsonElement(call.string("values")).jsonObject.mapValues { (_, value) ->
                            val scalar = value.jsonPrimitive
                            require(scalar !is JsonNull) { "Invalid tag value" }
                            when {
                                scalar.isString -> scalar.content
                                scalar.booleanOrNull != null -> scalar.boolean
                                scalar.longOrNull != null -> scalar.long
                                else -> scalar.double.also { require(it.isFinite()) }
                            }
                        }
                        NotificationDev.setTags(values)
                    }
                    "removeTags" -> NotificationDev.removeTags(requireNotNull(call.argument<List<String>>("keys")).toSet())
                    "track" -> NotificationDev.track(call.string("name"),
                        json.parseToJsonElement(call.string("properties")).jsonObject, call.argument<String>("eventId"))
                    "refresh" -> json.encodeToString(NotificationDev.refresh())
                    "acknowledgeOpen" -> NotificationDev.acknowledgeOpen(call.string("interactionId"))
                    "getPushPermissionStatus" -> permissionStatus()
                    "requestPushPermission" -> {
                        val request = NotificationDev.requestPushPermission(requireActivity(), call.boolean("settingsFallback"))
                        val (outcome, error) = when (request) {
                            PushPermissionResult.Granted -> "granted" to null
                            PushPermissionResult.Denied -> "denied" to null
                            PushPermissionResult.SettingsRequired -> "settingsRequired" to null
                            PushPermissionResult.SettingsOpened -> "settingsOpened" to null
                            is PushPermissionResult.Failed -> "failed" to request.code
                        }
                        buildJsonObject {
                            put("outcome", outcome)
                            if (error != null) {
                                put("errorCode", error)
                            }
                        }.toString()
                    }
                    "openPushSettings" -> NotificationDev.openPushSettings(requireActivity()) == SettingsOpenResult.OPENED
                    else -> {
                        result.notImplemented()
                        return@launch
                    }
                }

                result.success(if (value is Unit) null else value)
            } catch (failure: CancellationException) {
                result.error("ENGINE_DETACHED", "Flutter engine detached", null)
            } catch (failure: Exception) {
                val error = bridgeError(failure)
                result.error(error.code, error.message, null)
            }
        }
    }

    private fun permissionStatus(): String {
        val status = NotificationDev.getPushPermissionStatus()

        return buildJsonObject {
            put("areNotificationsEnabled", status.areNotificationsEnabled)
            put("runtimePermission", status.runtimePermission.name)
            put("channelStatus", status.channelStatus.name)
        }.toString()
    }

    private fun requireActivity(): Activity = activityBinding?.activity
        ?: throw SdkException(SdkError("ACTIVITY_UNAVAILABLE", "An attached activity is required"))

    private fun handleInitialIntent() {
        if (!automatic || !NotificationDev.isInitialized) {
            return
        }
        val intent = pendingIntent ?: activityBinding?.activity?.intent ?: return
        pendingIntent = null

        scope.launch { handleIntent(intent) }
    }

    private suspend fun handleIntent(intent: Intent) {
        try {
            NotificationDev.handleIntent(intent)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            val error = bridgeError(failure)
            streams.firstOrNull { it.name == "diagnostics" }?.emit(json.encodeToString(error))
        }
    }

    override fun onNewIntent(intent: Intent): Boolean {
        if (!automatic) {
            return false
        }
        val owned = intent.hasExtra("dev.notification.sdk.payload") && intent.hasExtra("dev.notification.sdk.signature")
        if (!owned) {
            return false
        }
        if (NotificationDev.isInitialized) {
            scope.launch { handleIntent(intent) }
        } else {
            pendingIntent = intent
        }

        return true
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addOnNewIntentListener(this)
        handleInitialIntent()
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeOnNewIntentListener(this)
        activityBinding = null
    }

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        streams.forEach {
            it.close()
            EventChannel(binding.binaryMessenger, "dev.notification/sdk/${it.name}").setStreamHandler(null)
        }
        streams.clear()
        scope.cancel()
        onDetachedFromActivity()
    }

    private inner class NativeStream(val name: String) : EventChannel.StreamHandler {
        private var job: Job? = null
        private var sink: EventChannel.EventSink? = null

        override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
            sink = events
            job?.cancel()
            job = scope.launch {
                try {
                    check(initialized) { "Initialize the Flutter SDK first" }
                    when (name) {
                        "states" -> collect(NotificationDev.state, events) { json.encodeToString(it) }
                        "pendingOpens" -> collect(NotificationDev.pendingOpens, events) { json.encodeToString(it) }
                        "notifications" -> collect(NotificationDev.notifications, events) { json.encodeToString(it) }
                        "diagnostics" -> collect(NotificationDev.diagnostics, events) { json.encodeToString(it) }
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    val error = bridgeError(failure)
                    events.error(error.code, error.message, null)
                }
            }
        }

        private suspend fun <T> collect(flow: Flow<T>, events: EventChannel.EventSink, encode: (T) -> String) {
            flow.collect { events.success(encode(it)) }
        }

        fun emit(value: String) { sink?.success(value) }

        override fun onCancel(arguments: Any?) = close()

        fun close() {
            job?.cancel()
            job = null
            sink = null
        }
    }
}

internal fun MethodCall.string(key: String): String = requireNotNull(argument<String>(key)) { "Missing argument" }

internal fun MethodCall.boolean(key: String): Boolean = requireNotNull(argument<Boolean>(key)) { "Missing argument" }

internal fun bridgeError(failure: Exception): SdkError = when (failure) {
    is SdkException -> failure.error
    is IllegalArgumentException -> SdkError("INVALID_ARGUMENT", "Invalid SDK arguments or configuration")
    is IllegalStateException -> SdkError("INVALID_STATE", "Initialize the SDK with matching configuration first")
    else -> SdkError("NATIVE_FAILURE", "Native SDK operation failed")
}

internal fun codecObject(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key as String to codecObject(item) })
    is List<*> -> JsonArray(value.map(::codecObject))
    else -> throw IllegalArgumentException("Unsupported channel value")
}
