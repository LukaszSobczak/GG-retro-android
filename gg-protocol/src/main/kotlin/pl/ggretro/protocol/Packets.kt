package pl.ggretro.protocol

object PacketType {
    const val WELCOME = 0x0001
    const val NEW_STATUS = 0x0002
    const val LOGIN_OK = 0x0003
    const val SEND_MSG_ACK = 0x0005
    const val PONG = 0x0007
    const val PING = 0x0008
    const val LOGIN_FAILED = 0x0009
    const val RECV_MSG = 0x000a
    const val SEND_MSG = 0x000b
    const val DISCONNECTING = 0x000b
    const val ADD_NOTIFY = 0x000d
    const val REMOVE_NOTIFY = 0x000e
    const val NOTIFY_FIRST = 0x000f
    const val STATUS60 = 0x000f
    const val NOTIFY_LAST = 0x0010
    const val USERLIST_REPLY = 0x0010
    const val NOTIFY_REPLY60 = 0x0011
    const val LIST_EMPTY = 0x0012
    const val NEED_EMAIL = 0x0014
    const val LOGIN60 = 0x0015
    const val USERLIST_REQUEST = 0x0016

    const val MAX_PAYLOAD = 65_536
}

/** A raw packet: type + payload. */
class Frame(val type: Int, val payload: ByteArray) {
    fun toBytes(): ByteArray = WireWriter().u32(type).u32(payload.size).bytes(payload).toByteArray()
}

data class ContactStatus(
    val uin: Int,
    val flags: Int,
    val status: Int,
    val imageSize: Int,
    val description: String?,
    val time: Long?,
)

data class ContactEntry(val uin: Int, val type: Int = UserType.NORMAL)

/** Packets the client sends to the server. */
object ClientPackets {
    const val GG_VERSION_60 = 0x20

    fun login60(
        uin: Int,
        password: String,
        seed: Int,
        status: Int,
        description: String?,
        imageSize: Int = 255,
    ): Frame {
        val w = WireWriter()
            .u32(uin)
            .u32(GgHash.loginHash(password, seed))
            .u32(status)
            .u32(GG_VERSION_60)
            .u8(0)
            .u32(0).u16(0) // local ip / port (no direct connections)
            .u32(0).u16(0) // external ip / port
            .u8(imageSize)
            .u8(0xbe)
        if (description != null && GgStatus.hasDescription(status)) {
            w.bytes(Cp1250.encode(description.take(GgStatus.MAX_DESCRIPTION))).u8(0)
        }
        return Frame(PacketType.LOGIN60, w.toByteArray())
    }

    fun newStatus(status: Int, description: String?, time: Long? = null): Frame {
        val w = WireWriter().u32(status)
        if (description != null && GgStatus.hasDescription(status)) {
            w.bytes(Cp1250.encode(description.take(GgStatus.MAX_DESCRIPTION))).u8(0)
            if (time != null) w.u32(time)
        }
        return Frame(PacketType.NEW_STATUS, w.toByteArray())
    }

    fun ping() = Frame(PacketType.PING, ByteArray(0))

    fun listEmpty() = Frame(PacketType.LIST_EMPTY, ByteArray(0))

    private fun entries(type: Int, list: List<ContactEntry>): Frame {
        val w = WireWriter()
        for (e in list) w.u32(e.uin).u8(e.type)
        return Frame(type, w.toByteArray())
    }

    fun notifyFirst(list: List<ContactEntry>) = entries(PacketType.NOTIFY_FIRST, list)

    fun notifyLast(list: List<ContactEntry>) = entries(PacketType.NOTIFY_LAST, list)

    /** The whole contact list split into 400-entry chunks (or GG_LIST_EMPTY). */
    fun contactList(list: List<ContactEntry>): List<Frame> {
        if (list.isEmpty()) return listOf(listEmpty())
        val chunks = list.chunked(400)
        return chunks.mapIndexed { i, chunk -> if (i == chunks.lastIndex) notifyLast(chunk) else notifyFirst(chunk) }
    }

    fun addNotify(e: ContactEntry) = Frame(PacketType.ADD_NOTIFY, WireWriter().u32(e.uin).u8(e.type).toByteArray())

    fun removeNotify(e: ContactEntry) = Frame(PacketType.REMOVE_NOTIFY, WireWriter().u32(e.uin).u8(e.type).toByteArray())

    /** [styled] must already be in wire form (see [RichText.toWire]). */
    fun sendMessage(recipient: Int, seq: Int, styled: StyledText, messageClass: Int = MessageClass.CHAT): Frame {
        val w = WireWriter()
            .u32(recipient)
            .u32(seq)
            .u32(messageClass)
            .bytes(Cp1250.encode(styled.text))
            .u8(0)
            .bytes(RichText.encode(styled.formats))
        return Frame(PacketType.SEND_MSG, w.toByteArray())
    }

    fun sendImageOption(recipient: Int, seq: Int, transfer: ImageTransfer, messageClass: Int = MessageClass.MSG): Frame {
        val w = WireWriter()
            .u32(recipient)
            .u32(seq)
            .u32(messageClass)
            .u8(0)
            .bytes(transfer.encode())
        return Frame(PacketType.SEND_MSG, w.toByteArray())
    }

    /** kind 2 = GG_USERLIST_GET; kind 0/1 = PUT / PUT_MORE. */
    fun userListRequest(kind: Int, data: ByteArray = ByteArray(0)) =
        Frame(PacketType.USERLIST_REQUEST, WireWriter().u8(kind).bytes(data).toByteArray())

