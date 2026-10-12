/*
 * Copyright (c) Mikolaj Olszewski 2026-10-12.
 */
package eu.mikus.edziennik.data.api.task

import eu.mikus.edziennik.config.ConfigSync
import eu.mikus.edziennik.utils.models.Time

/**
 * Whether quiet hours are on and we are inside the window right now.
 *
 * An extension on [ConfigSync] because that is where all three values live, and a free function
 * taking a single config object as its only parameter invites a second one taking a different one.
 *
 * It sits here rather than on `PostNotifications` because two notification posters need the answer
 * and that class cannot be constructed to ask: its `init` posts notifications. Widening the old
 * method's visibility would not have helped - it was already public.
 */
internal fun ConfigSync.isQuietNow(): Boolean {
    if (!quietHoursEnabled)
        return false
    val now = Time.getNow().value
    val start = quietHoursStart?.value ?: return false
    val end = quietHoursEnd?.value ?: return false
    return quietHoursContain(now, start, end)
}

/**
 * Whether [now] falls inside the quiet-hours window [start]..[end], each a [Time] value
 * (`hhmmss`). A window whose start is after its end spans midnight, so the comparison is retried
 * with a day added - the branch the default 22:30-06:30 window takes.
 *
 * It takes the time of day rather than reading [Time.getNow] so that a test can state one. The
 * obstacle was never visibility - the old `shouldBeQuiet` was already public - but that calling it
 * means constructing the whole class with an `App`, and that the clock it reads is a static
 * `Calendar` call which Robolectric cannot move. That last point was probed, not assumed.
 *
 * The second half of the return is dead when the window does not wrap - `now + 240000` is always
 * past `end` there - and is kept exactly as it was, because this is existing behaviour being
 * characterised, not rewritten.
 */
internal fun quietHoursContain(now: Int, start: Int, end: Int): Boolean {
    // the range spans between two days
    val rangeEnd = if (start > end) end + 240000 else end
    return now in start..rangeEnd || now + 240000 in start..rangeEnd
}
