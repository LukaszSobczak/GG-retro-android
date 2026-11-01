package pl.ggretro.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.ggretro.android.data.AutoAway
import pl.ggretro.protocol.Presence

class AutoAwayTest {
    @Test
    fun lockSwitchesOnlyAvailableToBusy() {
        assertTrue(AutoAway.shouldGoAway(Presence.AVAILABLE, enabled = true, alreadyAway = false))
        // "Zaraz wracam", "Niewidoczny" and "Niedostępny" chosen by the user stay as they are.
        assertFalse(AutoAway.shouldGoAway(Presence.BUSY, enabled = true, alreadyAway = false))
        assertFalse(AutoAway.shouldGoAway(Presence.INVISIBLE, enabled = true, alreadyAway = false))
        assertFalse(AutoAway.shouldGoAway(Presence.NOT_AVAILABLE, enabled = true, alreadyAway = false))
    }

    @Test
    fun disabledOrAlreadyAwayDoesNothing() {
        assertFalse(AutoAway.shouldGoAway(Presence.AVAILABLE, enabled = false, alreadyAway = false))
        assertFalse(AutoAway.shouldGoAway(Presence.AVAILABLE, enabled = true, alreadyAway = true))
    }

    @Test
    fun effectivePresence() {
        assertEquals(Presence.BUSY, AutoAway.effective(Presence.AVAILABLE, away = true))
        assertEquals(Presence.AVAILABLE, AutoAway.effective(Presence.AVAILABLE, away = false))
        // A different choice made while away is announced as is.
        assertEquals(Presence.INVISIBLE, AutoAway.effective(Presence.INVISIBLE, away = true))
    }
}
