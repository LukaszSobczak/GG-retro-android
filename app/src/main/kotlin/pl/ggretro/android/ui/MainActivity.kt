package pl.ggretro.android.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import pl.ggretro.android.app
import pl.ggretro.android.data.ConnState
import pl.ggretro.android.service.GgService
import pl.ggretro.android.ui.chat.ChatScreen
import pl.ggretro.android.ui.contacts.ContactsScreen
import pl.ggretro.android.ui.login.LoginScreen
import pl.ggretro.android.ui.login.SettingsScreen
import pl.ggretro.android.ui.share.SharePayload
import pl.ggretro.android.ui.share.ShareScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pl.ggretro.android.ui.theme.GgColors

class MainActivity : ComponentActivity() {
    private val pendingChat = MutableStateFlow<Int?>(null)
    private val pendingShare = MutableStateFlow<SharePayload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(GgColors.OrangeDark.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(GgColors.OrangeDark.toArgb()),
        )
        // Only a fresh launch carries a new request; recreation (rotation) or a relaunch from
        // Recents must not reopen the chat from an old notification.
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            handleIntent(intent)
        }
        setContent { GgRoot(pendingChat, pendingShare) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && SharePayload.isShare(intent)) {
            handleShare(intent)
            return
        }
        val uin = intent?.getIntExtra(EXTRA_UIN, 0) ?: 0
        intent?.removeExtra(EXTRA_UIN)
        if (uin > 0) pendingChat.value = uin
    }

    /** "Udostępnij" from another app: straight to the chat (Direct Share) or to the contact picker. */
    private fun handleShare(intent: Intent) {
        val shortcutUin = SharePayload.shortcutUin(intent)
        // Never handle the same share twice (e.g. after the activity is recreated).
        setIntent(Intent(this, MainActivity::class.java))
        lifecycleScope.launch {
            val payload = SharePayload.read(applicationContext, intent)
            when {
                payload == null -> Toast.makeText(this@MainActivity, "Nie ma czego udostępnić.", Toast.LENGTH_SHORT).show()
                shortcutUin != null -> {
                    app.putShare(shortcutUin, payload)
                    pendingChat.value = shortcutUin
                }
                else -> pendingShare.value = payload
            }
        }
    }

    companion object {
        const val EXTRA_UIN = "uin"
    }
}

@Composable
private fun GgRoot(pendingChat: MutableStateFlow<Int?>, pendingShare: MutableStateFlow<SharePayload?>) {
    val context = LocalContext.current
    val app = context.app
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val chatRequest by pendingChat.collectAsStateWithLifecycle()
    val shareRequest by pendingShare.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(GgColors.Orange)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        val s = settings ?: return@Box
        val nav = rememberNavController()
        val start = remember { if (s.loggedIn && s.hasAccount) "contacts" else "login" }

        LaunchedEffect(Unit) {
            app.manager.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
        LaunchedEffect(Unit) {
            // App opened while logged in: make sure the connection is running.
            val current = app.settings.current()
            if (current.loggedIn && current.hasAccount && !app.manager.running.value && app.manager.state.value !is ConnState.Error) {
                GgService.start(context)
            }
        }
        LaunchedEffect(s.loggedIn) {
            val route = nav.currentDestination?.route
            if (!s.loggedIn && route != null && route != "login") {
                nav.navigate("login") { popUpTo(0) }
            }
        }
        LaunchedEffect(chatRequest, s.loggedIn) {
            val uin = chatRequest ?: return@LaunchedEffect
            if (s.loggedIn) {
                nav.navigate("chat/$uin") { launchSingleTop = true }
                pendingChat.value = null
            }
        }

        LaunchedEffect(shareRequest, s.loggedIn) {
            if (shareRequest == null) return@LaunchedEffect
            if (s.loggedIn) {
                nav.navigate("share") { launchSingleTop = true }
            } else {
                Toast.makeText(context, "Zaloguj się, aby udostępnić.", Toast.LENGTH_LONG).show()
            }
        }

        NavHost(navController = nav, startDestination = start) {
            composable("share") {
                val payload = shareRequest
                if (payload == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    ShareScreen(
                        payload = payload,
                        onPick = { uin ->
                            app.putShare(uin, payload)
                            nav.navigate("chat/$uin") { popUpTo("share") { inclusive = true } }
                            pendingShare.value = null
                        },
                        onCancel = {
                            nav.popBackStack()
                            pendingShare.value = null
                        },
                    )
                }
            }
            composable("login") {
                LoginScreen(onLoggedIn = { nav.navigate("contacts") { popUpTo(0) } })
            }
            composable("contacts") {
                ContactsScreen(
                    onOpenChat = { uin -> nav.navigate("chat/$uin") { launchSingleTop = true } },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("settings") {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
            composable("chat/{uin}", arguments = listOf(navArgument("uin") { type = NavType.IntType })) { entry ->
                val uin = entry.arguments?.getInt("uin") ?: 0
                // Fresh draft/formatting state per contact (launchSingleTop may reuse the entry).
                key(uin) {
                    ChatScreen(uin = uin, onClose = {
                        if (!nav.popBackStack("contacts", inclusive = false)) nav.navigate("contacts") { popUpTo(0) }
                    })
                }
            }
        }
    }
}
