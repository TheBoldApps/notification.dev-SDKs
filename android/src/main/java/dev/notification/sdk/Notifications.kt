package dev.notification.sdk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal class NotificationPresenter(
    private val context: Context,
    private val config: SdkConfig,
    private val encodePayload: (NotificationPayload) -> String,
    private val diagnostic: (SdkError) -> Unit = {}
) {
    companion object {
        const val EXTRA_PAYLOAD = "dev.notification.sdk.payload"
        const val EXTRA_SIGNATURE = "dev.notification.sdk.signature"
        const val TAG = "notification.dev:"
    }

    private val signingKey: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("notification.dev.intents.v1", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore"
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    "notification.dev.intents.v1",
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                ).build()
            )
        }.generateKey()
    }

    private fun sign(value: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(signingKey) }.doFinal(value.toByteArray())

    fun verify(value: String, signature: String): Boolean = runCatching {
        MessageDigest.isEqual(sign(value), Base64.decode(signature, Base64.NO_WRAP))
    }.getOrDefault(false)

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannel(config.channelId, config.channelName, NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun display(payload: NotificationPayload, image: Bitmap? = null, avatar: Bitmap? = null) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return
        }
        val channelId = payload.android?.channelId ?: config.channelId
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId)
            if (channel == null) {
                diagnostic(SdkError("NOTIFICATION_CHANNEL_MISSING", "Requested notification channel is not registered"))
                return
            }
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return
        }
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val raw = encodePayload(payload)
        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        intent.data = Uri.Builder().scheme("notification-dev").authority("open").appendPath(payload.deliveryId).build()
        intent.putExtra(EXTRA_PAYLOAD, raw)
        intent.putExtra(EXTRA_SIGNATURE, Base64.encodeToString(sign(raw), Base64.NO_WRAP))
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(config.smallIcon).setContentTitle(payload.title).setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body)).setAutoCancel(true)
            .setTimeoutAfter((payload.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
            .setContentIntent(pending).setOnlyAlertOnce(true)
        payload.android?.groupKey?.let { builder.setGroup(it) }
        val communication = payload.communication?.takeIf { it.isValid() }
        if (communication != null) {
            val sender = Person.Builder().setKey(communicationPersonKey(payload.associationId, communication.sender.id))
                .setName(communication.sender.name)
                .apply { if (avatar != null) setIcon(IconCompat.createWithBitmap(avatar)) }.build()
            conversationShortcut(payload, communication, sender, avatar)?.let { shortcutId ->
                builder.setShortcutId(shortcutId)
            }
            builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .addPerson(sender)
                .setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
                    .setGroupConversation(false)
                    .addMessage(payload.body, System.currentTimeMillis(), sender))
            if (payload.android?.groupKey == null) {
                builder.setGroup(communicationGroup(payload.associationId, communication.conversationId))
            }
        } else if (image != null) {
            builder.setLargeIcon(image)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(image)
                    .bigLargeIcon(null as Bitmap?).setSummaryText(payload.body))
        }
        val manager = NotificationManagerCompat.from(context)
        manager.notify(notificationTag(payload), 0, builder.build())
        val group = payload.android?.groupKey
        val summary = payload.android?.groupSummary
        if (group != null && summary != null) {
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(config.smallIcon).setContentTitle(payload.title).setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setGroup(group).setGroupSummary(true).setAutoCancel(true)
                .setContentIntent(pending).setOnlyAlertOnce(true).build()
            manager.notify(TAG + "group:" + payload.associationId + ":" + group, 0, notification)
        }
    }

    private fun conversationShortcut(
        payload: NotificationPayload,
        communication: CommunicationNotification,
        sender: Person,
        avatar: Bitmap?
    ): String? = runCatching {
        val destination = payload.deepLink?.takeIf(::isSafeConversationDestination) ?: return null
        // A launcher shortcut opens the conversation, never a particular delivery.
        // Do not reuse the signed notification PendingIntent or its analytics extras.
        val route = Intent(Intent.ACTION_VIEW, Uri.parse(destination)).setPackage(context.packageName)
        val target = context.packageManager.resolveActivity(route, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.takeIf { it.packageName == context.packageName } ?: return null
        route.setClassName(target.packageName, target.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val id = communicationShortcutId(payload.associationId, communication.conversationId)
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return null
        if (ShortcutManagerCompat.isRateLimitingActive(context)) return null
        val dynamic = ShortcutManagerCompat.getDynamicShortcuts(context).filter { it.activity == launcher }
        val maximum = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        if (dynamic.none { it.id == id } && dynamic.size >= maximum) {
            val oldestOwned = dynamic.filter { it.id.startsWith(COMMUNICATION_SHORTCUT_PREFIX) }
                .minByOrNull { it.lastChangedTimestamp } ?: return null
            // Only drop its launcher/dynamic slot. Cached long-lived and pinned state
            // remains valid, including the user's conversation preferences.
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(oldestOwned.id))
        }
        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(communication.sender.name)
            .setActivity(launcher)
            .setIntent(route)
            .setPerson(sender)
            .setIsConversation()
            .setLongLived(true)
            .setIcon(if (avatar == null) IconCompat.createWithResource(context, config.smallIcon)
                else IconCompat.createWithBitmap(avatar))
            .build()
        // Unlike pushDynamicShortcut, this cannot evict an unrelated host-app shortcut.
        if (ShortcutManagerCompat.addDynamicShortcuts(context, listOf(shortcut))) id else null
    }.getOrElse {
        diagnostic(SdkError("COMMUNICATION_SHORTCUT_UNAVAILABLE", "Conversation shortcut is unavailable; showing the message normally"))
        null
    }

    fun clearConversationShortcuts() = runCatching {
        val owned = ShortcutManagerCompat.getShortcuts(context,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED or
                ShortcutManagerCompat.FLAG_MATCH_CACHED)
            .filter { it.id.startsWith(COMMUNICATION_SHORTCUT_PREFIX) }
        if (owned.isEmpty()) return
        val ids = owned.map { it.id }
        // Apps cannot remove user-pinned shortcuts. Remove personal labels/avatars and
        // their destination before disabling those entries, then clear SDK-owned caches.
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
        if (launcher != null) {
            val sanitized = owned.filter { it.isPinned }.map { shortcut ->
                ShortcutInfoCompat.Builder(context, shortcut.id)
                    .setShortLabel("Conversation unavailable")
                    .setIcon(IconCompat.createWithResource(context, config.smallIcon))
                    .setPersons(emptyArray())
                    .setLongLived(false)
                    .setIntent(Intent(Intent.ACTION_MAIN).setComponent(launcher).setPackage(context.packageName))
                    .build()
            }
            if (sanitized.isNotEmpty() && !ShortcutManagerCompat.updateShortcuts(context, sanitized)) {
                diagnostic(SdkError("COMMUNICATION_SHORTCUT_CLEANUP_FAILED", "Pinned conversation metadata update was rejected; disabling the shortcut"))
            }
        }
        ShortcutManagerCompat.disableShortcuts(context, ids, "Conversation no longer available")
        ShortcutManagerCompat.removeDynamicShortcuts(context, ids)
        ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
    }.getOrElse {
        diagnostic(SdkError("COMMUNICATION_SHORTCUT_CLEANUP_FAILED", "Conversation shortcuts could not be cleared"))
    }

    fun clear() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith(TAG) == true }.forEach { manager.cancel(it.tag, it.id) }
    }
}

