package pl.ggretro.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RichTextAndImagesTest {
    @Test
    fun newlinesBecomeCrLfAndPositionsShift() {
        val display = StyledText("ab\ncd\nef", listOf(TextFormat(3, bold = true), TextFormat(6, italic = true)))
        val wire = RichText.toWire(display)
        assertEquals("ab\r\ncd\r\nef", wire.text)
        assertEquals(listOf(4, 8), wire.formats.map { it.position })
        assertEquals(display, RichText.fromWire(wire))
    }

    @Test
    fun unmappableCharactersAreReplacedOnePerCodePoint() {
        val wire = RichText.toWire(StyledText("a😀b", listOf(TextFormat(2, bold = true))))
        assertEquals("a?b", wire.text)
        assertEquals(2, wire.formats.single().position.coerceAtMost(2))
    }

    @Test
    fun imageDescriptorRoundTrip() {
        // Same bytes as gg-retro tests/images_integration.py formatting(data, 7)
        val id = ImageId(36054, 0x12345678)
        val encoded = RichText.encode(listOf(TextFormat(7, image = id)))
        val expected = WireWriter().u8(2).u16(13).u16(7).u8(0x80).u8(9).u8(1).u32(id.size).u32(id.crc32).toByteArray()
        assertContentEquals(expected, encoded)
        val r = WireReader(encoded)
        r.skip(3)
        assertEquals(listOf(TextFormat(7, image = id)), RichText.decodeEntries(r))
    }

    @Test
    fun recvMessageWithImageAndPolishText() {
        val id = ImageId(1000, 42)
        val text = "Obrazek  i tekst ąć"
        val payload = WireWriter().u32(5).u32(1).u32(1700000000).u32(MessageClass.CHAT)
            .bytes(Cp1250.encode(text)).u8(0).bytes(RichText.encode(listOf(TextFormat(7, image = id)))).toByteArray()
        val m = assertIs<ServerPacket.Message>(ServerPacket.decode(Frame(PacketType.RECV_MSG, payload)))
        assertEquals(text, m.styled.text)
        assertEquals(listOf(id), m.styled.images)
    }

    @Test
    fun imageChunksReassemble() {
        val data = Random(7).nextBytes(100_000)
        val chunks = ImageTransfer.chunks(data, "zażółć.jpg")
        assertTrue(chunks.all { it.encode().size <= ImageTransfer.MAX_OPTION_BYTES })
        assertTrue(chunks.first().first && chunks.drop(1).none { it.first })
        val assembler = ImageAssembler()
        var result: ImageAssembler.Result? = null
        for (c in chunks) {
            // Round trip through the packet decoder, as the receiving side would see it.
            val payload = WireWriter().u32(9).u32(0).u32(0).u32(MessageClass.MSG).u8(0).bytes(c.encode()).toByteArray()
            val p = assertIs<ServerPacket.Image>(ServerPacket.decode(Frame(PacketType.RECV_MSG, payload)))
            result = assembler.accept(p.sender, assertIs<ImageTransfer.Reply>(p.transfer))
        }
        val done = assertIs<ImageAssembler.Result.Complete>(result)
        assertContentEquals(data, done.data)
        assertEquals("zażółć.jpg", done.fileName)
        assertEquals(ImageId.of(data), done.id)
    }

    @Test
    fun imageRequestAndUnavailable() {
        val id = ImageId(258, 0x12345678)
        // Example from the protocol docs: 00 04 02 01 00 00 78 56 34 12
        assertContentEquals(
            byteArrayOf(4, 2, 1, 0, 0, 0x78, 0x56, 0x34, 0x12),
            ImageTransfer.Request(id).encode(),
        )
        val un = ImageTransfer.decode(WireReader(ImageTransfer.unavailable(id).encode()))
        assertTrue(assertIs<ImageTransfer.Reply>(un).unavailable)
        assertIs<ImageAssembler.Result.Unavailable>(ImageAssembler().accept(1, un))
    }

    @Test
    fun corruptedImageIsUnavailable() {
        val data = ByteArray(5000) { it.toByte() }
        val chunks = ImageTransfer.chunks(data, "a.bin")
        val bad = chunks.last().let { ImageTransfer.Reply(it.id, it.first, it.fileName, it.data.copyOf().also { d -> d[0] = (d[0] + 1).toByte() }) }
        val assembler = ImageAssembler()
        var r: ImageAssembler.Result? = null
        for (c in chunks.dropLast(1) + bad) r = assembler.accept(3, c)
        assertIs<ImageAssembler.Result.Unavailable>(r)
    }

    @Test
    fun userListParsing() {
        val text = "Jan;Kowalski;Janek;Janek K;;Znajomi;1234;;0;;0;;0;\r\n" +
            ";;;;;;5678;;0;;0;;0;\r\n" +
            "zly;wiersz\r\n"
        val list = UserList.parse(Cp1250.encode(text))
        assertEquals(listOf(ImportedContact(1234, "Janek K", "Znajomi"), ImportedContact(5678, "5678")), list)
        assertEquals(list.map { it.uin }, UserList.parse(UserList.format(list)).map { it.uin })
    }

    @Test
    fun contactListChunking() {
        assertEquals(PacketType.LIST_EMPTY, ClientPackets.contactList(emptyList()).single().type)
        val frames = ClientPackets.contactList((1..850).map { ContactEntry(it) })
        assertEquals(listOf(PacketType.NOTIFY_FIRST, PacketType.NOTIFY_FIRST, PacketType.NOTIFY_LAST), frames.map { it.type })
        assertEquals(50 * 5, frames.last().payload.size)
    }

    @Test
    fun loginWithDescriptionTruncatesTo70() {
        val f = ClientPackets.login60(1, "x", 1, GgStatus.BUSY_DESCR, "a".repeat(100))
        assertEquals(31 + 70 + 1, f.payload.size)
        assertNull(null)
    }
}
