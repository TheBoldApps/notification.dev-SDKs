@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.notification.sdk

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSURL
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
    if (size > 0) {
        bytes.usePinned {
            check(SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) == 0) {
                "Secure random generation failed"
            }
        }
    }
}

internal actual fun platformHttpEngine(): HttpClientEngine = Darwin.create()

internal actual fun asciiDomain(domain: String): String =
    requireNotNull(NSURL.URLWithString("https://$domain")?.host) { "Invalid email domain" }
