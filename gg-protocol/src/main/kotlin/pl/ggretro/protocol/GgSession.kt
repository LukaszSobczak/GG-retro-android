package pl.ggretro.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

data class GgConfig(
    val host: String,
    val port: Int,
    val uin: Int,
    val password: String,
    /** Initial status code, e.g. [GgStatus.AVAIL] or [GgStatus.AVAIL_DESCR]. */
    val status: Int = GgStatus.AVAIL,
    val description: String? = null,
    val contacts: List<ContactEntry> = emptyList(),
    /** Largest image (KiB) we accept from others. */
    val imageSizeKb: Int = 255,
    val connectTimeoutMs: Int = 15_000,
    /**
     * Keep-alive interval. gg-retro drops a client after 300 s without any packet; 3 minutes leaves
     * room for late wake-ups while waking the radio 3× less often than every minute.
     */
    val pingIntervalMs: Long = 180_000,
)

sealed class GgEvent {
    data object LoggedIn : GgEvent()
    /** [initial] is true for the statuses sent right after login (GG_NOTIFY_REPLY60). */
    data class Status(val status: ContactStatus, val initial: Boolean = false) : GgEvent()
    data class Message(
        val sender: Int,
        val time: Long,
        val messageClass: Int,
        /** Display form ('\n' line breaks). */
        val styled: StyledText,
        val conference: List<Int>,
    ) : GgEvent()
    data class Ack(val recipient: Int, val seq: Int, val status: Int) : GgEvent()
    data class ImageRequest(val from: Int, val id: ImageId) : GgEvent()
    data class ImageReceived(val owner: Int, val id: ImageId, val fileName: String, val data: ByteArray) : GgEvent()
    data class ImageUnavailable(val owner: Int, val id: ImageId) : GgEvent()
    data class UserList(val contacts: List<ImportedContact>) : GgEvent()
    data object UserListStored : GgEvent()
}

sealed class DisconnectReason {
    data object LoginFailed : DisconnectReason()
    /** Server sent GG_DISCONNECTING: someone else logged in with this number. Do not auto-reconnect. */
    data object Kicked : DisconnectReason()
    data object ClosedByUser : DisconnectReason()
    data class Network(val cause: Throwable) : DisconnectReason()
}

/**
 * One TCP session with a GG 6 server. Call [run] once; it suspends until the connection ends and
 * returns why. All `send*` methods are safe to call from any thread while the session is running.
 */
class GgSession(private val config: GgConfig) {
    private val outgoing = Channel<Frame>(Channel.UNLIMITED)
    private val seqCounter = AtomicInteger(((System.currentTimeMillis() / 1000) and 0x3fffffff).toInt())
    private val assembler = ImageAssembler()
    private val userListBuffer = java.io.ByteArrayOutputStream()
    private val imageRequestTimes = ArrayDeque<Long>()

    @Volatile private var socket: Socket? = null
    /** Wall-clock time of the last packet written to the server (any packet resets the server's idle timer). */
    @Volatile private var lastWriteAt = 0L
    /** Number of packets written by the writer; lets pingIfIdle() see its ping leave the socket. */
    private val writeCount = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var closedByUser = false
    @Volatile var loggedIn: Boolean = false
        private set

    fun nextSeq(): Int = seqCounter.incrementAndGet() and 0x7fffffff

    /** Queues a packet; false when the session is already closing. */
    fun send(frame: Frame): Boolean = outgoing.trySend(frame).isSuccess

    /**
     * Sends a chat message; returns the sequence number used (matched by [GgEvent.Ack]),
     * or null when the session is closing and the message was not queued.
     */
    fun sendMessage(recipient: Int, styled: StyledText, seq: Int = nextSeq()): Int? =
        if (send(ClientPackets.sendMessage(recipient, seq, RichText.toWire(styled)))) seq else null

    fun setStatus(status: Int, description: String?) = send(ClientPackets.newStatus(status, description))

    fun addContact(uin: Int) = send(ClientPackets.addNotify(ContactEntry(uin)))

    fun removeContact(uin: Int) = send(ClientPackets.removeNotify(ContactEntry(uin)))

    fun requestUserList() {
        synchronized(userListBuffer) { userListBuffer.reset() }
        send(ClientPackets.userListGet())
    }

    fun storeUserList(contacts: List<ImportedContact>) {
        ClientPackets.userListPut(UserList.format(contacts)).forEach(::send)
    }

