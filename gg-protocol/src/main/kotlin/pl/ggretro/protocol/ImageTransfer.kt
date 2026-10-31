package pl.ggretro.protocol

/**
 * Classic GG 6 inline image transfer. Image bytes travel as "options" appended to an empty
 * GG_SEND_MSG / GG_RECV_MSG: 0x04 = request, 0x05 = first reply chunk (with file name),
 * 0x06 = next chunk. A 0x05 reply with no file name and no data means "unavailable".
 */
sealed class ImageTransfer {
    abstract val id: ImageId

    data class Request(override val id: ImageId) : ImageTransfer()

    class Reply(
        override val id: ImageId,
        val first: Boolean,
        val fileName: ByteArray?,
        val data: ByteArray,
    ) : ImageTransfer() {
        val unavailable: Boolean get() = first && fileName == null && data.isEmpty()
    }

    fun encode(): ByteArray {
        val w = WireWriter()
        w.u8(
            when (this) {
                is Request -> 4
                is Reply -> if (first) 5 else 6
            }
        )
        w.u32(id.size)
        w.u32(id.crc32)
        if (this is Reply) {
            fileName?.let { w.bytes(it).u8(0) }
            w.bytes(data)
        }
        return w.toByteArray()
    }

    companion object {
        /** Largest image the server relays (255 KiB). */
        const val MAX_IMAGE_BYTES = 255 * 1024

        /** Maximum option size (header + file name + data) per packet. */
        const val MAX_OPTION_BYTES = 1909

        fun unavailable(id: ImageId) = Reply(id, true, null, ByteArray(0))

        fun decode(r: WireReader): ImageTransfer {
            val total = r.remaining
            if (total < 9 || total > MAX_OPTION_BYTES) throw GgProtocolException("bad image option")
            val flag = r.u8()
            val id = ImageId(r.u32().toInt(), r.u32())
            return when (flag) {
                4 -> Request(id)
                5 -> {
                    if (r.remaining == 0) return unavailable(id)
                    val nul = r.indexOfNul()
                    if (nul <= 0) throw GgProtocolException("bad image file name")
                    val name = r.bytes(nul)
                    r.skip(1)
                    Reply(id, true, name, r.rest())
                }
                6 -> Reply(id, false, null, r.rest())
                else -> throw GgProtocolException("unknown image option $flag")
            }
        }

        /** Splits an image into the 0x05 / 0x06 chunks the server expects. */
        fun chunks(data: ByteArray, fileName: String): List<Reply> {
            val id = ImageId.of(data)
            val encoded = Cp1250.encode(fileName).filter { it.toInt() != 0 }.take(1024).toByteArray()
            val name = if (encoded.isEmpty()) "obrazek.jpg".toByteArray() else encoded
            val out = ArrayList<Reply>()
            var offset = 0
            while (offset < data.size) {
                val first = offset == 0
                val capacity = MAX_OPTION_BYTES - 9 - if (first) name.size + 1 else 0
                val end = minOf(data.size, offset + capacity)
                out += Reply(id, first, if (first) name else null, data.copyOfRange(offset, end))
                offset = end
            }
            return out
        }
    }
}

/** Collects 0x05/0x06 chunks until the whole image has arrived and the CRC matches. */
class ImageAssembler {
    private class Partial(val fileName: String, val buffer: java.io.ByteArrayOutputStream)

    private val partial = HashMap<Pair<Int, ImageId>, Partial>()

    sealed class Result {
        data class Complete(val owner: Int, val id: ImageId, val fileName: String, val data: ByteArray) : Result()
        data class Unavailable(val owner: Int, val id: ImageId) : Result()
    }

    /** Returns a result when the image is complete (or known to be unavailable), else null. */
    fun accept(owner: Int, reply: ImageTransfer.Reply): Result? {
        val key = owner to reply.id
        if (reply.unavailable) {
            partial.remove(key)
            return Result.Unavailable(owner, reply.id)
        }
        val p = if (reply.first) {
            Partial(Cp1250.decode(reply.fileName ?: ByteArray(0)), java.io.ByteArrayOutputStream()).also { partial[key] = it }
        } else {
            partial[key] ?: return null
        }
        p.buffer.write(reply.data)
        val size = p.buffer.size()
        if (size < reply.id.size) return null
        partial.remove(key)
        val bytes = p.buffer.toByteArray()
        return if (size == reply.id.size && ImageId.of(bytes) == reply.id) {
            Result.Complete(owner, reply.id, p.fileName, bytes)
        } else {
            Result.Unavailable(owner, reply.id)
        }
    }

    fun clear() = partial.clear()
}
