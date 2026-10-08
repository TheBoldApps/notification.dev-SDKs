package dev.notification.sdk

import io.ktor.client.engine.HttpClientEngine
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

internal fun newId(): String = Uuid.random().toString()

internal expect fun secureRandomBytes(size: Int): ByteArray

internal expect fun platformHttpEngine(): HttpClientEngine

internal expect fun asciiDomain(domain: String): String

internal fun registrationSecret(): String = secureRandomBytes(32).toHexString()
