package pl.ggretro.android.ui.contacts

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.ggretro.android.app
import pl.ggretro.android.data.displayName
import pl.ggretro.android.data.AppSettings
import pl.ggretro.android.data.ConnState
import pl.ggretro.android.data.ContactEntity
import pl.ggretro.android.ui.theme.GgColors
import pl.ggretro.android.ui.theme.OrangeBar
import pl.ggretro.android.ui.theme.RetroButton
import pl.ggretro.android.ui.theme.RetroField
import pl.ggretro.android.ui.theme.RetroLabel
import pl.ggretro.android.ui.theme.RetroPanel
import pl.ggretro.android.ui.theme.RetroTitleBar
import pl.ggretro.android.ui.theme.SunkenBox
import pl.ggretro.android.ui.theme.retroText
import pl.ggretro.protocol.GgStatus
import pl.ggretro.protocol.Presence

fun presenceLabel(p: Presence): String = when (p) {
    Presence.AVAILABLE -> "Dostępny"
    Presence.BUSY -> "Zaraz wracam"
    Presence.INVISIBLE -> "Niewidoczny"
    Presence.NOT_AVAILABLE -> "Niedostępny"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(onOpenChat: (Int) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val contacts by app.db.contacts().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val state by app.manager.state.collectAsStateWithLifecycle()
    val skin = app.skin

    var menuOpen by remember { mutableStateOf(false) }
    var statusDialog by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    var contextFor by remember { mutableStateOf<ContactEntity?>(null) }
    var renameFor by remember { mutableStateOf<ContactEntity?>(null) }
    var removeFor by remember { mutableStateOf<ContactEntity?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val myStatus = if (state == ConnState.Online) settings.presence.code(settings.description.isNotBlank()) else GgStatus.NOT_AVAIL
    Column(Modifier.fillMaxSize()) {
        RetroTitleBar("Gadu-Gadu (${settings.uin})", icon = skin.statusIcon(myStatus))
        val info = when (val st = state) {
            ConnState.Online -> null
            ConnState.Connecting -> "Łączenie z ${settings.host}…"
            ConnState.Offline -> "Niedostępny"
            is ConnState.Waiting -> st.message
            is ConnState.Error -> st.message
        }
        if (info != null) {
            Row(
                Modifier.fillMaxWidth().background(GgColors.Yellow).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RetroLabel(info, Modifier.weight(1f), size = 13)
                if (state is ConnState.Error || state == ConnState.Offline) {
                    RetroButton("Połącz", onClick = {
                        if (settings.presence == Presence.NOT_AVAILABLE) app.manager.setStatus(Presence.AVAILABLE, settings.description)
                        else app.manager.reconnect()
                    })
                }
            }
        }
        SunkenBox(Modifier.weight(1f).fillMaxWidth(), color = Color.White) {
            if (contacts.isEmpty()) {
                RetroLabel(
                    "Brak kontaktów.\nDodaj kontakt lub zaimportuj listę z serwera (Menu).",
                    Modifier.padding(12.dp),
                    color = GgColors.Grey,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(contacts, key = { it.uin }) { c ->
                    Box {
                        ContactRow(
                            c,
                            icon = skin.statusIcon(c.status),
                            modifier = Modifier.combinedClickable(
                                onClick = { onOpenChat(c.uin) },
                                onLongClick = { contextFor = c },
                            ),
                        )
                        DropdownMenu(expanded = contextFor?.uin == c.uin, onDismissRequest = { contextFor = null }) {
                            DropdownMenuItem(text = { Text("Rozmowa") }, onClick = { contextFor = null; onOpenChat(c.uin) })
                            DropdownMenuItem(text = { Text("Zmień nazwę") }, onClick = { renameFor = c; contextFor = null })
                            DropdownMenuItem(text = { Text("Usuń kontakt") }, onClick = { removeFor = c; contextFor = null })
                        }
                    }
                }
            }
        }
        OrangeBar {
            Box {
                RetroButton("Menu", onClick = { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Dodaj kontakt") }, onClick = { menuOpen = false; addDialog = true })
                    DropdownMenuItem(text = { Text("Importuj listę z serwera") }, onClick = { menuOpen = false; app.manager.importContacts() })
                    DropdownMenuItem(text = { Text("Eksportuj listę na serwer") }, onClick = { menuOpen = false; app.manager.exportContacts() })
                    DropdownMenuItem(text = { Text("Ustawienia") }, onClick = { menuOpen = false; onSettings() })
                    DropdownMenuItem(text = { Text("Wyloguj") }, onClick = { menuOpen = false; app.manager.logout() })
                }
            }
            Row(
                Modifier
                    .weight(1f)
                    .background(GgColors.Face)
                    .clickable { statusDialog = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                skin.statusIcon(settings.presence.code(settings.description.isNotBlank()))?.let {
                    Image(it, null, Modifier.size(20.dp), filterQuality = FilterQuality.None)
                    Spacer(Modifier.width(6.dp))
                }
                BasicText(
                    presenceLabel(settings.presence) + if (settings.description.isNotBlank()) ": ${settings.description}" else "",
                    style = retroText(14),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                BasicText("▾", style = retroText(14))
            }
        }
    }

    if (statusDialog) {
        StatusDialog(
            current = settings.presence,
            description = settings.description,
            onDismiss = { statusDialog = false },
            onConfirm = { p, d ->
                statusDialog = false
                app.manager.setStatus(p, d)
            },
        )
    }
    if (addDialog) {
        ContactDialog("Dodaj kontakt", initialUin = "", initialName = "", uinEditable = true, onDismiss = { addDialog = false }) { uin, name ->
            addDialog = false
            app.manager.addContact(uin, name)
        }
    }
    renameFor?.let { c ->
        ContactDialog("Zmień nazwę", c.uin.toString(), c.displayName, uinEditable = false, onDismiss = { renameFor = null }) { _, name ->
            renameFor = null
            app.manager.renameContact(c.uin, name)
        }
    }
    removeFor?.let { c ->
        Dialog(onDismissRequest = { removeFor = null }) {
            RetroPanel("Usuń kontakt", onClose = { removeFor = null }) {
                RetroLabel("Usunąć ${c.displayName} (${c.uin}) z listy razem z historią rozmów?")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    RetroButton("Anuluj", onClick = { removeFor = null })
                    RetroButton("Usuń", onClick = { app.manager.removeContact(c.uin); removeFor = null })
                }
            }
        }
    }
}

@Composable
private fun ContactRow(c: ContactEntity, icon: androidx.compose.ui.graphics.ImageBitmap?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Image(icon, null, Modifier.size(20.dp), filterQuality = FilterQuality.None)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                c.displayName + if (c.unread > 0) "  ✉ ${c.unread}" else "",
                style = retroText(16, if (c.unread > 0) GgColors.MyName else GgColors.Text, bold = c.unread > 0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!c.description.isNullOrBlank()) {
                BasicText(
                    c.description,
                    style = retroText(13, GgColors.Grey).copy(fontStyle = FontStyle.Italic),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun StatusDialog(current: Presence, description: String, onDismiss: () -> Unit, onConfirm: (Presence, String) -> Unit) {
    val skin = LocalContext.current.app.skin
    var selected by remember { mutableStateOf(current) }
    var desc by remember { mutableStateOf(description) }
    Dialog(onDismissRequest = onDismiss) {
        RetroPanel("Zmiana statusu", onClose = onDismiss) {
            for (p in Presence.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (p == selected) GgColors.TitleEnd else Color.Transparent)
                        .clickable { selected = p }
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    skin.statusIcon(p.code(desc.isNotBlank()))?.let { Image(it, null, Modifier.size(20.dp), filterQuality = FilterQuality.None) }
                    Spacer(Modifier.width(8.dp))
                    RetroLabel(presenceLabel(p), bold = p == selected)
                }
            }
            RetroLabel("Opis (${desc.length}/${GgStatus.MAX_DESCRIPTION}):")
            RetroField(desc, { desc = it.replace('\n', ' ').take(GgStatus.MAX_DESCRIPTION) }, singleLine = false)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                RetroButton("Bez opisu", onClick = { desc = "" })
                RetroButton("OK", onClick = { onConfirm(selected, desc.trim()) })
            }
        }
    }
}

@Composable
private fun ContactDialog(
    title: String,
    initialUin: String,
    initialName: String,
    uinEditable: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int, String) -> Unit,
) {
    var uin by remember { mutableStateOf(initialUin) }
    var name by remember { mutableStateOf(initialName) }
    Dialog(onDismissRequest = onDismiss) {
        RetroPanel(title, onClose = onDismiss) {
            RetroLabel("Numer GG:")
            if (uinEditable) RetroField(uin, { uin = it.filter(Char::isDigit).take(8) }, number = true) else RetroLabel(uin, bold = true)
            RetroLabel("Nazwa:")
            RetroField(name, { name = it.take(40) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                RetroButton("Anuluj", onClick = onDismiss)
                RetroButton("OK", onClick = {
                    val n = uin.toIntOrNull()
                    if (n != null && n in 1..0xffffff) onConfirm(n, name.trim())
                })
            }
        }
    }
}
