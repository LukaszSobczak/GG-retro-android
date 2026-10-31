package pl.ggretro.android.ui.login

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pl.ggretro.android.BuildConfig
import pl.ggretro.android.app
import pl.ggretro.android.data.AppSettings
import pl.ggretro.android.data.ConnState
import pl.ggretro.android.ui.theme.GgColors
import pl.ggretro.android.ui.theme.RetroButton
import pl.ggretro.android.ui.theme.RetroCheckbox
import pl.ggretro.android.ui.theme.RetroField
import pl.ggretro.android.ui.theme.RetroLabel
import pl.ggretro.android.ui.theme.RetroPanel

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val app = LocalContext.current.app
    val state by app.manager.state.collectAsStateWithLifecycle()
    var uin by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf(AppSettings.DEFAULT_HOST) }
    var port by rememberSaveable { mutableStateOf(AppSettings.DEFAULT_PORT.toString()) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!loaded) {
            val s = app.settings.current()
            if (s.uin > 0) uin = s.uin.toString()
            password = s.password
            host = s.host
            port = s.port.toString()
            loaded = true
        }
    }

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        RetroPanel("Gadu-Gadu – logowanie", Modifier.padding(16.dp).widthIn(max = 420.dp)) {
            RetroLabel("Numer GG:")
            RetroField(uin, { uin = it.filter(Char::isDigit).take(8) }, number = true)
            RetroLabel("Hasło:")
            RetroField(password, { password = it }, password = true)
            RetroLabel("Serwer:")
            RetroField(host, { host = it.trim() })
            RetroLabel("Port:")
            RetroField(port, { port = it.filter(Char::isDigit).take(5) }, number = true)
            val message = error ?: (state as? ConnState.Error)?.message
            if (message != null) RetroLabel(message, color = GgColors.MyName, bold = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                RetroButton("Zaloguj", onClick = {
                    val number = uin.toIntOrNull()
                    val p = port.toIntOrNull()
                    error = when {
                        number == null || number <= 0 || number > 0xffffff -> "Podaj poprawny numer GG."
                        password.isEmpty() -> "Podaj hasło."
                        host.isBlank() -> "Podaj adres serwera."
                        p == null || p !in 1..65535 -> "Podaj poprawny port."
                        else -> null
                    }
                    if (error == null) {
                        app.manager.login(host, p!!, number!!, password)
                        onLoggedIn()
                    }
                })
            }
            RetroLabel("GG Retro ${BuildConfig.VERSION_NAME}", color = GgColors.Grey, size = 12)
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val s = settings ?: return
    var host by rememberSaveable { mutableStateOf(s.host) }
    var port by rememberSaveable { mutableStateOf(s.port.toString()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        RetroPanel("Ustawienia", Modifier.fillMaxWidth(), onClose = onBack) {
            RetroLabel("Serwer:")
            RetroField(host, { host = it.trim() })
            RetroLabel("Port:")
            RetroField(port, { port = it.filter(Char::isDigit).take(5) }, number = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                RetroButton("Zapisz serwer", onClick = {
                    val p = port.toIntOrNull()
                    if (host.isNotBlank() && p != null && p in 1..65535) {
                        scope.launch {
                            app.settings.setServer(host, p)
                            if (app.manager.running.value) app.manager.restartConnection()
                        }
                    }
                })
            }
            RetroCheckbox("Dźwięk nowej wiadomości", s.soundMessage) { v -> scope.launch { app.settings.setSoundMessage(v) } }
            RetroCheckbox("Dźwięk, gdy ktoś staje się dostępny", s.soundOnline) { v -> scope.launch { app.settings.setSoundOnline(v) } }
            RetroCheckbox("Enter wysyła wiadomość", s.enterSends) { v -> scope.launch { app.settings.setEnterSends(v) } }
            RetroLabel("Zestaw emotikon:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RetroButton("Zestaw 1", onClick = { scope.launch { app.settings.setEmoticonSet(1) } }, pressed = s.emoticonSet == 1)
                RetroButton("Zestaw 2", onClick = { scope.launch { app.settings.setEmoticonSet(2) } }, pressed = s.emoticonSet == 2)
            }
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                RetroLabel("Aby połączenie nie było zrywane w tle, wyłącz optymalizację baterii dla GG Retro.", size = 13)
                RetroButton("Wyłącz optymalizację baterii", onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                        )
                    }
                })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RetroButton("Wyloguj", onClick = { app.manager.logout() })
                RetroButton("OK", onClick = onBack)
            }
            RetroLabel("GG Retro ${BuildConfig.VERSION_NAME} · numer ${s.uin}", color = GgColors.Grey, size = 12)
        }
    }
}
