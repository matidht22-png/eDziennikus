/*
 * Copyright (c) Mikolaj Olszewski 2026-10-12.
 */
package eu.mikus.edziennik.config

import eu.mikus.edziennik.config.db.ConfigDao
import eu.mikus.edziennik.config.db.ConfigEntry
import eu.mikus.edziennik.data.db.AppDb
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding BB: `BaseConfig`'s `Job` was never cancelled and `set` persisted in
 * `launch(Dispatchers.IO)`, so a screen still holding the `ProfileConfig` that `removeProfile` had
 * just evicted went on writing rows under the removed profile's id - and `Config.get` now reads a
 * profile's live rows, so it handed those rows straight to the next child to take that id.
 *
 * **Cancelling the `Job` on its own would have been a second defect, not the fix.** `launch` on a
 * cancelled scope does not throw: the coroutine is created already-cancelled and its block never
 * runs, so `values[key] = value` and `_changes.tryEmit(key)` would both still have happened. The
 * screen would show the edit, a live `configFlow` would re-render it, and only the row would
 * vanish. So `set` refuses the write outright, and the first test asserts the map **and** the
 * emission, not just one of them.
 *
 * The row itself is deliberately not asserted anywhere here. `set` persists with
 * `launch(Dispatchers.IO)`, which names its own dispatcher and therefore escapes `runTest`'s
 * scheduler, so any assertion on the fake DAO would be a race. The three effects are one early
 * return; the two that can be observed deterministically stand for the third.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigRetirementTest {

    /**
     * One map behind every read and every write. The shape is still load-bearing even though no
     * test in this file asserts on a row (see the class doc): it is what `Config.get`'s seeded
     * per-profile read consumes, so `config[2]` is built from the row [setUp] stored, and what
     * makes `clear` remove exactly what `getAllNow` would then return, rather than a fake whose
     * halves can disagree. Mirrors the fake in [ConfigProfileLifecycleTest] on purpose; that file's
     * assertions are the ones that round-trip through it.
     *
     * Every method is `@Synchronized` because this class, unlike that one, drives the real
     * `BaseConfig.set`, which persists on `launch(Dispatchers.IO)` - a real pool thread calling
     * [add] while the test thread is inside [clear] would otherwise throw
     * `ConcurrentModificationException` at random.
     */
    private class FakeConfigDao : ConfigDao {
        val rows = linkedMapOf<Pair<Int, String>, String?>()

        /** Fires at the start of [clear], so a test can observe the world at the DELETE. */
        var onClear: ((Int) -> Unit)? = null

        @Synchronized
        override fun add(entry: ConfigEntry) {
            rows[entry.profileId to entry.key] = entry.value
        }

        @Synchronized
        override fun addAll(list: List<ConfigEntry>) = list.forEach(::add)

        @Synchronized
        override fun getAllNow(): List<ConfigEntry> =
            rows.map { (key, value) -> ConfigEntry(key.first, key.second, value) }

        @Synchronized
        override fun getAllNow(profileId: Int): List<ConfigEntry> =
            rows.filter { it.key.first == profileId }
                .map { (key, value) -> ConfigEntry(key.first, key.second, value) }

        @Synchronized
        override fun clear(profileId: Int) {
            onClear?.invoke(profileId)
            rows.keys.removeAll { it.first == profileId }
        }
    }

    private val configDao = FakeConfigDao()

    private lateinit var db: AppDb
    private lateinit var config: Config

    @BeforeTest
    fun setUp() {
        db = mockk(relaxed = true)
        every { db.configDao() } returns configDao

        // the removed child's setting, stored before this process started
        configDao.add(ConfigEntry(2, "homeEventsLimit", "9"))
        config = Config(db)
    }

    @Test
    fun `a retired profile config refuses further writes`() = runTest {
        val evicted = config[2]
        val seen = mutableListOf<String>()
        val collector = launch { evicted.changes.collect { seen += it } }
        runCurrent()

        // anti-vacuity: a live write reaches both the map and the signal
        evicted.ui.homeEventsLimit = 7
        runCurrent()
        assertEquals("7", evicted.values["homeEventsLimit"])
        assertEquals(listOf("homeEventsLimit"), seen)

        config.removeProfile(2)

        // the screen still holds `evicted` and writes through it anyway
        evicted.ui.homeEventsLimit = 8
        runCurrent()
        assertEquals("7", evicted.values["homeEventsLimit"])
        assertEquals(listOf("homeEventsLimit"), seen)

        collector.cancel()
    }

    /**
     * `BaseConfig` is the base of the global [Config] **and** every [ProfileConfig]. Cancelling the
     * global instance's `Job` would stop every app-wide setting persisting for the rest of the
     * process, with nothing to show for it, so this asserts a triple rather than a single value.
     */
    @Test
    fun `retiring a profile cancels only that profile's scope`() {
        val evicted = config[2]
        val sibling = config[3]
        assertTrue(evicted.isActive)
        assertTrue(sibling.isActive)
        assertTrue(config.isActive)

        config.removeProfile(2)

        assertFalse(evicted.isActive)
        assertTrue(sibling.isActive)
        assertTrue(config.isActive)
    }

    /**
     * Retiring has to happen **before** the `DELETE`, which is the whole reason it buys anything: a
     * write already queued on `Dispatchers.IO` is cancelled before it can re-insert a row into the
     * gap the `DELETE` opens. Clear-then-retire leaves exactly that gap and keeps every other test
     * here green, so the order is pinned directly rather than left to review.
     *
     * `assertEquals(false, …)` rather than `assertFalse` on purpose: if the hook never fires, the
     * value is still null and the test fails instead of passing vacuously.
     */
    @Test
    fun `the instance is retired before the rows are deleted`() {
        val evicted = config[2]
        var activeAtDelete: Boolean? = null
        configDao.onClear = { activeAtDelete = evicted.isActive }

        config.removeProfile(2)

        assertEquals(false, activeAtDelete)
    }
}
