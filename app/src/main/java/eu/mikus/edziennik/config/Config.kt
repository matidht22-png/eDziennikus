/*
 * Copyright (c) Kuba Szczodrzyński 2019-11-26.
 */

package eu.mikus.edziennik.config

import com.google.gson.JsonObject
import eu.mikus.edziennik.App
import eu.mikus.edziennik.BuildConfig
import eu.mikus.edziennik.config.utils.*
import eu.mikus.edziennik.data.api.models.PendingUserAction
import eu.mikus.edziennik.data.api.models.Update
import eu.mikus.edziennik.data.db.AppDb
import java.util.concurrent.ConcurrentHashMap

@Suppress("RemoveExplicitTypeArguments")
class Config(db: AppDb) : BaseConfig(db) {
    companion object {
        const val DATA_VERSION = 12
    }

    /**
     * Concurrent because [removeProfile] runs off the main thread - `ProfileRemoveDialog` calls it
     * inside `withContext(Dispatchers.Default)` - while `SzkolnyTask` is already putting into this
     * map from a sync thread, and a concurrent structural removal is what corrupts a `HashMap`.
     *
     * Keep the `?:` + put in [get]; do not "finish the job" with `computeIfAbsent`. minSdk is 23,
     * every Robolectric test here runs at sdk 35, and only `lintDebug` would report the `NewApi` -
     * and the atomicity it buys guards a double-construct in which both instances read the same
     * rows and one simply wins.
     */
    private val profileConfigs = ConcurrentHashMap<Int, ProfileConfig>()

    val ui by lazy { ConfigUI(this) }
    val sync by lazy { ConfigSync(this) }
    val timetable by lazy { ConfigTimetable(this) }
    val grades by lazy { ConfigGrades(this) }

    var dataVersion by config<Int>(DATA_VERSION)
    var hash by config<String>("")

    var lastProfileId by config<Int>(0)
    var loginFinished by config<Boolean>(false)
    var privacyPolicyAccepted by config<Boolean>(false)
    var pendingUserAction by config<PendingUserAction?>(null)
    var update by config<Update?>(null)
    var updatesChannel by config<String>("release")

    var devMode by config<Boolean?>("debugMode", null)
    var devModePassword by config<String?>(null)
    var enableChucker by config<Boolean?>(null)

    var apiAvailabilityCheck by config<Boolean>(true)
    var apiInvalidCert by config<String?>(null)
    var apiKeyCustom by config<String?>(null)
    var appInstalledTime by config<Long>(0L)
    var appRateSnackbarTime by config<Long>(0L)
    var appVersion by config<Int>(BuildConfig.VERSION_CODE)
    var appVersionCore by config<Int>(0)
    var validation by config<String?>(null, "buildValidation")

    var archiverEnabled by config<Boolean>(true)
    var runSync by config<Boolean>(false)
    var widgetConfigs by config<JsonObject> { JsonObject() }

    fun migrate(app: App) {
        if (dataVersion < DATA_VERSION || hash == "")
            // migrate old data version OR freshly installed app (or updated from 3.x)
            ConfigMigration(app, this)
    }

    /**
     * Retires [profileId]'s settings: deletes the stored rows **and** drops the in-memory copy.
     *
     * Both halves are needed and this is the only place that does both. Profile ids are reused -
     * `ProfileDao.lastId` is a MAX over the survivors, not a counter - so clearing only the table
     * leaves the next child reading the previous child's settings until the app restarts, and
     * clearing only memory hands them back on the next launch. Dropping the instance, rather than
     * clearing its `values`, is what retires the per-property caches inside `ConfigDelegate`.
     *
     * **The order of the two statements is load-bearing.** Deleting first means a concurrent [get]
     * in the window between them rebuilds from an already-empty table, and the remove that follows
     * costs one needless rebuild. Evicting first means that same [get] rebuilds from rows that are
     * still there and re-caches them - the defect this method exists to prevent, restored, with no
     * cache entry left to blame.
     */
    fun removeProfile(profileId: Int) {
        db.configDao().clear(profileId)
        profileConfigs.remove(profileId)
    }

    /**
     * This profile's settings, built on first use and cached for the life of the process.
     *
     * Reads the profile's own rows rather than reusing [entries]. That snapshot is taken once, when
     * `App.onCreate` constructs this object, and nothing ever refreshes it - so a profile removed
     * during this process would still be served its deleted rows from here, and profile ids are
     * reused. Do not "optimise" this back to [entries].
     */
    operator fun get(profileId: Int): ProfileConfig {
        return profileConfigs[profileId] ?: ProfileConfig(
            db,
            profileId,
            db.configDao().getAllNow(profileId),
        ).also {
            profileConfigs[profileId] = it
        }
    }
}
