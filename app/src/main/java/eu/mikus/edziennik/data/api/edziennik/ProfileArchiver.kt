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
        // Finding #5. Config rows are keyed by profileId and are NOT re-keyed below, so the new
        // school year would start with every per-profile setting back at its default. It cannot
        // fire today: Profile.shouldArchive() is `return false` ("vulcan hotfix", unchanged since
        // 2025-02-01) and EdziennikTask only builds this class behind it. Before anyone removes
        // that `return false`, copy the rows across and drop any instance cached for the id about
        // to be minted:
        //     app.db.configDao().addAll(
        //         app.db.configDao().getAllNow(oldId).map { it.copy(profileId = newId) },
        //     )
        //     app.config.removeProfile(newId)
        // Copy rather than move: the archived profile keeps oldId and should keep its settings.
        // And note that `profile.config` is a `by lazy` resolved against whatever id this object
        // held when it was first read, so it still points at oldId's ProfileConfig after the
        // reassignment below - re-load the profile row instead of relying on it across the re-key.
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
