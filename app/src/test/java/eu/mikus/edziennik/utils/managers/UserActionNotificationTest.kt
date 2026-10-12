/*
 * Copyright (c) Mikolaj Olszewski 2026-10-12.
 */
package eu.mikus.edziennik.utils.managers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import eu.mikus.edziennik.App
import eu.mikus.edziennik.R
import eu.mikus.edziennik.data.api.events.UserActionRequiredEvent
import eu.mikus.edziennik.ext.Bundle
import eu.mikus.edziennik.utils.models.Time
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The "you must act" prompt `UserActionManager` posts when a background sync hits a Librus captcha
 * and no Activity is in the foreground to show the dialog.
 *
 * Why this needs pinning: the prompt is raised from `LibrusLoginPortal`'s `authorize()` branch,
 * which every sync re-enters for as long as the portal refresh token is gone, and `SyncWorker`
 * reschedules itself from a `finally` at a default one-hour interval. So the posting path runs
 * roughly eight times between 22:30 and 06:30, and what it does on the second run matters as much
 * as the first.
 *
 * The id assertions carry the weight. In app and test code `System.currentTimeMillis()` is the real
 * wall clock under Robolectric (framework code sees the paused fake clock instead), and two sends
 * on a warm JVM routinely land in the same millisecond, so "two calls leave one notification" is
 * NOT on its own evidence that the id is fixed - under the old code both posts could land on the
 * same timestamp. Asserting that the survivor sits under `userAttention.id` is what cannot pass by
 * accident. `awaitNextMillisecond` below depends on exactly that split.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserActionNotificationTest {

    private val app get() = RuntimeEnvironment.getApplication() as App
    private val channels get() = app.notificationChannelsManager
    private val system get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val manager get() = shadowOf(system)
    private val posted get() = manager.allNotifications

    /** The prompt, as the system holds it: fetched by the id it is supposed to be posted under. */
    private val prompt: Notification? get() = manager.getNotification(channels.userAttention.id)

    /**
     * A captcha event as `LibrusLoginPortal.checkError` builds one. All three params must be
     * present or `toPending()` returns null and `sendToUser` bails before posting anything.
     */
    private fun event(profileId: Int = 1) = UserActionRequiredEvent(
        profileId = profileId,
        type = UserActionRequiredEvent.Type.RECAPTCHA,
        params = Bundle(
            "siteKey" to "6Lsite",
            "referer" to "https://portal.librus.pl/rodzina/login",
            "userAgent" to "Mozilla/5.0",
        ),
        errorText = R.string.notification_user_action_required_captcha_librus,
    )

    private fun quietHours(on: Boolean) {
        app.config.sync.quietHoursEnabled = on
        app.config.sync.quietHoursStart = Time(0, 0, 0)
        app.config.sync.quietHoursEnd = Time(23, 59, 59)
    }

    private fun sendToUser(profileId: Int = 1) =
        UserActionManager(app).sendToUser(event(profileId))

    /**
     * Blocks until `System.currentTimeMillis()` has ticked over. The PendingIntent request code is
     * a timestamp, and `ShadowPendingIntent` - like the platform - hands back the existing
     * PendingIntent, extras and all, for a matching request code. Two sends in the same millisecond
     * therefore collapse into one and the second profile's id never reaches the intent, which
     * fails a correct implementation. A real device never posts twice in one millisecond.
     *
     * Bounded by a count of real sleeps, not by a clock deadline: if `currentTimeMillis` ever
     * becomes the paused fake clock for test code, a deadline read from it would pause too and the
     * loop would hang CI instead of failing.
     */
    private fun awaitNextMillisecond() {
        val start = System.currentTimeMillis()
        repeat(100) {
            if (System.currentTimeMillis() != start) return
            Thread.sleep(1)
        }
        error(
            "System.currentTimeMillis() did not advance in ~100 ms - " +
                "is Robolectric now pausing the clock for app and test code?",
        )
    }

    /**
     * The guard for every other case here. `sendToUser` returns early both when an EventBus
     * subscriber exists and when a param is missing, and an early return posts nothing - under
     * which an assertion about the tray could pass while testing the wrong thing entirely.
     */
    @Test fun `the prompt parks the action it is a notification for`() {
        quietHours(on = false)
        sendToUser()

        assertNotNull("sendToUser returned before parking", app.config.pendingUserAction)
        assertEquals(1, app.config.pendingUserAction?.profileId)
    }

    /**
     * One config slot holds one parked action, so the tray must hold one prompt. Before this, each
     * sync posted under a fresh id and the user woke to a stack in which only the newest was
     * actionable - tapping any older one hit `pendingFor`, missed, and merely opened the app.
     */
    @Test fun `a second prompt replaces the first instead of stacking`() {
        quietHours(on = false)
        sendToUser()
        sendToUser()

        assertNotNull("nothing posted under userAttention's id ${channels.userAttention.id}", prompt)
        assertEquals(1, posted.size)
    }

    /**
     * The other half of "one prompt, one alert", and it is inert without the fixed id: the platform
     * only honours FLAG_ONLY_ALERT_ONCE on an update to an id it already holds. So this fetches the
     * notification under that id rather than taking the first one posted - which would pass even if
     * the id were reverted to a timestamp, leaving the flag set and doing nothing.
     *
     * What it observes is the flag the platform needs in order to mute an update, not the muting
     * itself: Robolectric cannot model alert suppression, so no test here sees a notification being
     * muted or not muted.
     */
    @Test fun `a replaced prompt does not alert again`() {
        quietHours(on = false)
        sendToUser()
        sendToUser()

        val notification = prompt
        assertNotNull(notification)
        assertTrue(
            "the prompt is missing FLAG_ONLY_ALERT_ONCE",
            (notification!!.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0,
        )
    }

    /**
     * The prompt replaces, so the survivor's tap target must be the newest one's. This is why the
     * PendingIntent request code deliberately stays a timestamp: a fixed one under FLAG_ONE_SHOT
     * and without FLAG_UPDATE_CURRENT would hand back the first PendingIntent, still carrying the
     * first profile's id, and the tap would find no parked action for it and do nothing.
     */
    @Test fun `the surviving prompt taps through to the newest profile`() {
        quietHours(on = false)
        sendToUser(profileId = 1)
        awaitNextMillisecond()
        sendToUser(profileId = 2)

        val tapped = shadowOf(prompt!!.contentIntent).savedIntent
        assertEquals(2, tapped.getIntExtra("profileId", -1))
        assertEquals("userActionRequired", tapped.getStringExtra("action"))
    }

    /**
     * Guards that `sendToUser` still posts after the tray has been cleared, so a fixed id plus
     * FLAG_ONLY_ALERT_ONCE cannot turn into "posted once, never again" - say, through a check that
     * the prompt already exists. The platform rule behind the intent (a post to an id the system no
     * longer holds is a new notification and alerts afresh) is not something Robolectric models, so
     * that half is argued here, not observed.
     */
    @Test fun `a dismissed prompt comes back on the next sync`() {
        quietHours(on = false)
        sendToUser()
        system.cancelAll()
        assertEquals(0, posted.size)

        sendToUser()

        assertNotNull("the prompt did not return after being dismissed", prompt)
        assertEquals(1, posted.size)
    }

    /**
     * The captcha cannot be solved asleep, and the prompt sets setAutoCancel(true) and nothing else
     * cancels it, so it is waiting in the morning either way. Keep the visual, drop the alert.
     *
     * What actually mutes it at API 26+ is the group: the framework suppresses an alert for a group
     * child whose behaviour is GROUP_ALERT_SUMMARY, and that branch is gated on the notification
     * being in a group at all. `setSilent` supplies one itself when the caller has none - which is
     * why no setGroup call accompanies it, and why this asserts the group is non-null rather than
     * asserting the key androidx happens to use for it.
     */
    @Test fun `in quiet hours the prompt is posted silenced`() {
        quietHours(on = true)
        sendToUser()

        val notification = prompt
        assertNotNull("quiet hours dropped the prompt instead of silencing it", notification)
        assertNotNull("not in a group, so the framework cannot mute it", notification!!.group)
        assertEquals(
            NotificationCompat.GROUP_ALERT_SUMMARY,
            notification.groupAlertBehavior,
        )
    }

    /**
     * The guard on "keep the visual". The decision allows silencing the prompt, never dropping it
     * and never emptying it: what is waiting in the morning is the shade entry and its text, and
     * that is what this pins. It deliberately says nothing about the LED - the grouping that mutes
     * the sound mutes the light with it at API 26+, so the LED is not part of what survives, and
     * Robolectric could not show a light blinking or not blinking in any case.
     */
    @Test fun `a silenced prompt is still posted, with its text intact`() {
        quietHours(on = true)
        sendToUser()

        val notification = prompt
        assertNotNull("quiet hours dropped the prompt instead of silencing it", notification)
        assertEquals(
            app.getString(R.string.notification_user_action_required_title),
            notification!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            app.getString(R.string.notification_user_action_required_captcha_librus, 1),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test fun `outside quiet hours the prompt alerts`() {
        quietHours(on = false)
        sendToUser()

        val notification = prompt!!
        assertEquals(NotificationCompat.GROUP_ALERT_ALL, notification.groupAlertBehavior)
        assertNull(
            "the prompt was grouped outside quiet hours, so the framework can mute it",
            notification.group,
        )
    }

    /**
     * quietHoursEnabled is settable on its own and the two times default to null, so "on, with no
     * window" is reachable. It must mean "not quiet" - and must not throw on a null Time.
     */
    @Test fun `quiet hours with no window set is not quiet`() {
        app.config.sync.quietHoursEnabled = true
        app.config.sync.quietHoursStart = null
        app.config.sync.quietHoursEnd = null
        sendToUser()

        assertEquals(NotificationCompat.GROUP_ALERT_ALL, prompt!!.groupAlertBehavior)
    }
}
