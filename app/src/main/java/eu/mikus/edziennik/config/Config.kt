/*
 * Copyright (c) Kuba Szczodrzyński 2019-11-26.
 */

package eu.mikus.edziennik.config

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import eu.mikus.edziennik.App
import eu.mikus.edziennik.BuildConfig
import eu.mikus.edziennik.config.utils.*
import eu.mikus.edziennik.data.api.models.PendingUserAction
import eu.mikus.edziennik.data.api.models.Update
import eu.mikus.edziennik.data.db.AppDb
import eu.mikus.edziennik.ui.widgets.WidgetConfig
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
     * Retires [profileId] from config: deletes the stored rows, drops the in-memory copy, and
     * unbinds every widget pointed at it - see [unbindWidgets].
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
     * until the process restarted - and would now also re-point every unified-timetable and every
     * notifications widget, both of which store `-1`.
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
     * one DAO call rather than closing. And any [get] for this id that completes after the eviction
     * installs a **fresh, live** instance that nothing retires: the straddling one above, which is
     * a race, or any later one, which needs no concurrency at all. `Profile`'s
     * `val config by lazy { App.config[this.id] }` resolving for the first time while `App.profile`
     * is still the removed profile, in the window before `profileLoadLast` swaps it, is enough. A
     * write through either instance persists a row under the removed id, which the next child to
     * take it inherits. This is left open on purpose.
     */
    fun removeProfile(profileId: Int) {
        profileConfigs[profileId]?.retire()
        db.configDao().clear(profileId)
        profileConfigs.remove(profileId)
        unbindWidgets(profileId)
    }

    /**
     * The `profileId` this entry names, or `null` when it names none that can be compared.
     *
     * `null` covers three shapes that must all be left alone rather than treated as a match: the
     * key absent (Gson reads that as `-1`, so the entry is a unified widget), the value a JSON
     * object or array, and the value a primitive that is neither a number nor a string that parses
     * as one. The last one is why this exists instead of [eu.mikus.edziennik.ext.getInt], which
     * checks `isJsonPrimitive` and then calls `asInt` - a boolean, or a string that does not
     * parse, passes that check and `Integer.parseInt` throws. A numeric string such as `"2"` does
     * parse, and answers 2 exactly as `getInt` did: Gson's reader accepts a string token for an
     * `Int` field, so the providers resolve that widget as profile 2 and it must not be skipped.
     * The blob is hand-editable from LAB, and both callers run somewhere a throw is expensive:
     * inside `withContext(Dispatchers.Default)` on the profile-removal path, and inside [migrate]
     * at startup, above everything else `App.onCreate` has left to do.
     */
    private fun JsonObject.boundProfileId(): Int? =
        (get("profileId") as? JsonPrimitive)?.let {
            if (it.isNumber) it.asInt else it.asString.toIntOrNull()
        }

    /**
     * Points every widget bound to [profileId] at [WidgetConfig.PROFILE_REMOVED], so it stops
     * resolving to a profile at all.
     *
     * [widgetConfigs] is a **global** value keyed by `appWidgetId` whose entries each carry their
     * own `profileId`, so nothing that sweeps profile-scoped *tables* can see it - and ids are
     * reused, so a widget set up for a removed child would otherwise render the next child's name,
     * lessons or lucky number under it.
     *
     * The entry is re-pointed, not removed. Removing it makes each provider's `?: continue` /
     * `?: return@forEach` fire, `updateAppWidget` is then never called, and the launcher goes on
     * showing the last frame it was handed - the removed child's data - indefinitely. It would also
     * discard the user's style, which a placed widget can never be asked for again, because
     * `android:configure` runs at placement only. Re-pointed, the providers reach the "profile does
     * not exist" state they already render.
     *
     * The member is edited in place rather than round-tripped through [WidgetConfig]: a round-trip
     * would drop the stale `"opacity"` key and rewrite every absent key to its declared default,
     * which is exactly what turns an already-placed widget translucent. Entries that do not match
     * are left untouched, which is also what keeps `-1` - all profiles, and every notifications
     * widget - out of this. A member whose value is not an object (a string, a `null`) is skipped
     * rather than cast: the `as` is this function's own, and a `ClassCastException` here would take
     * the removal down. The `profileId` read is guarded by [boundProfileId], which answers `null`
     * for every shape that does not name an id - so a hand-edited `"profileId": "two"` is skipped
     * rather than throwing out of this loop, while `"profileId": "2"` still reads as 2 and is
     * re-pointed.
     *
     * Runs last in [removeProfile] on purpose: if anything above it throws, the widgets stay bound
     * to a profile whose row has not been deleted yet, and the retry unbinds them. Unbinding first
     * would leave them detached from a profile that still exists.
     *
     * The same failure has a second cost, which a retry repairs only once it succeeds: if
     * `configDao().clear` throws, `profileConfigs.remove` never runs either, so the map keeps the
     * instance [ProfileConfig.retire] already retired - for a profile that still exists - and every
     * settings change for that child is silently refused until a retry of the removal succeeds or
     * the process restarts. Before retiring moved ahead of the `DELETE`, the same failure left a
     * working instance. It takes a SQLite failure that also propagates out of
     * `ProfileRemoveDialog`, so in practice the crash dominates; this records the cost, it does
     * not close it.
     *
     * **This is the first mutation of [widgetConfigs] that is not on the main thread, and it takes
     * no lock.** `ProfileRemoveDialog` calls [removeProfile] inside
     * `withContext(Dispatchers.Default)`, while the three widget providers read this same
     * [JsonObject] instance on the main thread and their `onDeleted` removes from it there. A
     * widget broadcast landing inside the loop can race it: `onDeleted`'s `remove` against this
     * `entrySet()` iteration, or against the `toString()` the delegate setter then runs over the
     * whole object, makes either throw `ConcurrentModificationException` out of [removeProfile].
     * The `addProperty` itself is not part of that: it only ever reaches an entry whose `profileId`
     * is already present, so it replaces a value rather than inserting a key, which
     * `LinkedTreeMap` does not count as a structural change - a provider reading that child sees
     * either id and does not throw. The window is microseconds and the trigger is a widget update
     * arriving during the removal tap, so it is accepted rather than closed; closing it would take
     * a lock shared with the providers, not one here.
     */
    private fun unbindWidgets(profileId: Int) {
        val configs = widgetConfigs
        var changed = false
        for ((_, element) in configs.entrySet()) {
            val widget = element as? JsonObject ?: continue
            if (widget.boundProfileId() != profileId)
                continue
            widget.addProperty("profileId", WidgetConfig.PROFILE_REMOVED)
            changed = true
        }
        // through the delegate setter, never config.set: ConfigDelegate caches after the first read
        // and BaseConfig.set does not invalidate that cache, so a raw write emits a stale key
        if (changed)
            widgetConfigs = configs
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
