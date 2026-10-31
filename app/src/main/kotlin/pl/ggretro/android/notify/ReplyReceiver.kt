package pl.ggretro.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import pl.ggretro.android.app
import pl.ggretro.android.ui.MainActivity
import pl.ggretro.protocol.StyledText

/** Handles "Odpowiedz" typed directly in a message notification. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uin = intent.getIntExtra(MainActivity.EXTRA_UIN, 0)
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        if (uin <= 0 || text.isNullOrEmpty()) return
        val app = context.app
        app.manager.sendText(uin, StyledText(text))
        app.notifier.cancel(uin)
        app.manager.openedChat(uin)
    }

    companion object {
        const val KEY_TEXT = "reply_text"
    }
}
