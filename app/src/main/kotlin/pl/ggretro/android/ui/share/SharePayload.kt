package pl.ggretro.android.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.ggretro.android.service.ShareShortcuts
import java.io.File

/** Something shared to GG Retro from another app ("Udostępnij"). Images are private copies. */
data class SharePayload(val text: String?, val images: List<Uri>, val droppedImages: Int = 0) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && images.isEmpty()

    companion object {
        fun isShare(intent: Intent?): Boolean =
            intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE

        /** Contact chosen directly in the system share sheet (Direct Share), if any. */
        fun shortcutUin(intent: Intent): Int? =
            ShareShortcuts.uinFromId(intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))

        /**
         * Reads a share intent. Images are copied into the app's cache right away: the read permission
         * granted by the sending app ends with this activity, while sending happens later.
         */
        suspend fun read(context: Context, intent: Intent): SharePayload? = withContext(Dispatchers.IO) {
            val text = ShareText.combine(
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            )
            val uris = buildList {
                if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
                } else {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                }
                intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add) }
            }
            val resolver = context.contentResolver
            val images = uris.filter { uri ->
                val type = runCatching { resolver.getType(uri) }.getOrNull() ?: intent.type
                type?.startsWith("image/") == true
            }
            val (kept, dropped) = ShareText.limit(images)
            val dir = File(context.cacheDir, "shared").apply {
                deleteRecursively()
                mkdirs()
            }
            val copies = kept.mapIndexedNotNull { i, uri ->
                runCatching {
                    val file = File(dir, "shared_$i")
                    resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                        ?: return@runCatching null
                    Uri.fromFile(file)
                }.getOrNull()
            }
            SharePayload(text, copies, dropped + (kept.size - copies.size)).takeUnless { it.isEmpty }
        }
    }
}
