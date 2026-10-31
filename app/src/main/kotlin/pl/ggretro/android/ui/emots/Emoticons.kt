package pl.ggretro.android.ui.emots

import android.content.Context

/** One emoticon from the original GG 6.1 emots.txt. */
data class Emoticon(
    val codes: List<String>,
    /** Asset path of the animated GIF. */
    val animated: String,
    /** Asset path of the static preview (picker), falls back to [animated]. */
    val preview: String,
    val width: Int,
    val height: Int,
    /** Lines starting with '*' are recognised in text but not shown in the picker. */
    val hidden: Boolean,
) {
    val uri: String get() = "file:///android_asset/$animated"
    val previewUri: String get() = "file:///android_asset/$preview"
}

data class EmoticonMatch(val start: Int, val end: Int, val emoticon: Emoticon)

/** Both GG 6.1 emoticon sets plus a matcher used to render codes like ":)" or "<serce>" in text. */
class Emoticons(val sets: Map<Int, List<Emoticon>>) {
    private var cachedSet = -1
    private var byFirstChar: Map<Char, List<Pair<String, Emoticon>>> = emptyMap()

    @Synchronized
    private fun index(primarySet: Int): Map<Char, List<Pair<String, Emoticon>>> {
        if (primarySet == cachedSet) return byFirstChar
        val codes = LinkedHashMap<String, Pair<String, Emoticon>>()
        val order = listOf(primarySet) + sets.keys.filter { it != primarySet }
        for (set in order) for (e in sets[set].orEmpty()) for (code in e.codes) {
            if (code.isEmpty()) continue
            codes.putIfAbsent(code.lowercase(), code to e)
        }
        byFirstChar = codes.values
            .groupBy { it.first.first().lowercaseChar() }
            .mapValues { (_, list) -> list.sortedByDescending { it.first.length } }
        cachedSet = primarySet
        return byFirstChar
    }

    /** Finds emoticon codes in [text] (case-insensitive, longest match wins). */
    fun find(text: String, primarySet: Int = 1): List<EmoticonMatch> {
        val idx = index(primarySet)
        if (idx.isEmpty()) return emptyList()
        val out = ArrayList<EmoticonMatch>()
        var i = 0
        while (i < text.length) {
            val candidates = idx[text[i].lowercaseChar()]
            val hit = candidates?.firstOrNull { (code, _) -> text.regionMatches(i, code, 0, code.length, ignoreCase = true) }
            if (hit != null) {
                out += EmoticonMatch(i, i + hit.first.length, hit.second)
                i += hit.first.length
            } else {
                i++
            }
        }
        return out
    }

    companion object {
        fun load(context: Context): Emoticons {
            val sets = LinkedHashMap<Int, List<Emoticon>>()
            for (set in 1..2) {
                val dir = "gg/emots/$set"
                val text = runCatching {
                    context.assets.open("$dir/emots.txt").use { String(it.readBytes(), charset("windows-1250")) }
                }.getOrNull() ?: continue
                sets[set] = parse(text).mapNotNull { line ->
                    val anim = line.files.firstOrNull()?.lowercase() ?: return@mapNotNull null
                    val preview = line.files.getOrNull(1)?.lowercase() ?: anim
                    val (w, h) = gifSize(context, "$dir/$anim") ?: return@mapNotNull null
                    Emoticon(line.codes, "$dir/$anim", "$dir/$preview", w, h, line.hidden)
                }
            }
            return Emoticons(sets)
        }

        private fun gifSize(context: Context, path: String): Pair<Int, Int>? = runCatching {
            context.assets.open(path).use { input ->
                val h = ByteArray(10)
                if (input.read(h) < 10) return@use null
                val w = (h[6].toInt() and 0xff) or ((h[7].toInt() and 0xff) shl 8)
                val hh = (h[8].toInt() and 0xff) or ((h[9].toInt() and 0xff) shl 8)
                w to hh
            }
        }.getOrNull()

        data class Line(val codes: List<String>, val files: List<String>, val hidden: Boolean)

        /**
         * Parses emots.txt lines such as
         * `(":)",":))"),"wesoly.gif","wesoly_na.gif"`, `"<lol>","lol.gif"` or `*"<sex>","sex.gif"`.
         */
        fun parse(text: String): List<Line> = text.lines().mapNotNull { raw ->
            var line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val hidden = line.startsWith("*")
            if (hidden) line = line.substring(1).trim()
            val grouped = ArrayList<String>()
            val loose = ArrayList<String>()
            var depth = 0
            var i = 0
            while (i < line.length) {
                when (line[i]) {
                    '(' -> depth++
                    ')' -> depth--
                    '"' -> {
                        val end = line.indexOf('"', i + 1)
                        if (end < 0) break
                        val s = line.substring(i + 1, end)
                        if (depth > 0) grouped += s else loose += s
                        i = end
                    }
                    else -> {}
                }
                i++
            }
            val codes: List<String>
            val files: List<String>
            if (grouped.isNotEmpty()) {
                codes = grouped
                files = loose
            } else {
                if (loose.size < 2) return@mapNotNull null
                codes = listOf(loose[0])
                files = loose.drop(1)
            }
            if (files.isEmpty()) null else Line(codes, files, hidden)
        }
    }
}
