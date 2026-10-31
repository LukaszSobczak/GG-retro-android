package pl.ggretro.protocol

/** Identity of an inline GG 6 image: size in bytes + CRC32 of its contents. */
data class ImageId(val size: Int, val crc32: Long) {
    fun valid(): Boolean = size in 1..ImageTransfer.MAX_IMAGE_BYTES

    /** Stable key usable as a file name. */
    val key: String get() = "${size}_${java.lang.Long.toHexString(crc32)}"

    companion object {
        fun of(data: ByteArray): ImageId {
            val crc = java.util.zip.CRC32()
            crc.update(data)
            return ImageId(data.size, crc.value)
        }
    }
}

/**
 * One rich-text attribute entry. It applies from [position] (0-based character index) until the
 * next entry or the end of the text. [color] is 0xRRGGBB. When [image] is set, an inline image
 * is shown at [position].
 */
data class TextFormat(
    val position: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: Int? = null,
    val image: ImageId? = null,
) {
    fun fontByte(): Int {
        var b = 0
        if (bold) b = b or FONT_BOLD
        if (italic) b = b or FONT_ITALIC
        if (underline) b = b or FONT_UNDERLINE
        if (color != null) b = b or FONT_COLOR
        if (image != null) b = b or FONT_IMAGE
        return b
    }

    fun sameStyle(other: TextFormat): Boolean =
        bold == other.bold && italic == other.italic && underline == other.underline && color == other.color

    companion object {
        const val FONT_BOLD = 0x01
        const val FONT_ITALIC = 0x02
        const val FONT_UNDERLINE = 0x04
        const val FONT_COLOR = 0x08
        const val FONT_IMAGE = 0x80
    }
}

/** Message text + formatting, as shown to the user (newlines are '\n'). */
data class StyledText(val text: String, val formats: List<TextFormat> = emptyList()) {
    val images: List<ImageId> get() = formats.mapNotNull { it.image }
}

object RichText {
    /** Placeholder character GG 6 puts where an image sits in the text. */
    const val IMAGE_PLACEHOLDER = ' '

    /** Encodes the gg_msg_richtext block (flag 0x02 + length + entries). Empty list → empty array. */
    fun encode(formats: List<TextFormat>): ByteArray {
        if (formats.isEmpty()) return ByteArray(0)
        val body = WireWriter()
        for (f in formats) {
            body.u16(f.position)
            body.u8(f.fontByte())
            f.color?.let { c ->
                body.u8((c shr 16) and 0xff)
                body.u8((c shr 8) and 0xff)
                body.u8(c and 0xff)
            }
            f.image?.let { img ->
                body.u8(9)
                body.u8(1)
                body.u32(img.size)
                body.u32(img.crc32)
            }
        }
        val bytes = body.toByteArray()
        return WireWriter().u8(0x02).u16(bytes.size).bytes(bytes).toByteArray()
    }

    /** Decodes entries of a rich text block whose 3-byte header has already been consumed. */
    fun decodeEntries(r: WireReader): List<TextFormat> {
        val out = ArrayList<TextFormat>()
        while (r.remaining > 0) {
            if (r.remaining < 3) throw GgProtocolException("bad rich text entry")
            val position = r.u16()
            val font = r.u8()
            val color = if (font and TextFormat.FONT_COLOR != 0) {
                (r.u8() shl 16) or (r.u8() shl 8) or r.u8()
            } else null
            val image = if (font and TextFormat.FONT_IMAGE != 0) {
                if (r.remaining < 10) throw GgProtocolException("bad image descriptor")
                r.skip(2) // 0x09 0x01
                ImageId(r.u32().toInt(), r.u32())
            } else null
            out += TextFormat(
                position = position,
                bold = font and TextFormat.FONT_BOLD != 0,
                italic = font and TextFormat.FONT_ITALIC != 0,
                underline = font and TextFormat.FONT_UNDERLINE != 0,
                color = color,
                image = image,
            )
        }
        return out
    }

    /**
     * Converts user text ('\n' line breaks, any Unicode) to the wire form: CP1250-safe characters
     * and "\r\n" line breaks, shifting format positions accordingly.
     */
    fun toWire(styled: StyledText): StyledText {
        val clean = Cp1250.sanitize(styled.text.replace("\r\n", "\n"))
        val sb = StringBuilder(clean.length + 8)
        val shift = IntArray(clean.length + 1)
        var added = 0
        for (i in clean.indices) {
            shift[i] = added
            if (clean[i] == '\n') {
                sb.append('\r')
                added++
            }
            sb.append(clean[i])
        }
        shift[clean.length] = added
        val formats = styled.formats.map {
            val p = it.position.coerceIn(0, clean.length)
            it.copy(position = p + shift[p])
        }
        return StyledText(sb.toString(), normalize(formats))
    }

    /** Converts wire text ("\r\n" line breaks) to display text ('\n'), shifting positions. */
    fun fromWire(styled: StyledText): StyledText {
        val src = styled.text
        val sb = StringBuilder(src.length)
        val map = IntArray(src.length + 1)
        for (i in src.indices) {
            map[i] = sb.length
            val c = src[i]
            if (c == '\r') {
                if (i + 1 < src.length && src[i + 1] == '\n') continue
                sb.append('\n')
            } else {
                sb.append(c)
            }
        }
        map[src.length] = sb.length
        val formats = styled.formats.map { it.copy(position = map[it.position.coerceIn(0, src.length)]) }
        return StyledText(sb.toString(), formats)
    }

    /** Sorts entries and drops redundant style-only entries that change nothing. */
    fun normalize(formats: List<TextFormat>): List<TextFormat> {
        val sorted = formats.sortedWith(compareBy<TextFormat> { it.position }.thenBy { if (it.image == null) 0 else 1 })
        val out = ArrayList<TextFormat>()
        for (f in sorted) {
            val last = out.lastOrNull()
            if (f.image == null && last != null && last.image == null && last.position == f.position) {
                out[out.size - 1] = f
                continue
            }
            if (f.image == null && last != null && last.sameStyle(f)) continue
            if (f.image == null && last == null && f.fontByte() == 0) continue
            out += f
        }
        return out
    }
}
