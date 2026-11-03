package pl.ggretro.android

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.ggretro.android.data.EmojiConverter
import java.io.File

class EmojiConverterTest {
    private val converter = EmojiConverter(
        mapOf(
            "🦄" to "głowa jednorożca",
            "🍔" to "hamburger",
            "👨‍👩‍👧" to "rodzina: mężczyzna, kobieta, dziewczynka",
            "🇵🇱" to "flaga: Polska",
            "👋" to "machająca dłoń",
            "#⃣" to "klawisz: #",
        ),
    )

    @Test
    fun commonEmojiBecomeGgCodes() {
        assertEquals("hej :) co tam ;)", converter.convert("hej 🙂 co tam 😉"))
        assertEquals("<serce><serce>", converter.convert("❤️❤"))
        assertEquals("<ok>", converter.convert("👍🏽")) // skin tone ignored
        assertEquals("<lol>!", converter.convert("😂!"))
    }

    @Test
    fun otherEmojiBecomePolishNames() {
        assertEquals("(głowa jednorożca) i (hamburger)", converter.convert("🦄 i 🍔"))
        assertEquals("(rodzina: mężczyzna, kobieta, dziewczynka)", converter.convert("👨‍👩‍👧"))
        assertEquals("(flaga: Polska)", converter.convert("🇵🇱"))
        assertEquals("(klawisz: #)", converter.convert("#️⃣"))
        assertEquals("(emotka)", converter.convert("🫠"))
    }

    @Test
    fun plainTextIsUntouched() {
        val text = "Zażółć gęślą jaźń 123 #tag *gwiazdka* € ™"
        assertEquals(text, converter.convert(text))
    }

    @Test
    fun cursorFollowsTheReplacement() {
        // "ab😉|cd" → "ab;)|cd"
        val r = converter.convert("ab😉cd", cursor = 4)
        assertEquals("ab;)cd", r.text)
        assertEquals(4, r.cursor)
        // cursor before the emoji stays put
        assertEquals(2, converter.convert("ab🦄cd", cursor = 2).cursor)
        // cursor after: shifted by the length difference
        val r2 = converter.convert("ab🦄cd", cursor = 5)
        assertEquals("ab(głowa jednorożca)cd", r2.text)
        assertEquals(21, r2.cursor)
    }

    @Test
    fun bundledNamesLoad() {
        val file = listOf("src/main/assets/emoji_pl.tsv", "app/src/main/assets/emoji_pl.tsv").map(::File).firstOrNull { it.isFile }
            ?: return
        val loaded = file.inputStream().use { EmojiConverter.load(it) }
        assertEquals("<je_pizze>", loaded.convert("🍕")) // GG code wins over the name
        assertEquals("(głowa jednorożca)", loaded.convert("🦄"))
        assertEquals("(hamburger)", loaded.convert("🍔"))
    }
}