    /**
     * Asks [owner] for an image. The server allows only ~20 requests per minute, so extra requests
     * are refused locally (returns false) instead of getting us disconnected.
     */
    fun requestImage(owner: Int, id: ImageId): Boolean {
        val now = System.currentTimeMillis()
        synchronized(imageRequestTimes) {
            while (imageRequestTimes.isNotEmpty() && now - imageRequestTimes.first() > 60_000) imageRequestTimes.removeFirst()
            if (imageRequestTimes.size >= 15) return false
            imageRequestTimes.addLast(now)
        }
        send(ClientPackets.sendImageOption(owner, nextSeq(), ImageTransfer.Request(id), MessageClass.MSG or MessageClass.ACK))
        return true
    }

    /** Sends image bytes to [recipient] (answer to an [GgEvent.ImageRequest]). */
    fun sendImageData(recipient: Int, data: ByteArray, fileName: String) {
        for (chunk in ImageTransfer.chunks(data, fileName)) {
            send(ClientPackets.sendImageOption(recipient, nextSeq(), chunk, MessageClass.MSG or MessageClass.ACK))
        }
    }

    fun sendImageUnavailable(recipient: Int, id: ImageId) {
        send(ClientPackets.sendImageOption(recipient, nextSeq(), ImageTransfer.unavailable(id), MessageClass.MSG or MessageClass.ACK))
    }

    /**
     * Sends GG_PING unless something was written within [minIdleMs], then waits (up to [awaitMs])
     * until it has actually left the socket. Meant for a short wake-up from the system (alarm):
     * returns once it is safe to let the CPU sleep again. Returns true if a ping was written.
     */
    suspend fun pingIfIdle(minIdleMs: Long, awaitMs: Long = 3_000): Boolean {
        if (!loggedIn) return false
        if (System.currentTimeMillis() - lastWriteAt < minIdleMs) return false
        // Timestamps can repeat within a millisecond; count writes instead. Other frames queued
        // ahead of the ping also count: anything leaving the socket resets the server's idle timer.
        val before = writeCount.get()
        if (!send(ClientPackets.ping())) return false
        val deadline = System.currentTimeMillis() + awaitMs
        while (writeCount.get() == before && System.currentTimeMillis() < deadline) delay(25)
        return writeCount.get() != before
    }

    /** Politely goes offline (with optional description) and closes the connection. */
    fun logout(description: String? = null) {
        closedByUser = true
        val status = if (description.isNullOrEmpty()) GgStatus.NOT_AVAIL else GgStatus.NOT_AVAIL_DESCR
        send(ClientPackets.newStatus(status, description))
        outgoing.close()
    }

    /** Closes the connection immediately. */
    fun close() {
        closedByUser = true
        outgoing.close()
        runCatching { socket?.close() }
    }

