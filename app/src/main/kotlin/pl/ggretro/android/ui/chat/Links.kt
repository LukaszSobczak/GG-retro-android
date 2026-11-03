package pl.ggretro.android.ui.chat

/** A web link found in message text: [start] inclusive, [end] exclusive, [url] ready to open. */
data class TextLink(val start: Int, val end: Int, val url: String)

/** Finds web links (http://, https://, ftp://, www.) in plain message text. */
object Links {
    // Stops at whitespace (also GG's no-break-space image placeholder), quotes and angle brackets.
    private val CANDIDATE = Regex("""(?i)(?<![\p{L}\p{N}@])(?:(?:https?|ftp)://|www\.)[^\s <>"]+""")
    private const val TRAILING = ".,;:!?'*"
    private val CLOSING = mapOf(')' to '(', ']' to '[', '}' to '{')

    fun find(text: String): List<TextLink> = CANDIDATE.findAll(text).mapNotNull { m ->
        val end = m.range.first + trimmedLength(m.value)
        val raw = text.substring(m.range.first, end)
        if (!hasHost(raw)) return@mapNotNull null
        TextLink(m.range.first, end, if (raw.startsWith("www.", ignoreCase = true)) "http://$raw" else raw)
    }.toList()

    /** Drops sentence punctuation and unbalanced closing brackets at the end ("(see http://x.pl/a)."). */
    private fun trimmedLength(s: String): Int {
        var len = s.length
        while (len > 0) {
            val c = s[len - 1]
            val open = CLOSING[c]
            when {
                c in TRAILING -> len--
                open != null && s.take(len).count { it == c } > s.take(len).count { it == open } -> len--
                else -> break
            }
        }
        return len
    }

    /** Something after the scheme or "www." (rejects a bare "https://" or "www."). */
    private fun hasHost(s: String): Boolean {
        val rest = s.substringAfter("://", s.drop(4))
        return rest.isNotEmpty() && rest.first().isLetterOrDigit()
    }
}
