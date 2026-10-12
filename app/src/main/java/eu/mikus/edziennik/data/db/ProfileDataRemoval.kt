/*
 * Copyright (c) Mikolaj Olszewski 2026-10-11.
 */
package eu.mikus.edziennik.data.db

import eu.mikus.edziennik.config.Config

/**
 * Deletes the content rows owned by [profileId]. `config` rows belong to the profile too, but
 * [removeProfileData] retires them separately, below the keep-profile guard.
 *
 * Profile ids are reused - [eu.mikus.edziennik.data.db.dao.ProfileDao.lastId] is a MAX over the
 * surviving profiles, not a counter - so a profile-scoped table left out of here does not merely
 * waste space. Its rows are handed to the next profile created and rendered under that person's
 * name, which is how one child's notes ended up on another child's Notes screen.
 *
 * This function deliberately takes no "keep the profile" flag. It runs on both paths - a real
 * profile removal and the LAB wipe that keeps the profile - so a clear placed here cannot be made
 * conditional on that flag by accident.
 */
internal fun clearProfileData(db: AppDb, profileId: Int) {
    db.announcementDao().clear(profileId)
    db.attendanceDao().clear(profileId)
    db.attendanceTypeDao().clear(profileId)
    db.classroomDao().clear(profileId)
    db.endpointTimerDao().clear(profileId)
    db.eventDao().clear(profileId)
    db.eventTypeDao().clear(profileId)
    db.gradeCategoryDao().clear(profileId)
    db.gradeDao().clear(profileId)
    db.lessonRangeDao().clear(profileId)
    db.librusLessonDao().clear(profileId)
    db.luckyNumberDao().clear(profileId)
    db.messageDao().clear(profileId)
    db.messageRecipientDao().clear(profileId)
    db.noteDao().clear(profileId)
    db.noticeDao().clear(profileId)
    db.noticeTypeDao().clear(profileId)
    db.notificationDao().clear(profileId)
    db.subjectDao().clear(profileId)
    db.teacherAbsenceDao().clear(profileId)
    db.teacherAbsenceTypeDao().clear(profileId)
    db.teacherDao().clear(profileId)
    db.teamDao().clear(profileId)
    db.timetableDao().clear(profileId)

    db.metadataDao().deleteAll(profileId)
}

/**
 * Clears [profileId]'s data and, unless [keepProfile], retires the profile record itself.
 *
 * @param config the live [Config]. A profile's settings exist in the `config` table *and* in
 *        `Config`'s in-memory cache, and [Config.removeProfile] is the one call that retires both;
 *        reaching for `db.configDao().clear` here instead would leave the next profile to take this
 *        id reading this one's settings until the app restarted.
 * @param keepProfile wipe the profile's data but leave the profile in place - the LAB action.
 * @return whether the profile row was removed, which is the caller's cue to reload the active
 *         profile. False both when nothing was removed and when [profileId] does not exist.
 */
internal fun removeProfileData(
    db: AppDb,
    config: Config,
    profileId: Int,
    keepProfile: Boolean,
): Boolean {
    val profile = db.profileDao().getByIdNow(profileId) ?: return false

    clearProfileData(db, profileId)

    if (keepProfile)
        return false

    config.removeProfile(profileId)

    val profilesUsingLoginStore = db.profileDao().getIdsByLoginStoreIdNow(profile.loginStoreId)
    if (profilesUsingLoginStore.size == 1)
        db.loginStoreDao().remove(profile.loginStoreId)
    db.profileDao().remove(profileId)
    return true
}
