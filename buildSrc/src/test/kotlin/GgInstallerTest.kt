import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs only when GG61_EXE points at the original installer (webs1821/gg-retro server/static/gg61.exe). */
class GgInstallerTest {
    @Test
    fun extractsEmoticonsSoundsAndSkin() {
        val path = System.getenv("GG61_EXE")
        assumeTrue(path != null && File(path).isFile, "GG61_EXE not set")
        val exe = File(path!!).readBytes()
        assertEquals("bcc8157aa6bface009d8018c308bf3cef8725546b4f826bdbaf6bbeaa953b06f", GgInstaller.sha256(exe))
        val files = GgInstaller.extract(exe)
        assertEquals(120, files.keys.count { it.startsWith("emots/1/") })
        assertEquals(149, files.keys.count { it.startsWith("emots/2/") })
        assertTrue(String(files.getValue("emots/1/emots.txt"), charset("windows-1250")).startsWith("(\":)\""))
        for (wav in listOf("sounds/wiadomosc.wav", "sounds/dostepny.wav")) {
            assertEquals("RIFF", String(files.getValue(wav), 0, 4, Charsets.US_ASCII))
        }
        val gif = files.getValue("emots/1/wesoly.gif")
        assertEquals("GIF8", String(gif, 0, 4, Charsets.US_ASCII))
        val status = files.getValue("skins/classic/status.bmp")
        assertEquals("BM", String(status, 0, 2, Charsets.US_ASCII))
        // Every emoticon referenced by emots.txt exists (case-insensitively).
        for (set in listOf("1", "2")) {
            val text = String(files.getValue("emots/$set/emots.txt"), charset("windows-1250"))
            val names = Regex("\"([^\"]+\\.gif)\"", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1].lowercase() }.toList()
            assertTrue(names.isNotEmpty())
            val missing = names.filter { "emots/$set/$it" !in files }
            assertTrue(missing.isEmpty(), "missing in set $set: $missing")
        }
    }
}
