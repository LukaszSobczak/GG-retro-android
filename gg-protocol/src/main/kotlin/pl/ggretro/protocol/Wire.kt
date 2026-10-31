package pl.ggretro.protocol

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Raised when a packet coming from the server cannot be parsed. */
class GgProtocolException(message: String) : Exception(message)

/** Little-endian writer used to build packet payloads. */
class WireWriter {
    private val out = ByteArrayOutputStream()

    fun u8(value: Int) = apply { out.write(value and 0xff) }

    fun u16(value: Int) = apply {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    fun u32(value: Long) = u32(value.toInt())

    fun u32(value: Int) = apply {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }

    fun bytes(value: ByteArray) = apply { out.write(value) }

    fun size(): Int = out.size()

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Little-endian reader over a single packet payload. */
class WireReader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
    val remaining: Int get() = end - pos
    val position: Int get() = pos

    private fun need(n: Int) {
        if (remaining < n) throw GgProtocolException("truncated packet")
    }

    fun peek(): Int {
        need(1)
        return data[pos].toInt() and 0xff
    }

    fun peekAt(offset: Int): Int {
        need(offset + 1)
        return data[pos + offset].toInt() and 0xff
    }

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xff
    }

    fun u16(): Int {
        need(2)
        val v = (data[pos].toInt() and 0xff) or ((data[pos + 1].toInt() and 0xff) shl 8)
        pos += 2
        return v
    }

    fun u32(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = v or ((data[pos + i].toLong() and 0xff) shl (8 * i))
        pos += 4
        return v
    }

    fun i32(): Int = u32().toInt()

    fun bytes(n: Int): ByteArray {
        need(n)
        val r = data.copyOfRange(pos, pos + n)
        pos += n
        return r
    }

    fun rest(): ByteArray = bytes(remaining)

    fun skip(n: Int) {
        need(n)
        pos += n
    }

    /** Index of the next NUL byte relative to the current position, or -1. */
    fun indexOfNul(): Int {
        for (i in pos until end) if (data[i].toInt() == 0) return i - pos
        return -1
    }

    fun sub(n: Int): WireReader {
        need(n)
        val r = WireReader(data, pos, pos + n)
        pos += n
        return r
    }
}

/** GG 6 speaks Windows-1250. */
object Cp1250 {
    val charset: Charset = Charset.forName("windows-1250")

    fun encode(text: String): ByteArray = sanitize(text).toByteArray(charset)

    fun decode(bytes: ByteArray): String = String(bytes, charset)

    /**
     * Replaces every code point that does not exist in CP1250 with '?'. Afterwards each char of the
     * returned string maps to exactly one byte on the wire, so rich-text positions stay aligned.
     */
    fun sanitize(text: String): String {
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chars = Character.toChars(cp)
            if (chars.size == 1 && encoder.canEncode(chars[0])) sb.append(chars[0]) else sb.append('?')
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
