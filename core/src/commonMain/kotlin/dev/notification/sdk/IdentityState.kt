package dev.notification.sdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface IdentityTransition {
    val id: String

    @Serializable
    @SerialName("login")
    data class Login(override val id: String, val externalId: String) : IdentityTransition

    @Serializable
    @SerialName("logout")
    data class Logout(override val id: String) : IdentityTransition
}

/** A pending login can only be retried for the same user; logout hides the old user immediately. */
@Serializable
internal data class IdentityState(
    val transition: IdentityTransition? = null
) {
    val isPending: Boolean get() = transition != null
    val isLoggingIn: Boolean get() = transition is IdentityTransition.Login
    val isLoggingOut: Boolean get() = transition is IdentityTransition.Logout

    fun requireSettled() {
        if (isPending) {
            sdkFailure("IDENTITY_CHANGE_PENDING")
        }
    }

    fun beginLogin(externalId: String): IdentityState {
        val login = transition as? IdentityTransition.Login
        if (isLoggingOut || (login != null && login.externalId != externalId)) {
            sdkFailure("IDENTITY_CHANGE_PENDING")
        }

        return copy(transition = login ?: IdentityTransition.Login(newId(), externalId))
    }

    fun beginLogout(): IdentityState {
        if (isLoggingIn) {
            sdkFailure("IDENTITY_CHANGE_PENDING")
        }

        return IdentityState(transition ?: IdentityTransition.Logout(newId()))
    }

    fun rejectLogin(): IdentityState = if (isLoggingIn) copy(transition = null) else this
}

internal fun sdkFailure(code: String): Nothing = throw SdkException(SdkError(code, "SDK operation failed: $code"))
