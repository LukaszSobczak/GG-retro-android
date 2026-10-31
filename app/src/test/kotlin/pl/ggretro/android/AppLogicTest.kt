package pl.ggretro.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.ggretro.android.data.FormatCodec
import pl.ggretro.android.ui.emots.Emoticon
import pl.ggretro.android.ui.emots.Emoticons
import pl.ggretro.protocol.ImageId
import pl.ggretro.protocol.TextFormat

class AppLogicTest {
    @Test
    fun parsesEmotsTxtLines() {
        val text = """
            (":)",":))",":)))",":))))"),"wesoly.gif","wesoly_na.gif"
            (":[","<zly>","wrrrr!"),"zly.gif","zly_na.gif"
            "<lol>","lol.gif"
            *"<sex>","sex.gif"
            *(":-)",":-))"),"usmiech2.gif","usmiech2_na.gif"
            "<zniesmaczony","zniesmaczony.gif"
        """.trimIndent()
        val lines = Emoticons.parse(text)
        assertEquals(6, lines.size)
        assertEquals(listOf(":)", ":))", ":)))", ":))))"), lines[0].codes)
        assertEquals(listOf("wesoly.gif", "wesoly_na.gif"), lines[0].files)
        assertEquals(listOf("<lol>"), lines[2].codes)
        assertEquals(listOf("lol.gif"), lines[2].files)
        assertTrue(lines[3].hidden)
        assertEquals(listOf(":-)", ":-))"), lines[4].codes)
        assertEquals(listOf("<zniesmaczony"), lines[5].codes)
    }

    @Test
    fun matcherPrefersLongestCodeAndIgnoresCase() {
        fun e(name: String, vararg codes: String) = Emoticon(codes.toList(), name, name, 18, 18, false)
        val smile = e("wesoly", ":)", ":))")
        val lol = e("lol", "<lol>")
        val emots = Emoticons(mapOf(1 to listOf(smile, lol)))
        val found = emots.find("hej :)) <LOL>!")
        assertEquals(2, found.size)
        assertEquals(4 to 7, found[0].start to found[0].end)
        assertEquals("wesoly", found[0].emoticon.animated)
        assertEquals("lol", found[1].emoticon.animated)
    }

    @Test
    fun formatCodecRoundTrip() {
        val formats = listOf(
            TextFormat(0, bold = true),
            TextFormat(3, italic = true, underline = true, color = 0xff8000),
            TextFormat(7, image = ImageId(12345, 0xdeadbeefL)),
        )
        assertEquals(formats, FormatCodec.decode(FormatCodec.encode(formats)))
        assertEquals(emptyList<TextFormat>(), FormatCodec.decode(""))
    }
}
