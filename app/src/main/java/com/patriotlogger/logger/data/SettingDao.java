package com.patriotlogger.logger.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface SettingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertConfig(Setting setting);

    /**
     * Retrieves the settings configuration by its ID.
     * @param id The ID of the settings row (should be Setting.SETTINGS_ID).
     * @return The Setting object, or null if not found.
     */
    @Query("SELECT * FROM settings_config WHERE id = :id")
    Setting getConfigSync(int id);

    /**
     * Retrieves the settings configuration as LiveData by its ID.
     * @param id The ID of the settings row (should be Setting.SETTINGS_ID).
     * @return LiveData emitting the Setting object, or null if not found.
     */
    @Query("SELECT * FROM settings_config WHERE id = :id")
    LiveData<Setting> getLiveConfig(int id);

    @Query("DELETE FROM settings_config")
    void clear();
}
