package com.patriotlogger.logger.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TagDataDao {

    // --- NEW QUERY FOR DEBUG SCREEN ---
    /**
     * Joins TagData with TagStatus to get a combined list for the debug screen.
     * This returns a list of POJOs (DebugTagData) rather than full entities.
     */
    @Query("SELECT td.dataId, td.trackId, ts.tagId, td.timestampMs, td.rssi " +
           "FROM tag_data AS td " +
           "INNER JOIN tag_status AS ts ON td.trackId = ts.trackId " +
           "ORDER BY td.timestampMs DESC")
    LiveData<List<DebugTagData>> liveGetAllDebugTagData();


    // --- Write Operations (Synchronous) ---
    @Insert
    void insert(TagData tagData);

    @Query("DELETE FROM tag_data WHERE trackId = :trackId")
    void deleteSamplesForTrackIdSync(int trackId);

    @Query("DELETE FROM tag_data")
    void clear();

    // --- Read Operations (Returning LiveData) ---

    @Query("SELECT * FROM tag_data WHERE trackId = :trackId ORDER BY timestampMs ASC")
    LiveData<List<TagData>> liveGetSamplesForTrackId(int trackId);

    @Query("SELECT * FROM tag_data ORDER BY timestampMs ASC")
    LiveData<List<TagData>> liveGetAllTagData();

    @Query("SELECT COUNT(*) FROM tag_data")
    LiveData<Integer> getTotalSamplesCount();

    // --- Synchronous Read Operations ---

    @Query("SELECT * FROM tag_data WHERE trackId = :trackId ORDER BY timestampMs ASC")
    List<TagData> getSamplesForTrackIdSync(int trackId);

    @Query("SELECT * FROM tag_data ORDER BY timestampMs ASC")
    List<TagData> getAllTagDataSync();

    @Query("SELECT COUNT(*) FROM tag_data")
    int getTotalSamplesCountSync();
}
