/*
 * Copyright (c) Mikolaj Olszewski 2026-10-13.
 */

package eu.mikus.edziennik.ui.widgets.luckynumber

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * What the lucky-number widget's two text slots say, decided in one place.
 *
 * The widget used to set its profile line only when the profile resolved, so a widget whose binding
 * had gone stale showed a sad emoji, no name, no number and no explanation - while the timetable
 * widget in the same situation tells the user to remove the widget and add it again. Phase 67 is
 * what puts widgets into that state deliberately, so it is what owes the user the message.
 *
 * Takes the resolved string rather than a `Context` or an `R.string` id, so the decision is testable
 * without Robolectric and does not depend on Android at all. The provider does the resolving.
 */
class LuckyNumberTextTest {

    private companion object {
        const val NO_PROFILE = "Brak profilu"
    }

    @Test
    fun `a widget whose profile is gone says so instead of showing nothing`() {
        assertEquals(
            NO_PROFILE,
            luckyNumberText(profileName = null, number = 7, noProfileText = NO_PROFILE).profileLine,
        )
    }

    @Test
    fun `a widget with a profile shows the name, not the message`() {
        assertEquals(
            "Ala",
            luckyNumberText(profileName = "Ala", number = 7, noProfileText = NO_PROFILE).profileLine,
        )
    }

    /**
     * The two slots are one decision, not two. A stale widget must not show a number under
     * "Brak profilu": the row it came from belongs to whoever holds that id, which is exactly the
     * leak this phase exists to stop.
     */
    @Test
    fun `a profile that is gone shows no number`() {
        assertNull(luckyNumberText(profileName = null, number = 7, noProfileText = NO_PROFILE).number)
    }

    /** `-1` is the no-number-today sentinel stored in the `luckyNumbers` table, not a number. */
    @Test
    fun `the no-number sentinel still hides the number`() {
        assertNull(luckyNumberText(profileName = "Ala", number = -1, noProfileText = NO_PROFILE).number)
        assertNull(luckyNumberText(profileName = "Ala", number = null, noProfileText = NO_PROFILE).number)
    }

    @Test
    fun `a real number is rendered`() {
        assertEquals(
            "7",
            luckyNumberText(profileName = "Ala", number = 7, noProfileText = NO_PROFILE).number,
        )
    }
}
