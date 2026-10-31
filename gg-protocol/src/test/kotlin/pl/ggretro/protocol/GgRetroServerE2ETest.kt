package pl.ggretro.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end tests against a real gg-retro server (https://github.com/webs1821/gg-retro).
 * CI starts the server and registers accounts with e2e/start_gg_retro.py, then exports:
 * GG_E2E_HOST, GG_E2E_PORT, GG_E2E_PASSWORD, GG_E2E_UINS (comma separated, at least 3).
 */
@EnabledIfEnvironmentVariable(named = "GG_E2E_PORT", matches = "\\d+")
class GgRetroServerE2ETest {
    private val host = System.getenv("GG_E2E_HOST") ?: "127.0.0.1"
    private val port = System.getenv("GG_E2E_PORT")!!.toInt()
    private val password = System.getenv("GG_E2E_PASSWORD")!!
    private val uins = System.getenv("GG_E2E_UINS")!!.split(',').map { it.trim().toInt() }

    private inner class Client(
        scope: CoroutineScope,
        val uin: Int,
        contacts: List<Int> = emptyList(),
        status: Int = GgStatus.AVAIL,
        description: String? = null,
        pass: String = password,
    ) {
        val events = Channel<GgEvent>(Channel.UNLIMITED)
        val session = GgSession(GgConfig(host, port, uin, pass, status, description, contacts.map { ContactEntry(it) }))
        val result: Deferred<DisconnectReason> = scope.async(Dispatchers.IO) { session.run { events.send(it) } }

        suspend inline fun <reified T : GgEvent> await(crossinline predicate: (T) -> Boolean = { true }): T {
            while (true) {
                val e = events.receive()
                if (e is T && predicate(e)) return e
            }
        }

        suspend fun loggedIn() = await<GgEvent.LoggedIn>()

        suspend fun stop() {
            session.close()
            result.await()
        }
    }

    private fun e2e(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(60_000) { block() } }

    @Test
    fun wrongPasswordIsRejected() = e2e {
        val c = Client(this, uins[0], pass = "definitely-wrong")
        assertEquals(DisconnectReason.LoginFailed, c.result.await())
    }

    @Test
    fun statusDescriptionMessagesAcksAndFormatting() = e2e {
        val (a, b) = uins
        val alice = Client(this, a, contacts = listOf(b), status = GgStatus.BUSY, description = "Zajęta – żółw")
        alice.loggedIn()
        val bob = Client(this, b, contacts = listOf(a))
        bob.loggedIn()
        val st = bob.await<GgEvent.Status> { it.status.uin == a && GgStatus.isOnline(it.status.status) }.status
        assertEquals(GgStatus.BUSY_DESCR, GgStatus.base(st.status))
        assertEquals("Zajęta – żółw", st.description)

        val styled = StyledText(
            "Zażółć gęślą jaźń\ndruga linia",
            listOf(TextFormat(0, bold = true), TextFormat(7, color = 0xff0000), TextFormat(18, italic = true, underline = true)),
        )
        val seq = bob.session.sendMessage(a, styled)
        val ack = bob.await<GgEvent.Ack> { it.seq == seq }
        // gg-retro stores every message durably and always answers "queued" (GG6 has no receipt ack).
        assertTrue(ack.status == AckStatus.DELIVERED || ack.status == AckStatus.QUEUED, "ack ${ack.status}")
        val got = alice.await<GgEvent.Message> { it.sender == b }
        assertEquals(styled.text, got.styled.text)
        assertEquals(RichText.normalize(styled.formats), got.styled.formats)

        // Status change with a new description reaches the contact.
        alice.session.setStatus(GgStatus.AVAIL_DESCR, "już jestem")
        val changed = bob.await<GgEvent.Status> { it.status.uin == a && it.status.description == "już jestem" }
        assertEquals(GgStatus.AVAIL_DESCR, GgStatus.base(changed.status.status))
        alice.stop()
        bob.stop()
    }

    @Test
    fun offlineMessageIsDeliveredAfterLogin() = e2e {
        val (a, b) = uins
        val bob = Client(this, b, contacts = listOf(a))
        bob.loggedIn()
        val seq = bob.session.sendMessage(a, StyledText("wiadomość offline"))
        assertEquals(AckStatus.QUEUED, bob.await<GgEvent.Ack> { it.seq == seq }.status)
        val alice = Client(this, a, contacts = listOf(b))
        val msg = alice.await<GgEvent.Message> { it.sender == b }
        assertEquals("wiadomość offline", msg.styled.text)
        alice.stop()
        bob.stop()
    }

    @Test
    fun inlineImageTransfer() = e2e {
        val (a, b) = uins
        val alice = Client(this, a, contacts = listOf(b))
        alice.loggedIn()
        val bob = Client(this, b, contacts = listOf(a))
        bob.loggedIn()
        val data = Random(1).nextBytes(100_000)
        val id = ImageId.of(data)
        alice.session.sendMessage(b, StyledText(RichText.IMAGE_PLACEHOLDER.toString(), listOf(TextFormat(0, image = id))))
        val msg = bob.await<GgEvent.Message> { it.sender == a }
        assertEquals(listOf(id), msg.styled.images)
        assertTrue(bob.session.requestImage(a, id))
        val req = alice.await<GgEvent.ImageRequest>()
        assertEquals(ImageId.of(data), req.id)
        alice.session.sendImageData(req.from, data, "obrazek.jpg")
        val img = bob.await<GgEvent.ImageReceived>()
        assertEquals(a, img.owner)
        assertEquals("obrazek.jpg", img.fileName)
        assertContentEquals(data, img.data)
        alice.stop()
        bob.stop()
    }

    @Test
    fun serverSideContactList() = e2e {
        val (a, b, c) = uins
        val alice = Client(this, a)
        alice.loggedIn()
        alice.session.storeUserList(listOf(ImportedContact(b, "Bartek"), ImportedContact(c, "Celina", "Praca")))
        alice.await<GgEvent.UserListStored>()
        alice.session.requestUserList()
        val list = alice.await<GgEvent.UserList>()
        assertEquals(listOf(b to "Bartek", c to "Celina"), list.contacts.map { it.uin to it.name })
        alice.stop()
    }

    @Test
    fun secondLoginKicksFirst() = e2e {
        val a = uins[2]
        val first = Client(this, a)
        first.loggedIn()
        delay(300)
        val second = Client(this, a)
        second.loggedIn()
        assertIs<DisconnectReason.Kicked>(first.result.await())
        second.stop()
    }
}
