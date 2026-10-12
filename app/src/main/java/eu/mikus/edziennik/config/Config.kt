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
     * Built on first use and cached until [removeProfile] retires it.
     *
     * Concurrent because [removeProfile] runs off the main thread - `ProfileRemoveDialog` calls it
     * inside `withContext(Dispatchers.Default)` - while `SzkolnyTask` is already putting into this
     * map from a sync thread, and a concurrent structural removal is what corrupts a `HashMap`.
     *
     * It does **not** make the `?:` + put in [get] atomic, and the loser of that race is not
     * harmless. Two threads missing on the same id both build; whoever was handed the losing
     * instance keeps it - `Profile.config`'s lazy and the view models that capture a
     * `ProfileConfig` all do - and the two then hold separate `values` maps and separate
     * [ConfigDelegate] caches, so a write through one never reaches the other and the setting
     * appears to revert. This change widens that window, because [get]'s miss path now runs a Room
     * query where it used to filter an in-memory list.
     *
     * If that is ever worth closing, the call is `ConcurrentHashMap.putIfAbsent`, which is **API
     * 1**; `computeIfAbsent` is the API 24 one. Leaving the race alone preserves the behaviour that
     * predates this change rather than endorsing it - and note that minSdk is 23 while every
     * Robolectric test here runs at sdk 35, so only `lintDebug` would catch a `NewApi` slip.
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
     * clearing its `values`, is what retires the per-property caches inside [ConfigDelegate]:
     * those cache the *default* too when a key is absent, so an emptied `values` would change
     * nothing.
     *
     * Pass a real profile id. Nothing here guards the argument, and `removeProfile(-1)` would
     * delete every global setting while the live global [Config] went on serving them from memory
     * until the process restarted.
     *
     * **Deleting before evicting narrows a race; it does not close one.** A [get] that misses the
     * cache holds nothing while it reads the rows, so one that read them *before* the `clear` can
     * still `put` *after* the `remove`, re-caching the removed profile's settings - and that holds
     * under either statement order. This order is still the right one, because reversed, the whole
     * gap between the two statements is unsafe as well. Closing it would need [get]'s miss path and
     * this method under a shared lock.
     *
     * **Retiring before deleting is what closes the write leak.** [ProfileConfig.retire] refuses
     * further writes through the instance *and* cancels anything already queued on its scope, so a
     * write in flight cannot re-insert a row after the `DELETE` and be handed to the reused id by
     * [get]. It has to come first: retired after the `clear`, the queued write lands in the gap,
     * which is the leak itself. It does not move the eviction, so the ordering above is unaffected
     * - a concurrent [get] in the window is simply handed the retired instance instead of a live
     * one.
     *
     * Two residues remain. A write already *executing* `ConfigDao.add` cannot be interrupted -
     * Room's generated insert is blocking, not suspending - so that window narrows to the width of
     * one DAO call rather than closing. And the straddling [get] above installs a **fresh, live**
     * instance that nothing retires, which is the same race under a different name.
     */
    fun removeProfile(profileId: Int) {
        profileConfigs[profileId]?.retire()
        db.configDao().clear(profileId)
        profileConfigs.remove(profileId)
    }

    /**
     * This profile's settings, built on first use and cached until [removeProfile] retires it.
     *
     * Reads the profile's own rows rather than reusing [entries]. That snapshot is taken once, when
     * `App.onCreate` constructs this object, and nothing ever refreshes it - so before this read
     * existed, a profile removed during the process went on being served its deleted rows from
     * here, and profile ids are reused. Do not "optimise" this back to [entries].
     *
     * The cost is one `WHERE profileId = ?` read per profile per process, against the `config`
     * table's own primary key. It runs on the main thread for `Profile.config`'s lazy and for
     * `AgendaScreen`'s default argument, which is safe only because `AppDb` is built with
     * `allowMainThreadQueries()`.
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
