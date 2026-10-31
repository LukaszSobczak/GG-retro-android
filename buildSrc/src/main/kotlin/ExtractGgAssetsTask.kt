import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI

/**
 * Pulls the original GG 6.1 emoticons, sounds and classic-skin bitmaps out of `gg61.exe`
 * (downloaded from webs1821/gg-retro at a pinned commit and verified by SHA-256).
 * Nothing from the installer is committed to this repository; it only ends up inside the APK.
 */
@CacheableTask
abstract class ExtractGgAssetsTask : DefaultTask() {
    @get:Input
    abstract val installerUrl: Property<String>

    @get:Input
    abstract val installerSha256: Property<String>

    /** Optional local copy of gg61.exe (gradle property `ggInstaller`), skips the download. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localInstaller: RegularFileProperty

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resDir: DirectoryProperty

    @TaskAction
    fun extract() {
        val exe = loadInstaller()
        val files = GgInstaller.extract(exe)

        val assets = assetsDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val res = resDir.get().asFile.apply { deleteRecursively(); mkdirs() }

        var emots = 0
        for ((path, bytes) in files) {
            val target = when {
                path.startsWith("emots/1/") || path.startsWith("emots/2/") -> "gg/$path".also { emots++ }
                path.startsWith("skins/classic/") && path.substringAfterLast('/') in SKIN_FILES -> "gg/skin/${path.substringAfterLast('/')}"
                else -> null
            } ?: continue
            File(assets, target).apply { parentFile.mkdirs() }.writeBytes(bytes)
        }
        for ((source, raw) in SOUNDS) {
            val bytes = files[source] ?: throw GradleException("Missing $source in installer")
            File(res, "raw/$raw").apply { parentFile.mkdirs() }.writeBytes(bytes)
        }
        for (required in listOf("emots/1/emots.txt", "emots/2/emots.txt", "skins/classic/status.bmp")) {
            if (required !in files) throw GradleException("Missing $required in installer")
        }
        logger.lifecycle("GG 6.1 assets: $emots emoticon files, ${SOUNDS.size} sounds, ${SKIN_FILES.size} skin bitmaps")
    }

    private fun loadInstaller(): ByteArray {
        val expected = installerSha256.get().lowercase()
        localInstaller.orNull?.asFile?.let { file ->
            val bytes = file.readBytes()
            check(GgInstaller.sha256(bytes) == expected) { "Unexpected SHA-256 of $file" }
            return bytes
        }
        val cached = cacheDir.get().file("gg61-$expected.exe").asFile
        if (cached.isFile && GgInstaller.sha256(cached.readBytes()) == expected) return cached.readBytes()
        val url = installerUrl.get()
        logger.lifecycle("Downloading GG 6.1 installer from $url")
        val bytes = try {
            URI(url).toURL().openStream().use { it.readBytes() }
        } catch (e: Exception) {
            throw GradleException(
                "Could not download the GG 6.1 installer ($url). Pass -PggInstaller=/path/to/gg61.exe to use a local copy.",
                e,
            )
        }
        val actual = GgInstaller.sha256(bytes)
        if (actual != expected) throw GradleException("GG installer checksum mismatch: expected $expected, got $actual")
        cached.parentFile.mkdirs()
        cached.writeBytes(bytes)
        return bytes
    }

    companion object {
        val SKIN_FILES = setOf(
            "status.bmp", "bold.bmp", "italic.bmp", "underl.bmp", "color.bmp", "emot.bmp", "graph.bmp", "tray.bmp",
        )
        val SOUNDS = mapOf(
            "sounds/wiadomosc.wav" to "gg_wiadomosc.wav",
            "sounds/dostepny.wav" to "gg_dostepny.wav",
        )
    }
}
