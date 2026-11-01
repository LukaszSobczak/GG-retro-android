package pl.ggretro.android.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import pl.ggretro.protocol.Presence

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val uin: Int = 0,
    val password: String = "",
    val presence: Presence = Presence.AVAILABLE,
    val description: String = "",
    /** User logged in and wants to stay connected (service running). */
    val loggedIn: Boolean = false,
    val soundOnline: Boolean = true,
    /** Announce "Zaraz wracam" while the phone is locked (only when "Dostępny" is chosen). */
    val autoAway: Boolean = true,
    val soundMessage: Boolean = true,
    val enterSends: Boolean = true,
    val emoticonSet: Int = 1,
) {
    val hasAccount: Boolean get() = uin > 0 && password.isNotEmpty() && host.isNotBlank()

    companion object {
        const val DEFAULT_HOST = "gadu-gadu.k13.pl"
        const val DEFAULT_PORT = 8074
    }
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val host = stringPreferencesKey("host")
        val port = intPreferencesKey("port")
        val uin = intPreferencesKey("uin")
        val password = stringPreferencesKey("password")
        val presence = stringPreferencesKey("presence")
        val description = stringPreferencesKey("description")
        val loggedIn = booleanPreferencesKey("logged_in")
        val soundOnline = booleanPreferencesKey("sound_online")
        val autoAway = booleanPreferencesKey("auto_away")
        val soundMessage = booleanPreferencesKey("sound_message")
        val enterSends = booleanPreferencesKey("enter_sends")
        val emoticonSet = intPreferencesKey("emoticon_set")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    private fun Preferences.toSettings() = AppSettings(
        host = this[Keys.host] ?: AppSettings.DEFAULT_HOST,
        port = this[Keys.port] ?: AppSettings.DEFAULT_PORT,
        uin = this[Keys.uin] ?: 0,
        password = this[Keys.password] ?: "",
        presence = this[Keys.presence]?.let { runCatching { Presence.valueOf(it) }.getOrNull() } ?: Presence.AVAILABLE,
        description = this[Keys.description] ?: "",
        loggedIn = this[Keys.loggedIn] ?: false,
        soundOnline = this[Keys.soundOnline] ?: true,
        autoAway = this[Keys.autoAway] ?: true,
        soundMessage = this[Keys.soundMessage] ?: true,
        enterSends = this[Keys.enterSends] ?: true,
        emoticonSet = this[Keys.emoticonSet] ?: 1,
    )

    suspend fun saveAccount(host: String, port: Int, uin: Int, password: String) {
        context.dataStore.edit {
            it[Keys.host] = host.trim()
            it[Keys.port] = port
            it[Keys.uin] = uin
            it[Keys.password] = password
        }
    }

    suspend fun setServer(host: String, port: Int) {
        context.dataStore.edit {
            it[Keys.host] = host.trim()
            it[Keys.port] = port
        }
    }

    suspend fun setLoggedIn(value: Boolean) {
        context.dataStore.edit { it[Keys.loggedIn] = value }
    }

    suspend fun setStatus(presence: Presence, description: String) {
        context.dataStore.edit {
            it[Keys.presence] = presence.name
            it[Keys.description] = description
        }
    }

    suspend fun setSoundOnline(value: Boolean) = context.dataStore.edit { it[Keys.soundOnline] = value }

    suspend fun setAutoAway(value: Boolean) = context.dataStore.edit { it[Keys.autoAway] = value }

    suspend fun setSoundMessage(value: Boolean) = context.dataStore.edit { it[Keys.soundMessage] = value }

    suspend fun setEnterSends(value: Boolean) = context.dataStore.edit { it[Keys.enterSends] = value }

    suspend fun setEmoticonSet(value: Int) = context.dataStore.edit { it[Keys.emoticonSet] = value }

    suspend fun forgetPassword() {
        context.dataStore.edit {
            it.remove(Keys.password)
            it[Keys.loggedIn] = false
        }
    }
}
