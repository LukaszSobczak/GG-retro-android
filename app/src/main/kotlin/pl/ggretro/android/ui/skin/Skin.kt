package pl.ggretro.android.ui.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import pl.ggretro.protocol.GgStatus

/**
 * Bitmaps of the original GG 6.1 "classic" skin (extracted from the installer at build time).
 * Magenta (#FF00FF) is the transparency key.
 */
class Skin private constructor(
    private val statusIcons: List<ImageBitmap>,
    val bold: ImageBitmap?,
    val italic: ImageBitmap?,
    val underline: ImageBitmap?,
    val color: ImageBitmap?,
    val emoticon: ImageBitmap?,
    val image: ImageBitmap?,
) {
    /** 16×16 icon for a GG status code (null when the skin could not be loaded). */
    fun statusIcon(status: Int): ImageBitmap? {
        val index = when (GgStatus.base(status)) {
            GgStatus.AVAIL -> 0
            GgStatus.NOT_AVAIL -> 1
            GgStatus.BUSY -> 2
            GgStatus.AVAIL_DESCR -> 7
            GgStatus.NOT_AVAIL_DESCR -> 8
            GgStatus.BLOCKED -> 9
            GgStatus.BUSY_DESCR -> 10
            GgStatus.INVISIBLE -> 11
            GgStatus.INVISIBLE_DESCR -> 12
            else -> 6
        }
        return statusIcons.getOrNull(index)
    }

    companion object {
        fun load(context: Context): Skin {
            fun bmp(name: String): Bitmap? = runCatching {
                context.assets.open("gg/skin/$name").use { BitmapFactory.decodeStream(it) }?.let(::keyed)
            }.getOrNull()

            val strip = bmp("status.bmp")
            val icons = if (strip != null) {
                (0 until strip.width / 16).map { i -> Bitmap.createBitmap(strip, i * 16, 0, 16, minOf(16, strip.height)).asImageBitmap() }
            } else emptyList()
            return Skin(
                statusIcons = icons,
                bold = bmp("bold.bmp")?.asImageBitmap(),
                italic = bmp("italic.bmp")?.asImageBitmap(),
                underline = bmp("underl.bmp")?.asImageBitmap(),
                color = bmp("color.bmp")?.asImageBitmap(),
                emoticon = bmp("emot.bmp")?.asImageBitmap(),
                image = bmp("graph.bmp")?.asImageBitmap(),
            )
        }

        private fun keyed(src: Bitmap): Bitmap {
            val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            for (i in pixels.indices) if (pixels[i] and 0xffffff == 0xff00ff) pixels[i] = 0
            bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return bmp
        }
    }
}
