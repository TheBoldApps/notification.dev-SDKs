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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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

    fun display(payload: NotificationPayload, image: Bitmap? = null) {
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
        if (image != null) builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).setSummaryText(payload.body))
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
