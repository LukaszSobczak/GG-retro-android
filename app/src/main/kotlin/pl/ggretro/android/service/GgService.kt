package pl.ggretro.android.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import pl.ggretro.android.app
import pl.ggretro.android.data.AutoAway
import pl.ggretro.android.data.ConnState
import pl.ggretro.android.notify.Notifier

/** Foreground service that keeps the GG connection alive while the app is in the background. */
class GgService : LifecycleService() {
    @Volatile private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    /** Screen lock/unlock → automatic "Zaraz wracam" (these broadcasts are only delivered to registered receivers). */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val manager = context.app.manager
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> manager.screenLocked()
                Intent.ACTION_USER_PRESENT -> manager.screenUnlocked()
                // Without a lock screen there is no USER_PRESENT: screen on and not locked means unlocked.
                Intent.ACTION_SCREEN_ON -> {
                    val keyguard = context.getSystemService(KeyguardManager::class.java)
                    if (keyguard == null || !keyguard.isKeyguardLocked) manager.screenUnlocked()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val gg = this.app
        startInForeground(gg.notifier.connectionNotification(ConnState.Connecting, "GG Retro"))
        gg.manager.start()
        // Keeps the 60 s keep-alive ping running while the phone sleeps; without it the server
        // drops the session after 5 minutes of silence.
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GgRetro:connection")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleScope.launch {
            combine(gg.manager.state, gg.settings.flow, gg.manager.autoAway) { state, s, away -> Triple(state, s, away) }
                .collect { (state, s, away) ->
                    val title = if (s.uin > 0) "GG Retro – ${s.uin}" else "GG Retro"
                    val presence = AutoAway.effective(s.presence, away)
                    startInForeground(gg.notifier.connectionNotification(state, title, presence, s.description))
                }
        }
        lifecycleScope.launch {
            // stopSelf(startId) never cancels a start request that arrived after this one.
            gg.manager.running.collect { running -> if (!running) stopSelf(lastStartId) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        app.manager.start()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        if (app.manager.running.value) app.manager.stopLoop()
        super.onDestroy()
    }

    private fun startInForeground(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, Notifier.CONNECTION_NOTIFICATION_ID, notification, type)
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GgService::class.java))
        }
    }
}

/** Reconnects after a reboot / app update if the user was logged in. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val s = context.app.settings.current()
                if (s.loggedIn && s.hasAccount) GgService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
