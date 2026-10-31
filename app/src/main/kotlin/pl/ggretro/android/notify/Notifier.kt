package pl.ggretro.android.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import pl.ggretro.android.R
import pl.ggretro.android.data.displayName
import pl.ggretro.android.data.ConnState
import pl.ggretro.android.data.ContactEntity
import pl.ggretro.android.ui.MainActivity

class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    private val history = HashMap<Int, MutableList<NotificationCompat.MessagingStyle.Message>>()

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val sound = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.gg_wiadomosc}")
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Wiadomości", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nowe wiadomości (dźwięk GG)"
                setSound(sound, attrs)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES_SILENT, "Wiadomości (bez dźwięku)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nowe wiadomości, gdy dźwięk jest wyłączony w ustawieniach"
                setSound(null, null)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "Połączenie", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Stałe powiadomienie, gdy GG Retro jest połączone"
                setShowBadge(false)
            },
        )
    }

    private fun openApp(uin: Int?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (uin != null) putExtra(MainActivity.EXTRA_UIN, uin)
        }
        return PendingIntent.getActivity(
            context, uin ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun connectionNotification(state: ConnState, title: String): Notification {
        val text = when (state) {
            ConnState.Online -> "Połączono"
            ConnState.Connecting -> "Łączenie…"
            ConnState.Offline -> "Rozłączono"
            is ConnState.Waiting -> state.message
            is ConnState.Error -> state.message
        }
        return NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_gg)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(null))
            .build()
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun showMessage(contact: ContactEntity, text: String, sound: Boolean) {
        if (!canNotify()) return
        val person = Person.Builder().setName(contact.displayName).setKey(contact.uin.toString()).build()
        val messages = synchronized(history) {
            history.getOrPut(contact.uin) { mutableListOf() }.apply {
                add(NotificationCompat.MessagingStyle.Message(text, System.currentTimeMillis(), person))
                while (size > 8) removeAt(0)
            }.toList()
        }
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Ja").build())
        messages.forEach { style.addMessage(it) }

        val replyIntent = Intent(context, ReplyReceiver::class.java).putExtra(MainActivity.EXTRA_UIN, contact.uin)
        val replyPending = PendingIntent.getBroadcast(
            context, contact.uin, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_stat_gg, "Odpowiedz", replyPending)
            .addRemoteInput(RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("Wiadomość").build())
            .setAllowGeneratedReplies(true)
            .build()

        val notification = NotificationCompat.Builder(context, if (sound) CHANNEL_MESSAGES else CHANNEL_MESSAGES_SILENT)
            .setSmallIcon(R.drawable.ic_stat_gg)
            .setStyle(style)
            .setContentTitle(contact.displayName)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openApp(contact.uin))
            .addAction(reply)
            .build()
        manager.notify(contact.uin, notification)
    }

    fun cancel(uin: Int) {
        synchronized(history) { history.remove(uin) }
        manager.cancel(uin)
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_MESSAGES_SILENT = "messages_silent"
        const val CHANNEL_CONNECTION = "connection"
        const val CONNECTION_NOTIFICATION_ID = Int.MAX_VALUE
    }
}
