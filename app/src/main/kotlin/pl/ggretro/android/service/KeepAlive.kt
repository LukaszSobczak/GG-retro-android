package pl.ggretro.android.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.ggretro.android.app

/**
 * Keep-alive while the phone sleeps, without holding a wake lock all the time.
 *
 * gg-retro drops a client after 300 s without any packet. While the CPU is awake GgSession pings by
 * itself; in deep sleep its timer is frozen, so a system alarm wakes the app every [INTERVAL_MS]
 * for a moment, sends GG_PING if nothing was sent recently and lets the phone sleep again.
 * Incoming messages do not depend on this: the server pushes them over the open connection at once.
 */
object KeepAlive {
    const val INTERVAL_MS = 180_000L

    /** The server's idle limit. */
    private const val SERVER_IDLE_LIMIT_MS = 300_000L

    /**
     * Ping now unless something was sent within this time. Then even if the next alarm comes
     * [INTERVAL_MS] later (+30 s of slack), the server still sees a packet within its limit.
     */
    const val MIN_IDLE_FOR_PING_MS = SERVER_IDLE_LIMIT_MS - INTERVAL_MS - 30_000L

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, KeepAliveReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        // Inexact "allow while idle": no exact-alarm permission needed; it also fires in Doze
        // (the system may stretch it there; a dropped connection then reconnects on wake-up).
        alarms.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            pendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }
}

/** Fired by the keep-alive alarm; the system keeps the CPU awake until [goAsync] finishes. */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val manager = context.app.manager
                if (manager.running.value) {
                    manager.keepAlive()
                    KeepAlive.schedule(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
