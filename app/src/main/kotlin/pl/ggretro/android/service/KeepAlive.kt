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
    /**
     * Alarm interval. An inexact allow-while-idle alarm may be delivered up to 0.75 × the interval
     * late (Android 12+ wakes at the end of the batching window), so the worst gap between two
     * wake-ups is 1.75 × 120 s = 210 s.
     */
    const val INTERVAL_MS = 120_000L

    /** The server's idle limit. */
    private const val SERVER_IDLE_LIMIT_MS = 300_000L

    /**
     * Skip the ping only if something was sent within this time: then even the latest possible next
     * wake-up (+210 s) plus the probe's own time stays under the server's 300 s limit
     * (60 + 210 + ~10 = 280 s).
     */
    const val MIN_IDLE_FOR_PING_MS = SERVER_IDLE_LIMIT_MS - INTERVAL_MS * 7 / 4 - 30_000L

    /** Also probe if nothing was received for this long, to notice a dead (half-open) connection. */
    const val MAX_SILENCE_MS = INTERVAL_MS

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
                // Re-arm first and independently of the connection state, so the chain never ends
                // while the service lives (a stop cancels it; a late orphan alarm just finds it dead).
                if (GgService.alive) {
                    KeepAlive.schedule(context)
                    context.app.manager.keepAlive()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
