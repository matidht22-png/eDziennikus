/*
 * Copyright (c) Mikolaj Olszewski 2026-10-10.
 */
package eu.mikus.edziennik.data.api.task

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import eu.mikus.edziennik.App
import eu.mikus.edziennik.data.db.enums.NotificationType
import eu.mikus.edziennik.utils.models.Time
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.abs
import eu.mikus.edziennik.data.db.entity.Notification as AppNotification

/**
 * Whether a batch arrives at all, and whether anything in it makes a sound - the two ways a
 * correctly-channelled notification still fails to reach the user. `PostNotificationsChannelTest`
 * covers which channel it lands on.
 *
 * **What the timestamp cases cannot prove.** From Android 15 NotificationManagerService refuses to
 * enqueue a notification whose `when` is more than 14 days old
 * (`NOTIFICATION_MAX_AGE_AT_POST`, NotificationManagerService.java:620 and :8782). That rejection
 * lives in the system service, which Robolectric does not model - its shadow accepts exactly what
 * a real device drops. These assertions gate the value handed to the builder and nothing beyond
 * it. That the user now sees the item is device-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostNotificationsDeliveryTest {

    private val app get() = RuntimeEnvironment.getApplication() as App
    private val manager
        get() = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    private val posted get() = manager.allNotifications

    private fun quietHoursOff() {
        app.config.sync.quietHoursEnabled = false
    }

    /** Distinct texts: the alert cases need to name a specific child, not trust posting order. */
    private fun grades(count: Int, addedDate: Long) = List(count) {
        AppNotification(
            id = it + 1L,
            title = "Nowa ocena",
            text = "ocena $it",
            type = NotificationType.GRADE,
            profileId = 1,
            profileName = "Profil",
            addedDate = addedDate,
        )
    }

    /**
     * The absolute difference, not `now - when`: a future `addedDate` makes that difference
     * negative, so a one-sided assertion would pass on exactly the bug it is meant to catch.
     */
    private fun assertPostedNow(notification: Notification) = assertTrue(
        "when=${notification.`when`} is not the post time; it is " +
            "${System.currentTimeMillis() - notification.`when`} ms away",
        abs(System.currentTimeMillis() - notification.`when`) < 60_000,
    )

    private fun daysAgo(days: Long) = System.currentTimeMillis() - Duration.ofDays(days).toMillis()

    private fun daysFromNow(days: Long) =
        System.currentTimeMillis() + Duration.ofDays(days).toMillis()

    /**
     * Whether this notification will make a sound. `addDefaults` sets DEFAULT_ALL outside quiet
     * hours, and androidx's `removeSoundAndVibration` clears DEFAULT_SOUND from any grouped
     * non-summary carrying GROUP_ALERT_SUMMARY. Reading the bit after `build()` therefore asserts
     * the outcome of androidx's real strip, not a restatement of the flag this class sets.
     *
     * Only meaningful below API 26: from Oreo buildInternal returns before the strip, the channel
     * decides whether a notification sounds, and this reads false for everything. Assert on it only
     * from a test pinned to sdk 23 or 24.
     */
    @Suppress("DEPRECATION")
    private val Notification.alerts get() = (defaults and Notification.DEFAULT_SOUND) != 0

    /** `getCharSequence`, not `getString`: the extra is a CharSequence and may not be a String. */
    private fun childNamed(text: String) = posted.single {
        it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == text
    }

    private fun now() = System.currentTimeMillis()

    @Test fun `an item the school recorded weeks ago still posts with a fresh timestamp`() {
        quietHoursOff()
        PostNotifications(app, grades(2, addedDate = daysAgo(30)))

        assertEquals(3, posted.size) // two children and their summary
        posted.forEach(::assertPostedNow)
    }

    /**
     * A regression pin, not a red-green test: the combined builder never set a `when`, so this
     * passes before and after the fix. It exists to fail if one is ever added there.
     */
    @Test fun `a combined batch of old items posts with a fresh timestamp`() {
        quietHoursOff()
        PostNotifications(app, grades(9, addedDate = daysAgo(30)))

        assertEquals(1, posted.size)
        posted.forEach(::assertPostedNow)
    }

    /**
     * A zero date renders as 1970 and sits below the `getWhen() > 0` guard, so it is never
     * rejected but is always mis-sorted.
     *
     * One batch per test, deliberately. Children are posted under
     * `(System.currentTimeMillis() + index).toInt()`, not under their own id, so two batches
     * raised in the same millisecond would replace each other's children and the surviving one
     * might be either.
     */
    @Test fun `a zero added date posts with a fresh timestamp`() {
        quietHoursOff()
        PostNotifications(app, grades(1, addedDate = 0L))

        assertEquals(2, posted.size) // the child and its summary
        posted.forEach(::assertPostedNow)
    }

    /** A future date sorts above everything the user already has. */
    @Test fun `a future added date posts with a fresh timestamp`() {
        quietHoursOff()
        PostNotifications(app, grades(1, addedDate = daysFromNow(30)))

        assertEquals(2, posted.size) // the child and its summary
        posted.forEach(::assertPostedNow)
    }

    /**
     * Below N this class posts no group summary, and androidx strips sound and vibration from any
     * grouped child carrying GROUP_ALERT_SUMMARY - so without the first child carrying the alert,
     * a batch of four or fewer is completely silent. minSdk is 23, so this is a live device class.
     */
    @Config(sdk = [23])
    @Test fun `on Android 6 the first child of a small batch carries the alert`() {
        quietHoursOff()
        PostNotifications(app, grades(2, addedDate = now()))

        assertEquals("no group summary is posted below N", 2, posted.size)
        assertTrue("the first child must alert", childNamed("ocena 0").alerts)
        assertFalse("only one notification per batch alerts", childNamed("ocena 1").alerts)
    }

    /** The shape UpdateManager always posts: index 0 is the only child there is. */
    @Config(sdk = [23])
    @Test fun `on Android 6 a batch of one alerts`() {
        quietHoursOff()
        PostNotifications(app, grades(1, addedDate = now()))

        assertEquals(1, posted.size)
        assertTrue(posted.single().alerts)
    }

    /**
     * Above four, below N, the batch posts as one combined notification instead. That sets no
     * group alert behaviour and androidx's strip skips when it is GROUP_ALERT_ALL, so it already
     * alerted; this pins it, so a later change to the batch-size rule cannot quietly re-create the
     * silence.
     */
    @Config(sdk = [23])
    @Test fun `on Android 6 a batch of five posts as one combined notification that alerts`() {
        quietHoursOff()
        PostNotifications(app, grades(5, addedDate = now()))

        assertEquals(1, posted.size)
        assertTrue(posted.single().alerts)
    }

    /**
     * The alerting child skips androidx's strip, so quiet hours have to silence it some other way.
     * They do: below O there is no channel to carry it, and `addDefaults` nulls the sound and
     * leaves `defaults` at zero whenever `quiet` is true. A regression pin: it passes before this
     * commit's fix as well as after.
     */
    @Config(sdk = [23])
    @Test fun `on Android 6 quiet hours still silence the alerting child`() {
        app.config.sync.quietHoursEnabled = true
        app.config.sync.quietHoursStart = Time(0, 0, 0)
        app.config.sync.quietHoursEnd = Time(23, 59, 59)
        PostNotifications(app, grades(2, addedDate = now()))

        assertEquals(2, posted.size)
        assertFalse(childNamed("ocena 0").alerts)
        assertFalse(childNamed("ocena 1").alerts)
    }

    /**
     * The binding partner of the API 23 case. From N the summary alerts and every child defers, so
     * inverting `hasSummary` has to redden both tests - that is the single point of truth the two
     * sites now share. A regression pin for the path that already worked; it passes before the fix
     * too. Pinned at 24, the boundary where hasSummary flips true and a level where androidx still
     * strips (21 to 25) - at 26 and up the channel decides and `defaults` is zeroed, so the probe
     * would read false for everything.
     */
    @Config(sdk = [24])
    @Test fun `from Android 7 only the summary alerts`() {
        quietHoursOff()
        PostNotifications(app, grades(2, addedDate = now()))

        val summary = posted.single { (it.flags and Notification.FLAG_GROUP_SUMMARY) != 0 }
        assertTrue("the summary carries the batch's alert", summary.alerts)
        assertFalse(childNamed("ocena 0").alerts)
        assertFalse(childNamed("ocena 1").alerts)
    }

    /**
     * Nothing pinned the per-item subtext before, so replacing it with null left the suite green -
     * and the chain rewrite in this commit is exactly when that would have gone unnoticed.
     */
    @Test fun `a per-item notification carries its title as the subtext`() {
        quietHoursOff()
        PostNotifications(app, grades(1, addedDate = now()))

        assertEquals(
            "Nowa ocena",
            childNamed("ocena 0").extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        )
    }
}
