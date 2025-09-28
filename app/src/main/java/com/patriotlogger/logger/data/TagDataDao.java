// Updated TagDataDao.java
package com.patriotlogger.logger.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TagDataDao {

    // --- Write Operations (Synchronous) ---
    @Insert
    void insert(TagData tagData); // For Repository's async insertTagData

    @Query("DELETE FROM tag_data WHERE trackId = :trackId")
    void deleteSamplesForTrackIdSync(int trackId); // For Repository's async delete

    @Query("DELETE FROM tag_data")
    void clear();


    // --- Read Operations (Returning LiveData) ---

    @Query("SELECT * FROM tag_data WHERE trackId = :trackId ORDER BY timestampMs ASC")
    LiveData<List<TagData>> liveGetSamplesForTrackId(int trackId); // NEW for Repository

    @Query("SELECT * FROM tag_data ORDER BY timestampMs ASC")
    LiveData<List<TagData>> liveGetAllTagData(); // NEW for Repository

    @Query("SELECT COUNT(*) FROM tag_data")
    LiveData<Integer> getTotalSamplesCount(); // You might already have this


    // --- Synchronous Read Operations (For internal use or background tasks like Workers) ---

    @Query("SELECT * FROM tag_data WHERE trackId = :trackId ORDER BY timestampMs ASC")
    List<TagData> getSamplesForTrackIdSync(int trackId);

    @Query("SELECT * FROM tag_data ORDER BY timestampMs ASC")
    List<TagData> getAllTagDataSync();

    @Query("SELECT COUNT(*) FROM tag_data")
    int getTotalSamplesCountSync(); // If needed by workers/background tasks
}
