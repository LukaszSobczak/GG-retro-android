package pl.ggretro.android.ui.share

/** Plain helpers for shared content (no Android types, unit-tested). */
object ShareText {
    /** At most this many images per share: each is a separate GG image fetched from us later. */
    const val MAX_IMAGES = 10

    /** Joins EXTRA_SUBJECT and EXTRA_TEXT (e.g. page title + link), skipping duplicates and blanks. */
    fun combine(subject: String?, text: String?): String? {
        val s = subject?.trim().orEmpty()
        val t = text?.trim().orEmpty()
        return when {
            s.isEmpty() && t.isEmpty() -> null
            s.isEmpty() -> t
            t.isEmpty() -> s
            t.contains(s) -> t
            else -> "$s\n$t"
        }
    }

    /** First [MAX_IMAGES] items (distinct) and how many were dropped. */
    fun <T> limit(items: List<T>, max: Int = MAX_IMAGES): Pair<List<T>, Int> {
        val distinct = items.distinct()
        return distinct.take(max) to (distinct.size - max).coerceAtLeast(0)
    }

    /** "1 obrazek", "3 obrazki", "5 obrazków", "22 obrazki". */
    fun images(count: Int): String {
        val word = when {
            count == 1 -> "obrazek"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "obrazki"
            else -> "obrazków"
        }
        return "$count $word"
    }
}
