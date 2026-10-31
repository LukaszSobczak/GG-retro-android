package pl.ggretro.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Exercises [GgSession] against a tiny in-process server speaking the gg-retro dialect. */
class GgSessionFakeServerTest {
    private val password = "Synthetic123!"

    private fun fakeServer(script: (DataInputStream, BufferedOutputStream, Int) -> Unit): Int {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.use { ss ->
                ss.accept().use { c ->
                    val input = DataInputStream(c.getInputStream())
                    val out = BufferedOutputStream(c.getOutputStream())
                    val seed = 0x1234567
                    GgSession.write(out, Frame(PacketType.WELCOME, WireWriter().u32(seed).toByteArray()))
                    script(input, out, seed)
                }
            }
        }
        return server.localPort
    }

    @Test
    fun loginContactsMessagesAndKick() = runBlocking {
        val received = Channel<Frame>(Channel.UNLIMITED)
        val port = fakeServer { input, out, seed ->
            val login = GgSession.readFrame(input)
            val r = WireReader(login.payload)
            assertEquals(PacketType.LOGIN60, login.type)
            assertEquals(4242, r.i32())
            assertEquals(GgHash.loginHash(password, seed), r.i32())
            assertEquals(GgStatus.BUSY_DESCR, r.i32())
            GgSession.write(out, Frame(PacketType.LOGIN_OK, ByteArray(0)))
            received.trySend(GgSession.readFrame(input)) // contact list
            val status = WireWriter().u32(77).u8(GgStatus.AVAIL_DESCR).u32(0).u16(0).u8(0x20).u8(255).u8(0)
                .u8(5).bytes(Cp1250.encode("żółw")).u8(0).toByteArray()
            GgSession.write(out, Frame(PacketType.NOTIFY_REPLY60, status))
            val msg = WireWriter().u32(77).u32(1).u32(1700000000).u32(MessageClass.CHAT)
                .bytes(Cp1250.encode("cześć\r\nco słychać?")).u8(0).toByteArray()
            GgSession.write(out, Frame(PacketType.RECV_MSG, msg))
            val sent = GgSession.readFrame(input)
            received.trySend(sent)
            val sr = WireReader(sent.payload)
            val recipient = sr.i32()
            val seq = sr.i32()
            GgSession.write(out, Frame(PacketType.SEND_MSG_ACK, WireWriter().u32(AckStatus.QUEUED).u32(recipient).u32(seq).toByteArray()))
            GgSession.write(out, Frame(PacketType.DISCONNECTING, ByteArray(0)))
            Thread.sleep(200)
        }
        val session = GgSession(
            GgConfig("127.0.0.1", port, 4242, password, GgStatus.BUSY, "w pracy", listOf(ContactEntry(77))),
        )
        val events = Channel<GgEvent>(Channel.UNLIMITED)
        val result = async { session.run { events.send(it) } }
        withTimeout(10_000) {
            assertIs<GgEvent.LoggedIn>(events.receive())
            val list = received.receive()
            assertEquals(PacketType.NOTIFY_LAST, list.type)
            val st = assertIs<GgEvent.Status>(events.receive()).status
            assertEquals("żółw", st.description)
            val msg = assertIs<GgEvent.Message>(events.receive())
            assertEquals("cześć\nco słychać?", msg.styled.text)
            val seq = session.sendMessage(77, StyledText("odp\nok"))
            val sent = received.receive()
            assertEquals(PacketType.SEND_MSG, sent.type)
            val ack = assertIs<GgEvent.Ack>(events.receive())
            assertEquals(seq, ack.seq)
            assertEquals(AckStatus.QUEUED, ack.status)
            assertEquals(DisconnectReason.Kicked, result.await())
        }
    }

    @Test
    fun pingIfIdleSendsOnlyWhenIdle(): Unit = runBlocking {
        val received = Channel<Frame>(Channel.UNLIMITED)
        val port = fakeServer { input, out, _ ->
            GgSession.readFrame(input) // login
            GgSession.write(out, Frame(PacketType.LOGIN_OK, ByteArray(0)))
            GgSession.readFrame(input) // contact list
            while (true) received.trySend(runCatching { GgSession.readFrame(input) }.getOrNull() ?: break)
        }
        val session = GgSession(GgConfig("127.0.0.1", port, 1, password))
        val events = Channel<GgEvent>(Channel.UNLIMITED)
        val result = async { session.run { events.send(it) } }
        withTimeout(10_000) {
            assertIs<GgEvent.LoggedIn>(events.receive())
            // The contact list was just written: not idle yet, no ping.
            assertEquals(false, session.pingIfIdle(minIdleMs = 60_000))
            assertEquals(true, session.pingIfIdle(minIdleMs = 0))
            assertEquals(PacketType.PING, received.receive().type)
            session.close()
            result.await()
        }
    }

    @Test
    fun wrongPassword() = runBlocking {
        val port = fakeServer { input, out, _ ->
            GgSession.readFrame(input)
            GgSession.write(out, Frame(PacketType.LOGIN_FAILED, ByteArray(0)))
            Thread.sleep(200)
        }
        val reason = withTimeout(10_000) { GgSession(GgConfig("127.0.0.1", port, 1, "bad")).run { } }
        assertEquals(DisconnectReason.LoginFailed, reason)
    }

    @Test
    fun connectionRefusedIsNetworkError(): Unit = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val reason = GgSession(GgConfig("127.0.0.1", port, 1, "x", connectTimeoutMs = 2000)).run { }
        assertIs<DisconnectReason.Network>(reason)
        Unit
    }
}
