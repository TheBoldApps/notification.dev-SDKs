package dev.notification.sdk

internal fun normalizeEmail(email: String): String {
    val value = email.trim()
    require(value.length in 3..254 && value.count { it == '@' } == 1 && value.none { it.isWhitespace() || it.isISOControl() }) { "Invalid email address" }
    val (local, domain) = value.split('@')
    require(
        local.isNotEmpty() && local.length <= 64 && !local.startsWith('.') && !local.endsWith('.') && !local.contains(
            ".."
        )
    ) { "Invalid email address" }
    require(domain.none { it in "/\\:@?#%[]" }) { "Invalid email domain" }
    val ascii = asciiDomain(domain).lowercase()
    require(
        ascii.contains('.') && ascii.split('.')
            .all { it.isNotEmpty() && it.length <= 63 && it.first() != '-' && it.last() != '-' && it.all { ch -> ch.isLetterOrDigit() || ch == '-' } }) { "Invalid email domain" }

    return "$local@$ascii"
}