    suspend fun run(onEvent: suspend (GgEvent) -> Unit): DisconnectReason = withContext(Dispatchers.IO) {
        val s = Socket()
        socket = s
        try {
            if (closedByUser) return@withContext DisconnectReason.ClosedByUser
            s.connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 30_000
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val output = BufferedOutputStream(s.getOutputStream())

            val welcome = ServerPacket.decode(readFrame(input))
            if (welcome !is ServerPacket.Welcome) throw GgProtocolException("expected GG_WELCOME")
            val status = loginStatus()
            write(output, ClientPackets.login60(config.uin, config.password, welcome.seed, status, config.description, config.imageSizeKb))
            while (true) {
                when (ServerPacket.decode(readFrame(input))) {
                    ServerPacket.LoginOk -> break
                    ServerPacket.LoginFailed -> return@withContext DisconnectReason.LoginFailed
                    ServerPacket.Disconnecting -> return@withContext DisconnectReason.LoginFailed
                    else -> Unit
                }
            }
            // The server publishes our status and delivers queued messages only after the list.
            for (f in ClientPackets.contactList(config.contacts)) write(output, f)
            lastWriteAt = System.currentTimeMillis()
            loggedIn = true
            s.soTimeout = (config.pingIntervalMs * 3).toInt().coerceAtLeast(30_000)
            onEvent(GgEvent.LoggedIn)

            coroutineScope {
                val writer = launch {
                    try {
                        // gg-retro disconnects clients sending more than 600 packets per minute
                        // (e.g. serving several large images); stay well below that.
                        val sentAt = ArrayDeque<Long>()
                        for (f in outgoing) {
                            val now = System.currentTimeMillis()
                            while (sentAt.isNotEmpty() && now - sentAt.first() >= 60_000) sentAt.removeFirst()
                            if (sentAt.size >= MAX_PACKETS_PER_MINUTE) delay(60_000 - (now - sentAt.first()) + 50)
                            write(output, f)
                            lastWriteAt = System.currentTimeMillis()
                            writeCount.incrementAndGet()
                            sentAt.addLast(lastWriteAt)
                        }
                        // Channel closed by logout(): give the server a moment, then hang up.
                        delay(300)
                    } catch (_: IOException) {
                    }
                    runCatching { s.close() }
                }
                // Runs while the CPU is awake (ticks are cheap, only the ping wakes the radio), so the
                // gap between packets never exceeds pingIntervalMs. During deep sleep this coroutine
                // is frozen and the app wakes the session with pingIfIdle() from an alarm.
                val pinger = launch {
                    val tick = minOf(30_000L, config.pingIntervalMs / 2)
                    while (isActive) {
                        delay(tick)
                        if (System.currentTimeMillis() - lastWriteAt >= config.pingIntervalMs - tick) {
                            outgoing.trySend(ClientPackets.ping())
                        }
                    }
                }
                // Blocking reads ignore cancellation: closing the socket is what unblocks them.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { s.close() }
                    }
                }
                try {
                    var reason: DisconnectReason? = null
                    while (reason == null) {
                        val packet = ServerPacket.decode(readFrame(input))
                        if (packet == ServerPacket.Disconnecting) reason = DisconnectReason.Kicked
                        else handle(packet, onEvent)
                    }
                    reason
                } finally {
                    pinger.cancel()
                    writer.cancel()
                    watcher.cancel()
                }
            }
        } catch (e: Throwable) {
            if (closedByUser) DisconnectReason.ClosedByUser
            else if (e is kotlinx.coroutines.CancellationException) throw e
            else DisconnectReason.Network(e)
        } finally {
            loggedIn = false
            runCatching { s.close() }
            assembler.clear()
        }
    }

    private fun loginStatus(): Int {
        val presence = GgStatus.presence(config.status) ?: Presence.AVAILABLE
        return presence.code(!config.description.isNullOrEmpty()) or (config.status and GgStatus.FRIENDS_MASK)
    }

    private suspend fun handle(packet: ServerPacket, onEvent: suspend (GgEvent) -> Unit) {
        when (packet) {
            is ServerPacket.NotifyReply -> packet.statuses.forEach { onEvent(GgEvent.Status(it, initial = true)) }
            is ServerPacket.Status -> onEvent(GgEvent.Status(packet.status))
            is ServerPacket.Message -> {
                if (packet.messageClass and MessageClass.CTCP != 0) return
                onEvent(GgEvent.Message(packet.sender, packet.time, packet.messageClass, RichText.fromWire(packet.styled), packet.conference))
            }
            is ServerPacket.SendAck -> onEvent(GgEvent.Ack(packet.recipient, packet.seq, packet.status))
            is ServerPacket.Image -> when (val t = packet.transfer) {
                is ImageTransfer.Request -> onEvent(GgEvent.ImageRequest(packet.sender, t.id))
                is ImageTransfer.Reply -> when (val r = assembler.accept(packet.sender, t)) {
                    is ImageAssembler.Result.Complete -> onEvent(GgEvent.ImageReceived(r.owner, r.id, r.fileName, r.data))
                    is ImageAssembler.Result.Unavailable -> onEvent(GgEvent.ImageUnavailable(r.owner, r.id))
                    null -> Unit
                }
            }
            is ServerPacket.UserListReply -> when (packet.kind) {
                0, 2 -> onEvent(GgEvent.UserListStored)
                4 -> synchronized(userListBuffer) { userListBuffer.write(packet.data) }
                6 -> {
                    val bytes = synchronized(userListBuffer) {
                        userListBuffer.write(packet.data)
                        userListBuffer.toByteArray().also { userListBuffer.reset() }
                    }
                    onEvent(GgEvent.UserList(UserList.parse(bytes)))
                }
            }
            else -> Unit
        }
    }

    companion object {
        const val MAX_PACKETS_PER_MINUTE = 450

        fun readFrame(input: InputStream): Frame {
            val din = if (input is DataInputStream) input else DataInputStream(input)
            val header = ByteArray(8)
            try {
                din.readFully(header)
            } catch (e: EOFException) {
                throw IOException("connection closed by server", e)
            }
            val r = WireReader(header)
            val type = r.i32()
            val length = r.i32()
            if (length < 0 || length > PacketType.MAX_PAYLOAD) throw GgProtocolException("payload too large: $length")
            val payload = ByteArray(length)
            din.readFully(payload)
            return Frame(type, payload)
        }

        fun write(output: OutputStream, frame: Frame) {
            output.write(frame.toBytes())
            output.flush()
        }
    }
}
