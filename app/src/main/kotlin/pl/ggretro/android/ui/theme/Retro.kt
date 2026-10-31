package pl.ggretro.android.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText

/** Colours of the GG 6 chat window (see the reference screenshot). */
object GgColors {
    val Orange = Color(0xFFFF8040)
    val OrangeDark = Color(0xFFE0662A)
    val Yellow = Color(0xFFFFFF99)
    val Face = Color(0xFFD4D0C8) // Windows classic button face
    val Light = Color(0xFFFFFFFF)
    val Shadow = Color(0xFF808080)
    val DarkShadow = Color(0xFF404040)
    val TitleStart = Color(0xFF0A246A)
    val TitleEnd = Color(0xFFA6CAF0)
    val Text = Color(0xFF000000)
    val Grey = Color(0xFF606060)
    val ContactName = Color(0xFF000080)
    val MyName = Color(0xFF800000)
}

val RetroFont = FontFamily.SansSerif

fun retroText(size: Int = 14, color: Color = GgColors.Text, bold: Boolean = false) =
    TextStyle(fontFamily = RetroFont, fontSize = size.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

/** Windows-classic 3D border: raised (buttons, panels) or sunken (text areas). */
fun Modifier.bevel(raised: Boolean = true, width: Dp = 1.5.dp): Modifier = drawBehind {
    val w = width.toPx()
    val tl = if (raised) GgColors.Light else GgColors.Shadow
    val br = if (raised) GgColors.DarkShadow else GgColors.Light
    drawLine(tl, Offset(0f, w / 2), Offset(size.width, w / 2), w)
    drawLine(tl, Offset(w / 2, 0f), Offset(w / 2, size.height), w)
    drawLine(br, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
    drawLine(br, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
}

@Composable
fun RetroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressed: Boolean = false,
    minWidth: Dp = 72.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val down = isPressed || pressed
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minWidth, minHeight = 30.dp)
            .background(GgColors.Face)
            .bevel(raised = !down)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = retroText(14, if (enabled) GgColors.Text else GgColors.Shadow),
            modifier = if (down) Modifier.padding(start = 1.dp, top = 1.dp) else Modifier,
            maxLines = 1,
        )
    }
}

/** Small square toolbar button showing a skin bitmap (B, I, U, colours, emoticons, image). */
@Composable
fun RetroIconButton(
    bitmap: ImageBitmap?,
    fallback: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    toggled: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val down = isPressed || toggled
    Box(
        modifier = modifier
            .size(32.dp)
            .then(if (down) Modifier.background(GgColors.OrangeDark).bevel(raised = false) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = fallback, modifier = Modifier.size(24.dp), filterQuality = FilterQuality.None)
        } else {
            BasicText(fallback, style = retroText(15, bold = true))
        }
    }
}

/** Classic title bar: dark blue → light blue gradient, white bold caption. */
@Composable
fun RetroTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageBitmap? = null,
    onClose: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(GgColors.TitleStart, GgColors.TitleEnd)))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(18.dp), filterQuality = FilterQuality.None)
            Spacer(Modifier.width(6.dp))
        }
        BasicText(
            title,
            style = retroText(15, Color.White, bold = true),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onClose != null) {
            Box(
                Modifier
                    .size(22.dp)
                    .background(GgColors.Face)
                    .bevel()
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { BasicText("✕", style = retroText(12, bold = true)) }
        }
    }
}

/** Sunken white/yellow area, like the GG text boxes. */
@Composable
fun SunkenBox(
    modifier: Modifier = Modifier,
    color: Color = GgColors.Yellow,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.background(color).bevel(raised = false, width = 2.dp).padding(2.dp), content = content)
}

/** Grey dialog-like panel used for login, settings and dialogs. */
@Composable
fun RetroPanel(
    title: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.background(GgColors.Face).bevel().padding(2.dp)) {
        RetroTitleBar(title, onClose = onClose)
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun RetroLabel(text: String, modifier: Modifier = Modifier, bold: Boolean = false, color: Color = GgColors.Text, size: Int = 14) {
    BasicText(text, style = retroText(size, color, bold), modifier = modifier)
}

@Composable
fun RetroField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    number: Boolean = false,
    singleLine: Boolean = true,
    background: Color = Color.White,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = retroText(15),
        cursorBrush = SolidColor(Color.Black),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                password -> KeyboardType.Password
                number -> KeyboardType.Number
                else -> KeyboardType.Text
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .bevel(raised = false, width = 2.dp)
            .padding(PaddingValues(horizontal = 6.dp, vertical = 7.dp)),
    )
}

@Composable
fun RetroCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .background(Color.White)
                .bevel(raised = false),
            contentAlignment = Alignment.Center,
        ) { if (checked) BasicText("✓", style = retroText(14, bold = true)) }
        Spacer(Modifier.width(8.dp))
        BasicText(label, style = retroText(14))
    }
}

@Composable
fun OrangeBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .background(GgColors.Orange)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))
