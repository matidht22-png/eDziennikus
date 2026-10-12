/*
 * Copyright (c) Kuba Szczodrzyński 2019-11-27.
 */

package eu.mikus.edziennik.config.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun add(entry: ConfigEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addAll(list: List<ConfigEntry>)

    @Query("SELECT * FROM config")
    fun getAllNow(): List<ConfigEntry>

    @Query("SELECT * FROM config WHERE profileId = :profileId")
    fun getAllNow(profileId: Int): List<ConfigEntry>

    /**
     * Prefer [eu.mikus.edziennik.config.Config.removeProfile], which also drops the in-memory copy.
     *
     * A profile's settings live in this table *and* in `Config`'s `profileConfigs` cache. Clearing
     * only the table leaves the next profile to take this id - ids are reused - reading the
     * previous one's settings until the process restarts. Kotlin has no package-private and
     * `internal` means nothing inside this single module, so this comment is the only thing
     * stopping that.
     */
    @Query("DELETE FROM config WHERE profileId = :profileId")
    fun clear(profileId: Int)
}
