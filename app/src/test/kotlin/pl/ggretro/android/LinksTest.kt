package pl.ggretro.android

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.ggretro.android.ui.chat.Links

class LinksTest {
    private fun urls(text: String) = Links.find(text).map { it.url }
    private fun spans(text: String) = Links.find(text).map { text.substring(it.start, it.end) }

    @Test
    fun findsSchemeAndWwwLinks() {
        val text = "Home \\ Anthropic\nhttps://www.anthropic.com/"
        assertEquals(listOf("https://www.anthropic.com/"), spans(text))
        assertEquals(listOf("http://www.wp.pl"), urls("zobacz www.wp.pl"))
        assertEquals(listOf("HTTP://Example.com/a?b=1&c=%20#x"), urls("HTTP://Example.com/a?b=1&c=%20#x"))
        assertEquals(listOf("ftp://ftp.example.org/pub"), urls("ftp://ftp.example.org/pub"))
        assertEquals(listOf("https://a.pl", "http://www.b.pl"), urls("https://a.pl i www.b.pl"))
    }

    @Test
    fun linkRangeCoversTheUrl() {
        val l = Links.find("ab https://x.pl cd").single()
        assertEquals(3, l.start)
        assertEquals(15, l.end)
    }

    @Test
    fun trailingPunctuationIsNotPartOfTheLink() {
        assertEquals(listOf("https://x.pl/a"), spans("to https://x.pl/a."))
        assertEquals(listOf("https://x.pl/a"), spans("(https://x.pl/a)"))
        assertEquals(listOf("https://x.pl/a"), spans("https://x.pl/a, potem"))
        assertEquals(listOf("https://x.pl/a"), spans("https://x.pl/a?!"))
        // Balanced brackets stay (Wikipedia style).
        assertEquals(listOf("https://pl.wikipedia.org/wiki/Gadu-Gadu_(komunikator)"), spans("https://pl.wikipedia.org/wiki/Gadu-Gadu_(komunikator)"))
    }

    @Test
    fun stopsAtWhitespaceQuotesAndImagePlaceholder() {
        assertEquals(listOf("https://x.pl"), spans("https://x.pl reszta"))
        assertEquals(listOf("https://x.pl"), spans("\"https://x.pl\""))
        assertEquals(listOf("https://x.pl"), spans("<https://x.pl>"))
    }

    @Test
    fun noFalsePositives() {
        assertEquals(emptyList<String>(), spans("https:// www. awww.pl mail@www.x.pl :/ zwykły tekst"))
    }
}
