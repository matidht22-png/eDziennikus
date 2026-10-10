package eu.mikus.edziennik.data.api.task

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import com.mikepenz.iconics.utils.colorRes
import eu.mikus.edziennik.App
import eu.mikus.edziennik.MainActivity
import eu.mikus.edziennik.R
import eu.mikus.edziennik.data.db.enums.NotificationType
import eu.mikus.edziennik.ext.Intent
import eu.mikus.edziennik.ext.asBoldSpannable
import eu.mikus.edziennik.ext.concat
import eu.mikus.edziennik.ext.pendingIntentFlag
import eu.mikus.edziennik.ui.base.enums.NavTarget
import eu.mikus.edziennik.utils.models.Time
import eu.mikus.edziennik.data.db.entity.Notification as AppNotification

class PostNotifications(val app: App, nList: List<AppNotification>) {
    companion object {
        private const val TAG = "PostNotifications"
    }

    private val quiet by lazy { shouldBeQuiet() }
    private val channel by lazy { channelFor(nList) }
    // Quiet hours, on a channel that cannot be quiet itself: silence this instance instead.
    private val silent get() = quiet && !channel.quiet
    fun shouldBeQuiet(): Boolean {
        if (!app.config.sync.quietHoursEnabled)
            return false
        val now = Time.getNow().value
        val start = app.config.sync.quietHoursStart?.value ?: return false
        val end = app.config.sync.quietHoursEnd?.value ?: return false
        return quietHoursContain(now, start, end)
    }

    /**
     * The one place a batch's channel is chosen. The builders, the group key, the summary's id and
     * its tap target all derive from it, so the summary - the notification that actually alerts -
     * can never sit on a different channel from the notifications it summarises.
     *
     * An update keeps the Updates channel in quiet hours too, because blocking "Aktualizacje" in
     * system settings must stop it at any hour; [silent] mutes it instead. UpdateManager posts an
     * UPDATE alone, so no mixed batch occurs; one would post as data, so that an Updates block can
     * never hide data.
     */
    private fun channelFor(notifications: List<AppNotification>) = when {
        notifications.all { it.type == NotificationType.UPDATE } -> app.notificationChannelsManager.updates
        quiet && !dataBlocked -> app.notificationChannelsManager.dataQuiet
        else -> app.notificationChannelsManager.data
    }

    // Blocking DATA is how the user switches the e-diary off. DATA_QUIET is only its night-time
    // form, which nobody had a reason to block, so quiet hours must not reopen what DATA closed.
    private val dataBlocked get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        NotificationManagerCompat.from(app)
            .getNotificationChannel(app.notificationChannelsManager.data.key)
            ?.importance == NotificationManagerCompat.IMPORTANCE_NONE

