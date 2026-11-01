package pl.ggretro.android.data

import pl.ggretro.protocol.Presence

/**
 * "Zaraz wracam" while the phone is locked.
 *
 * Only a user who chose "Dostępny" is switched to "Zaraz wracam" (keeping the description) when the
 * screen locks, and switched back when it unlocks. "Zaraz wracam", "Niewidoczny" and "Niedostępny"
 * chosen by the user are never touched. The user's choice in the settings stays unchanged; only the
 * presence announced to the server differs.
 */
object AutoAway {
    /** Should locking the screen switch the user to "Zaraz wracam"? */
    fun shouldGoAway(chosen: Presence, enabled: Boolean, alreadyAway: Boolean): Boolean =
        enabled && !alreadyAway && chosen == Presence.AVAILABLE

    /** Presence announced to the server for the user's [chosen] one. */
    fun effective(chosen: Presence, away: Boolean): Presence =
        if (away && chosen == Presence.AVAILABLE) Presence.BUSY else chosen
}
