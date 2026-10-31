package pl.ggretro.android.data

import pl.ggretro.protocol.Presence

/** GG 6 names of the user-selectable statuses. */
fun presenceLabel(p: Presence): String = when (p) {
    Presence.AVAILABLE -> "Dostępny"
    Presence.BUSY -> "Zaraz wracam"
    Presence.INVISIBLE -> "Niewidoczny"
    Presence.NOT_AVAILABLE -> "Niedostępny"
}

/** Status with its description, e.g. "Zaraz wracam: na obiedzie". */
fun statusLine(p: Presence, description: String): String =
    presenceLabel(p) + if (description.isNotBlank()) ": ${description.trim()}" else ""
