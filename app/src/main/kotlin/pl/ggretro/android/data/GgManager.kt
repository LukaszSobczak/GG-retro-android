package pl.ggretro.android.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.ggretro.android.notify.Notifier
import pl.ggretro.android.notify.Sounds
import pl.ggretro.android.service.GgService
import pl.ggretro.protocol.ContactEntry
import pl.ggretro.protocol.DisconnectReason
import pl.ggretro.protocol.GgConfig
import pl.ggretro.protocol.GgEvent
import pl.ggretro.protocol.GgSession
import pl.ggretro.protocol.GgStatus
import pl.ggretro.protocol.ImageId
import pl.ggretro.protocol.ImportedContact
import pl.ggretro.protocol.Presence
import pl.ggretro.protocol.RichText
import pl.ggretro.protocol.StyledText
import pl.ggretro.protocol.TextFormat

sealed interface ConnState {
    /** Not connected and not trying (logged out or status "Niedostępny"). */
    data object Offline : ConnState
    data object Connecting : ConnState
    data object Online : ConnState
    /** Connection lost; waiting before the next attempt. */
    data class Waiting(val message: String) : ConnState
    /** Stopped because of an error that needs the user (bad password, logged in elsewhere). */
    data class Error(val message: String) : ConnState
}

/**
 * Owns the GG connection: reconnects, stores everything in the database, answers image requests,
 * triggers notifications and sounds. Lives as long as the app process; [GgService] keeps the
 * process alive while connected.
 */
