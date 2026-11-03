package pl.ggretro.android.service

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import pl.ggretro.android.R
import pl.ggretro.android.data.ContactEntity
import pl.ggretro.android.data.displayName
import pl.ggretro.android.ui.MainActivity

/**
 * Recent conversations as sharing shortcuts, so contacts appear directly in the system share
 * sheet (Direct Share). Declared in res/xml/share_targets.xml; androidx.sharetarget covers
 * Android 8–9.
 */
object ShareShortcuts {
    const val CATEGORY = "pl.ggretro.android.category.SHARE_TARGET"
    private const val PREFIX = "gg-"
    private const val MIN_REPUSH_MS = 10 * 60_000L
    private val lastPush = HashMap<Int, Long>()

    fun id(uin: Int) = "$PREFIX$uin"

    fun uinFromId(id: String?): Int? = id?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toIntOrNull()

    /** A conversation with [contact] was used (message sent or received). */
    fun report(context: Context, contact: ContactEntity) {
        val now = System.currentTimeMillis()
        synchronized(lastPush) {
            val last = lastPush[contact.uin]
            if (last != null && now - last < MIN_REPUSH_MS) return
            lastPush[contact.uin] = now
        }
        val id = id(contact.uin)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_UIN, contact.uin)
        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(contact.displayName)
            .setLongLabel("${contact.displayName} (${contact.uin})")
            .setPerson(Person.Builder().setName(contact.displayName).setKey(id).build())
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .setIntent(intent)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }

    fun remove(context: Context, uin: Int) {
        synchronized(lastPush) { lastPush.remove(uin) }
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(id(uin))) }
    }

    fun removeAll(context: Context) {
        synchronized(lastPush) { lastPush.clear() }
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }
}
