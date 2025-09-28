package com.patriotlogger.logger.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TagDataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE) // Or IGNORE, depending on desired behavior for duplicate timestamps (unlikely)
    void insert(TagData tagData);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<TagData> tagDataList); // For potential bulk inserts

    @Query("SELECT * FROM tag_data WHERE trackId = :trackId ORDER BY entryTimeMs ASC")
    List<TagData> getSamplesForTrackIdSync(int trackId);
    
    @Query("SELECT COUNT(*) FROM tag_data")
    LiveData<Integer> getTotalSamplesCount(); // For Request #6g
    
    @Query("SELECT COUNT(*) FROM tag_data")
    int getTotalSamplesCountSync(); // Synchronous version for CSV export or other background tasks

    @Query("DELETE FROM tag_data WHERE trackId = :trackId")
    void deleteSamplesForTrackIdSync(int trackId);

    @Query("DELETE FROM tag_data")
    void clear();
    
    @Query("SELECT * FROM tag_data ORDER BY dataId ASC") // Generic query to get all data for CSV export
    List<TagData> getAllTagDataSync();
}
