package dev.notification.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Captured requests are immutable once attempted, including across process death. */
@Serializable
internal data class LiveActivityRequest(
    val id: String,
    val kind: String,
    val target: String,
    val body: JsonObject,
    val associationId: String? = null,
    val attempted: Boolean = false
) {
    val key: String get() = if (kind == "start") "start:$target" else "activity:$target"
}

@Serializable
internal data class LiveActivityBinding(
    val associationId: String, val stopped: Boolean = false, val createdAt: Long = currentTimeMillis()
)

@Serializable
internal data class LiveActivityStorage(
    val requests: List<LiveActivityRequest> = emptyList(),
    val values: Map<String, JsonObject> = emptyMap(),
    val bindings: Map<String, LiveActivityBinding> = emptyMap()
) {
    // ActivityKit activities expire within hours. Keep bindings for seven days to cover
    // relaunch/identity races, then release tokens and tombstones instead of accumulating forever.
    fun prune(now: Long): LiveActivityStorage {
        val retained = bindings.filterValues { it.createdAt >= now - RETENTION }
        return copy(
            bindings = retained,
            values = values.filterKeys { it.startsWith("start:") || it.removePrefix("activity:") in retained },
            requests = requests.filter { it.kind == "start" || it.target in retained }
        )
    }

    fun enqueue(request: LiveActivityRequest): LiveActivityStorage {
        if (values[request.key] == request.body) return this
        if ((requests.size >= 1000 && requests.none { it.key == request.key && !it.attempted }) ||
            (values.size >= 1000 && request.key !in values)) sdkFailure("QUEUE_FULL")

        return copy(
            requests = requests.filterNot { it.key == request.key && !it.attempted } + request,
            values = values + (request.key to request.body)
        )
    }

    /** Only installation-wide start eligibility can carry over to another identity. */
    fun changeAssociation(): LiveActivityStorage = copy(
        requests = values.filterKeys { it.startsWith("start:") }.map { (key, body) ->
            LiveActivityRequest(newId(), "start", key.removePrefix("start:"), body)
        },
        values = values.filterKeys { it.startsWith("start:") }
    )
}

internal fun validateLiveActivityText(value: String) {
    if (value.length !in 1..255 || !value.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.:-]*"))) {
        sdkFailure("INVALID_LIVE_ACTIVITY")
    }
}

internal fun validateLiveActivityToken(value: String) {
    if (value.length !in 2..8192 || value.length % 2 != 0 || !value.all { it in "0123456789abcdefABCDEF" }) {
        sdkFailure("INVALID_TOKEN")
    }
}
