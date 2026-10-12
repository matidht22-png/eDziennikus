/*
 * Copyright (c) Kuba Szczodrzyński 2019-11-27.
 */

package eu.mikus.edziennik.config

import eu.mikus.edziennik.config.db.ConfigEntry
import eu.mikus.edziennik.config.utils.ProfileConfigMigration
import eu.mikus.edziennik.data.db.AppDb

@Suppress("RemoveExplicitTypeArguments")
class ProfileConfig(
    db: AppDb,
    profileId: Int,
    entries: List<ConfigEntry>?,
) : BaseConfig(db, profileId, entries) {
    companion object {
        const val DATA_VERSION = 5
    }

    val grades by lazy { ProfileConfigGrades(this) }
    val ui by lazy { ProfileConfigUI(this) }
    val sync by lazy { ProfileConfigSync(this) }
    val attendance by lazy { ProfileConfigAttendance(this) }
    /*
    val timetable by lazy { ConfigTimetable(this) }
    val grades by lazy { ConfigGrades(this) }*/

    var dataVersion by config<Int>(DATA_VERSION)
    var hash by config<String>("")

    var shareByDefault by config<Boolean>(false)

    init {
        if (dataVersion < DATA_VERSION)
            ProfileConfigMigration(this)
    }

    /**
     * Retires this instance. [Config.removeProfile] calls it on the way to evicting this profile,
     * and nothing else should.
     *
     * After this, a write through any reference a screen still holds is refused outright rather
     * than half-applied (map updated and flow emitted, row lost), and a write already queued on
     * `Dispatchers.IO` is cancelled before it can re-insert a row after the `DELETE` - which is how
     * a removed child's settings used to reach the next child to take this id.
     */
    internal fun retire() = retireInstance()
}
