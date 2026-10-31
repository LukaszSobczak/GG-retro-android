package pl.ggretro.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
import pl.ggretro.android.data.ConnState
import pl.ggretro.android.notify.Notifier

/** Foreground service that keeps the GG connection alive while the app is in the background. */
class GgService : LifecycleService() {
    override fun onCreate() {
        super.onCreate()
        val app = app
        startInForeground(app.notifier.connectionNotification(ConnState.Connecting, "GG Retro"))
        app.manager.start()
        lifecycleScope.launch {
            combine(app.manager.state, app.settings.flow) { state, s -> state to s }.collect { (state, s) ->
                val title = if (s.uin > 0) "GG Retro – ${s.uin}" else "GG Retro"
                startInForeground(app.notifier.connectionNotification(state, title))
            }
        }
        lifecycleScope.launch {
            app.manager.running.collect { running -> if (!running) stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        app.manager.start()
        return START_STICKY
    }

    override fun onDestroy() {
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