    fun userListPut(text: String): List<Frame> {
        val bytes = Cp1250.encode(text)
        if (bytes.isEmpty()) return listOf(userListRequest(0))
        return bytes.toList().chunked(2047).mapIndexed { i, c -> userListRequest(if (i == 0) 0 else 1, c.toByteArray()) }
    }

    fun userListGet() = userListRequest(2)
}

/** Packets the server sends to the client. */
sealed class ServerPacket {
    data class Welcome(val seed: Int) : ServerPacket()
    data object LoginOk : ServerPacket()
    data object LoginFailed : ServerPacket()
    data object NeedEmail : ServerPacket()
    data object Disconnecting : ServerPacket()
    data object Pong : ServerPacket()
    data class NotifyReply(val statuses: List<ContactStatus>) : ServerPacket()
    data class Status(val status: ContactStatus) : ServerPacket()
    data class Message(
        val sender: Int,
        val seq: Int,
        val time: Long,
        val messageClass: Int,
        /** Wire form (use [RichText.fromWire] for display). */
        val styled: StyledText,
        val conference: List<Int>,
    ) : ServerPacket()
    data class Image(val sender: Int, val transfer: ImageTransfer) : ServerPacket()
    data class SendAck(val status: Int, val recipient: Int, val seq: Int) : ServerPacket()
    data class UserListReply(val kind: Int, val data: ByteArray) : ServerPacket()
    data class Unknown(val type: Int) : ServerPacket()

    companion object {
        fun decode(frame: Frame): ServerPacket {
            val r = WireReader(frame.payload)
            return when (frame.type) {
                PacketType.WELCOME -> Welcome(r.i32())
                PacketType.LOGIN_OK -> LoginOk
                PacketType.LOGIN_FAILED -> LoginFailed
                PacketType.NEED_EMAIL -> NeedEmail
                PacketType.DISCONNECTING -> Disconnecting
                PacketType.PONG -> Pong
                PacketType.NOTIFY_REPLY60 -> NotifyReply(decodeNotifyReply(r))
                PacketType.STATUS60 -> Status(decodeStatus60(r))
                PacketType.RECV_MSG -> decodeMessage(r)
                PacketType.SEND_MSG_ACK -> SendAck(r.i32(), r.i32(), r.i32())
                PacketType.USERLIST_REPLY -> UserListReply(r.u8(), r.rest())
                else -> Unknown(frame.type)
            }
        }

        private fun statusHeader(r: WireReader): IntArray {
            val uinFlags = r.u32()
            val status = r.u8()
            r.skip(4) // remote ip
            r.skip(2) // remote port
            r.u8() // version
            val imageSize = r.u8()
            r.u8() // unknown
            return intArrayOf((uinFlags and 0xffffff).toInt(), ((uinFlags ushr 24) and 0xff).toInt(), status, imageSize)
        }

        private fun decodeNotifyReply(r: WireReader): List<ContactStatus> {
            val out = ArrayList<ContactStatus>()
            while (r.remaining >= 14) {
                val h = statusHeader(r)
                var description: String? = null
                var time: Long? = null
                if (GgStatus.hasDescription(h[2]) && r.remaining >= 1) {
                    val size = r.u8()
                    if (size > 0 && r.remaining >= size) {
                        val block = r.bytes(size)
                        val hasTime = size >= 5 && block[size - 5].toInt() == 0
                        val end = block.indexOf(0).let { if (it < 0) size else it }
                        if (end > 0) description = Cp1250.decode(block.copyOfRange(0, end))
                        if (hasTime) time = WireReader(block, size - 4).u32()
                    }
                }
                out += ContactStatus(h[0], h[1], h[2], h[3], description, time)
            }
            return out
        }

        private fun decodeStatus60(r: WireReader): ContactStatus {
            val h = statusHeader(r)
            var description: String? = null
            var time: Long? = null
            if (r.remaining > 0) {
                val nul = r.indexOfNul()
                val end = if (nul < 0) r.remaining else nul
                description = Cp1250.decode(r.bytes(end))
                if (r.remaining >= 5) {
                    r.skip(1)
                    time = r.u32()
                }
            }
            return ContactStatus(h[0], h[1], h[2], h[3], description, time)
        }

        private fun decodeMessage(r: WireReader): ServerPacket {
            val sender = r.i32()
            val seq = r.i32()
            val time = r.u32()
            val cls = r.i32()
            val nul = r.indexOfNul()
            if (nul < 0) throw GgProtocolException("unterminated message")
            val text = Cp1250.decode(r.bytes(nul))
            r.skip(1)
            if (r.remaining > 0 && r.peek() in 4..6 && text.isEmpty()) {
                return Image(sender, ImageTransfer.decode(r))
            }
            var conference = emptyList<Int>()
            var formats = emptyList<TextFormat>()
            loop@ while (r.remaining > 0) {
                when (r.peek()) {
                    1 -> {
                        if (r.remaining < 5) break@loop
                        r.skip(1)
                        val count = r.u32().toInt()
                        if (count < 0 || count > 100 || r.remaining < count * 4) break@loop
                        conference = List(count) { r.i32() }
                    }
                    2 -> {
                        if (r.remaining < 3) break@loop
                        r.skip(1)
                        val len = r.u16()
                        if (r.remaining < len) break@loop
                        formats = RichText.decodeEntries(r.sub(len))
                    }
                    else -> break@loop
                }
            }
            return Message(sender, seq, time, cls, StyledText(text, formats), conference)
        }
    }
}
