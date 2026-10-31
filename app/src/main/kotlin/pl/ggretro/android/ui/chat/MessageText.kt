package pl.ggretro.android.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import pl.ggretro.android.data.ImageState
import pl.ggretro.android.ui.emots.Emoticon
import pl.ggretro.android.ui.emots.Emoticons
import pl.ggretro.android.ui.theme.GgColors
import pl.ggretro.android.ui.theme.retroText
import pl.ggretro.protocol.ImageId
import pl.ggretro.protocol.RichText
import pl.ggretro.protocol.TextFormat
import java.io.File

/** What the UI knows about an inline image. */
data class InlineImage(val id: ImageId, val state: Int, val file: File?)

/** Scale applied to the tiny original emoticon GIFs so they are readable on phones. */
private const val EMOT_SCALE = 1.4f

private fun spanFor(f: TextFormat?): SpanStyle = SpanStyle(
    fontWeight = if (f?.bold == true) FontWeight.Bold else null,
    fontStyle = if (f?.italic == true) FontStyle.Italic else null,
    textDecoration = if (f?.underline == true) TextDecoration.Underline else null,
    color = f?.color?.let { Color(0xff000000.toInt() or it) } ?: Color.Unspecified,
)

/**
 * Renders a GG message: rich-text attributes, original emoticons (animated GIFs) and inline images.
 */
@Composable
fun MessageText(
    text: String,
    formats: List<TextFormat>,
    emoticons: Emoticons,
    emoticonSet: Int,
    imageFor: (ImageId) -> InlineImage,
    onImageClick: (InlineImage) -> Unit,
    style: TextStyle = retroText(15),
) {
    val density = LocalDensity.current
    val sorted = formats.sortedBy { it.position }
    val styleRuns = sorted // each entry applies from its position to the next entry
    val imagesAt = sorted.filter { it.image != null }.groupBy { it.position.coerceIn(0, text.length) }
    val emots = emoticons.find(text, emoticonSet).associateBy { it.start }
    val inline = HashMap<String, InlineTextContent>()

    fun formatAt(i: Int): TextFormat? = styleRuns.lastOrNull { it.position <= i }

    val annotated: AnnotatedString = buildAnnotatedString {
        var i = 0
        val run = StringBuilder()
        var runStyle: SpanStyle? = null
        fun flush() {
            if (run.isNotEmpty()) {
                val st = runStyle
                if (st != null) pushStyle(st)
                append(run.toString())
                if (st != null) pop()
                run.setLength(0)
            }
        }
        while (i <= text.length) {
            val entries = imagesAt[i]
            if (entries != null) {
                flush()
                for (e in entries) {
                    val img = imageFor(e.image!!)
                    val key = "img:${img.id.key}:$i"
                    inline[key] = imageContent(img, density, onImageClick)
                    appendInlineContent(key, "[obrazek]")
                }
                if (i < text.length && text[i] == RichText.IMAGE_PLACEHOLDER) {
                    // The image replaces GG's placeholder character.
                    i++
                    continue
                }
            }
            if (i == text.length) break
            val match = emots[i]
            if (match != null) {
                flush()
                val key = "emot:${match.emoticon.animated}"
                if (key !in inline) inline[key] = emotContent(match.emoticon, density)
                appendInlineContent(key, text.substring(match.start, match.end))
                i = match.end
                continue
            }
            val st = spanFor(formatAt(i))
            if (st != runStyle) {
                flush()
                runStyle = st
            }
            run.append(text[i])
            i++
        }
        flush()
    }
    BasicText(annotated, style = style, inlineContent = inline)
}

private fun emotContent(e: Emoticon, density: Density): InlineTextContent {
    val w: TextUnit
    val h: TextUnit
    with(density) {
        w = (e.width * EMOT_SCALE).dp.toSp()
        h = (e.height * EMOT_SCALE).dp.toSp()
    }
    return InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.TextCenter)) {
        AsyncImage(model = e.uri, contentDescription = e.codes.firstOrNull(), modifier = Modifier.fillMaxSize())
    }
}

private fun imageContent(img: InlineImage, density: Density, onClick: (InlineImage) -> Unit): InlineTextContent {
    val file = img.file
    val (wDp, hDp) = if (img.state == ImageState.READY && file != null) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        val w = o.outWidth.coerceAtLeast(1)
        val h = o.outHeight.coerceAtLeast(1)
        val scale = minOf(200f / w, 200f / h, 1.5f)
        (w * scale) to (h * scale)
    } else {
        150f to 40f
    }
    val (w, h) = with(density) { wDp.dp.toSp() to hDp.dp.toSp() }
    return InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.Bottom)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable { onClick(img) },
            contentAlignment = Alignment.Center,
        ) {
            if (img.state == ImageState.READY && file != null) {
                AsyncImage(model = file, contentDescription = "obrazek", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFFFFCC))
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        if (img.state == ImageState.UNAVAILABLE) "[obrazek niedostępny – dotknij]" else "[pobieranie obrazka…]",
                        style = retroText(12, GgColors.Grey),
                    )
                }
            }
        }
    }
}

@Composable
fun SkinImage(bitmap: androidx.compose.ui.graphics.ImageBitmap, modifier: Modifier = Modifier) {
    Image(bitmap, contentDescription = null, modifier = modifier, filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
}
