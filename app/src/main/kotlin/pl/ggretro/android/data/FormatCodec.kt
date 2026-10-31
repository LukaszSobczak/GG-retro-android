package pl.ggretro.android.data

import pl.ggretro.protocol.ImageId
import pl.ggretro.protocol.TextFormat

/** Compact text serialization of rich-text formats for the database: "pos,font,color,size,crc;...". */
object FormatCodec {
    fun encode(formats: List<TextFormat>): String = formats.joinToString(";") { f ->
        listOf(
            f.position,
            f.fontByte(),
            f.color ?: -1,
            f.image?.size ?: 0,
            f.image?.crc32 ?: 0,
        ).joinToString(",")
    }

    fun decode(text: String): List<TextFormat> {
        if (text.isBlank()) return emptyList()
        return text.split(';').mapNotNull { part ->
            val p = part.split(',')
            if (p.size < 5) return@mapNotNull null
            val font = p[1].toIntOrNull() ?: return@mapNotNull null
            val color = p[2].toIntOrNull()?.takeIf { it >= 0 }
            val image = if (font and TextFormat.FONT_IMAGE != 0) {
                ImageId(p[3].toIntOrNull() ?: 0, p[4].toLongOrNull() ?: 0)
            } else null
            TextFormat(
                position = p[0].toIntOrNull() ?: 0,
                bold = font and TextFormat.FONT_BOLD != 0,
                italic = font and TextFormat.FONT_ITALIC != 0,
                underline = font and TextFormat.FONT_UNDERLINE != 0,
                color = color,
                image = image,
            )
        }
    }
}
