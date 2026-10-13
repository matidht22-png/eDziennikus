/*
 * Copyright (c) Mikolaj Olszewski 2026-10-13.
 */

package eu.mikus.edziennik.ui.widgets.luckynumber

/**
 * The two text slots on the lucky-number widget.
 *
 * [profileLine] rather than `profile`, because when the binding has gone stale that line is a
 * message and not a name, and `text.profile` beside the `Profile` entity reads like one.
 */
data class LuckyNumberText(val profileLine: String, val number: String?)

/**
 * Decides both slots together, because they are one decision.
 *
 * A widget whose bound profile no longer resolves used to leave its profile line untouched, so it
 * rendered a sad emoji, no name and no number, and nothing said why. [noProfileText] is the label
 * shown in the profile line's place. It names the problem and offers no remedy, unlike the
 * timetable widget's missing-profile message, which also tells the user what to do with the widget
 * (delete it and try again in Polish and German, add it again in English).
 *
 * The number is hidden whenever the profile is gone, not only when the number is: a number resolved
 * for a profile id that has been removed belongs to whoever takes that id next. `-1` is the table's
 * "no number today" sentinel, not a number.
 *
 * Takes the resolved string rather than a `Context`, so nothing here depends on Android and the
 * decision can be tested without Robolectric - which matters, because `RemoteViews` output
 * cannot be driven in this repo at all.
 */
fun luckyNumberText(
    profileName: String?,
    number: Int?,
    noProfileText: String,
): LuckyNumberText = LuckyNumberText(
    profileLine = profileName ?: noProfileText,
    number = if (profileName == null || number == null || number == -1) null else number.toString(),
)