class GgManager(
    private val context: Context,
    private val db: GgDatabase,
    private val settings: SettingsStore,
    private val images: ImageStore,
    private val notifier: Notifier,
    private val sounds: Sounds,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null
    @Volatile private var session: GgSession? = null

    private val _state = MutableStateFlow<ConnState>(ConnState.Offline)
    val state: StateFlow<ConnState> = _state

    private val _running = MutableStateFlow(false)
    /** True while the connection loop runs; the service stops itself when this turns false. */
    val running: StateFlow<Boolean> = _running

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts

    /** Contact whose chat window is currently visible (no notification for it, only a sound). */
    @Volatile var activeChat: Int? = null

    val isOnline: Boolean get() = session?.loggedIn == true

    private var loopGeneration = 0
    @Volatile private var restartRequested = false

    init {
        // Nothing is connected right after process start: clear stale presence and pending sends.
        scope.launch { cleanupAfterSession() }
    }

    @Synchronized
    fun start() {
        // isCompleted (not isActive): a loop that is still cancelling must not be doubled.
        if (loopJob?.isCompleted == false) return
        val generation = ++loopGeneration
        _running.value = true
        loopJob = scope.launch {
            try {
                connectionLoop()
            } finally {
                withContext(NonCancellable) {
                    val current = synchronized(this@GgManager) { loopGeneration == generation }
                    if (current) {
                        session = null
                        cleanupAfterSession()
                        if (_state.value !is ConnState.Error) _state.value = ConnState.Offline
                        _running.value = false
                    }
                }
            }
        }
    }

    /** Stops the loop without changing settings (e.g. the service is being destroyed). */
    fun stopLoop() {
        loopJob?.cancel()
        session?.close()
    }

    /** Reconnects with fresh settings (e.g. new server address) without stopping the service. */
    fun restartConnection() = scope.launch {
        settings.setLoggedIn(true)
        if (loopJob?.isCompleted != false) {
            GgService.start(context)
            return@launch
        }
        restartRequested = true
        session?.close() ?: wake.trySend(Unit)
    }

    /** Called whenever no session is alive: nothing can be online or still in flight. */
    private suspend fun cleanupAfterSession() {
        db.contacts().markAllOffline()
        db.messages().failPending()
        db.images().failPending()
    }

    fun networkAvailable() {
        wake.trySend(Unit)
    }

    private suspend fun connectionLoop() {
        var backoffMs = 2_000L
        while (scope.isActive) {
            val s = settings.current()
            if (!s.hasAccount || !s.loggedIn || s.presence == Presence.NOT_AVAILABLE) {
                _state.value = ConnState.Offline
                return
            }
            _state.value = ConnState.Connecting
            // A restart requested while we were waiting is satisfied by this fresh attempt.
            restartRequested = false
            val description = s.description.trim().ifEmpty { null }
            val contacts = db.contacts().all().map { ContactEntry(it.uin) }
            val gg = GgSession(
                GgConfig(
                    host = s.host,
                    port = s.port,
                    uin = s.uin,
                    password = s.password,
                    status = s.presence.code(description != null),
                    description = description,
                    contacts = contacts,
                ),
            )
            session = gg
            var loggedInAt = 0L
            val reason = gg.run { event ->
                if (event == GgEvent.LoggedIn) loggedInAt = System.currentTimeMillis()
                handle(gg, event)
            }
            if (session === gg) session = null
            cleanupAfterSession()
            // Reset the backoff only after a session that actually stayed up for a while.
            if (loggedInAt > 0 && System.currentTimeMillis() - loggedInAt > 60_000L) backoffMs = 2_000L
            if (restartRequested) {
                restartRequested = false
                backoffMs = 2_000L
                continue
            }
            when (reason) {
                DisconnectReason.LoginFailed -> {
                    _state.value = ConnState.Error("Nieprawidłowy numer GG lub hasło.")
                    settings.setLoggedIn(false)
                    return
                }
                DisconnectReason.Kicked -> {
                    _state.value = ConnState.Error("Rozłączono: ktoś zalogował się na ten numer z innego miejsca.")
                    settings.setLoggedIn(false)
                    return
                }
                DisconnectReason.ClosedByUser -> {
                    // A UI action may have asked to be online again while we were closing.
                    val cur = settings.current()
                    if (cur.hasAccount && cur.loggedIn && cur.presence != Presence.NOT_AVAILABLE) continue
                    _state.value = ConnState.Offline
                    return
                }
                is DisconnectReason.Network -> {
                    val seconds = backoffMs / 1000
                    _state.value = ConnState.Waiting("Brak połączenia z serwerem. Ponowna próba za $seconds s…")
                    withTimeoutOrNull(backoffMs) { wake.receive() }
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    private suspend fun handle(gg: GgSession, event: GgEvent) {
        when (event) {
            GgEvent.LoggedIn -> _state.value = ConnState.Online
            is GgEvent.Status -> {
                val st = event.status
                val contact = db.contacts().get(st.uin) ?: return
                val wasOnline = GgStatus.isOnline(contact.status)
                db.contacts().updateStatus(st.uin, GgStatus.base(st.status), st.description, st.imageSize)
                if (!event.initial && !wasOnline && GgStatus.isOnline(st.status) && settings.current().soundOnline) {
                    sounds.playOnline()
                }
            }
            is GgEvent.Message -> onMessage(gg, event)
            is GgEvent.Ack -> db.messages().updateAck(event.recipient, event.seq, event.status)
            is GgEvent.ImageRequest -> {
                val data = images.read(event.id)
                if (data != null) gg.sendImageData(event.from, data, "obrazek.jpg") else gg.sendImageUnavailable(event.from, event.id)
            }
            is GgEvent.ImageReceived -> {
                images.save(event.data)
                db.images().upsert(ImageEntity(event.id.key, event.owner, ImageState.READY))
            }
            is GgEvent.ImageUnavailable -> {
                if (!images.has(event.id)) db.images().upsert(ImageEntity(event.id.key, event.owner, ImageState.UNAVAILABLE))
            }
            is GgEvent.UserList -> mergeImported(gg, event.contacts)
            GgEvent.UserListStored -> _toasts.tryEmit("Lista kontaktów zapisana na serwerze.")
        }
    }

    private suspend fun onMessage(gg: GgSession, m: GgEvent.Message) {
        val now = System.currentTimeMillis()
        val time = if (m.time > 0) minOf(m.time * 1000, now) else now
        var contact = db.contacts().get(m.sender)
        if (contact == null) {
            contact = ContactEntity(m.sender, m.sender.toString())
            db.contacts().insertIfMissing(contact)
            gg.addContact(m.sender)
        }
        db.messages().insert(
            MessageEntity(
                contactUin = m.sender,
                outgoing = false,
                time = time,
                text = m.styled.text,
                formats = FormatCodec.encode(m.styled.formats),
            ),
        )
        for (id in m.styled.images.distinct()) requestImageIfNeeded(gg, m.sender, id)
        val sound = settings.current().soundMessage
        if (activeChat == m.sender) {
            db.contacts().touch(m.sender, now)
            if (sound) sounds.playMessage()
        } else {
            db.contacts().incrementUnread(m.sender, now)
            if (notifier.canNotify()) notifier.showMessage(contact, preview(m.styled), sound)
            else if (sound) sounds.playMessage()
        }
    }

    private suspend fun requestImageIfNeeded(gg: GgSession, owner: Int, id: ImageId) {
        if (!id.valid() || images.has(id)) return
        val now = System.currentTimeMillis()
        val row = db.images().get(id.key)
        if (row != null && row.state == ImageState.PENDING && now - row.requestedAt < 60_000L) return
        val state = if (gg.requestImage(owner, id)) ImageState.PENDING else ImageState.UNAVAILABLE
        db.images().upsert(ImageEntity(id.key, owner, state, now))
    }

    private suspend fun mergeImported(gg: GgSession, imported: List<ImportedContact>) {
        var added = 0
        for (c in imported) {
            val existing = db.contacts().get(c.uin)
            if (existing == null) {
                db.contacts().upsert(ContactEntity(c.uin, c.name))
                gg.addContact(c.uin)
                added++
            } else if (existing.name == existing.uin.toString() && c.name != existing.name) {
                db.contacts().rename(c.uin, c.name)
            }
        }
        _toasts.tryEmit(if (imported.isEmpty()) "Lista kontaktów na serwerze jest pusta." else "Zaimportowano kontakty: $added nowych.")
    }

    private fun preview(styled: StyledText): String {
        val text = styled.text.replace(RichText.IMAGE_PLACEHOLDER, ' ').trim()
        return when {
            text.isNotEmpty() -> text
            styled.images.isNotEmpty() -> "[obrazek]"
            else -> ""
        }
    }

    // ---- actions from the UI ----

    fun login(host: String, port: Int, uin: Int, password: String) = scope.launch {
        settings.saveAccount(host, port, uin, password)
        settings.setLoggedIn(true)
        val s = settings.current()
        if (s.presence == Presence.NOT_AVAILABLE) settings.setStatus(Presence.AVAILABLE, s.description)
        GgService.start(context)
    }

    fun reconnect() = scope.launch {
        settings.setLoggedIn(true)
        GgService.start(context)
    }

    fun logout() = scope.launch {
        settings.setLoggedIn(false)
        closeSession(settings.current().description.trim().ifEmpty { null })
        _state.value = ConnState.Offline
    }

    /** Goes offline: a logged-in session says goodbye (with description), anything else is aborted. */
    private fun closeSession(description: String?) {
        val s = session
        when {
            s == null -> stopLoop()
            s.loggedIn -> s.logout(description)
            else -> s.close()
        }
        if (session === s) session = null
    }

    fun setStatus(presence: Presence, description: String) = scope.launch {
        val desc = description.trim().take(GgStatus.MAX_DESCRIPTION)
        settings.setStatus(presence, desc)
        val s = session
        when {
            presence == Presence.NOT_AVAILABLE -> closeSession(desc.ifEmpty { null })
            // Also while still connecting: frames are queued and written right after login.
            s != null -> s.setStatus(presence.code(desc.isNotEmpty()), desc.ifEmpty { null })
            else -> {
                if (settings.current().hasAccount) {
                    settings.setLoggedIn(true)
                    GgService.start(context)
                    wake.trySend(Unit)
                }
            }
        }
    }

    fun sendText(uin: Int, styled: StyledText) = scope.launch { sendStyled(uin, styled) }

    private suspend fun sendStyled(uin: Int, styled: StyledText) {
        if (styled.text.isBlank() && styled.images.isEmpty()) return
        val s = session?.takeIf { it.loggedIn }
        val seq = s?.nextSeq() ?: 0
        val id = db.messages().insert(
            MessageEntity(
                contactUin = uin,
                outgoing = true,
                time = System.currentTimeMillis(),
                text = styled.text,
                formats = FormatCodec.encode(styled.formats),
                seq = seq,
                ack = if (s != null) Ack.PENDING else Ack.FAILED,
            ),
        )
        db.contacts().touch(uin, System.currentTimeMillis())
        val sent = s != null && s.sendMessage(uin, styled, seq) != null
        if (!sent) {
            if (s != null) db.messages().updateAckById(id, Ack.FAILED)
            _toasts.tryEmit("Brak połączenia – wiadomość nie została wysłana.")
        }
    }

    fun sendImage(uin: Int, uri: Uri) = scope.launch {
        try {
            val contact = db.contacts().get(uin)
            val limit = contact?.imageSizeKb?.takeIf { it > 0 }?.let { it * 1024 } ?: ImageStore.DEFAULT_MAX_BYTES
            val data = images.prepareForSending(uri, minOf(limit, ImageStore.DEFAULT_MAX_BYTES))
            val id = images.save(data)
            db.images().upsert(ImageEntity(id.key, settings.current().uin, ImageState.READY))
            sendStyled(uin, StyledText(RichText.IMAGE_PLACEHOLDER.toString(), listOf(TextFormat(0, image = id))))
        } catch (e: Exception) {
            _toasts.tryEmit("Nie udało się wysłać obrazka: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun retryImage(owner: Int, id: ImageId) = scope.launch {
        val s = session?.takeIf { it.loggedIn }
        if (s == null) {
            _toasts.tryEmit("Brak połączenia.")
            return@launch
        }
        db.images().upsert(ImageEntity(id.key, owner, ImageState.PENDING, System.currentTimeMillis()))
        if (!s.requestImage(owner, id)) _toasts.tryEmit("Za dużo próśb o obrazki – spróbuj za minutę.")
    }

    fun addContact(uin: Int, name: String) = scope.launch {
        val existing = db.contacts().get(uin)
        if (existing != null) {
            db.contacts().rename(uin, name.ifBlank { existing.name })
        } else {
            db.contacts().upsert(ContactEntity(uin, name.ifBlank { uin.toString() }))
            session?.addContact(uin)
        }
    }

    fun renameContact(uin: Int, name: String) = scope.launch { db.contacts().rename(uin, name.ifBlank { uin.toString() }) }

    fun removeContact(uin: Int) = scope.launch {
        db.contacts().delete(uin)
        db.messages().deleteConversation(uin)
        session?.removeContact(uin)
        notifier.cancel(uin)
    }

    fun clearHistory(uin: Int) = scope.launch { db.messages().deleteConversation(uin) }

    fun importContacts() {
        val s = session?.takeIf { it.loggedIn }
        if (s == null) _toasts.tryEmit("Najpierw połącz się z serwerem.") else s.requestUserList()
    }

    fun exportContacts() = scope.launch {
        val s = session?.takeIf { it.loggedIn }
        if (s == null) {
            _toasts.tryEmit("Najpierw połącz się z serwerem.")
            return@launch
        }
        s.storeUserList(db.contacts().all().map { ImportedContact(it.uin, it.displayName) })
    }

    fun openedChat(uin: Int) = scope.launch {
        db.contacts().clearUnread(uin)
        notifier.cancel(uin)
    }
}
