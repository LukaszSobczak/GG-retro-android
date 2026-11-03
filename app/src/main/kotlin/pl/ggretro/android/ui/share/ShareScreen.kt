package pl.ggretro.android.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.ggretro.android.app
import pl.ggretro.android.ui.contacts.ContactRow
import pl.ggretro.android.ui.theme.GgColors
import pl.ggretro.android.ui.theme.OrangeBar
import pl.ggretro.android.ui.theme.RetroButton
import pl.ggretro.android.ui.theme.RetroLabel
import pl.ggretro.android.ui.theme.RetroTitleBar
import pl.ggretro.android.ui.theme.SunkenBox
import pl.ggretro.android.ui.theme.retroText

/** "Udostępnij do…": pick the contact that gets content shared from another app. */
@Composable
fun ShareScreen(payload: SharePayload, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    val app = LocalContext.current.app
    val contacts by app.db.contacts().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val skin = app.skin
    BackHandler(onBack = onCancel)

    val preview = listOfNotNull(
        payload.text?.replace('\n', ' ')?.take(160),
        payload.images.size.takeIf { it > 0 }?.let { ShareText.images(it) },
    ).joinToString("  +  ")

    Column(Modifier.fillMaxSize()) {
        RetroTitleBar("Udostępnij do…", onClose = onCancel)
        SunkenBox(Modifier.fillMaxWidth().heightIn(min = 34.dp), color = Color.White) {
            BasicText(
                preview,
                style = retroText(13, GgColors.Grey),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        SunkenBox(Modifier.weight(1f).fillMaxWidth(), color = Color.White) {
            if (contacts.isEmpty()) {
                RetroLabel("Brak kontaktów.", Modifier.padding(12.dp), color = GgColors.Grey)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(contacts, key = { it.uin }) { c ->
                    ContactRow(c, skin.statusIcon(c.status), Modifier.clickable { onPick(c.uin) })
                }
            }
        }
        OrangeBar {
            Spacer(Modifier.weight(1f))
            RetroButton("Anuluj", onClick = onCancel)
        }
    }
}
