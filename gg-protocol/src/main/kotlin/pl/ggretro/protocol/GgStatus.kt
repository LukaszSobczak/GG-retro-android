package pl.ggretro.protocol

/** The four user-selectable GG 6 states. Each has a variant with a description. */
enum class Presence(val plain: Int, val withDescription: Int) {
    AVAILABLE(0x02, 0x04),
    BUSY(0x03, 0x05),
    INVISIBLE(0x14, 0x16),
    NOT_AVAILABLE(0x01, 0x15);

    fun code(hasDescription: Boolean): Int = if (hasDescription) withDescription else plain
}

object GgStatus {
    const val NOT_AVAIL = 0x01
    const val NOT_AVAIL_DESCR = 0x15
    const val AVAIL = 0x02
    const val AVAIL_DESCR = 0x04
    const val BUSY = 0x03
    const val BUSY_DESCR = 0x05
    const val INVISIBLE = 0x14
    const val INVISIBLE_DESCR = 0x16
    const val BLOCKED = 0x06
    const val FRIENDS_MASK = 0x8000

    /** Maximum description length accepted by the server (characters). */
    const val MAX_DESCRIPTION = 70

    fun base(status: Int): Int = status and FRIENDS_MASK.inv() and 0xff

    fun hasDescription(status: Int): Boolean = when (base(status)) {
        AVAIL_DESCR, BUSY_DESCR, NOT_AVAIL_DESCR, INVISIBLE_DESCR -> true
        else -> false
    }

    fun presence(status: Int): Presence? = when (base(status)) {
        AVAIL, AVAIL_DESCR -> Presence.AVAILABLE
        BUSY, BUSY_DESCR -> Presence.BUSY
        INVISIBLE, INVISIBLE_DESCR -> Presence.INVISIBLE
        NOT_AVAIL, NOT_AVAIL_DESCR -> Presence.NOT_AVAILABLE
        else -> null
    }

    fun isOnline(status: Int): Boolean = when (presence(status)) {
        Presence.AVAILABLE, Presence.BUSY -> true
        else -> false
    }
}

object MessageClass {
    const val QUEUED = 0x01
    const val MSG = 0x04
    const val CHAT = 0x08
    const val CTCP = 0x10
    const val ACK = 0x20
}

/** Delivery status reported by GG_SEND_MSG_ACK. */
object AckStatus {
    const val BLOCKED = 0x01
    const val DELIVERED = 0x02
    const val QUEUED = 0x03
    const val MBOX_FULL = 0x04
    const val NOT_DELIVERED = 0x06
}

/** Contact list entry flags sent in GG_NOTIFY_*. */
object UserType {
    const val BUDDY = 0x01
    const val FRIEND = 0x02
    const val BLOCKED = 0x04
    const val NORMAL = BUDDY or FRIEND
}
