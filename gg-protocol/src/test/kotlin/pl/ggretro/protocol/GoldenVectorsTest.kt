package pl.ggretro.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Byte-exact vectors produced by the gg-retro server's own codec
 * (webs1821/gg-retro protocol/src/snapshots, *.snap.packet files).
 */
class GoldenVectorsTest {
    private fun vector(name: String): ByteArray =
        javaClass.getResourceAsStream("/vectors/$name.bin")!!.readBytes()

    private fun frame(bytes: ByteArray): Frame = GgSession.readFrame(bytes.inputStream())

    @Test
    fun loginHashMatchesServer() {
        assertEquals(3039321583L, GgHash.loginHash("Random password1", 111).toLong() and 0xffffffffL)
    }

    @Test
    fun login60() {
        val f = ClientPackets.login60(123456, "my password", 33, GgStatus.AVAIL, null, 255)
        assertContentEquals(vector("it_handles_login60_packet"), f.toBytes())
    }

    @Test
    fun newStatusWithDescription() {
        val f = ClientPackets.newStatus(GgStatus.AVAIL_DESCR, "Hello world!", 1234567890)
        assertContentEquals(vector("it_handles_new_status_packet"), f.toBytes())
    }

    @Test
    fun newStatusWithoutDescription() {
        assertContentEquals(vector("it_handles_new_status_packet_without_description"), ClientPackets.newStatus(GgStatus.AVAIL, null).toBytes())
    }

    @Test
    fun notifyFirstAndLast() {
        assertContentEquals(
            vector("it_handles_notify_first_packet"),
            ClientPackets.notifyFirst(listOf(ContactEntry(1000, UserType.BUDDY), ContactEntry(2000, UserType.FRIEND))).toBytes(),
        )
        assertContentEquals(
            vector("it_handles_notify_last_packet"),
            ClientPackets.notifyLast(listOf(ContactEntry(3000, UserType.BLOCKED))).toBytes(),
        )
    }

    @Test
    fun sendMessagePlainPolishAndFormatted() {
        assertContentEquals(
            vector("it_handles_send_message_packet"),
            ClientPackets.sendMessage(12345, 1, StyledText("Hello!")).toBytes(),
        )
        assertContentEquals(
            vector("it_handles_send_message_with_polish_chars"),
            ClientPackets.sendMessage(12345, 2, StyledText("Zażółć gęślą jaźń")).toBytes(),
        )
        assertContentEquals(
            vector("it_handles_send_message_with_bold_formatting"),
            ClientPackets.sendMessage(12345, 1, StyledText("Hello", listOf(TextFormat(0, bold = true)))).toBytes(),
        )
        assertContentEquals(
            vector("it_handles_message_with_italic_and_underline"),
            ClientPackets.sendMessage(
                12345, 1,
                StyledText("Styled text", listOf(TextFormat(0, bold = true, italic = true, underline = true))),
                MessageClass.MSG,
            ).toBytes(),
        )
    }

    @Test
    fun decodeServerPackets() {
        assertEquals(ServerPacket.Welcome(5000), ServerPacket.decode(frame(vector("it_handles_welcome_packet"))))
        assertEquals(ServerPacket.LoginOk, ServerPacket.decode(frame(vector("it_handles_login_ok_packet"))))
        assertEquals(ServerPacket.LoginFailed, ServerPacket.decode(frame(vector("it_handles_login_failed_packet"))))
        assertEquals(
            ServerPacket.SendAck(AckStatus.DELIVERED, 12345, 1),
            ServerPacket.decode(frame(vector("it_handles_send_message_ack_packet"))),
        )
    }

    @Test
    fun decodeRecvMessage() {
        val m = assertIs<ServerPacket.Message>(ServerPacket.decode(frame(vector("it_handles_recv_message_packet"))))
        assertEquals(54321, m.sender)
        assertEquals(42, m.seq)
        assertEquals(1234567890L, m.time)
        assertEquals(MessageClass.MSG, m.messageClass)
        assertEquals(StyledText("Hi there!"), m.styled)
    }

    @Test
    fun decodeRecvMessageWithFormats() {
        val m = assertIs<ServerPacket.Message>(ServerPacket.decode(frame(vector("it_handles_recv_message_with_multiple_formats"))))
        assertEquals(
            StyledText(
                "Hello World",
                listOf(TextFormat(0, bold = true), TextFormat(5), TextFormat(6, color = 0xff0000)),
            ),
            m.styled,
        )
    }

    @Test
    fun decodeNotifyReply60() {
        val p = assertIs<ServerPacket.NotifyReply>(ServerPacket.decode(frame(vector("it_handles_notify_reply60_packet"))))
        assertEquals(
            listOf(
                ContactStatus(1000, 0x40, GgStatus.AVAIL_DESCR, 64, "Hello!", 1234567890),
                ContactStatus(2000, 0, GgStatus.NOT_AVAIL, 0, null, null),
            ),
            p.statuses,
        )
    }

    @Test
    fun decodeStatus60() {
        val p = assertIs<ServerPacket.Status>(ServerPacket.decode(frame(vector("it_handles_status60_packet"))))
        assertEquals(ContactStatus(12345, 0x40, GgStatus.AVAIL_DESCR, 64, "Working", 1234567890), p.status)
    }

    @Test
    fun status60WithoutDescription() {
        val payload = WireWriter().u32(777).u8(GgStatus.BUSY).u32(0).u16(0).u8(0x20).u8(255).u8(0).toByteArray()
        val p = assertIs<ServerPacket.Status>(ServerPacket.decode(Frame(PacketType.STATUS60, payload)))
        assertEquals(777, p.status.uin)
        assertEquals(GgStatus.BUSY, p.status.status)
        assertNull(p.status.description)
    }
}
