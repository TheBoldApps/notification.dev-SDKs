package dev.notification.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class EmailChange(
    val version: Long,
    val remove: Boolean = false,
    val address: String? = null,
    val optedIn: Boolean? = null
)

@Serializable
internal data class TagChange(val version: Long, val value: JsonPrimitive?)

@Serializable
internal data class UserChanges(
    val email: EmailChange? = null,
    val tags: Map<String, TagChange> = emptyMap()
) {
    val isEmpty: Boolean get() = email == null && tags.isEmpty()

    fun applyTo(user: UserProperties): UserProperties {
        val nextEmail = when {
            email == null -> user.email
            email.remove -> null
            email.address != null -> EmailSubscription(
                email.address,
                email.optedIn ?: false,
                user.email?.takeIf { it.address == email.address }?.suppressed ?: false
            )
            else -> user.email?.copy(optedIn = email.optedIn ?: user.email.optedIn)
        }
        val nextTags = user.tags.toMutableMap()
        tags.forEach { (key, change) ->
            if (change.value == null) {
                nextTags.remove(key)
            } else {
                nextTags[key] = change.value
            }
        }

        return UserProperties(nextEmail, nextTags)
    }

    fun without(sent: UserChanges) = UserChanges(
        email = email?.takeUnless { it.version == sent.email?.version },
        tags = tags.filter { (key, value) -> value.version != sent.tags[key]?.version }
    )
}

@Serializable
internal data class UserRequest(val id: String, val associationId: String, val changes: UserChanges)

/** Keeps local edits and the exact request being retried together. */
@Serializable
internal data class PendingUser(
    val version: Long = 0,
    val changes: UserChanges = UserChanges(),
    val request: UserRequest? = null,
    val blocked: Boolean = false
) {
    val needsSync: Boolean get() = !changes.isEmpty && !blocked

    fun edit(update: UserChanges.(Long) -> UserChanges): PendingUser {
        val nextVersion = version + 1

        return copy(version = nextVersion, changes = changes.update(nextVersion), blocked = false)
    }

    fun capture(associationId: String): PendingUser = copy(
        request = request ?: UserRequest(newId(), associationId, changes)
    )

    fun acknowledge(sent: UserRequest): PendingUser = copy(
        changes = changes.without(sent.changes),
        request = null
    )

    fun reject(before: PendingUser): PendingUser = copy(
        request = null,
        blocked = changes == before.changes
    )

    fun discard(): PendingUser = PendingUser(version = version)
}
