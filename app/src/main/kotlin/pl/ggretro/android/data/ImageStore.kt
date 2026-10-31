package pl.ggretro.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import pl.ggretro.protocol.ImageId
import pl.ggretro.protocol.ImageTransfer
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Keeps GG inline images on disk, keyed by size + CRC32 (the identity used by the protocol).
 * Sent images must stay here: the classic GG 6 relay fetches them from the live sender on demand.
 */
class ImageStore(context: Context) {
    private val dir = File(context.filesDir, "images").apply { mkdirs() }
    private val resolver = context.contentResolver

    fun file(id: ImageId): File = File(dir, id.key)

    fun has(id: ImageId): Boolean = file(id).let { it.isFile && it.length() == id.size.toLong() }

    fun read(id: ImageId): ByteArray? = if (has(id)) file(id).readBytes() else null

    fun save(data: ByteArray): ImageId {
        val id = ImageId.of(data)
        val f = file(id)
        if (!has(id)) {
            val tmp = File(dir, "${id.key}.tmp")
            tmp.writeBytes(data)
            tmp.renameTo(f)
        }
        return id
    }

    /**
     * Loads a picked image, scales it down and re-encodes it as JPEG small enough for the GG relay
     * ([maxBytes], at most 255 KiB).
     */
    fun prepareForSending(uri: Uri, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray {
        val limit = maxBytes.coerceIn(16 * 1024, ImageTransfer.MAX_IMAGE_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE || bounds.outHeight / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Nie można odczytać obrazka")
        var bitmap = rotateByExif(uri, decoded)
        var edge = MAX_EDGE
        while (true) {
            val scaled = scaleToEdge(bitmap, edge)
            for (quality in intArrayOf(85, 75, 65, 55, 45, 35)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= limit) return out.toByteArray()
            }
            edge = edge * 3 / 4
            bitmap = scaled
            if (edge < 160) error("Obrazek jest za duży")
        }
    }

    private fun scaleToEdge(bitmap: Bitmap, edge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= edge) return bitmap
        val f = edge.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun rotateByExif(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 250 * 1024
        private const val MAX_EDGE = 1280
    }
}
