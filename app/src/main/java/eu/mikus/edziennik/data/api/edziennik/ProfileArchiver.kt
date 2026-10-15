/*
 * Copyright (c) Kuba Szczodrzyński 2020-8-25.
 */

package eu.mikus.edziennik.data.api.edziennik

import android.content.Intent
import eu.mikus.edziennik.App
import eu.mikus.edziennik.data.db.entity.Profile
import eu.mikus.edziennik.data.db.enums.LoginType
import eu.mikus.edziennik.ext.Intent
import eu.mikus.edziennik.utils.Utils.d
import eu.mikus.edziennik.utils.models.Date

class ProfileArchiver(val app: App, val profile: Profile) {
    companion object {
        private const val TAG = "ProfileArchiver"
    }

    init {
        if (profile.archiveId == null)
            profile.archiveId = profile.id
        d(TAG, "Processing ${profile.name}#${profile.id}, archiveId = ${profile.archiveId}")

        profile.archived = true
        app.db.profileDao().add(profile)
        //app.db.metadataDao().setAllSeen(profile.id, true)
        app.db.notificationDao().clear(profile.id)
        app.db.endpointTimerDao().clear(profile.id)
        d(TAG, "Archived profile ${profile.id} saved")
        profile.archived = false

        // guess the nearest school year
        val today = Date.getToday()
        profile.studentSchoolYearStart = when {
            today.month <= profile.dateYearEnd.month -> today.year - 1
            else -> today.year
        }

        // set default semester dates
        profile.dateSemester1Start = Date(profile.studentSchoolYearStart, 9, 1)
        profile.dateSemester2Start = Date(profile.studentSchoolYearStart + 1, 2, 1)
        profile.dateYearEnd = Date(profile.studentSchoolYearStart + 1, 6, 30)

        val oldId = profile.id
        // Finding #5: config rows are keyed by profileId and are NOT re-keyed below, so the new
        // school year would start without the settings the user had. It cannot fire today:
        // Profile.shouldArchive() is `return false` (aeecc486, "[App] Disable profile archiver
        // permanently, ...", 2025-02-01), and this class has one construction site, behind that
        // call in EdziennikTask. Removing that `return false` is what makes this live - and it
        // also wakes SyncTrigger.kt's archiving dialog, which sits behind the same call.
        //
        // Do NOT write the fix as "copy the rows across, then Config.removeProfile(newId)".
        // removeProfile DELETES that id's rows before it evicts the cache, so it would wipe the
        // copy it was meant to protect. The id IS reused - ProfileDao.lastId is a MAX, not a
        // counter - so it may already carry an older profile's orphaned rows and does need
        // clearing first. The order is therefore clear, then copy, then evict, and Config exposes
        // no eviction on its own. Give Config one method that does all three, for the same reason
        // removeProfile exists: it is the only object holding both the table and the cache. Copy
        // rather than move - the archived profile keeps oldId and should keep its own settings.
        //
        // Second trap: Profile.config is a `by lazy` bound to the id the object held when it was
        // first read, and `profile.id = newId` two lines down mutates that id in place. On today's
        // path the Profile is freshly loaded in EdziennikTask.prepare, so nothing has read it yet -
        // but any future reader that touches profile.config before the re-key pins that object to
        // oldId for as long as the object lives. Re-load the profile row after the re-key rather
        // than relying on profile.config across it.
        val newId = (app.db.profileDao().lastId ?: profile.id) + 1
        profile.id = newId
        profile.subname = "Nowy rok szkolny - ${profile.studentSchoolYearStart}"
        profile.studentClassName = null

        d(TAG, "New profile ID for ${profile.name}: ${profile.id}")

        when (profile.loginStoreType) {
            LoginType.LIBRUS -> {
                profile.studentData.remove("isPremium")
                profile.studentData.remove("pushDeviceId")
                profile.studentData.remove("startPointsSemester1")
                profile.studentData.remove("startPointsSemester2")
                profile.studentData.remove("enablePointGrades")
                profile.studentData.remove("enableDescriptiveGrades")
            }
            LoginType.MOBIDZIENNIK -> {}
            LoginType.DEMO -> {}
        }

        d(TAG, "Processed student data: ${profile.studentData}")

        app.db.profileDao().add(profile)

        if (app.profileId == oldId) {
            val intent = Intent(
                    Intent.ACTION_MAIN,
                    "profileId" to newId
            ).setPackage(app.packageName)
            app.sendBroadcast(intent)
        }
    }
}
