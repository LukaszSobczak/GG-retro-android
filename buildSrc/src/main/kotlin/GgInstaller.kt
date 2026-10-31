import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Reads files out of the original Gadu-Gadu 6.1 installer (`gg61.exe`, NSIS 2.0rc1, solid LZMA).
 *
 * Layout: after the NSIS "first header" comes one LZMA stream containing
 * `[u32 header size][header][u32 size + data]...`. The header holds a block table; block 2 lists
 * script entries (7 ints each) and block 3 is the string table. Entry opcode 11 with parm1 == 1 is
 * SetOutPath, opcode 20 is ExtractFile (parm1 = file name string, parm2 = data offset).
 */
object GgInstaller {
    private val SIGNATURE = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0xAD.toByte(), 0xDE.toByte()) +
        "NullsoftInst".toByteArray(Charsets.US_ASCII)
    private const val OP_SET_OUT_PATH = 11
    private const val OP_EXTRACT_FILE = 20
    private const val NS_VAR_CODE = 252
    private val CP1250 = charset("windows-1250")

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** Returns every installed file as "dir/sub/name" (lower-case, '/' separators) → bytes. */
    fun extract(exe: ByteArray): Map<String, ByteArray> {
        val sigAt = indexOf(exe, SIGNATURE)
        require(sigAt >= 4) { "Not an NSIS installer" }
        val first = sigAt - 4
        val props = exe[first + 28].toInt() and 0xff
        require(props == 0x5d) { "Unsupported LZMA properties: $props" }
        val dictSize = le(exe, first + 29)
        val stream = LZMAInputStream(
            ByteArrayInputStream(exe, first + 33, exe.size - first - 33), -1L, props.toByte(), dictSize,
        ).use { it.readBytes() }

        val headerSize = le(stream, 0)
        val header = stream.copyOfRange(4, 4 + headerSize)
        val data = stream.copyOfRange(4 + headerSize, stream.size)
        val entriesOffset = le(header, 4 + 2 * 8)
        val entriesCount = le(header, 4 + 2 * 8 + 4)
        val stringsOffset = le(header, 4 + 3 * 8)

        fun rawString(offset: Int): ByteArray {
            val start = stringsOffset + offset
            var end = start
            while (header[end].toInt() != 0) end++
            return header.copyOfRange(start, end)
        }

        val files = LinkedHashMap<String, ByteArray>()
        var dir = ""
        for (i in 0 until entriesCount) {
            val base = entriesOffset + i * 28
            val op = le(header, base)
            val p = IntArray(6) { le(header, base + 4 + it * 4) }
            when {
                op == OP_SET_OUT_PATH && p[1] == 1 -> {
                    var raw = rawString(p[0])
                    if (raw.isNotEmpty() && (raw[0].toInt() and 0xff) == NS_VAR_CODE) raw = raw.copyOfRange(3, raw.size)
                    dir = String(raw, CP1250).trim('\\').replace('\\', '/').lowercase()
                }
                op == OP_EXTRACT_FILE -> {
                    val raw = rawString(p[1])
                    val name = String(raw, CP1250).substringAfterLast('\\').lowercase()
                    if (name.isEmpty() || name.any { it.code < 32 || it.code == NS_VAR_CODE }) continue
                    val offset = p[2]
                    if (offset < 0 || offset + 4 > data.size) continue
                    val size = le(data, offset)
                    require(size >= 0) { "Compressed entries are not supported ($name)" }
                    val path = if (dir.isEmpty()) name else "$dir/$name"
                    files[path] = data.copyOfRange(offset + 4, offset + 4 + size)
                }
            }
        }
        return files
    }

    private fun le(b: ByteArray, at: Int): Int = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
