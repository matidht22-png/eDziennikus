/*
 * Copyright (c) Mikolaj Olszewski 2026-10-9.
 */
package eu.mikus.edziennik.data.api.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import eu.mikus.edziennik.App
import eu.mikus.edziennik.data.db.enums.NotificationType
import eu.mikus.edziennik.utils.managers.NotificationChannelsManager.Channel
import eu.mikus.edziennik.utils.models.Time
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import eu.mikus.edziennik.data.db.entity.Notification as AppNotification

/**
 * Pins which channel [PostNotifications] posts on. On Android 8+ the channel alone decides whether a
 * notification makes a sound, and whether the user's per-category block in system settings applies.
 *
 * It asserts on the notification that makes the NOISE. Per-item notifications carry
 * `GROUP_ALERT_SUMMARY`, so in a batch of up to eight it is the group summary that alerts, and above
 * eight it is the single combined notification. A test that checked only the per-item channel would
 * pass while the summary still woke the user at 03:00 on `DATA`.
 *
 * Every case but the coexistence one also counts the children: they are posted under
 * `(currentTimeMillis + index)`, and one landing on a summary's id would be silently replaced
 * rather than fail anything else here. The coexistence case posts two batches whose child ids
 * may or may not coincide, so it has no fixed count to assert.
 *
 * Quiet hours are made deterministic without a clock seam: 00:00:00–23:59:59 contains every
 * `Time.value`, so "now" is always inside it.
 *
 * "Silenced" is asserted as `GROUP_ALERT_CHILDREN` on a group summary, which is what
 * `NotificationCompat.Builder.setSilent` sets and the rule the framework mutes a summary by. This
 * encodes the rule; it does not observe a muted device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostNotificationsChannelTest {

    private val app get() = RuntimeEnvironment.getApplication() as App
    private val channels get() = app.notificationChannelsManager
    private val manager
        get() = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    private val posted get() = manager.allNotifications
    private val children get() = posted.filter { (it.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }

    private fun quietHours(on: Boolean) {
        app.config.sync.quietHoursEnabled = on
        app.config.sync.quietHoursStart = Time(0, 0, 0)
        app.config.sync.quietHoursEnd = Time(23, 59, 59)
    }

    private fun grades(count: Int) = List(count) {
        AppNotification(
            id = it + 1L,
            title = "Nowa ocena",
            text = "5",
            type = NotificationType.GRADE,
            profileId = 1,
            profileName = "Profil",
        )
    }

    private fun update() = AppNotification(
        id = 1_000L, // a child's request code is id.toInt(); keep it off the channel ids
        title = "Aktualizacja",
        text = "Dostępna jest nowa wersja",
        type = NotificationType.UPDATE,
        profileId = null,
        profileName = "Aktualizacja",
    )

    /** The notification posted under [channel]'s id, which must be a group summary. */
    private fun summaryOf(channel: Channel): Notification {
        val summary = manager.getNotification(channel.id)
        assertNotNull("nothing posted under ${channel.key}'s summary id ${channel.id}", summary)
        assertTrue(
            "id ${channel.id} is not a group summary",
            (summary.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
        )
        return summary
    }

    /** Channel AND group: a summary only summarises the children that share its group key. */
    private fun assertEverythingOn(channel: Channel) = posted.forEach {
        assertEquals(channel.key, it.channelId)
        assertEquals(channel.key, it.group)
    }

    @Test fun `in quiet hours a batch's summary and its items post on the quiet channel`() {
        quietHours(on = true)
        PostNotifications(app, grades(2))

        val summary = summaryOf(channels.dataQuiet)
        assertEquals(2, children.size)
        assertEverythingOn(channels.dataQuiet)
        // The quiet channel already silences it. Muting the instance as well would override a user
        // who has customised "Powiadomienia (cisza nocna)".
        assertEquals(NotificationCompat.GROUP_ALERT_ALL, summary.groupAlertBehavior)
    }

    /**
     * The default 22:30-06:30 window crosses midnight, so `shouldBeQuiet` takes its wrapping
     * branch. 00:00:01-00:00:00 wraps too and contains every possible [Time] value, so it reaches
     * that branch without a clock seam.
     *
     * This pins the wiring, not the arithmetic: the window is true for every `now`, so on its own
     * it could not tell the wrapping branch apart from `return true`. `QuietHoursTest` walks the
     * boundaries.
     */
    @Test fun `a window that crosses midnight still routes the batch to the quiet channel`() {
        app.config.sync.quietHoursEnabled = true
        app.config.sync.quietHoursStart = Time(0, 0, 1)
        app.config.sync.quietHoursEnd = Time(0, 0, 0)
        PostNotifications(app, grades(2))

        summaryOf(channels.dataQuiet)
        assertEquals(2, children.size)
        assertEverythingOn(channels.dataQuiet)
    }

    @Test fun `in quiet hours a combined batch posts on the quiet channel`() {
        quietHours(on = true)
        PostNotifications(app, grades(9))

        assertEquals(1, posted.size)
        assertEverythingOn(channels.dataQuiet)
    }

    /**
     * Blocking "Powiadomienia" is how a user switches the e-diary off, and nothing posted to
     * "Powiadomienia (cisza nocna)" before, so nobody had a reason to block that too. Quiet hours
     * must not reopen what the block closed: the batch stays on DATA, where the system drops it.
     *
     * Robolectric, like the platform, only ever lowers an existing channel's importance, so this
     * cannot race the App's own background channel registration.
     */
    @Test fun `in quiet hours a blocked data channel is not bypassed through the quiet one`() {
        quietHours(on = true)
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(channels.data.key, channels.data.name, NotificationManager.IMPORTANCE_NONE),
        )
        PostNotifications(app, grades(2))

        summaryOf(channels.data)
        assertEquals(2, children.size)
        assertEverythingOn(channels.data)
    }

    @Test fun `outside quiet hours a batch's summary and its items post on the data channel`() {
        quietHours(on = false)
        PostNotifications(app, grades(2))

        summaryOf(channels.data)
        assertEquals(2, children.size)
        assertEverythingOn(channels.data)
    }

    @Test fun `outside quiet hours a combined batch posts on the data channel`() {
        quietHours(on = false)
        PostNotifications(app, grades(9))

        assertEquals(1, posted.size)
        assertEverythingOn(channels.data)
    }

    @Test fun `an update posts on the updates channel, summary included`() {
        quietHours(on = false)
        PostNotifications(app, listOf(update()))

        val summary = summaryOf(channels.updates)
        assertEquals(1, children.size)
        assertEverythingOn(channels.updates)
        assertEquals(NotificationCompat.GROUP_ALERT_ALL, summary.groupAlertBehavior)
    }

    /**
     * The 03:00 update. It stays on UPDATES, so a user who blocked "Aktualizacje" never sees it, and
     * it is muted per instance, because UPDATES is not a quiet channel.
     */
    @Test fun `an update in quiet hours stays on the updates channel, silenced`() {
        quietHours(on = true)
        PostNotifications(app, listOf(update()))

        val summary = summaryOf(channels.updates)
        assertEquals(1, children.size)
        assertEverythingOn(channels.updates)
        assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
    }

    /** No caller produces one. If one ever does, an Updates block must not be able to hide data. */
    @Test fun `a batch mixing an update with data posts as data`() {
        quietHours(on = false)
        PostNotifications(app, grades(2) + update())

        summaryOf(channels.data)
        assertEquals(3, children.size)
        assertEverythingOn(channels.data)
    }

    /**
     * Equal PendingIntents are one PendingIntent to the system. Both summaries come from one
     * `getActivity` call with one set of flags, so here that comes down to package, `filterEquals`
     * Intent and request code, exactly what Robolectric's `equals` compares. The summaries are
     * `FLAG_ONE_SHOT`, so if two coexisting ones were equal, tapping either would kill the other.
     */
    @Test fun `a loud and a quiet summary coexist, each with its own tap target`() {
        quietHours(on = false)
        PostNotifications(app, grades(2))
        quietHours(on = true)
        PostNotifications(app, grades(2))

        assertNotEquals(
            summaryOf(channels.data).contentIntent,
            summaryOf(channels.dataQuiet).contentIntent,
        )
    }
}
