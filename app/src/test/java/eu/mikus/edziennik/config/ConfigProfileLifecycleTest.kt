/*
 * Copyright (c) Mikolaj Olszewski 2026-10-12.
 */
package eu.mikus.edziennik.config

import eu.mikus.edziennik.config.db.ConfigDao
import eu.mikus.edziennik.config.db.ConfigEntry
import eu.mikus.edziennik.data.db.AppDb
import eu.mikus.edziennik.data.db.dao.LoginStoreDao
import eu.mikus.edziennik.data.db.dao.ProfileDao
import eu.mikus.edziennik.data.db.entity.Profile
import eu.mikus.edziennik.data.db.enums.LoginType
import eu.mikus.edziennik.data.db.removeProfileData
import io.mockk.every
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding #7: remove one child's profile and add another without restarting the app, and the new
 * child opens with the removed child's settings. Profile ids are reused - `ProfileDao.lastId` is a
 * MAX over the survivors, not a counter - so removing the highest-numbered profile guarantees the
 * next one takes its id.
 *
 * Three latches kept the old settings alive. Fixing either of the two that can be fixed on their
 * own leaves the defect intact:
 *  1. `Config.profileConfigs` was never evicted;
 *  2. `BaseConfig.entries` is a whole-table snapshot taken once per process, which `Config.get`
 *     handed to every `ProfileConfig` it ever built - so even a freshly built one repopulated from
 *     rows that had been deleted;
 *  3. `ConfigDelegate` caches each property after its first read, so clearing `values` on the
 *     cached instance would not have been enough either - the instance has to be dropped.
 *
 * **The trap this class exists to avoid.** A database-only fix is invisible until the process
 * restarts, and a test of it passes anyway: construct a FRESH `Config` after the delete and
 * `BaseConfig.init` re-queries the table. So every test here drives ONE live `Config` across the
 * removal, which is what `App.config` is for the life of the process.
 *
 * Every test seeds [configDao] BEFORE that `Config` is built - so the snapshot is taken with the
 * rows in it, as on a phone - and the first assertion reads the seeded value back. That assertion
 * is the anti-vacuity guard: a test whose seeding silently did nothing fails there instead of
 * passing green.
 */
class ConfigProfileLifecycleTest {

    /**
     * One map behind every read and every write. A "survives a removal" assertion built on two
     * independent fakes - one for the load, one for the save - cannot fail, and this repo has
     * already shipped one.
     */
    private class FakeConfigDao : ConfigDao {
        val rows = linkedMapOf<Pair<Int, String>, String?>()

        override fun add(entry: ConfigEntry) {
            rows[entry.profileId to entry.key] = entry.value
        }

        override fun addAll(list: List<ConfigEntry>) = list.forEach(::add)

        override fun getAllNow(): List<ConfigEntry> =
            rows.map { (key, value) -> ConfigEntry(key.first, key.second, value) }

        override fun getAllNow(profileId: Int): List<ConfigEntry> =
            getAllNow().filter { it.profileId == profileId }

        override fun clear(profileId: Int) {
            rows.keys.removeAll { it.first == profileId }
        }
    }

    private val configDao = FakeConfigDao()
    private val profileDao = mockk<ProfileDao>(relaxed = true)
    private val loginStoreDao = mockk<LoginStoreDao>(relaxed = true)

    private lateinit var db: AppDb
    private lateinit var config: Config

    @BeforeTest
    fun setUp() {
        db = mockk(relaxed = true)
        every { db.configDao() } returns configDao
        every { db.profileDao() } returns profileDao
        every { db.loginStoreDao() } returns loginStoreDao
        every { profileDao.getByIdNow(2) } returns
            Profile(id = 2, loginStoreId = 7, loginStoreType = LoginType.LIBRUS)
        every { profileDao.getIdsByLoginStoreIdNow(7) } returns listOf(2)

        // the removed child's setting, stored before this process started
        configDao.add(ConfigEntry(2, "homeEventsLimit", "9"))
        config = Config(db)
    }

    @Test
    fun `a reused profile id does not inherit the removed profile's settings`() {
        // reading it here caches BOTH the ProfileConfig instance and the property inside its
        // ConfigDelegate, which is the state a running app is in when the parent hits Remove
        assertEquals(9, config[2].ui.homeEventsLimit)
        assertSame(config[2], config[2])   // and the caching is what makes that state reachable

        removeProfileData(db, config, profileId = 2, keepProfile = false)

        assertEquals(4, config[2].ui.homeEventsLimit)
    }

    @Test
    fun `the startup snapshot does not resurrect a removed profile's rows`() {
        assertEquals(1, configDao.getAllNow(2).size)
        // nothing has touched profile 2's config in this process, so no instance and no delegate is
        // cached: the only thing that can still serve the deleted rows is the `entries` snapshot
        removeProfileData(db, config, profileId = 2, keepProfile = false)

        assertEquals(4, config[2].ui.homeEventsLimit)
    }

    @Test
    fun `removal deletes the stored rows`() {
        assertEquals(1, configDao.getAllNow(2).size)
        removeProfileData(db, config, profileId = 2, keepProfile = false)

        assertTrue(configDao.getAllNow(2).isEmpty())
    }

    @Test
    fun `the LAB wipe keeps the profile's settings`() {
        assertEquals(9, config[2].ui.homeEventsLimit)

        val removed = removeProfileData(db, config, profileId = 2, keepProfile = true)

        assertFalse(removed)
        assertEquals(9, config[2].ui.homeEventsLimit)
        assertEquals(1, configDao.getAllNow(2).size)
    }
}