    private fun NotificationCompat.Builder.addDefaults(): NotificationCompat.Builder {
        return this.setColor(0xff2196f3.toInt())
                .setLights(0xff2196f3.toInt(), 2000, 2000)
                .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX)
                .also {
                    if (quiet) {
                        it.setSound(null)
                        it.setVibrate(longArrayOf())
                    }
                    else
                        it.setDefaults(NotificationCompat.DEFAULT_ALL)
                }
                .setGroup(channel.key)
                .setSilent(silent)
    }

    private fun buildSummaryText(summaryCounts: Map<NotificationType, Int>): CharSequence {
        val summaryTexts = mutableListOf<String>()
        summaryCounts.forEach { (key, value) ->
            if (value <= 0)
                return@forEach
            summaryTexts += app.resources.getQuantityString(key.pluralRes, value, value)
        }
        return summaryTexts.concat(", ")
    }

    init { run {
        val count = nList.size
        if (count == 0)
            return@run
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val summaryCounts = mutableMapOf<NotificationType, Int>()

        val newNotificationsText = app.resources.getQuantityString(R.plurals.notification_count_format, count, count)
        val newNotificationsShortText = app.resources.getQuantityString(R.plurals.notification_count_short_format, count, count)

        val intent = Intent(
                app,
                MainActivity::class.java,
                "fragmentId" to NavTarget.NOTIFICATIONS.id
        )
        val summaryIntent = PendingIntent.getActivity(app, channel.id, intent, PendingIntent.FLAG_ONE_SHOT or pendingIntentFlag())

        // On Nougat or newer - show maximum 8 notifications
        // On Marshmallow or older - show maximum 4 notifications

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N && count > 4 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && count > 8) {
            val summaryList = mutableListOf<CharSequence>()
            nList.forEach {
                summaryCounts[it.type] = summaryCounts.getOrDefault(it.type, 0) + 1
                summaryList += listOf(
                        it.profileName.asBoldSpannable(),
                        it.text
                ).concat(": ")
            }

            // Create a summary to show *instead* of notifications
            val combined = NotificationCompat.Builder(app, channel.key)
                    .setContentTitle(app.getString(R.string.app_name))
                    .setContentText(buildSummaryText(summaryCounts))
                    .setTicker(newNotificationsText)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setLargeIcon(IconicsDrawable(app).apply {
                        icon = CommunityMaterial.Icon.cmd_bell_ring_outline
                        colorRes = R.color.colorPrimary
                    }.toBitmap())
                    .setStyle(NotificationCompat.InboxStyle()
                            .also {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    it.setBigContentTitle(app.getString(R.string.app_name))
                                    it.setSummaryText(newNotificationsShortText)
                                }
                                else {
                                    it.setBigContentTitle(newNotificationsText)
                                    it.setSummaryText(app.getString(R.string.notification_click_to_see_all))
                                }
                                summaryList.forEach { line ->
                                    it.addLine(line)
                                }
                            })
                    .addDefaults()
                    .setContentIntent(summaryIntent)
                    .setAutoCancel(true)
                    .build()
            notificationManager.notify(System.currentTimeMillis().toInt(), combined)
        }
        else {
            // Eight or fewer from N, four or fewer below it - posted individually.

            // Exactly one notification in a batch alerts and the rest defer to it. From N that is
            // the group summary posted below. Below N none is posted, and androidx strips sound
            // and vibration from any grouped child carrying GROUP_ALERT_SUMMARY - so there the
            // first child must carry the alert, or a batch of four or fewer makes no sound at all.
            // The children's alert behaviour and the summary's guard both read this one value: if
            // they ever disagreed the result would be either that silence or a double alert.
            val hasSummary = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            @NotificationCompat.GroupAlertBehavior
            fun alertBehaviourFor(index: Int) =
                if (hasSummary || index != 0) NotificationCompat.GROUP_ALERT_SUMMARY
                else NotificationCompat.GROUP_ALERT_ALL

            val notifications = nList.mapIndexed { index, item ->
                summaryCounts[item.type] = summaryCounts.getOrDefault(item.type, 0) + 1
                NotificationCompat.Builder(app, channel.key)
                        .setContentTitle(item.profileName ?: app.getString(R.string.app_name))
                        .setContentText(item.text)
                        .setSubText(item.title)
                        .setTicker("${item.profileName}: ${item.title}")
                        .setSmallIcon(R.drawable.ic_notification)
                        .setLargeIcon(IconicsDrawable(app, item.type.icon).apply {
                            colorRes = R.color.colorPrimary
                        }.toBitmap())
                        .setStyle(NotificationCompat.BigTextStyle()
                                .bigText(item.textLong ?: item.text))
                        // addDefaults calls setSilent, which only writes a field that
                        // NotificationCompatBuilder's constructor consumes at build() time,
                        // overwriting whatever group alert behaviour is set below. The call order
                        // reads as though the later one wins; it does not. During quiet hours on a
                        // channel that is not itself quiet, the setGroupAlertBehavior call below is
                        // therefore discarded, which is the behaviour we want.
                        .addDefaults()
                        .setGroupAlertBehavior(alertBehaviourFor(index))
                        .setContentIntent(item.getPendingIntent(app))
                        .setAutoCancel(true)
                        .build()
            }

            val time = System.currentTimeMillis()
            notificationManager.apply {
                notifications.forEachIndexed { index, it ->
                    notificationManager.notify((time + index).toInt(), it)
                }
            }

            if (hasSummary) {
                val summary = NotificationCompat.Builder(app, channel.key)
                        .setContentTitle(newNotificationsText)
                        .setContentText(buildSummaryText(summaryCounts))
                        .setTicker(newNotificationsText)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setLargeIcon(IconicsDrawable(app).apply {
                            icon = CommunityMaterial.Icon.cmd_bell_ring_outline
                            colorRes = R.color.colorPrimary
                        }.toBitmap())
                        .addDefaults()
                        .setGroupSummary(true)
                        .setContentIntent(summaryIntent)
                        .setAutoCancel(true)
                        .build()

                notificationManager.notify(channel.id, summary)
            }
        }
    }}
}

/**
 * Whether [now] falls inside the quiet-hours window [start]..[end], each a [Time] value
 * (`hhmmss`). A window whose start is after its end spans midnight, so the comparison is retried
 * with a day added - the branch the default 22:30-06:30 window takes.
 *
 * It takes the time of day rather than reading [Time.getNow] so that a test can state one. The
 * obstacle was never visibility - `shouldBeQuiet` is already public - but that calling it means
 * constructing the whole class with an `App`, and that the clock it reads is a static `Calendar`
 * call which Robolectric cannot move. That last point was probed, not assumed.
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
