package dev.notification.sdk

internal fun decodeStoredState(json: String): StoredState = wireJson.decodeFromString(json)
