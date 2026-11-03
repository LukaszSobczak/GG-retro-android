package pl.ggretro.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.ggretro.android.ui.share.ShareText

class ShareTextTest {
    @Test
    fun combinesSubjectAndText() {
        assertEquals("Tytuł\nhttps://example.org", ShareText.combine("Tytuł", "https://example.org"))
        assertEquals("https://example.org", ShareText.combine(null, " https://example.org "))
        assertEquals("Sam temat", ShareText.combine("Sam temat", ""))
        // Subject already contained in the text: not repeated.
        assertEquals("Tytuł – https://example.org", ShareText.combine("Tytuł", "Tytuł – https://example.org"))
        assertNull(ShareText.combine("  ", null))
    }

    @Test
    fun limitsImages() {
        val (kept, dropped) = ShareText.limit((1..13).toList())
        assertEquals((1..10).toList(), kept)
        assertEquals(3, dropped)
        assertEquals(listOf(1, 2) to 0, ShareText.limit(listOf(1, 2, 2, 1)))
    }

    @Test
    fun polishPlural() {
        assertEquals("1 obrazek", ShareText.images(1))
        assertEquals("3 obrazki", ShareText.images(3))
        assertEquals("5 obrazków", ShareText.images(5))
        assertEquals("12 obrazków", ShareText.images(12))
        assertEquals("22 obrazki", ShareText.images(22))
    }
}