internal fun notificationTag(payload: NotificationPayload): String = NotificationPresenter.TAG +
    if (payload.collapseId != null) "collapse:${payload.associationId}:${payload.collapseId}" else "delivery:${payload.deliveryId}"

class NotificationMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // A Flutter host may receive a first-install token before its first Dart initialization.
        // Startup token acquisition recovers it when the host configures the SDK.
        if (!NotificationDev.isInitialized) {
            return
        }

        // Only local persistence here. WorkManager performs network I/O outside the FCM callback.
        runBlocking(Dispatchers.IO) { NotificationDev.onNewToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!NotificationDev.isInitialized) {
            return
        }

        runBlocking(Dispatchers.IO) { NotificationDev.handleRemoteMessage(message) }
    }
}

internal fun communicationGroup(association: String, conversation: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(conversation.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return "notification.dev:conversation:$association:$hash"
}

internal const val COMMUNICATION_SHORTCUT_PREFIX = "dev.notification.sdk.conversation."

internal fun communicationShortcutId(association: String, conversation: String): String {
    val scoped = "${association.length}:$association$conversation"
    val hash = MessageDigest.getInstance("SHA-256").digest(scoped.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return COMMUNICATION_SHORTCUT_PREFIX + hash
}

internal fun communicationPersonKey(association: String, sender: String): String =
    "dev.notification.sdk.person." + communicationShortcutId(association, sender)
        .removePrefix(COMMUNICATION_SHORTCUT_PREFIX)

internal fun isSafeConversationDestination(raw: String): Boolean = runCatching {
    if (raw.isBlank() || raw.length > 2048 || raw.any { it.isISOControl() }) return false
    val uri = java.net.URI(raw)
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme in setOf("intent", "javascript", "data", "file", "content", "about", "blob", "tel", "sms", "mailto", "geo", "market") || uri.userInfo != null) return false
    if (scheme in setOf("https", "http") && uri.host.isNullOrBlank()) return false
    true
}.getOrDefault(false)
