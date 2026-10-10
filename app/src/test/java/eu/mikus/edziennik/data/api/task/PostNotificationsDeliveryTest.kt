/*
 * Copyright (c) Mikolaj Olszewski 2026-10-10.
 */
package eu.mikus.edziennik.data.api.task

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import eu.mikus.edziennik.App
import eu.mikus.edziennik.data.db.enums.NotificationType
import org.junit.Assert.assertEquals
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
}
