/*
 * Copyright (c) Mikolaj Olszewski 2026-10-10.
 */
package eu.mikus.edziennik.data.api.task

import eu.mikus.edziennik.utils.models.Time
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The boundaries of the quiet-hours window, including the midnight-crossing branch that the
 * default 22:30-06:30 window takes (`SettingsViewModel.kt:127-128`) and that decides which channel
 * a batch posts on from API 26.
 *
 * This needs no Android: [quietHoursContain] takes `now` as a parameter instead of reading
 * [Time.getNow], which is what makes a boundary assertable at all. `PostNotificationsChannelTest`
 * proves `shouldBeQuiet` actually routes through it.
 */
class QuietHoursTest {

    private val defaultStart = Time(22, 30, 0).value
    private val defaultEnd = Time(6, 30, 0).value

    private fun quietAt(hour: Int, minute: Int, second: Int, start: Int, end: Int) =
        quietHoursContain(Time(hour, minute, second).value, start, end)

    @Test
    fun `the default window opens at its start and stays quiet until midnight`() {
        assertFalse(quietAt(22, 29, 59, defaultStart, defaultEnd))
        assertTrue(quietAt(22, 30, 0, defaultStart, defaultEnd))
        assertTrue(quietAt(23, 59, 59, defaultStart, defaultEnd))
    }

    @Test
    fun `the default window stays quiet across midnight up to its end`() {
        assertTrue(quietAt(0, 0, 0, defaultStart, defaultEnd))
        assertTrue(quietAt(3, 0, 0, defaultStart, defaultEnd))
        assertTrue(quietAt(6, 30, 0, defaultStart, defaultEnd))
    }

    @Test
    fun `the default window is not quiet during the day`() {
        assertFalse(quietAt(6, 30, 1, defaultStart, defaultEnd))
        assertFalse(quietAt(12, 0, 0, defaultStart, defaultEnd))
        assertFalse(quietAt(22, 0, 0, defaultStart, defaultEnd))
    }

    /** The control: the same function must still handle a window that does not wrap. */
    @Test
    fun `a window that does not cross midnight is quiet only inside itself`() {
        val start = Time(8, 0, 0).value
        val end = Time(16, 0, 0).value
        assertFalse(quietAt(7, 59, 59, start, end))
        assertTrue(quietAt(8, 0, 0, start, end))
        assertTrue(quietAt(12, 0, 0, start, end))
        assertTrue(quietAt(16, 0, 0, start, end))
        assertFalse(quietAt(16, 0, 1, start, end))
        assertFalse(quietAt(23, 0, 0, start, end))
    }
}
