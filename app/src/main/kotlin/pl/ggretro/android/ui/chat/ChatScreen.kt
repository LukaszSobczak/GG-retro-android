package pl.ggretro.android.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import pl.ggretro.android.app
import pl.ggretro.android.data.displayName
import pl.ggretro.android.data.Ack
import pl.ggretro.android.data.AppSettings
import pl.ggretro.android.data.FormatCodec
import pl.ggretro.android.data.ImageState
import pl.ggretro.android.data.MessageEntity
import pl.ggretro.android.ui.emots.Emoticons
import pl.ggretro.android.ui.theme.GgColors
import pl.ggretro.android.ui.theme.OrangeBar
import pl.ggretro.android.ui.theme.RetroButton
import pl.ggretro.android.ui.theme.RetroIconButton
import pl.ggretro.android.ui.theme.RetroPanel
import pl.ggretro.android.ui.theme.RetroTitleBar
import pl.ggretro.android.ui.theme.SunkenBox
import pl.ggretro.android.ui.theme.retroText
import pl.ggretro.protocol.AckStatus
import pl.ggretro.protocol.GgStatus
import pl.ggretro.protocol.StyledText
import pl.ggretro.protocol.TextFormat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The GG 6 chat window: history, formatting toolbar, input and the bottom button row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(uin: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val manager = app.manager
    val contact by app.db.contacts().observe(uin).collectAsStateWithLifecycle(initialValue = null)
    val messages by app.db.messages().observeConversation(uin).collectAsStateWithLifecycle(initialValue = emptyList())
    val imageRows by app.db.images().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val emoticons = app.emoticons
    val skin = app.skin

    // Draft and formatting survive rotation.
    // TextFieldState keeps the keyboard (IME) in sync: replacing a TextFieldValue while the keyboard
    // was still composing a word restored the old text after sending and broke deleting.
    val input = rememberTextFieldState()
    var bold by rememberSaveable { mutableStateOf(false) }
    var italic by rememberSaveable { mutableStateOf(false) }
    var underline by rememberSaveable { mutableStateOf(false) }
    var color by rememberSaveable { mutableStateOf<Int?>(null) }
    var showEmots by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var enterSendsOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val enterSends = enterSendsOverride ?: settings.enterSends

    // Mark this chat as visible (no notifications for it) while the screen is resumed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(uin, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    manager.activeChat = uin
                    manager.openedChat(uin)
                }
                Lifecycle.Event.ON_PAUSE -> if (manager.activeChat == uin) manager.activeChat = null
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (manager.activeChat == uin) manager.activeChat = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) manager.sendImage(uin, uri)
    }

    fun send() {
        val text = input.text.toString().trimEnd()
        if (text.isBlank()) return
        val f = TextFormat(0, bold = bold, italic = italic, underline = underline, color = color)
        val formats = if (f.fontByte() != 0) listOf(f) else emptyList()
        manager.sendText(uin, StyledText(text, formats))
        input.clearText()
    }

    fun insertAtCursor(code: String) {
        input.edit {
            val start = selection.min
            replace(start, selection.max, code)
            selectCharsIn(TextRange(start + code.length))
        }
    }

    BackHandler(onBack = onClose)

    val name = contact?.displayName ?: uin.toString()
    val status = contact?.status ?: GgStatus.NOT_AVAIL
    Column(Modifier.fillMaxSize().background(GgColors.Orange)) {
        RetroTitleBar("$name ($uin)", icon = skin.statusIcon(status), onClose = onClose)

        // Top white strip: the contact's description, like the info area of the GG window.
        SunkenBox(Modifier.fillMaxWidth().heightIn(min = 34.dp), color = Color.White) {
            BasicText(
                contact?.description?.takeIf { it.isNotBlank() } ?: statusText(status),
                style = retroText(13, GgColors.Grey).copy(fontStyle = FontStyle.Italic),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }

        // History
        val listState = rememberLazyListState()
        // Reverse layout keeps the newest message pinned to the bottom, also when the keyboard
        // opens or an image finishes loading.
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.scrollToItem(0)
        }
        val images = remember(imageRows) { imageRows.associateBy { it.imageKey } }
        SunkenBox(Modifier.weight(1f).fillMaxWidth().padding(top = 3.dp)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(6.dp),
                reverseLayout = true,
            ) {
                items(messages.asReversed(), key = { it.id }) { m ->
                    MessageItem(
                        m = m,
                        name = if (m.outgoing) "Ja" else name,
                        emoticons = emoticons,
                        emoticonSet = settings.emoticonSet,
                        imageFor = { id ->
                            val row = images[id.key]
                            val ready = app.images.has(id)
                            InlineImage(
                                id,
                                if (ready) ImageState.READY else row?.state ?: ImageState.UNAVAILABLE,
                                if (ready) app.images.file(id) else null,
                            )
                        },
                        onImageClick = { img ->
                            when {
                                img.state == ImageState.READY && img.file != null -> viewing = img.file
                                !m.outgoing -> manager.retryImage(uin, img.id)
                            }
                        },
                    )
                }
            }
        }

        // Formatting toolbar: B I U colour emoticons image
        OrangeBar {
            RetroIconButton(skin.bold, "B", { bold = !bold }, toggled = bold)
            RetroIconButton(skin.italic, "I", { italic = !italic }, toggled = italic)
            RetroIconButton(skin.underline, "U", { underline = !underline }, toggled = underline)
            RetroIconButton(skin.color, "K", { showColors = true }, toggled = color != null)
            RetroIconButton(skin.emoticon, ":)", { showEmots = true })
            RetroIconButton(skin.image, "Obr", {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
        }

        // Input
        val inputStyle = retroText(15, color?.let { Color(0xff000000.toInt() or it) } ?: GgColors.Text).copy(
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
        )
        SunkenBox(Modifier.fillMaxWidth().height(110.dp)) {
            BasicTextField(
                state = input,
                textStyle = inputStyle,
                cursorBrush = SolidColor(Color.Black),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = if (enterSends) ImeAction.Send else ImeAction.Default,
                ),
                // Keyboard's action key: send, or a new line when "↵" is off.
                onKeyboardAction = { performDefaultAction -> if (enterSends) send() else performDefaultAction() },
                lineLimits = TextFieldLineLimits.MultiLine(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    // Hardware keyboard: Enter sends (Shift+Enter = new line) when "↵" is on.
                    .onPreviewKeyEvent { e ->
                        if (enterSends && e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                            send()
                            true
                        } else {
                            false
                        }
                    },
            )
        }

        // Bottom buttons, as in GG 6: Wyślij, ↵, Menu, Wyczyść ... Zamknij
        OrangeBar {
            RetroButton("Wyślij", onClick = { send() })
            RetroButton("↵", onClick = { enterSendsOverride = !enterSends }, pressed = enterSends, minWidth = 30.dp)
            Box {
                RetroButton("Menu", onClick = { menuOpen = true }, minWidth = 60.dp)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Usuń historię rozmowy") }, onClick = {
                        menuOpen = false
                        manager.clearHistory(uin)
                    })
                }
            }
            RetroButton("Wyczyść", onClick = { input.clearText() }, minWidth = 60.dp)
            Spacer(Modifier.weight(1f))
            RetroButton("Zamknij", onClick = onClose, minWidth = 60.dp)
        }
    }

    if (showEmots) {
        EmoticonPicker(emoticons, settings.emoticonSet, onDismiss = { showEmots = false }) { code ->
            showEmots = false
            insertAtCursor(code)
        }
    }
    if (showColors) {
        ColorPicker(onDismiss = { showColors = false }) { c ->
            showColors = false
            color = c
        }
    }
    viewing?.let { file ->
        Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { viewing = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(model = file, contentDescription = "obrazek", modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun statusText(status: Int): String = when (GgStatus.base(status)) {
    GgStatus.AVAIL, GgStatus.AVAIL_DESCR -> "Dostępny"
    GgStatus.BUSY, GgStatus.BUSY_DESCR -> "Zaraz wracam"
    GgStatus.INVISIBLE, GgStatus.INVISIBLE_DESCR -> "Niewidoczny"
    GgStatus.BLOCKED -> "Zablokowany"
    else -> "Niedostępny"
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("pl"))
private val dateTimeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("pl"))

private fun formatTime(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return (if (sameDay) timeFormat else dateTimeFormat).format(Date(millis))
}

private fun ackText(ack: Int): String = when (ack) {
    // gg-retro answers "queued" for every accepted message (GG 6 has no delivery receipts).
    Ack.NONE, AckStatus.DELIVERED, AckStatus.QUEUED -> ""
    Ack.PENDING -> "  …"
    Ack.FAILED -> "  – nie wysłano"
    AckStatus.BLOCKED -> "  – zablokowana"
    AckStatus.MBOX_FULL -> "  – skrzynka odbiorcy pełna"
    AckStatus.NOT_DELIVERED -> "  – nie dostarczono"
    else -> ""
}

@Composable
private fun MessageItem(
    m: MessageEntity,
    name: String,
    emoticons: Emoticons,
    emoticonSet: Int,
    imageFor: (pl.ggretro.protocol.ImageId) -> InlineImage,
    onImageClick: (InlineImage) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        BasicText(
            "$name (${formatTime(m.time)})" + ackText(m.ack),
            style = retroText(13, if (m.outgoing) GgColors.MyName else GgColors.ContactName, bold = true),
        )
        Box(Modifier.padding(start = 10.dp, top = 1.dp)) {
            MessageText(
                text = m.text,
                formats = remember(m.formats) { FormatCodec.decode(m.formats) },
                emoticons = emoticons,
                emoticonSet = emoticonSet,
                imageFor = imageFor,
                onImageClick = onImageClick,
            )
        }
    }
}

@Composable
private fun EmoticonPicker(emoticons: Emoticons, initialSet: Int, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var set by remember { mutableStateOf(initialSet) }
    Dialog(onDismissRequest = onDismiss) {
        RetroPanel("Emotikony", Modifier.heightIn(max = 520.dp), onClose = onDismiss) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in emoticons.sets.keys) RetroButton("Zestaw $s", onClick = { set = s }, pressed = s == set)
            }
            val list = emoticons.sets[set].orEmpty().filter { !it.hidden }
            SunkenBox(Modifier.fillMaxWidth().heightIn(max = 400.dp), color = Color.White) {
                LazyVerticalGrid(columns = GridCells.Adaptive(52.dp), contentPadding = PaddingValues(4.dp)) {
                    items(list) { e ->
                        Box(
                            Modifier
                                .size(52.dp)
                                .clickable { onPick(e.codes.first()) }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(model = e.previewUri, contentDescription = e.codes.first(), modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

/** The 16-colour palette of the GG 6 colour button. */
private val GG_COLORS = listOf(
    0x000000, 0x808080, 0x800000, 0xFF0000, 0x808000, 0xFFFF00, 0x008000, 0x00FF00,
    0x008080, 0x00FFFF, 0x000080, 0x0000FF, 0x800080, 0xFF00FF, 0xFF8000, 0xC0C0C0,
)

@Composable
private fun ColorPicker(onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        RetroPanel("Kolor tekstu", onClose = onDismiss) {
            LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.heightIn(max = 240.dp)) {
                items(GG_COLORS) { c ->
                    Box(
                        Modifier
                            .padding(4.dp)
                            .aspectRatio(1f)
                            .background(Color(0xff000000.toInt() or c))
                            .clickable { onPick(if (c == 0) null else c) },
                    )
                }
            }
            RetroButton("Domyślny", onClick = { onPick(null) })
        }
    }
}
