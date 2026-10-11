/*
 * Copyright (c) Mikolaj Olszewski 2026-10-11.
 */
package eu.mikus.edziennik.data.db

import eu.mikus.edziennik.config.db.ConfigDao
import eu.mikus.edziennik.data.db.dao.LoginStoreDao
import eu.mikus.edziennik.data.db.dao.NoteDao
import eu.mikus.edziennik.data.db.dao.ProfileDao
import eu.mikus.edziennik.data.db.entity.Profile
import eu.mikus.edziennik.data.db.enums.LoginType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.BeforeTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding #6: a removed profile's notes outlived it and were handed to the next child, because
 * `ProfileRemoveDialog` never cleared the `notes` table and profile ids are reused.
 *
 * The first test is the one that matters. The LAB "wipe the data, keep the profile" action runs
 * the same code with `keepProfile = true`, so a note clear written below that guard looks correct,
 * compiles, and silently leaves one child's notes on another child's screen.
 */
class ProfileDataRemovalTest {

    private val noteDao = mockk<NoteDao>(relaxed = true)
    private val configDao = mockk<ConfigDao>(relaxed = true)
    private val profileDao = mockk<ProfileDao>(relaxed = true)
    private val loginStoreDao = mockk<LoginStoreDao>(relaxed = true)

    private lateinit var db: AppDb

    @BeforeTest
    fun setUp() {
        db = mockk(relaxed = true)
        every { db.noteDao() } returns noteDao
        every { db.configDao() } returns configDao
        every { db.profileDao() } returns profileDao
        every { db.loginStoreDao() } returns loginStoreDao
        every { profileDao.getByIdNow(1) } returns
            Profile(id = 1, loginStoreId = 7, loginStoreType = LoginType.LIBRUS)
        every { profileDao.getIdsByLoginStoreIdNow(7) } returns listOf(1)
    }

    @Test
    fun `clears notes even when the profile itself is kept`() {
        val removed = removeProfileData(db, profileId = 1, keepProfile = true)

        verify { noteDao.clear(1) }
        assertFalse(removed)
        verify(exactly = 0) { configDao.clear(any()) }
        verify(exactly = 0) { profileDao.remove(any()) }
    }

    @Test
    fun `clears notes and removes the profile`() {
        val removed = removeProfileData(db, profileId = 1, keepProfile = false)

        verify { noteDao.clear(1) }
        verify { configDao.clear(1) }
        verify { profileDao.remove(1) }
        verify { loginStoreDao.remove(7) }
        assertTrue(removed)
    }

    @Test
    fun `does nothing for an unknown profile`() {
        every { profileDao.getByIdNow(99) } returns null

        val removed = removeProfileData(db, profileId = 99, keepProfile = false)

        assertFalse(removed)
        verify(exactly = 0) { noteDao.clear(any()) }
        verify(exactly = 0) { profileDao.remove(any()) }
    }

    @Test
    fun `keeps a login store that other profiles still use`() {
        every { profileDao.getIdsByLoginStoreIdNow(7) } returns listOf(1, 2)

        removeProfileData(db, profileId = 1, keepProfile = false)

        verify { profileDao.remove(1) }
        verify(exactly = 0) { loginStoreDao.remove(any()) }
    }

    @Test
    fun `clearProfileData clears notes and retires nothing`() {
        clearProfileData(db, profileId = 1)

        verify { noteDao.clear(1) }
        verify(exactly = 0) { configDao.clear(any()) }
        verify(exactly = 0) { profileDao.remove(any()) }
        verify(exactly = 0) { loginStoreDao.remove(any()) }
    }
}
